package org.greenalienhouseparty.chat.tools;

import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.service.DataBackendService;
import org.greenalienhouseparty.service.FieldDetailsService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * The assistant's tools for reading what is known about one stored field.
 *
 * <p>Each tool calls the same service as the matching {@code /fields/{field_id}/...} route and returns a
 * shortened copy of its response (see {@link ToolResults}). The field identifier is optional: when the model
 * leaves it out, the field selected in the app is used (see {@link ChatToolContext#resolveFieldId}).</p>
 */
@Component
public class FieldDataTools {

    private static final String FIELD_ID_DESCRIPTION =
            "Field id. Leave empty to use the field the farmer has selected in the app.";

    private final FieldDetailsService details;

    private final DataBackendService dataBackend;

    private final ToolResults results;

    /**
     * Creates the tools.
     *
     * @param details     gathers everything known about a stored field
     * @param dataBackend access to the Python data backend
     * @param results     shrinks responses for the model
     */
    public FieldDataTools(FieldDetailsService details, DataBackendService dataBackend, ToolResults results) {
        this.details = details;
        this.dataBackend = dataBackend;
        this.results = results;
    }

    /**
     * Returns a summary of everything quickly available about a field.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param toolContext the chat context
     * @return size, soil, crop history, climate, crop calendar, agro-weather, regional soil and outlook
     */
    @Tool(name = "getFieldOverview", description = """
            Everything quickly available about one field: size, topsoil estimates, mapped crop history, climate \
            and water balance, frost dates, terrain and erosion rating, usual crop planting dates nearby, soil \
            wetness and evapotranspiration, regional land capability and drainage (Canada), and the seasonal \
            outlook. Start here for general questions about a field. 'sections' says which parts were \
            unavailable and why. Satellite greenness, vegetation history and drought indicators are not \
            included; use their own tools.""")
    public JsonNode getFieldOverview(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(details.details(id, null), 15,
                "soil.depths", "soil.units",
                "cropHistory.years.classes", "cropHistory.summary.timeline", "cropHistory.summary.recentYears",
                "cropHistory.summary.latestMapped.classes",
                "climate.yearly.years", "climate.layers", "climate.weatherCell",
                "agroWeather.seasonByYear", "agroWeather.units",
                "soilLandscape.indicators.history",
                "cropCalendar.cell");
    }

    /**
     * Returns the field's topsoil and deeper soil estimates.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param toolContext the chat context
     * @return soil properties by depth, derived texture and organic matter, and the source
     */
    @Tool(name = "getFieldSoil", description = """
            Soil estimates for one field from SoilGrids: pH, clay/sand/silt, organic carbon, nitrogen and CEC \
            for the topsoil and deeper layers, plus derived texture class and organic matter. These are \
            250 m model estimates, not lab tests.""")
    public JsonNode getFieldSoil(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldSoil(id), ToolResults.DEFAULT_MAX_ITEMS);
    }

    /**
     * Returns the field's long-term climate, water balance, frost and terrain summary.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param toolContext the chat context
     * @return the climate summary with yearly percentiles
     */
    @Tool(name = "getFieldClimate", description = """
            Long-term climate for one field from NASA POWER and terrain from NASA SRTM: growing season, \
            temperature, rainfall, heat stress, water balance (rain minus evapotranspiration), frost dates and \
            frost-free days, monthly normals, yearly percentiles (dry/normal/wet years), slope and erosion \
            rating.""")
    public JsonNode getFieldClimate(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldSummary(id, null), 15, "yearly.years", "layers", "weatherCell");
    }

    /**
     * Returns the crops mapped in the field in past years.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param toolContext the chat context
     * @return crops by year, the rotation pattern and the likely current crop
     */
    @Tool(name = "getCropHistory", description = """
            Crops mapped in one field in past years from the AAFC Annual Crop Inventory (satellite crop maps, \
            30 m), with the rotation pattern, the likely crop this year and how confident that is. Mapping can \
            be wrong for small or mixed fields; check flags and notes.""")
    public JsonNode getCropHistory(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldCropHistory(id), 15,
                "years.classes", "summary.timeline", "summary.recentYears", "summary.latestMapped.classes");
    }

    /**
     * Returns soil wetness, radiation and reference evapotranspiration for the field.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param years       years of history, 5 to 45, or {@code null} for 20
     * @param toolContext the chat context
     * @return latest soil wetness against past years, monthly wetness and growing-season averages
     */
    @Tool(name = "getAgroWeather", description = """
            Soil wetness (surface and root zone, latest value and how it compares with past years), solar \
            radiation, wind, humidity and reference evapotranspiration (ET0) for one field from NASA POWER.""")
    public JsonNode getAgroWeather(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Years of history to compare, 5 to 45. Default 20.", required = false) Integer years,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldAgroWeather(id, years), ToolResults.DEFAULT_MAX_ITEMS,
                "seasonByYear", "units");
    }

    /**
     * Returns several years of MODIS vegetation history for the field. Slow the first time.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param years       years of history, 1 to 10, or {@code null} for 5
     * @param toolContext the chat context
     * @return greenness by year, the typical season, bare-ground windows and yearly evapotranspiration
     */
    @Tool(name = "getVegetationTrend", description = """
            Several years of NASA MODIS vegetation history for one field: NDVI (greenness) peak, green-up and \
            brown-down dates by year, the typical season, windows when the ground is usually bare, yearly \
            productivity and actual vs potential evapotranspiration. SLOW: can take 20-45 seconds the first \
            time, so only use it when the question needs vegetation trends or bare-ground periods.""")
    public JsonNode getVegetationTrend(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Years of history, 1 to 10. Default 5.", required = false) Integer years,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldVegetationHistory(id, years), ToolResults.DEFAULT_MAX_ITEMS,
                "ndvi.observations", "ndvi.calendar", "source", "provider");
    }

    /**
     * Returns one season of Sentinel-2 greenness for the field. Slow the first time.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param year        growing season year, or {@code null} for the most recent
     * @param toolContext the chat context
     * @return the season's greenness summary and recent observations
     */
    @Tool(name = "getSeasonGreenness", description = """
            One growing season of Sentinel-2 satellite greenness (NDVI, 10 m) for one field: peak and latest \
            greenness and dates, and the most recent observations. Useful to see how the crop is doing this \
            season or did in a past season. SLOW: can take 20-45 seconds the first time.""")
    public JsonNode getSeasonGreenness(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Growing season year, 2017 to this year. Default: most recent season.",
                    required = false) Integer year,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldGreenness(id, year), ToolResults.DEFAULT_MAX_ITEMS);
    }

    /**
     * Returns GRACE drought indicators for the field. Slow the first time.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param toolContext the chat context
     * @return latest groundwater and soil moisture percentiles and recent weeks
     */
    @Tool(name = "getDroughtIndicators", description = """
            NASA GRACE-based drought indicators near one field: groundwater, root-zone and surface soil \
            moisture as percentiles against past years (low = drier than usual), the latest week and recent \
            weeks, and the same week in earlier years. Coarse (about 25 km). SLOW: can take 20-45 seconds the \
            first time.""")
    public JsonNode getDroughtIndicators(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        return results.compact(dataBackend.fieldDroughtIndicators(id, 8, 5), ToolResults.DEFAULT_MAX_ITEMS, "cell");
    }
}
