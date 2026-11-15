package org.greenalienhouseparty.landscape;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.Change;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.Drainage;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.Indicator;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.LandCapability;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.Limitation;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.YearValue;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;

/**
 * Soil survey and agri-environmental indicator values around a point, from
 * Agriculture and Agri-Food Canada's open feature services.
 *
 * <p>Six layers are read, each with one query for the polygon containing the
 * point: Canada Land Inventory capability for agriculture (1:250,000), the
 * dominant drainage of the Soil Landscapes of Canada (1:1,000,000), and four
 * agri-environmental indicators reported per soil landscape for each census
 * year from 1981 to 2021 (integrated soil erosion risk, soil cover days,
 * soil organic carbon change and residual soil nitrogen). Outside Canada
 * every layer is empty and the answer says so. Answers are cached because the
 * layers change only when a census is published.</p>
 */
@Service
public class SoilLandscapeService {

    private static final Logger log = LoggerFactory.getLogger(SoilLandscapeService.class);

    static final String CAPABILITY_SERVICE = "cli_agr_cap_250k";

    static final String DRAINAGE_SERVICE = "aafc_soc_drainage_fs";

    /**
     * One agri-environmental indicator layer.
     *
     * @param service feature service name
     * @param key     identifier used in responses
     * @param label   the indicator in plain words
     * @param unit    unit of its values
     * @param meaning how to read it
     */
    record IndicatorLayer(String service, String key, String label, String unit, String meaning) {
    }

    static final List<IndicatorLayer> INDICATORS = List.of(
            new IndicatorLayer("Risk_soil_erosion_integrated_2021", "erosionRisk", "Soil erosion risk",
                    "tonnes per hectare per year",
                    "Estimated soil lost to water, wind and tillage erosion together under the cropping and tillage "
                            + "reported in the census. Lower is better."),
            new IndicatorLayer("Soil_cover_days_2021", "soilCoverDays", "Soil cover days", "days per year",
                    "Days in the year the soil is covered by crop, residue or snow. Higher is better; bare soil is "
                            + "exposed to erosion."),
            new IndicatorLayer("Soil_organic_carbon_change_2021", "organicCarbonChange", "Soil organic carbon change",
                    "kilograms of carbon per hectare per year",
                    "Estimated yearly gain or loss of soil organic carbon. Positive means the soil is gaining organic "
                            + "matter; negative means it is losing it."),
            new IndicatorLayer("Residual_soil_nitrogen_2021", "residualSoilNitrogen", "Residual soil nitrogen",
                    "kilograms of nitrogen per hectare",
                    "Nitrogen left in the soil after harvest. Higher means more applied nitrogen went unused and is "
                            + "at risk of being lost to water or air."));

    private static final Map<String, String> CAPABILITY_CLASSES = Map.of(
            "1", "No significant limitations for crops.",
            "2", "Moderate limitations that restrict the range of crops or need moderate conservation practices.",
            "3", "Moderately severe limitations that restrict the range of crops or need special conservation practices.",
            "4", "Severe limitations that restrict the range of crops or need special conservation practices, or both.",
            "5", "Very severe limitations; suited to perennial forage crops, and improvement practices are feasible.",
            "6", "Capable only of producing perennial forage crops; improvement practices are not feasible.",
            "7", "No capability for arable crops or permanent pasture.",
            "O", "Organic soils, not placed in a capability class.");

    private static final Map<String, String> LIMITATIONS = Map.ofEntries(
            Map.entry("C", "adverse climate"),
            Map.entry("D", "undesirable soil structure or low permeability"),
            Map.entry("E", "erosion damage"),
            Map.entry("F", "low natural fertility"),
            Map.entry("I", "flooding by streams or lakes"),
            Map.entry("M", "low moisture-holding capacity (droughtiness)"),
            Map.entry("N", "salinity"),
            Map.entry("P", "stoniness"),
            Map.entry("R", "shallow bedrock"),
            Map.entry("S", "a combination of adverse soil characteristics"),
            Map.entry("T", "adverse topography (slope)"),
            Map.entry("W", "excess water"),
            Map.entry("X", "several minor adverse characteristics together"));

    private static final Map<String, String> DRAINAGE_CLASSES = Map.of(
            "R", "Very rapidly and rapidly drained",
            "W", "Well and moderately well drained",
            "I", "Imperfectly drained",
            "P", "Poorly and very poorly drained");

    /** Field such as {@code ERI_2021_VAL}: a prefix, a census year and the value suffix. */
    private static final Pattern YEAR_VALUE = Pattern.compile("^(.+)_((?:19|20)\\d{2})_VAL$");

    /** Field such as {@code ERI_81_21_CHG_VAL}: change between two census years given as two digits each. */
    private static final Pattern CHANGE_VALUE = Pattern.compile("^(.+)_(\\d{2})_(\\d{2})_CHG_VAL$");

    private static final int PARALLEL = 6;

    private static final String NOTE = "Values come from regional soil maps (1:250,000 for land capability, "
            + "1:1,000,000 soil landscapes for the rest), so they describe the surrounding area rather than one field. "
            + "Indicators are modelled from census farming practices for the whole soil landscape; a farm's own "
            + "rotation, tillage and fertilizer use can differ a great deal. Use them as regional context, and rely "
            + "on a soil test and local advice for the field itself.";

    private static final String OUTSIDE = "None of these layers covers this location; they are published for "
            + "Canada only. ";

    private final ArcGisFeatureClient client;

    private final Duration cacheTtl;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(PARALLEL);

    /**
     * A cached answer.
     *
     * @param storedNanos when it was stored, from {@link System#nanoTime()}
     * @param response    the answer
     */
    private record Cached(long storedNanos, SoilLandscapeResponse response) {
    }

    /**
     * Creates the service.
     *
     * @param client   AAFC feature service client
     * @param cacheTtl how long an answer is reused (see {@code aafc.cache-ttl})
     */
    public SoilLandscapeService(ArcGisFeatureClient client, @Value("${aafc.cache-ttl:24h}") Duration cacheTtl) {
        this.client = client;
        this.cacheTtl = cacheTtl;
    }

    /**
     * Stops the request threads when the application shuts down.
     */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Returns land capability, drainage and soil indicators around a point.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return the values found; empty of values outside Canada
     * @throws InvalidRequestException when the coordinates are out of range
     * @throws ExternalDataException   when the feature services cannot be reached
     */
    public SoilLandscapeResponse landscape(double lat, double lon) {
        log.trace("Entering landscape()");
        try {
            if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) {
                throw new InvalidRequestException("lat must be -90 to 90 and lon -180 to 180");
            }
            String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
            Cached hit = cache.get(key);
            if (hit != null && System.nanoTime() - hit.storedNanos() < cacheTtl.toNanos()) {
                log.debug("Returning cached soil landscape");
                return hit.response();
            }
            SoilLandscapeResponse response = fetch(lat, lon);
            cache.put(key, new Cached(System.nanoTime(), response));
            return response;
        } finally {
            log.trace("Exiting landscape()");
        }
    }

    /**
     * Queries every layer at the point and builds the answer.
     *
     * @param lat latitude
     * @param lon longitude
     * @return the values found
     */
    private SoilLandscapeResponse fetch(double lat, double lon) {
        log.debug("Requesting soil landscape layers from AAFC");
        var capability = query(CAPABILITY_SERVICE, lat, lon);
        var drainage = query(DRAINAGE_SERVICE, lat, lon);
        List<CompletableFuture<Optional<Map<String, JsonNode>>>> indicatorJobs =
                INDICATORS.stream().map(layer -> query(layer.service(), lat, lon)).toList();
        try {
            LandCapability landCapability = capability.join().map(SoilLandscapeService::landCapability).orElse(null);
            Drainage soilDrainage = drainage.join().map(SoilLandscapeService::drainage).orElse(null);
            List<Indicator> indicators = new ArrayList<>();
            for (int index = 0; index < INDICATORS.size(); index++) {
                IndicatorLayer layer = INDICATORS.get(index);
                indicatorJobs.get(index).join().map(attributes -> indicator(layer, attributes)).ifPresent(indicators::add);
            }
            boolean covered = landCapability != null || soilDrainage != null || !indicators.isEmpty();
            List<String> datasets = new ArrayList<>(List.of(
                    "Canada Land Inventory - Land Capability for Agriculture (1:250,000)",
                    "Soils of Canada - Soil Drainage (Soil Landscapes of Canada)"));
            INDICATORS.forEach(layer -> datasets.add("Agri-environmental indicator: " + layer.label() + " (1981-2021)"));
            return new SoilLandscapeResponse(
                    new SoilLandscapeResponse.Location(lat, lon),
                    covered ? "Canada" : null,
                    landCapability,
                    soilDrainage,
                    indicators,
                    new SoilLandscapeResponse.Source(
                            "Agriculture and Agri-Food Canada",
                            "https://services.arcgis.com/lGOekm0RsNxYnT3j/arcgis/rest/services",
                            datasets,
                            "Open Government Licence - Canada"),
                    covered ? NOTE : OUTSIDE + NOTE);
        } catch (CompletionException e) {
            if (e.getCause() instanceof ExternalDataException external) {
                throw external;
            }
            throw e;
        }
    }

    /**
     * Starts one layer query on the request threads.
     *
     * @param service feature service name
     * @param lat     latitude
     * @param lon     longitude
     * @return the pending attributes
     */
    private CompletableFuture<Optional<Map<String, JsonNode>>> query(String service, double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> client.attributesAt(service, lat, lon), executor);
    }

    /**
     * Reads the dominant capability class and its limitations.
     *
     * @param attributes Canada Land Inventory polygon attributes
     * @return the capability, or {@code null} when the polygon has no class
     */
    static LandCapability landCapability(Map<String, JsonNode> attributes) {
        String classCode = text(attributes.get("CLASS_A"));
        if (classCode == null) {
            return null;
        }
        List<Limitation> limitations = new ArrayList<>();
        for (String field : List.of("SUBCLAS_A1", "SUBCLAS_A2")) {
            String code = text(attributes.get(field));
            if (code != null) {
                limitations.add(new Limitation(code, LIMITATIONS.getOrDefault(code, "limitation code " + code)));
            }
        }
        return new LandCapability(
                classCode,
                CAPABILITY_CLASSES.getOrDefault(classCode,
                        "Not rated for agriculture (for example built-up or unmapped land)."),
                List.copyOf(limitations),
                "1:250,000");
    }

    /**
     * Reads the dominant drainage class.
     *
     * @param attributes soil drainage polygon attributes
     * @return the drainage, or {@code null} when unclassified
     */
    static Drainage drainage(Map<String, JsonNode> attributes) {
        String code = text(attributes.get("DRAINAGE_CODE"));
        if (code == null || !DRAINAGE_CLASSES.containsKey(code)) {
            return null;
        }
        JsonNode share = attributes.get("DRAINAGE_PCNT");
        return new Drainage(code, DRAINAGE_CLASSES.get(code), share == null || !share.isNumber() ? null : share.asInt());
    }

    /**
     * Reads an indicator's value for every census year and its change over the record.
     *
     * <p>The layers name fields {@code <PREFIX>_<YEAR>_VAL} with a class label
     * in {@code <PREFIX>_<YEAR>_CLASS_EN} (or {@code _CLASS_E}), and the change
     * in {@code <PREFIX>_<YY>_<YY>_CHG_VAL} with {@code _CHG_CLASS_EN}.</p>
     *
     * @param layer      the indicator layer
     * @param attributes soil landscape polygon attributes
     * @return the indicator, or {@code null} when the polygon has no values
     */
    static Indicator indicator(IndicatorLayer layer, Map<String, JsonNode> attributes) {
        TreeMap<Integer, YearValue> history = new TreeMap<>();
        Change change = null;
        for (Map.Entry<String, JsonNode> field : attributes.entrySet()) {
            JsonNode node = field.getValue();
            if (node == null || !node.isNumber()) {
                continue;
            }
            Matcher yearly = YEAR_VALUE.matcher(field.getKey());
            if (yearly.matches()) {
                int year = Integer.parseInt(yearly.group(2));
                String prefix = yearly.group(1) + "_" + year + "_CLASS_E";
                String rating = Optional.ofNullable(text(attributes.get(prefix + "N"))).orElse(text(attributes.get(prefix)));
                history.put(year, new YearValue(year, round(node.asDouble()), rating));
                continue;
            }
            Matcher changed = CHANGE_VALUE.matcher(field.getKey());
            if (changed.matches()) {
                String prefix = field.getKey().substring(0, field.getKey().length() - "VAL".length()) + "CLASS_E";
                String rating = Optional.ofNullable(text(attributes.get(prefix + "N"))).orElse(text(attributes.get(prefix)));
                change = new Change(century(changed.group(2)), century(changed.group(3)), round(node.asDouble()), rating);
            }
        }
        if (history.isEmpty()) {
            return null;
        }
        YearValue latest = history.lastEntry().getValue();
        return new Indicator(layer.key(), layer.label(), layer.unit(), layer.meaning(),
                latest.year(), latest.value(), latest.rating(), change, List.copyOf(history.values()));
    }

    /**
     * Expands a two-digit census year: 81 to 1981, 21 to 2021.
     *
     * @param twoDigits the last two digits of a year
     * @return the four-digit year
     */
    private static int century(String twoDigits) {
        int year = Integer.parseInt(twoDigits);
        return year >= 50 ? 1900 + year : 2000 + year;
    }

    /**
     * Reads a text attribute, treating blank and padded values as missing.
     *
     * @param node attribute value, or {@code null}
     * @return the trimmed text, or {@code null} when absent or blank
     */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Rounds a value to two decimal places.
     *
     * @param value value to round
     * @return the rounded value
     */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
