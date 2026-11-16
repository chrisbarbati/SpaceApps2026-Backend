package org.greenalienhouseparty.chat.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.dto.ChatEvent;
import org.greenalienhouseparty.dto.ChatRequest;
import org.greenalienhouseparty.dto.CropCalendarResponse;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.VegetationHistoryResponse;
import org.greenalienhouseparty.planning.CropOptimizationService;
import org.greenalienhouseparty.planning.CropSuitabilityAssessor;
import org.greenalienhouseparty.planning.CropSuitabilityAssessor.Fit;
import org.greenalienhouseparty.planning.CropSuitabilityAssessor.Reliability;
import org.greenalienhouseparty.planning.FieldInputService;
import org.greenalienhouseparty.planning.NormalizedField;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldPlan;
import org.greenalienhouseparty.planning.model.YearPlan;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assistant's crop-planning tools: which crops suit a field, a rotation plan for the farmer to approve,
 * a comparison of rotation strategies, and when a field is usually bare.
 *
 * <p>All calculations are the application's own: suitability uses {@link CropSuitabilityAssessor} and
 * comparisons use the optimizer's scoring, built from the same field input as {@code POST /crop-optimization}
 * (see {@link FieldInputService}). The model explains the results; it does not compute them.</p>
 */
@Component
public class PlanningTools {

    private static final String FIELD_ID_DESCRIPTION =
            "Field id. Leave empty to use the field the farmer has selected in the app.";

    /** Notes added to every proposal: what a person should check before accepting it. */
    static final List<String> STANDARD_REVIEW_NOTES = List.of(
            "Check the plan against your own records of what was grown, yields and problem areas in this field.",
            "Confirm seed availability, equipment, storage and buyers for each crop before committing.",
            "Review herbicide carry-over and planting restrictions between crops with an agronomist.",
            "Soil values are satellite and model estimates; a soil test will be more accurate.");

    private final FieldInputService fieldInputs;

    private final CropOptimizationService optimization;

    private final DataBackendService dataBackend;

    private final FieldLocator locator;

    private final ToolResults results;

    /**
     * Creates the tools.
     *
     * @param fieldInputs  builds planner input for a stored field
     * @param optimization the configured crop planner
     * @param dataBackend  access to the Python data backend
     * @param locator      finds field outlines
     * @param results      builds JSON for the model
     */
    public PlanningTools(FieldInputService fieldInputs, CropOptimizationService optimization,
                         DataBackendService dataBackend, FieldLocator locator, ToolResults results) {
        this.fieldInputs = fieldInputs;
        this.optimization = optimization;
        this.dataBackend = dataBackend;
        this.locator = locator;
        this.results = results;
    }

    /**
     * One rotation to compare.
     *
     * @param name  short name, for example "Current corn-soybean"
     * @param crops crop ids or names for years 1, 2, 3 and so on
     */
    public record RotationOption(
            @ToolParam(description = "Short name, for example 'Current corn-soybean'") String name,
            @ToolParam(description = "Crop ids or names for each year in order, for example ['corn','soybeans']")
            List<String> crops) {
    }

    /**
     * Rates how well each crop suits a field's climate record and soil.
     *
     * @param fieldId     field id, or {@code null} for the selected field
     * @param crops       crops to check, or {@code null} for the farmer's growable crops or every crop
     * @param toolContext the chat context
     * @return each crop's verdict, reliability, limiting condition and individual fits, best first
     */
    @Tool(name = "assessCropSuitability", description = """
            Rates how well crops suit one field's long-term climate and soil: soil pH, growing-season \
            temperature, share of past years with suitable rainfall, frost-free season length, soil texture, \
            drainage and land capability. Returns a verdict and reliability (0-1) per crop, best first, the \
            condition that limits each crop, and the assumptions made for missing data. Use it for 'what can I \
            grow here?' questions. It does not consider markets, equipment or rotation effects.""")
    public JsonNode assessCropSuitability(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Crop ids or names to check. Leave empty for the farmer's growable crops, "
                    + "or every crop if none are set.", required = false) List<String> crops,
            ToolContext toolContext) {
        ChatToolContext chat = ChatToolContext.from(toolContext);
        String id = chat.resolveFieldId(fieldId);
        NormalizedField input = load(chat, id);
        Field field = input.field();
        CropSuitabilityAssessor assessor = fieldInputs.suitability();

        Collection<Crop> candidates = cropsToCheck(crops, chat.growableCrops());
        List<Map<String, Object>> ratings = new ArrayList<>();
        for (Crop crop : candidates) {
            Reliability reliability = assessor.assessReliability(field, crop, 0);
            boolean phOk = assessor.acidityTolerated(field, crop);
            Map<String, Object> rating = new LinkedHashMap<>();
            rating.put("cropId", crop.id());
            rating.put("crop", crop.name());
            rating.put("verdict", verdict(phOk, reliability));
            rating.put("reliability", round(reliability.value()));
            rating.put("limitedBy", !phOk ? "soil pH" : reliability.value() >= 0.995 ? null : reliability.limitedBy());
            rating.put("explanation", explanation(field, crop, phOk, reliability, assessor));
            rating.put("checks", checks(field, crop, phOk, reliability));
            ratings.add(rating);
        }
        ratings.sort(Comparator.comparingDouble((Map<String, Object> rating) -> (double) rating.get("reliability"))
                .reversed());

        ObjectNode result = results.object();
        result.put("fieldId", id);
        result.set("crops", results.json(ratings));
        result.set("assumptions", results.json(input.assumptions()));
        result.put("note", "Based on the long-term climate record, satellite soil estimates and regional soil "
                + "maps. A check with no data counts as suitable, so 'unchecked' items need local confirmation.");
        return result;
    }

    /**
     * Plans a five-year rotation with the configured planner and sends it to the app for approval.
     *
     * @param fieldId       field id, or {@code null} for the selected field
     * @param currentCrop   crop in the field now, or {@code null} for the one set in the app
     * @param growableCrops crops the farmer can grow, or {@code null} for the ones set in the app
     * @param toolContext   the chat context
     * @return the proposal id and plan, for the model to explain
     */
    @Tool(name = "proposeRotationPlan", description = """
            Plans a five-year crop rotation for one field with this app's planner and sends it to the farmer \
            as a proposal they can accept or reject in the app. Nothing is changed unless they accept it. Use \
            it when the farmer asks for a recommendation or plan. Then explain the plan, why each crop was \
            chosen, and what should be checked locally; never say it has been applied.""")
    public JsonNode proposeRotationPlan(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Crop in the field now. Leave empty to use the one set in the app.",
                    required = false) String currentCrop,
            @ToolParam(description = "Crop ids or names the farmer can grow. Leave empty to use the ones set in "
                    + "the app.", required = false) List<String> growableCrops,
            ToolContext toolContext) {
        ChatToolContext chat = ChatToolContext.from(toolContext);
        String id = chat.resolveFieldId(fieldId);
        String current = firstNonBlank(currentCrop, chat.field(id).map(ChatRequest.FieldContext::currentCrop).orElse(null));
        if (current == null) {
            throw new IllegalArgumentException("The crop currently in this field is not known. Ask the farmer what "
                    + "is growing there now (or 'Nothing planted'), then try again.");
        }
        List<String> growable = growableCrops != null && !growableCrops.isEmpty() ? growableCrops : chat.growableCrops();
        if (growable.isEmpty()) {
            growable = fieldInputs.crops().stream().map(Crop::id).toList();
        }
        JsonNode outline = locator.outline(chat, id);
        CropOptimizationResponse response = optimization.optimize(new CropOptimizationRequest(List.of(
                new CropOptimizationRequest.FieldRequest(id, outline, current, growable, null))));
        CropOptimizationResponse.FieldPlan plan = response.plans().getFirst();

        String fieldName = chat.field(id).map(ChatRequest.FieldContext::name).filter(name -> !name.isBlank()).orElse(id);
        String sequence = String.join(" → ", plan.years().stream().map(CropOptimizationResponse.PlannedYear::crop).toList());
        List<String> reviewNotes = new ArrayList<>();
        if (plan.assumptions() != null) {
            reviewNotes.addAll(plan.assumptions());
        }
        reviewNotes.addAll(STANDARD_REVIEW_NOTES);
        ChatEvent.Proposal proposal = new ChatEvent.Proposal(
                UUID.randomUUID().toString(),
                id,
                "Five-year rotation for " + fieldName,
                sequence,
                plan,
                response.planner(),
                response.temporary(),
                response.disclaimer(),
                List.copyOf(reviewNotes));
        chat.emit(proposal);

        ObjectNode result = results.object();
        result.put("proposalId", proposal.proposalId());
        result.put("status", "Shown to the farmer to accept or reject. Not applied.");
        result.put("rotation", sequence);
        result.set("plan", results.json(plan));
        result.put("planner", response.planner());
        result.put("temporary", response.temporary());
        result.put("disclaimer", response.disclaimer());
        result.set("reviewNotes", results.json(reviewNotes));
        return result;
    }

    /**
     * Scores several rotation strategies for a field side by side.
     *
     * @param fieldId         field id, or {@code null} for the selected field
     * @param rotations       rotations to compare, such as the existing one and alternatives
     * @param includeOptimized whether to add the optimizer's best rotation, {@code null} for yes
     * @param toolContext     the chat context
     * @return each strategy's yearly crops, scores, reasons and totals, best soil score first
     */
    @Tool(name = "compareRotations", description = """
            Compares crop rotation strategies for one field using this app's scoring: soil health (pest and \
            disease breaks, nitrogen balance, organic matter, erosion cover), reliability in this field's \
            climate and soil, and an estimated income. Pass the farmer's existing rotation and any \
            alternatives; the optimizer's best rotation is added unless includeOptimized is false. If no \
            rotations are given, continuing the current crop every year is compared with the optimized \
            rotation. Returns per-year reasons and totals so you can explain benefits, trade-offs and risks.""")
    public JsonNode compareRotations(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Rotations to compare", required = false) List<RotationOption> rotations,
            @ToolParam(description = "Add the optimizer's best rotation. Default true.", required = false)
            Boolean includeOptimized,
            ToolContext toolContext) {
        ChatToolContext chat = ChatToolContext.from(toolContext);
        String id = chat.resolveFieldId(fieldId);
        NormalizedField input = load(chat, id);
        Field field = input.field();
        int years = fieldInputs.planningYears();

        Map<String, FieldPlan> strategies = new LinkedHashMap<>();
        if (rotations != null) {
            for (RotationOption option : rotations) {
                if (option == null || option.crops() == null || option.crops().isEmpty()) {
                    continue;
                }
                List<String> sequence = resolveSequence(option.crops());
                strategies.put(nameOr(option.name(), "Rotation " + (strategies.size() + 1)),
                        fieldInputs.planner().evaluateSequence(field, repeat(sequence, years)));
            }
        }
        if (strategies.isEmpty()) {
            String current = chat.field(id).map(ChatRequest.FieldContext::currentCrop).orElse(null);
            List<String> currentIds = current == null ? List.of() : resolveSequence(List.of(current));
            if (!currentIds.isEmpty()) {
                strategies.put("Keep growing " + current, fieldInputs.planner().evaluateSequence(field, repeat(currentIds, years)));
            }
        }
        if (includeOptimized == null || includeOptimized) {
            Set<String> growable = fieldInputs.resolveCropIds(chat.growableCrops());
            if (growable.isEmpty()) {
                growable = fieldInputs.resolveCropIds(fieldInputs.crops().stream().map(Crop::id).toList());
            }
            strategies.put("Optimized for soil health and reliability",
                    fieldInputs.planner().planField(field, growable, FieldInputService.DEFAULT_WEIGHTS));
        }
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("Nothing to compare. Pass at least one rotation, or set the current "
                    + "crop in the app.");
        }

        int firstYear = fieldInputs.firstPlanYear();
        List<Map<String, Object>> compared = new ArrayList<>();
        strategies.forEach((name, plan) -> compared.add(describe(name, plan, firstYear)));
        compared.sort(Comparator.comparingDouble((Map<String, Object> strategy) -> (double) strategy.get("totalSoilScore"))
                .reversed());

        ObjectNode result = results.object();
        result.put("fieldId", id);
        result.put("firstYear", firstYear);
        result.set("strategies", results.json(compared));
        result.set("assumptions", results.json(input.assumptions()));
        result.put("scoring", "Soil score per year is roughly -0.6 to +0.6: disease break 40%, nitrogen balance "
                + "25%, organic matter 20%, erosion cover 15%. Reliability is 0-1, the weakest of the climate and "
                + "soil checks. Nitrogen, residue, disease and income figures are illustrative estimates, so "
                + "income is only a rough comparison. Water use and markets are not scored yet.");
        return result;
    }

    /**
     * Finds when a field is usually bare, from the crop calendar and optionally satellite greenness.
     *
     * @param fieldId          field id, or {@code null} for the selected field
     * @param includeSatellite whether to read MODIS greenness history (slow), {@code null} for no
     * @param toolContext      the chat context
     * @return off-season windows for each crop and, when asked, observed low-cover windows
     */
    @Tool(name = "findBareFieldWindows", description = """
            Finds when the ground in one field is usually bare: for each common crop, the gap between its \
            usual harvest and the next planting from the crop calendar, and the crop grown now. With \
            includeSatellite=true it also reads several years of MODIS greenness (SLOW, 20-45 seconds the \
            first time) for windows when the field was actually low in green cover. Use it to suggest cover \
            crops or double crops.""")
    public JsonNode findBareFieldWindows(
            @ToolParam(description = FIELD_ID_DESCRIPTION, required = false) String fieldId,
            @ToolParam(description = "Also read satellite greenness history (slow). Default false.", required = false)
            Boolean includeSatellite,
            ToolContext toolContext) {
        ChatToolContext chat = ChatToolContext.from(toolContext);
        String id = chat.resolveFieldId(fieldId);
        ObjectNode result = results.object();
        result.put("fieldId", id);
        chat.field(id).map(ChatRequest.FieldContext::currentCrop).ifPresent(crop -> result.put("currentCrop", crop));

        CropCalendarResponse calendar = dataBackend.fieldCropCalendar(id, null, null);
        ArrayNode windows = result.putArray("calendarWindows");
        if (calendar != null && calendar.crops() != null) {
            for (CropCalendarResponse.CropSeason season : calendar.crops()) {
                ObjectNode window = windows.addObject();
                window.put("crop", season.label() != null ? season.label() : season.crop());
                window.put("plantingDate", season.plantingDate());
                window.put("maturityDate", season.maturityDate());
                if (season.offSeasonDays() != null) {
                    window.put("bareDays", season.offSeasonDays());
                }
                window.put("bareFrom", season.maturityDate());
                window.put("bareUntil", season.plantingDate());
            }
        }

        if (Boolean.TRUE.equals(includeSatellite)) {
            VegetationHistoryResponse history = dataBackend.fieldVegetationHistory(id, null);
            if (history != null && history.ndvi() != null) {
                ObjectNode satellite = result.putObject("satellite");
                satellite.set("lowCoverWindows", results.json(history.ndvi().lowCoverWindows()));
                satellite.set("typical", results.json(history.ndvi().typical()));
                satellite.put("lowCoverThreshold", history.ndvi().lowCoverThreshold());
                satellite.set("byYear", results.compact(history.ndvi().byYear(), 10));
            }
        }
        result.put("note", "Calendar dates are typical for the area, not this field's records. Bare windows after "
                + "harvest are candidates for a cover crop (for example cereal rye or oats) or a double crop; "
                + "check seeding dates, herbicide restrictions and moisture locally.");
        return result;
    }

    /**
     * Loads a field's planner input, with the current crop from the app when known.
     *
     * @param chat the chat context
     * @param id   field identifier
     * @return the planner input
     */
    private NormalizedField load(ChatToolContext chat, String id) {
        String current = chat.field(id).map(ChatRequest.FieldContext::currentCrop).orElse(null);
        return fieldInputs.load(id, current, chat.growableCrops()).field();
    }

    /**
     * Picks the crops to rate: the ones asked for, else the farmer's growable crops, else the whole catalog.
     *
     * @param requested crops the model asked about
     * @param growable  crops the farmer can grow
     * @return the crops
     * @throws IllegalArgumentException when a requested crop is unknown
     */
    private Collection<Crop> cropsToCheck(List<String> requested, List<String> growable) {
        List<Crop> catalog = fieldInputs.crops();
        if (requested != null && !requested.isEmpty()) {
            Set<String> ids = fieldInputs.resolveCropIds(requested);
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("None of " + requested + " is a crop this app knows. Known crops: "
                        + catalog.stream().map(Crop::id).toList());
            }
            return catalog.stream().filter(crop -> ids.contains(crop.id())).toList();
        }
        Set<String> growableIds = fieldInputs.resolveCropIds(growable);
        return growableIds.isEmpty() ? catalog : catalog.stream().filter(crop -> growableIds.contains(crop.id())).toList();
    }

    /**
     * Resolves crop ids or names to catalog ids, keeping order and repeats.
     *
     * @param crops crop ids or names
     * @return catalog ids
     * @throws IllegalArgumentException when a crop is unknown
     */
    private List<String> resolveSequence(List<String> crops) {
        List<String> ids = new ArrayList<>();
        for (String crop : crops) {
            Set<String> resolved = fieldInputs.resolveCropIds(List.of(crop));
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException("'" + crop + "' is not a crop this app knows. Use listCrops for the "
                        + "known crop ids.");
            }
            ids.add(resolved.iterator().next());
        }
        return ids;
    }

    /**
     * Repeats a rotation cycle until it fills the planning horizon.
     *
     * @param cycle crop ids of one cycle
     * @param years years to fill
     * @return crop ids for each year
     */
    static List<String> repeat(List<String> cycle, int years) {
        List<String> sequence = new ArrayList<>(years);
        for (int year = 0; year < Math.max(years, cycle.size()); year++) {
            sequence.add(cycle.get(year % cycle.size()));
        }
        return sequence;
    }

    /**
     * Describes one compared strategy.
     *
     * @param name      strategy name
     * @param plan      the scored sequence
     * @param firstYear calendar year of plan year 1
     * @return the description
     */
    private static Map<String, Object> describe(String name, FieldPlan plan, int firstYear) {
        double reliabilityTotal = 0;
        YearPlan weakest = null;
        List<Map<String, Object>> years = new ArrayList<>();
        for (YearPlan year : plan.years()) {
            reliabilityTotal += year.reliability();
            if (weakest == null || year.soilScore() + year.reliability() < weakest.soilScore() + weakest.reliability()) {
                weakest = year;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("year", firstYear + year.year() - 1);
            entry.put("crop", year.cropName());
            entry.put("soilScore", round(year.soilScore()));
            entry.put("reliability", round(year.reliability()));
            entry.put("incomePerHaEstimate", Math.round(year.incomePerHa()));
            entry.put("reasons", year.reasons());
            years.add(entry);
        }
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("name", name);
        strategy.put("rotation", String.join(" → ", plan.years().stream().map(YearPlan::cropName).toList()));
        strategy.put("totalSoilScore", round(plan.totalSoilScore()));
        strategy.put("averageReliability", plan.years().isEmpty() ? 0 : round(reliabilityTotal / plan.years().size()));
        strategy.put("totalIncomeEstimate", Math.round(plan.totalIncome()));
        strategy.put("weakestYear", weakest == null ? null : firstYear + weakest.year() - 1);
        strategy.put("years", years);
        return strategy;
    }

    /**
     * Gives a crop's suitability verdict in words.
     *
     * @param phOk        whether the soil pH is tolerated
     * @param reliability the crop's reliability
     * @return the verdict
     */
    static String verdict(boolean phOk, Reliability reliability) {
        if (!phOk || reliability.value() <= 0) {
            return "not suited";
        }
        if (reliability.value() < 0.6) {
            return "risky";
        }
        if (reliability.value() < 0.9) {
            return "suited with some limits";
        }
        return "well suited";
    }

    /**
     * Explains a crop's verdict in a sentence.
     *
     * @param field       the field
     * @param crop        the crop
     * @param phOk        whether the soil pH is tolerated
     * @param reliability the crop's reliability
     * @param assessor    describes the limiting condition
     * @return the explanation
     */
    private static String explanation(Field field, Crop crop, boolean phOk, Reliability reliability,
                                      CropSuitabilityAssessor assessor) {
        if (!phOk) {
            return "Estimated soil pH " + field.ph() + " is outside the " + crop.phMin() + "-" + crop.phMax()
                    + " range " + crop.name().toLowerCase() + " tolerates.";
        }
        if (!reliability.known()) {
            return "No climate or soil data could be checked, so suitability is unknown.";
        }
        if (reliability.value() >= 0.9) {
            Fit rainfall = reliability.rainfall();
            return rainfall.known() && rainfall.years() > 0
                    ? "Suits this field's climate and soil; rainfall was within its range in " + rainfall.yearsTolerated()
                            + " of the last " + rainfall.years() + " years."
                    : "Suits what is known of this field's climate and soil.";
        }
        return (reliability.value() >= 0.6 ? "Suits this field, though " : "Less reliable here: ")
                + assessor.describeLimit(field, reliability);
    }

    /**
     * Lists each individual check for a crop.
     *
     * @param field       the field
     * @param crop        the crop
     * @param phOk        whether the soil pH is tolerated
     * @param reliability the crop's reliability
     * @return each check's fit, or "unchecked" when there was no data
     */
    private static Map<String, Object> checks(Field field, Crop crop, boolean phOk, Reliability reliability) {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("soilPh", phOk ? "ok (" + field.ph() + ")" : "outside range (" + field.ph() + ")");
        checks.put("temperature", fit(reliability.temperature()));
        checks.put("rainfall", reliability.rainfall().known() && reliability.rainfall().years() > 0
                ? round(reliability.rainfall().value()) + " (" + reliability.rainfall().yearsTolerated() + " of "
                        + reliability.rainfall().years() + " years tolerable)"
                : fit(reliability.rainfall()));
        checks.put("seasonLength", fit(reliability.seasonLength()));
        checks.put("soilTexture", fit(reliability.texture()));
        checks.put("drainage", fit(reliability.drainage()));
        checks.put("landClass", fit(reliability.landClass()));
        return checks;
    }

    /**
     * Writes one fit for the model.
     *
     * @param fit the fit
     * @return the value, or "unchecked"
     */
    private static Object fit(Fit fit) {
        return fit.known() ? round(fit.value()) : "unchecked";
    }

    /**
     * Returns the first value that is not blank.
     *
     * @param first  first choice
     * @param second second choice
     * @return the chosen value, or {@code null}
     */
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second == null || second.isBlank() ? null : second.strip();
    }

    /**
     * Returns a name, or a default when it is blank.
     *
     * @param name     the name
     * @param fallback the default
     * @return the name to use
     */
    private static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.strip();
    }

    /**
     * Rounds to two decimals.
     *
     * @param value the value
     * @return the rounded value
     */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
