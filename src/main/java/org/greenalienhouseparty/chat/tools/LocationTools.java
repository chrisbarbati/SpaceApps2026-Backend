package org.greenalienhouseparty.chat.tools;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.dto.DailyPointResponse;
import org.greenalienhouseparty.dto.FieldFeature;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assistant's tools for questions about a place rather than a stored field: regional soil ratings, the
 * seasonal outlook, daily weather and nearby fields.
 *
 * <p>Coordinates are optional; when the model leaves them out, the pin or selected field in the app is used
 * (see {@link FieldLocator#locate}).</p>
 */
@Component
public class LocationTools {

    private static final String LAT_DESCRIPTION =
            "Latitude in degrees. Leave empty to use the pin or field selected in the app.";

    private static final String LON_DESCRIPTION =
            "Longitude in degrees. Leave empty to use the pin or field selected in the app.";

    /** Longest period of daily weather returned in one call. */
    static final int MAX_DAILY_DAYS = 366;

    /** Most recent daily values listed in full; the rest is summarized. */
    static final int RECENT_DAYS_LISTED = 14;

    /** NASA POWER's value for missing data. */
    private static final double POWER_FILL_VALUE = -999;

    private static final DateTimeFormatter POWER_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final SoilLandscapeService soilLandscape;

    private final SeasonalOutlookService seasonalOutlook;

    private final DataBackendService dataBackend;

    private final FieldLocator locator;

    private final ToolResults results;

    /**
     * Creates the tools.
     *
     * @param soilLandscape   regional soil ratings from Agriculture and Agri-Food Canada
     * @param seasonalOutlook seasonal outlook from Environment Canada
     * @param dataBackend     access to the Python data backend
     * @param locator         works out which point a question is about
     * @param results         shrinks responses for the model
     */
    public LocationTools(SoilLandscapeService soilLandscape, SeasonalOutlookService seasonalOutlook,
                         DataBackendService dataBackend, FieldLocator locator, ToolResults results) {
        this.soilLandscape = soilLandscape;
        this.seasonalOutlook = seasonalOutlook;
        this.dataBackend = dataBackend;
        this.locator = locator;
        this.results = results;
    }

    /**
     * Returns regional land capability, drainage and soil indicators at a point (Canada only).
     *
     * @param lat         latitude, or {@code null} for the app's location
     * @param lon         longitude, or {@code null} for the app's location
     * @param toolContext the chat context
     * @return the regional soil ratings
     */
    @Tool(name = "getSoilLandscape", description = """
            Regional soil ratings at a place in Canada from Agriculture and Agri-Food Canada's Soil Landscapes \
            of Canada (1:250,000 maps): Canada Land Inventory capability class and limitations, dominant \
            drainage, and indicators such as erosion risk, organic carbon change and residual nitrogen with \
            their trend. Regional, so a field can differ from the land around it.""")
    public JsonNode getSoilLandscape(
            @ToolParam(description = LAT_DESCRIPTION, required = false) Double lat,
            @ToolParam(description = LON_DESCRIPTION, required = false) Double lon,
            ToolContext toolContext) {
        FieldLocator.Point point = locator.locate(ChatToolContext.from(toolContext), lat, lon);
        ObjectNode result = results.object();
        result.put("locationSource", point.source());
        result.set("soilLandscape", results.compact(soilLandscape.landscape(point.lat(), point.lon()),
                ToolResults.DEFAULT_MAX_ITEMS, "indicators.history"));
        return result;
    }

    /**
     * Returns the seasonal temperature and precipitation outlook at a point.
     *
     * @param lat         latitude, or {@code null} for the app's location
     * @param lon         longitude, or {@code null} for the app's location
     * @param toolContext the chat context
     * @return chances of above, near and below normal for the coming seasons
     */
    @Tool(name = "getSeasonalOutlook", description = """
            Environment Canada's seasonal outlook at a place: for each coming three-month period, the chances \
            (percent) of above-, near- and below-normal temperature and precipitation. Probabilities, not \
            forecasts of specific weather.""")
    public JsonNode getSeasonalOutlook(
            @ToolParam(description = LAT_DESCRIPTION, required = false) Double lat,
            @ToolParam(description = LON_DESCRIPTION, required = false) Double lon,
            ToolContext toolContext) {
        FieldLocator.Point point = locator.locate(ChatToolContext.from(toolContext), lat, lon);
        ObjectNode result = results.object();
        result.put("locationSource", point.source());
        result.set("seasonalOutlook", results.compact(seasonalOutlook.outlook(point.lat(), point.lon()),
                ToolResults.DEFAULT_MAX_ITEMS));
        return result;
    }

    /**
     * Returns NASA POWER daily weather at a point for a period, summarized.
     *
     * @param start       first day, {@code YYYY-MM-DD} or {@code YYYYMMDD}
     * @param end         last day, {@code YYYY-MM-DD} or {@code YYYYMMDD}
     * @param parameters  comma-separated POWER parameters, or {@code null} for temperature and rainfall
     * @param lat         latitude, or {@code null} for the app's location
     * @param lon         longitude, or {@code null} for the app's location
     * @param toolContext the chat context
     * @return per parameter: days, minimum, mean, maximum, total and the last days in full
     */
    @Tool(name = "getDailyWeather", description = """
            NASA POWER daily weather at a place for a period of up to one year, summarized per parameter \
            (minimum, mean, maximum, total) with the last 14 days listed. Default parameters: T2M (mean air \
            temperature, C) and PRECTOTCORR (rainfall, mm/day). Others: T2M_MAX, T2M_MIN, RH2M (humidity %), \
            WS2M (wind m/s), ALLSKY_SFC_SW_DWN (solar MJ/m2/day), GWETROOT (root-zone soil wetness 0-1). \
            Satellite and model estimates; recent days may be missing.""")
    public JsonNode getDailyWeather(
            @ToolParam(description = "First day, YYYY-MM-DD") String start,
            @ToolParam(description = "Last day, YYYY-MM-DD") String end,
            @ToolParam(description = "Comma-separated POWER parameters. Default T2M,PRECTOTCORR.", required = false)
            String parameters,
            @ToolParam(description = LAT_DESCRIPTION, required = false) Double lat,
            @ToolParam(description = LON_DESCRIPTION, required = false) Double lon,
            ToolContext toolContext) {
        LocalDate from = parseDate(start, "start");
        LocalDate to = parseDate(end, "end");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("end must not be before start.");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAILY_DAYS) {
            throw new IllegalArgumentException("Ask for at most " + MAX_DAILY_DAYS + " days at a time.");
        }
        FieldLocator.Point point = locator.locate(ChatToolContext.from(toolContext), lat, lon);
        String wanted = parameters == null || parameters.isBlank() ? "T2M,PRECTOTCORR" : parameters.strip();
        DailyPointResponse response = dataBackend.dailyPoint(point.lat(), point.lon(),
                from.format(POWER_DATE), to.format(POWER_DATE), wanted);
        return summarizeDaily(response, point);
    }

    /**
     * Lists stored fields around a point, nearest first.
     *
     * @param radiusKm    search radius in kilometres, or {@code null} for 1
     * @param lat         latitude, or {@code null} for the app's location
     * @param lon         longitude, or {@code null} for the app's location
     * @param toolContext the chat context
     * @return up to ten nearby fields with id, area and distance
     */
    @Tool(name = "findFieldsNear", description = """
            Lists stored field outlines near a place, nearest first, with id, area and distance. Use it when \
            the farmer asks about a place but has not selected a field, then pass the chosen field id to the \
            field tools.""")
    public JsonNode findFieldsNear(
            @ToolParam(description = "Search radius in km, 0.1 to 10. Default 1.", required = false) Double radiusKm,
            @ToolParam(description = LAT_DESCRIPTION, required = false) Double lat,
            @ToolParam(description = LON_DESCRIPTION, required = false) Double lon,
            ToolContext toolContext) {
        FieldLocator.Point point = locator.locate(ChatToolContext.from(toolContext), lat, lon);
        double radius = radiusKm == null ? 1.0 : Math.max(0.1, Math.min(10.0, radiusKm));
        double degrees = radius / 111.0;
        FieldsResponse page = dataBackend.listFields(FieldLocator.bbox(point, degrees), 200, 0);

        record Nearby(String id, Double areaHa, Double lat, Double lon, double distanceKm) {
        }
        List<Nearby> nearby = new ArrayList<>();
        if (page != null && page.features() != null) {
            for (FieldFeature feature : page.features()) {
                List<Double> centroid = feature.properties() == null ? null : feature.properties().getCentroid();
                if (centroid == null || centroid.size() < 2) {
                    continue;
                }
                double distance = distanceKm(point.lat(), point.lon(), centroid.get(1), centroid.get(0));
                if (distance <= radius) {
                    String id = feature.id() != null ? feature.id() : feature.properties().getId();
                    nearby.add(new Nearby(id, feature.properties().getAreaHa(), centroid.get(1), centroid.get(0),
                            Math.round(distance * 100) / 100.0));
                }
            }
        }
        nearby.sort(Comparator.comparingDouble(Nearby::distanceKm));
        ObjectNode result = results.object();
        result.put("locationSource", point.source());
        result.put("radiusKm", radius);
        result.put("fieldsFound", nearby.size());
        result.set("fields", results.json(nearby.stream().limit(10).toList()));
        return result;
    }

    /**
     * Summarizes a NASA POWER daily response per parameter.
     *
     * @param response the daily response
     * @param point    where it is for
     * @return the summary
     */
    private JsonNode summarizeDaily(DailyPointResponse response, FieldLocator.Point point) {
        ObjectNode result = results.object();
        result.put("locationSource", point.source());
        result.put("lat", point.lat());
        result.put("lon", point.lon());
        ObjectNode summaries = result.putObject("parameters");
        Map<String, Map<String, JsonNode>> byParameter = response == null || response.getProperties() == null
                ? null : response.getProperties().getParameter();
        if (byParameter == null) {
            result.put("note", "No daily values were returned.");
            return result;
        }
        for (Map.Entry<String, Map<String, JsonNode>> parameter : byParameter.entrySet()) {
            ObjectNode summary = summaries.putObject(parameter.getKey());
            List<Map.Entry<String, JsonNode>> days = parameter.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double total = 0;
            int count = 0;
            int missing = 0;
            for (Map.Entry<String, JsonNode> day : days) {
                if (day.getValue() == null || !day.getValue().isNumber() || day.getValue().asDouble() == POWER_FILL_VALUE) {
                    missing++;
                    continue;
                }
                double value = day.getValue().asDouble();
                min = Math.min(min, value);
                max = Math.max(max, value);
                total += value;
                count++;
            }
            summary.put("days", count);
            summary.put("missingDays", missing);
            if (count > 0) {
                summary.put("min", round(min));
                summary.put("mean", round(total / count));
                summary.put("max", round(max));
                summary.put("total", round(total));
            }
            ArrayNode recent = summary.putArray("lastDays");
            for (Map.Entry<String, JsonNode> day : days.subList(Math.max(0, days.size() - RECENT_DAYS_LISTED), days.size())) {
                ObjectNode entry = recent.addObject();
                entry.put("date", day.getKey());
                JsonNode value = day.getValue();
                if (value != null && value.isNumber() && value.asDouble() != POWER_FILL_VALUE) {
                    entry.put("value", round(value.asDouble()));
                } else {
                    entry.putNull("value");
                }
            }
        }
        return result;
    }

    /**
     * Parses a date given as {@code YYYY-MM-DD} or {@code YYYYMMDD}.
     *
     * @param value the date
     * @param name  parameter name for the error message
     * @return the date
     * @throws IllegalArgumentException when it cannot be read
     */
    static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required, as YYYY-MM-DD.");
        }
        String text = value.strip();
        try {
            return text.contains("-") ? LocalDate.parse(text) : LocalDate.parse(text, POWER_DATE);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(name + " must be a date as YYYY-MM-DD.");
        }
    }

    /**
     * Great-circle distance between two points.
     *
     * @param lat1 first latitude
     * @param lon1 first longitude
     * @param lat2 second latitude
     * @param lon2 second longitude
     * @return distance in kilometres
     */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Rounds to two decimals for display.
     *
     * @param value the value
     * @return the rounded value
     */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
