package org.greenalienhouseparty.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.chat.tools.CropTools;
import org.greenalienhouseparty.chat.tools.FieldDataTools;
import org.greenalienhouseparty.chat.tools.KnowledgeTools;
import org.greenalienhouseparty.chat.tools.LocationTools;
import org.greenalienhouseparty.chat.tools.PlanningTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * The full set of tools the assistant may call, each wrapped so its calls are reported in the reply stream.
 *
 * <p>Tools are the {@code @Tool} methods of {@link FieldDataTools}, {@link LocationTools}, {@link CropTools},
 * {@link PlanningTools} and {@link KnowledgeTools}, turned into Spring AI {@link ToolCallback}s and wrapped in
 * {@link EventPublishingToolCallback} with a plain-language label for the app to show while each one runs.
 * The tools are deliberately not Spring {@code ToolCallback} beans, so they are only offered to the
 * assistant's own chat clients.</p>
 */
@Component
public class ChatToolRegistry {

    /** Plain-language label for each tool, shown while it runs. */
    static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("getFieldOverview", "Gathering what is known about the field"),
            Map.entry("getFieldSoil", "Checking soil estimates"),
            Map.entry("getFieldClimate", "Reading the climate record"),
            Map.entry("getCropHistory", "Looking up past crops"),
            Map.entry("getAgroWeather", "Checking soil wetness and evaporation"),
            Map.entry("getVegetationTrend", "Reading years of satellite greenness (this can take a minute)"),
            Map.entry("getSeasonGreenness", "Reading this season's satellite images (this can take a minute)"),
            Map.entry("getDroughtIndicators", "Checking groundwater and drought (this can take a minute)"),
            Map.entry("getSoilLandscape", "Checking regional soil maps"),
            Map.entry("getSeasonalOutlook", "Checking the seasonal outlook"),
            Map.entry("getDailyWeather", "Reading daily weather"),
            Map.entry("findFieldsNear", "Finding nearby fields"),
            Map.entry("listCrops", "Listing crops"),
            Map.entry("getCropProfile", "Looking up crop requirements"),
            Map.entry("getCropCalendar", "Checking usual planting dates"),
            Map.entry("getMarketPrices", "Checking yields and prices"),
            Map.entry("assessCropSuitability", "Checking which crops suit the field"),
            Map.entry("proposeRotationPlan", "Planning a rotation"),
            Map.entry("compareRotations", "Comparing rotations"),
            Map.entry("findBareFieldWindows", "Finding when the field is bare"),
            Map.entry("explainTerm", "Looking up a definition"),
            Map.entry("getDataSources", "Checking data sources"),
            Map.entry("getCurrentDate", "Checking the date"));

    private final List<ToolCallback> callbacks;

    /**
     * Builds the tool set.
     *
     * @param fieldData    field data tools
     * @param location     location tools
     * @param crops        crop tools
     * @param planning     planning tools
     * @param knowledge    reference tools
     * @param objectMapper parses tool arguments for progress events
     */
    public ChatToolRegistry(FieldDataTools fieldData, LocationTools location, CropTools crops,
                            PlanningTools planning, KnowledgeTools knowledge, ObjectMapper objectMapper) {
        List<ToolCallback> wrapped = new ArrayList<>();
        for (ToolCallback callback : ToolCallbacks.from(fieldData, location, crops, planning, knowledge)) {
            String name = callback.getToolDefinition().name();
            wrapped.add(new EventPublishingToolCallback(callback, LABELS.getOrDefault(name, "Looking up " + name), objectMapper));
        }
        this.callbacks = List.copyOf(wrapped);
    }

    /**
     * Returns the assistant's tools.
     *
     * @return the wrapped tool callbacks
     */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }
}
