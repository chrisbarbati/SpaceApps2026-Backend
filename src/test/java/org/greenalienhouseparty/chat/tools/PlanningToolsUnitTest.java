package org.greenalienhouseparty.chat.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.chat.ChatEventSink;
import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.dto.ChatEvent;
import org.greenalienhouseparty.dto.ChatRequest;
import org.greenalienhouseparty.dto.CropCalendarResponse;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.FieldDetailsResponse;
import org.greenalienhouseparty.planning.CropOptimizationService;
import org.greenalienhouseparty.planning.FieldInputService;
import org.greenalienhouseparty.planning.NormalizedField;
import org.greenalienhouseparty.planning.PlannerInputNormalizer;
import org.greenalienhouseparty.planning.ReferenceCropRepository;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.service.DataBackendService;
import org.greenalienhouseparty.service.FieldDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link PlanningTools} with the real crop catalog and mocked field data.
 */
class PlanningToolsUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JsonNode OUTLINE = JSON.readTree(
            "{\"type\":\"Polygon\",\"coordinates\":[[[-79.4,44.6],[-79.39,44.6],[-79.39,44.61],[-79.4,44.6]]]}");

    private final FieldDetailsService details = mock(FieldDetailsService.class);

    private final PlannerInputNormalizer normalizer = mock(PlannerInputNormalizer.class);

    private final CropOptimizationService optimization = mock(CropOptimizationService.class);

    private final DataBackendService dataBackend = mock(DataBackendService.class);

    private final ChatEventSink sink = new ChatEventSink();

    private PlanningTools tools;

    @BeforeEach
    void setUp() {
        ReferenceCropRepository crops = new ReferenceCropRepository(JSON);
        FieldInputService inputs = new FieldInputService(details, normalizer, crops);
        ToolResults results = new ToolResults(JSON);
        tools = new PlanningTools(inputs, optimization, dataBackend, new FieldLocator(dataBackend), results);

        when(details.details("field-7", null)).thenReturn(new FieldDetailsResponse("field-7", 10.0, 24.7, null,
                null, null, null, null, null, null, null, null, null, null, Map.of()));
        stubFieldWithPh(6.5);
    }

    @Test
    void ratesCropsForTheSelectedFieldBestFirst() {
        stubFieldWithPh(4.0);
        JsonNode result = tools.assessCropSuitability(null, List.of("corn", "alfalfa"), context(null));

        assertThat(result.get("fieldId").asString()).isEqualTo("field-7");
        assertThat(result.get("assumptions").get(0).asString()).isEqualTo("No climate record was available.");
        JsonNode crops = result.get("crops");
        assertThat(crops).hasSize(2);
        // Soil pH 4.0 is below both crops' ranges in the reference data
        for (JsonNode crop : crops) {
            assertThat(crop.get("verdict").asString()).isEqualTo("not suited");
            assertThat(crop.get("limitedBy").asString()).isEqualTo("soil pH");
            assertThat(crop.get("checks").get("temperature").asString()).isEqualTo("unchecked");
        }
        assertThatThrownBy(() -> tools.assessCropSuitability(null, List.of("rice"), context(null)))
                .hasMessageContaining("is a crop this app knows");
    }

    @Test
    void proposesAPlanForApprovalUsingTheAppsFieldOutlineAndCrops() {
        CropOptimizationResponse.FieldPlan plan = new CropOptimizationResponse.FieldPlan("field-7", "Corn", List.of(
                new CropOptimizationResponse.PlannedYear(2027, "Soybeans"),
                new CropOptimizationResponse.PlannedYear(2028, "Wheat")), null, null, List.of("Assumed neutral pH."));
        when(optimization.optimize(any())).thenReturn(
                new CropOptimizationResponse(List.of(plan), "random-placeholder", true, "Placeholder plan."));

        JsonNode result = tools.proposeRotationPlan(null, null, null, context(OUTLINE));

        ArgumentCaptor<CropOptimizationRequest> request = ArgumentCaptor.forClass(CropOptimizationRequest.class);
        verify(optimization).optimize(request.capture());
        CropOptimizationRequest.FieldRequest sent = request.getValue().fields().getFirst();
        assertThat(sent.fieldId()).isEqualTo("field-7");
        assertThat(sent.currentCrop()).isEqualTo("corn");
        assertThat(sent.growableCrops()).containsExactly("corn", "soybeans", "wheat");
        assertThat(sent.geometry()).isEqualTo(OUTLINE);

        ChatEvent.Proposal proposal = sink.proposals().getFirst();
        assertThat(proposal.fieldId()).isEqualTo("field-7");
        assertThat(proposal.title()).isEqualTo("Five-year rotation for North 40");
        assertThat(proposal.summary()).isEqualTo("Soybeans → Wheat");
        assertThat(proposal.reviewNotes()).startsWith("Assumed neutral pH.").containsAll(PlanningTools.STANDARD_REVIEW_NOTES);
        assertThat(result.get("proposalId").asString()).isEqualTo(proposal.proposalId());
        assertThat(result.get("status").asString()).contains("Not applied");
    }

    @Test
    void asksForTheCurrentCropWhenItIsUnknown() {
        ToolContext noCrop = new ToolContext(ChatToolContext.toMap(new ChatRequest.Context("field-9", List.of(), null,
                null, List.of()), sink, "c-1"));

        assertThatThrownBy(() -> tools.proposeRotationPlan(null, null, null, noCrop))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ask the farmer what is growing there now");
    }

    @Test
    void comparesTheFarmersRotationsWithTheOptimizedOne() {
        JsonNode result = tools.compareRotations(null, List.of(
                new PlanningTools.RotationOption("Continuous corn", List.of("corn")),
                new PlanningTools.RotationOption("Corn-soy", List.of("Corn", "Soybeans"))), null, context(null));

        JsonNode strategies = result.get("strategies");
        assertThat(strategies).hasSize(3);
        assertThat(strategies.findValuesAsString("name"))
                .containsExactlyInAnyOrder("Continuous corn", "Corn-soy", "Optimized for soil health and reliability");
        for (JsonNode strategy : strategies) {
            assertThat(strategy.get("years")).hasSize(5);
        }
        double first = strategies.get(0).get("totalSoilScore").asDouble();
        double last = strategies.get(2).get("totalSoilScore").asDouble();
        assertThat(first).isGreaterThanOrEqualTo(last);
        assertThatThrownBy(() -> tools.compareRotations(null, List.of(new PlanningTools.RotationOption("x", List.of("rice"))),
                false, context(null))).hasMessageContaining("'rice' is not a crop this app knows");
    }

    @Test
    void comparesContinuingTheCurrentCropWhenNoRotationIsGiven() {
        JsonNode result = tools.compareRotations(null, null, false, context(null));

        assertThat(result.get("strategies")).hasSize(1);
        assertThat(result.get("strategies").get(0).get("name").asString()).isEqualTo("Keep growing corn");
    }

    @Test
    void findsBareWindowsFromTheCropCalendar() {
        when(dataBackend.fieldCropCalendar("field-7", null, null)).thenReturn(new CropCalendarResponse(null, null, null, "rainfed", List.of(
                new CropCalendarResponse.CropSeason("corn", "Corn", 130, "May 10", 280, "Oct 7", 150, 215, false, "test")),
                null, null));

        JsonNode result = tools.findBareFieldWindows(null, false, context(null));

        assertThat(result.get("currentCrop").asString()).isEqualTo("corn");
        JsonNode window = result.get("calendarWindows").get(0);
        assertThat(window.get("crop").asString()).isEqualTo("Corn");
        assertThat(window.get("bareDays").asInt()).isEqualTo(215);
        assertThat(window.get("bareFrom").asString()).isEqualTo("Oct 7");
        assertThat(result.has("satellite")).isFalse();
    }

    @Test
    void repeatsARotationCycleToFillThePlan() {
        assertThat(PlanningTools.repeat(List.of("a", "b"), 5)).containsExactly("a", "b", "a", "b", "a");
    }

    /**
     * Makes field-7 a 10 ha loam field with the given soil pH, no climate record and corn then soybeans before.
     *
     * @param ph estimated soil pH
     */
    private void stubFieldWithPh(double ph) {
        Field field = new Field("field-7", 44.6, -79.4, 10, ph, "Loam", 3.0, 0.5, List.of("corn", "soybeans"));
        when(normalizer.toField(eq("field-7"), any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new NormalizedField(field, List.of("No climate record was available.")));
    }

    /**
     * Builds the tool context: field-7 ("North 40", corn) selected, growing corn, soybeans and wheat.
     *
     * @param outline the outline the app sent, or {@code null}
     * @return the tool context
     */
    private ToolContext context(JsonNode outline) {
        ChatRequest.Context context = new ChatRequest.Context("field-7",
                List.of(new ChatRequest.FieldContext("field-7", "North 40", "corn", outline)), null, "ON",
                List.of("corn", "soybeans", "wheat"));
        return new ToolContext(ChatToolContext.toMap(context, sink, "c-1"));
    }
}
