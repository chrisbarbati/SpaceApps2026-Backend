package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldPlan;
import org.greenalienhouseparty.planning.model.Weights;
import org.greenalienhouseparty.planning.model.YearPlan;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the dynamic-programming rotation planner, using small
 * synthetic crops so expected outcomes do not depend on the example catalog.
 */
class DynamicCropRotationPlannerUnitTest {

    private static final Weights SOIL_ONLY = new Weights(1.0, 0.0);

    /** Three crops that differ only in family, so only disease pressure separates them. */
    private static final List<Crop> THREE_FAMILIES = List.of(
            crop("a", "family-a", 100),
            crop("b", "family-b", 100),
            crop("c", "family-c", 100));

    /**
     * Verifies that with three interchangeable families the planner never
     * plants a family grown in either of the two previous years, including the
     * years before the plan starts.
     */
    @Test
    void rotatesFamiliesAcrossHistoryAndPlan() {
        var planner = new DynamicCropRotationPlanner(THREE_FAMILIES, 5);

        FieldPlan plan = planner.planField(field(6.5, List.of("a", "b")), SOIL_ONLY);

        assertEquals(5, plan.years().size());
        assertEquals(List.of(1, 2, 3, 4, 5), plan.years().stream().map(YearPlan::year).toList());
        List<String> sequence = new java.util.ArrayList<>(List.of("b", "a"));
        plan.years().forEach(year -> sequence.add(year.cropId()));
        for (int i = 2; i < sequence.size(); i++) {
            assertNotEquals(sequence.get(i - 1), sequence.get(i));
            assertNotEquals(sequence.get(i - 2), sequence.get(i));
        }
    }

    /**
     * Verifies only growable crops are planted, while a history crop the
     * farmer will not grow again still counts against its own family.
     */
    @Test
    void plantsOnlyGrowableCropsButReadsHistoryFromTheWholeCatalog() {
        List<Crop> crops = List.of(
                crop("a", "family-a", 100),
                crop("a2", "family-a", 100),
                crop("b", "family-b", 100));
        var planner = new DynamicCropRotationPlanner(crops, 1);

        FieldPlan plan = planner.planField(field(6.5, List.of("a")), Set.of("a2", "b"), SOIL_ONLY);

        assertEquals("b", plan.years().getFirst().cropId());
    }

    /**
     * Verifies unknown history entries and the no-crop marker are treated as
     * no crop rather than failing the plan.
     */
    @Test
    void treatsUnknownHistoryAsNoCrop() {
        var planner = new DynamicCropRotationPlanner(THREE_FAMILIES, 5);

        FieldPlan plan = planner.planField(field(6.5, List.of(Field.NO_CROP, "dragonfruit")), SOIL_ONLY);
        FieldPlan withoutHistory = planner.planField(field(6.5, List.of()), SOIL_ONLY);

        assertEquals(5, plan.years().size());
        assertEquals(5, withoutHistory.years().size());
    }

    /**
     * Verifies income weighting changes the choice and that totals are the sum
     * of the yearly values, with income scaled by field area.
     */
    @Test
    void incomeWeightPrefersTheProfitableCropAndTotalsAddUp() {
        List<Crop> crops = List.of(
                new Crop("cover", "Cover", "family-a", 50, 0, 1.0, 12, 5, 8, -50, 0),
                new Crop("cash", "Cash", "family-b", 0, 150, 0.1, 4, 5, 8, 1000, 0));
        var planner = new DynamicCropRotationPlanner(crops, 3);
        Field field = field(6.5, List.of());

        FieldPlan soilFirst = planner.planField(field, SOIL_ONLY);
        FieldPlan incomeFirst = planner.planField(field, new Weights(0.0, 1.0));

        assertTrue(soilFirst.years().stream().allMatch(year -> year.cropId().equals("cover")));
        assertTrue(incomeFirst.years().stream().allMatch(year -> year.cropId().equals("cash")));
        assertEquals(3 * 1000 * field.areaHa(), incomeFirst.totalIncome(), 1e-9);
        assertEquals(soilFirst.years().stream().mapToDouble(YearPlan::soilScore).sum(), soilFirst.totalSoilScore(), 1e-9);
    }

    /**
     * Verifies a field whose pH suits none of the growable crops is rejected
     * with a validation error instead of an index or state failure.
     */
    @Test
    void rejectsFieldWhereNoGrowableCropFits() {
        var planner = new DynamicCropRotationPlanner(THREE_FAMILIES, 5);

        assertThrows(CropOptimizationValidationException.class,
                () -> planner.planField(field(3.0, List.of("a")), SOIL_ONLY));
        assertThrows(CropOptimizationValidationException.class,
                () -> planner.planField(field(6.5, List.of("a")), Set.of(), SOIL_ONLY));
    }

    /**
     * Builds a crop with neutral nitrogen, full disease susceptibility and a
     * pH range of 5 to 8.
     *
     * @param id     crop id and name
     * @param family botanical family
     * @param margin net margin per hectare
     * @return the crop
     */
    private static Crop crop(String id, String family, double margin) {
        return new Crop(id, id, family, 0, 0, 0.5, 6, 5, 8, margin, 1.0);
    }

    /**
     * Builds a 10 ha field with healthy organic matter and moderate erosion risk.
     *
     * @param ph      topsoil pH
     * @param history crop ids, most recent first
     * @return the field
     */
    private static Field field(double ph, List<String> history) {
        return new Field("field-1", 44.6, -79.4, 10, ph, "Loam", 4.0, 0.5, history);
    }
}
