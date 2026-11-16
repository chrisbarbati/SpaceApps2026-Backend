package org.greenalienhouseparty.chat.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.market.CropMarketService;
import org.greenalienhouseparty.planning.CropReferenceCatalog;
import org.greenalienhouseparty.planning.ReferenceCropRepository;
import org.greenalienhouseparty.planning.model.CropReference;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assistant's tools for crop facts: the crops the application knows, each crop's requirements and soil
 * roles, usual planting dates near a field and market prices.
 */
@Component
public class CropTools {

    /** Default province for market data, matching {@code GET /crops/market}. */
    static final String DEFAULT_PROVINCE = "ON";

    private final ReferenceCropRepository reference;

    private final DataBackendService dataBackend;

    private final CropMarketService market;

    private final ToolResults results;

    /**
     * Creates the tools.
     *
     * @param reference   crop reference data
     * @param dataBackend access to the Python data backend
     * @param market      Statistics Canada yields and prices
     * @param results     shrinks responses for the model
     */
    public CropTools(ReferenceCropRepository reference, DataBackendService dataBackend, CropMarketService market,
                     ToolResults results) {
        this.reference = reference;
        this.dataBackend = dataBackend;
        this.market = market;
        this.results = results;
    }

    /**
     * Lists the crops the application can plan with and their soil roles.
     *
     * @return every crop with id, name, family and roles
     */
    @Tool(name = "listCrops", description = """
            Lists every crop this app can plan rotations with: id, name, family, whether it is perennial, \
            whether it fixes nitrogen, rooting depth and months of ground cover. Use the ids with the other \
            tools.""")
    public JsonNode listCrops() {
        CropReferenceCatalog catalog = reference.reference();
        ObjectNode result = results.object();
        result.set("crops", results.json(catalog.crops().stream().map(CropTools::summary).toList()));
        result.put("note", "Nitrogen, residue, cover, margin and disease figures are illustrative estimates, "
                + "not sourced data. Requirements come from FAO ECOCROP.");
        return result;
    }

    /**
     * Returns one crop's full requirements and estimates.
     *
     * @param crop crop id or name
     * @return the crop's reference entry, soil roles and the provenance of each group of values
     */
    @Tool(name = "getCropProfile", description = """
            Full profile of one crop: FAO ECOCROP requirements (pH, temperature, rainfall, cycle length, \
            killing temperature, rooting depth, soil texture and drainage it prefers and tolerates), estimated \
            nitrogen fixed and needed, residue, ground cover, net margin and disease susceptibility, plus a \
            plain summary of what it does for soil. Says which values are sourced and which are estimates.""")
    public JsonNode getCropProfile(@ToolParam(description = "Crop id or name, for example 'soybeans'") String crop) {
        CropReferenceCatalog catalog = reference.reference();
        String wanted = crop == null ? "" : crop.strip().toLowerCase(Locale.ROOT);
        CropReference match = catalog.crops().stream()
                .filter(entry -> entry.id().equalsIgnoreCase(wanted) || entry.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown crop '" + crop + "'. Known crops: "
                        + catalog.crops().stream().map(CropReference::id).toList()));
        ObjectNode result = results.object();
        result.set("crop", results.json(match));
        result.set("soilRoles", results.json(summary(match)));
        result.set("sources", results.json(catalog.sources()));
        return result;
    }

    /**
     * Returns the usual planting and maturity dates for crops around a field.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param crops       comma-separated calendar keys, or {@code null} for every crop
     * @param toolContext the chat context
     * @return the crop calendar around the field
     */
    @Tool(name = "getCropCalendar", description = """
            Usual planting and maturity dates, growing-season length and off-season (bare) days for crops in \
            the area around one field, from a global crop calendar. Calendar keys include corn, soybeans, \
            winter_wheat, spring_wheat, barley, canola, potatoes.""")
    public JsonNode getCropCalendar(
            @ToolParam(description = "Field id. Leave empty to use the field selected in the app.", required = false)
            String fieldId,
            @ToolParam(description = "Comma-separated calendar keys. Leave empty for every crop.", required = false)
            String crops,
            ToolContext toolContext) {
        String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
        String keys = crops == null || crops.isBlank() ? null : crops.strip();
        return results.compact(dataBackend.fieldCropCalendar(id, null, keys), 30, "cell");
    }

    /**
     * Returns recent yields and prices for the crops in a province.
     *
     * @param provinceCode two-letter province code, or {@code null} for the app's province or Ontario
     * @param toolContext  the chat context
     * @return yields, prices and gross revenue per hectare by crop
     */
    @Tool(name = "getMarketPrices", description = """
            Statistics Canada yields and farm prices for each crop in a Canadian province: latest and average \
            yield per hectare, latest and average price per tonne, and gross revenue per hectare. Gross \
            revenue is before costs, so it is not profit.""")
    public JsonNode getMarketPrices(
            @ToolParam(description = "Two-letter province code such as ON, QC, MB. Leave empty for the app's province.",
                    required = false) String provinceCode,
            ToolContext toolContext) {
        String province = provinceCode;
        if (province == null || province.isBlank()) {
            province = ChatToolContext.from(toolContext).context().provinceCode();
        }
        if (province == null || province.isBlank()) {
            province = DEFAULT_PROVINCE;
        }
        return results.compact(market.market(province.strip().toUpperCase(Locale.ROOT)), 30);
    }

    /**
     * Summarizes what a crop does for the soil, from its reference data.
     *
     * @param crop the reference entry
     * @return id, name, family and soil roles
     */
    static Map<String, Object> summary(CropReference crop) {
        CropReference.Requirements needs = crop.requirements();
        CropReference.Estimates estimates = crop.estimates();
        boolean fixesNitrogen = estimates != null && estimates.nFixKgHa() > 0;
        boolean perennial = needs != null && needs.perennial();
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", crop.id());
        summary.put("name", crop.name());
        summary.put("family", crop.family());
        summary.put("perennial", perennial);
        summary.put("fixesNitrogen", fixesNitrogen);
        summary.put("rootingDepth", needs == null ? null : needs.rootingDepth());
        summary.put("coverMonths", estimates == null ? null : estimates.coverMonths());
        summary.put("residueScore", estimates == null ? null : estimates.residueScore());
        summary.put("roles", roles(fixesNitrogen, perennial, needs, estimates));
        return summary;
    }

    /**
     * Describes a crop's soil roles in plain words.
     *
     * @param fixesNitrogen whether it fixes nitrogen
     * @param perennial     whether it is perennial
     * @param needs         its requirements, or {@code null}
     * @param estimates     its estimates, or {@code null}
     * @return short role descriptions
     */
    private static List<String> roles(boolean fixesNitrogen, boolean perennial, CropReference.Requirements needs,
                                      CropReference.Estimates estimates) {
        List<String> roles = new ArrayList<>();
        if (fixesNitrogen) {
            roles.add("nitrogen-fixing: adds nitrogen for the next crop and cuts fertilizer needs");
        }
        if (perennial) {
            roles.add("perennial: covers the ground year-round, limiting erosion and building soil structure");
        }
        if (needs != null && needs.rootingDepth() != null && needs.rootingDepth().toLowerCase(Locale.ROOT).contains("deep")) {
            roles.add("deep-rooting: reaches deeper water and nutrients and opens channels that improve infiltration");
        }
        if (estimates != null && estimates.residueScore() >= 0.6) {
            roles.add("high residue: returns plenty of organic matter to the soil");
        }
        if (estimates != null && estimates.coverMonths() >= 8) {
            roles.add("long ground cover: protects soil from erosion for most of the year");
        }
        if (needs != null && needs.rainMinMm() != null && needs.rainMinMm() <= 300) {
            roles.add("tolerates low rainfall (FAO ECOCROP minimum " + Math.round(needs.rainMinMm()) + " mm a year)");
        }
        return roles;
    }
}
