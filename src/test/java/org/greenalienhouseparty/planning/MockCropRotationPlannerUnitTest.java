package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.CropOptimizationResponse.PlannedYear;
import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the demonstration crop planner.
 */
class MockCropRotationPlannerUnitTest {

    private static final Clock IN_2026 = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    private static final List<String> WIDE = List.of("Corn", "Soybeans", "Wheat", "Canola", "Potatoes", "Alfalfa");

    private final CropRepository crops = new ExampleCropRepository();

    private final MockCropRotationPlanner planner = new MockCropRotationPlanner(crops, IN_2026);

    /**
     * Verifies a plan covers the five years after the current one with only
     * growable crops, changes crop family every year, uses several crops,
     * includes a nitrogen-fixing crop, and fills scores, reliability and
     * reasons for every year.
     */
    @Test
    void buildsAVariedPlanFromTheGrowableCrops() {
        CropOptimizationResponse.FieldPlan plan = planner.plan(request("field-1", "Corn", WIDE));

        assertEquals("field-1", plan.fieldId());
        assertEquals("Corn", plan.currentCrop());
        assertEquals(List.of(2027, 2028, 2029, 2030, 2031), plan.years().stream().map(PlannedYear::year).toList());
        String previousFamily = crops.find("Corn").orElseThrow().family();
        Set<String> used = new HashSet<>();
        boolean fixesNitrogen = false;
        for (PlannedYear year : plan.years()) {
            assertTrue(WIDE.contains(year.crop()));
            var crop = crops.find(year.cropId()).orElseThrow();
            assertNotEquals(previousFamily, crop.family());
            previousFamily = crop.family();
            used.add(crop.id());
            fixesNitrogen = fixesNitrogen || crop.nFixKgHa() > 0;
            assertTrue(year.reliability() >= 0.72 && year.reliability() <= 0.97);
            assertTrue(year.soilScore() != null && year.incomePerHa() != null);
            assertTrue(!year.reasons().isEmpty());
        }
        assertTrue(used.size() >= 4);
        assertTrue(fixesNitrogen);
        assertEquals(plan.years().stream().mapToDouble(PlannedYear::soilScore).sum(), plan.totalSoilScore(), 1e-3);
        assertNull(plan.totalIncome());
    }

    /**
     * Verifies the same field always gets the same plan and a different
     * field gets a different one.
     */
    @Test
    void repeatsThePlanForAFieldAndVariesItBetweenFields() {
        CropOptimizationResponse.FieldPlan first = planner.plan(request("field-1", "Corn", WIDE));
        CropOptimizationResponse.FieldPlan again = planner.plan(request("field-1", "Corn", WIDE));
        CropOptimizationResponse.FieldPlan other = planner.plan(request("field-2", "Corn", WIDE));

        assertEquals(first, again);
        assertNotEquals(first.years(), other.years());
    }

    /**
     * Verifies a repeated crop family is cautioned when the list leaves no
     * other choice, and that a nitrogen-fixing crop is credited.
     */
    @Test
    void explainsEachChoiceFromTheCropCatalog() {
        List<PlannedYear> onlyCorn = planner.plan(request("field-1", "Corn", List.of("Corn"))).years();
        List<PlannedYear> afterCorn = planner.plan(request("field-1", "Corn", List.of("Soybeans"))).years();

        assertTrue(onlyCorn.getFirst().reasons().contains("Same crop as last year, so pest and disease risk is higher."));
        assertTrue(afterCorn.getFirst().reasons().contains("Adds nitrogen to the soil for the following crop."));
        assertTrue(afterCorn.getFirst().reasons()
                .contains("Follows corn, a different crop family, which helps break pest and disease cycles."));
    }

    /**
     * Verifies every plan is labelled as a demonstration, in the planner's
     * disclaimer and in the plan's own assumptions.
     */
    @Test
    void labelsPlansAsDemonstrations() {
        CropOptimizationResponse.FieldPlan plan = planner.plan(request("field-1", "Corn", WIDE));

        assertEquals("mock-demonstration", planner.id());
        assertTrue(planner.temporary());
        assertTrue(planner.disclaimer().startsWith("Demonstration plan only."));
        assertEquals(1, plan.assumptions().size());
        assertTrue(plan.assumptions().getFirst().startsWith("Demonstration plan:"));
        assertTrue(plan.years().stream().allMatch(year -> year.rationale().contains("illustrative")));
    }

    /**
     * Verifies a growable crop outside the catalog is rejected.
     */
    @Test
    void rejectsAnUnknownGrowableCrop() {
        assertThrows(CropOptimizationValidationException.class,
                () -> planner.plan(request("field-1", "Corn", List.of("Dragonfruit"))));
    }

    /**
     * Builds a planning request without a geometry, which this planner does not read.
     *
     * @param fieldId     field identifier
     * @param currentCrop crop growing this year
     * @param growable    crops the farmer can grow
     * @return the request
     */
    private static CropOptimizationRequest.FieldRequest request(String fieldId, String currentCrop, List<String> growable) {
        return new CropOptimizationRequest.FieldRequest(fieldId, null, currentCrop, growable, null);
    }
}
