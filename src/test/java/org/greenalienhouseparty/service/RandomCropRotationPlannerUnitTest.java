package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the temporary random crop planner.
 */
class RandomCropRotationPlannerUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    /**
     * Verifies the placeholder produces five years using only the farmer's
     * growable crops.
     *
     * @throws Exception if the field geometry fixture cannot be parsed
     */
    @Test
    void createsFiveAssignmentsFromGrowableCrops() throws Exception {
        var field = field(List.of("Soybeans", "Wheat"), null);
        var planner = new RandomCropRotationPlanner(new Random(42), FIXED_CLOCK);

        CropOptimizationResponse.FieldPlan plan = planner.plan(field);

        assertEquals("field-1", plan.fieldId());
        assertEquals("Corn", plan.currentCrop());
        assertEquals(5, plan.years().size());
        assertEquals(List.of(2027, 2028, 2029, 2030, 2031),
                plan.years().stream().map(CropOptimizationResponse.PlannedYear::year).toList());
        assertTrue(plan.years().stream().allMatch(year -> field.growableCrops().contains(year.crop())));
    }

    /**
     * Verifies a later planting-ready date moves the start of the five-year
     * placeholder horizon forward.
     *
     * @throws Exception if the field geometry fixture cannot be parsed
     */
    @Test
    void startsAtLaterReadyDateYear() throws Exception {
        var field = field(List.of("Oats"), LocalDate.of(2030, 4, 15));
        var planner = new RandomCropRotationPlanner(new Random(1), FIXED_CLOCK);

        CropOptimizationResponse.FieldPlan plan = planner.plan(field);

        assertEquals(List.of(2030, 2031, 2032, 2033, 2034),
                plan.years().stream().map(CropOptimizationResponse.PlannedYear::year).toList());
        assertTrue(plan.years().stream().allMatch(year -> "Oats".equals(year.crop())));
    }

    /**
     * Builds a test field input with a valid GeoJSON polygon.
     *
     * @param growableCrops farmer-selected crops
     * @param readyDate     optional planting-ready date
     * @return crop-planning request for one field
     * @throws Exception if the geometry fixture cannot be parsed
     */
    private CropOptimizationRequest.FieldRequest field(List<String> growableCrops, LocalDate readyDate)
            throws Exception {
        var geometry = JSON.readTree("""
                {"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}
                """);
        return new CropOptimizationRequest.FieldRequest("field-1", geometry, "Corn", growableCrops, readyDate);
    }
}
