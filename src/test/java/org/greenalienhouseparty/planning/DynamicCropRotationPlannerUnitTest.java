package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.CropReference;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldConditions;
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

    private static final Weights RELIABILITY_ONLY = new Weights(0.0, 0.0, 1.0);

    /** Every month at 16 C, which suits a cool-season crop and not a warm-season one. */
    private static final List<Double> COOL_MONTHS = List.of(16.0, 16.0, 16.0, 16.0, 16.0, 16.0, 16.0, 16.0, 16.0, 16.0, 16.0, 16.0);

    /** Nine years of rainfall: three dry, three middling and three wet. */
    private static final List<Double> MIXED_RAINFALL = List.of(900.0, 400.0, 650.0, 1000.0, 450.0, 700.0, 950.0, 500.0, 600.0);

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
     * Verifies that with reliability as the only priority the planner plants
     * the crop whose temperature range matches the field's climate record.
     */
    @Test
    void reliabilityWeightPrefersTheCropThatSuitsTheClimate() {
        List<Crop> crops = List.of(
                crop("cool", "family-a", needs(10, 18, 5, 25, 300, 1500, 100, 3000, null, null)),
                crop("warm", "family-b", needs(22, 30, 15, 40, 300, 1500, 100, 3000, null, null)));
        var planner = new DynamicCropRotationPlanner(crops, 5);
        Field field = field(conditions(COOL_MONTHS, List.of(800.0, 900.0), false, false, null));

        FieldPlan plan = planner.planField(field, RELIABILITY_ONLY);

        assertTrue(plan.years().stream().allMatch(year -> year.cropId().equals("cool")));
        assertEquals(1.0, plan.years().getFirst().reliability(), 1e-9);
        assertTrue(plan.years().getFirst().reasons().stream().anyMatch(reason -> reason.startsWith("Well suited")));
        assertTrue(plan.years().getFirst().reasons().stream().anyMatch(reason -> reason.contains("2 of the last 2 years")));
    }

    /**
     * Verifies a crop that is less reliable is still planted when soil health
     * is the priority, and that its limiting condition is reported in words.
     */
    @Test
    void reportsWhatLimitsALessReliableCrop() {
        List<Crop> crops = List.of(crop("warm", "family-b", needs(22, 30, 15, 40, 300, 1500, 100, 3000, null, null)));
        var planner = new DynamicCropRotationPlanner(crops, 1);

        YearPlan year = planner.planField(field(conditions(COOL_MONTHS, List.of(800.0), false, false, null)), SOIL_ONLY)
                .years().getFirst();

        // 16 C sits one seventh of the way from the 15 C limit to the 22 C optimum
        assertEquals(1.0 / 7.0, year.reliability(), 1e-9);
        assertTrue(year.reasons().contains("Less reliable here: the growing season is cooler than this crop prefers."));
        assertTrue(year.rationale().contains("limited by temperature"));
    }

    /**
     * Verifies soil texture, drainage and land class from regional maps lower
     * reliability in steps without ruling a crop out, that a perennial is not
     * limited by a land class suited to forage, and that the limit is worded
     * for the farmer.
     */
    @Test
    void regionalSoilRatingsLowerReliabilityInSteps() {
        var annualNeeds = new CropReference.Requirements(5.0, 8.0, 6.0, 7.0, 10.0, 18.0, 5.0, 25.0, 300.0, 1500.0, 100.0, 3000.0,
                90, 150, null, null, "annual", List.of("medium"), List.of("medium", "heavy"), List.of("well"),
                List.of("well", "excessive"), null, null);
        var perennialNeeds = new CropReference.Requirements(5.0, 8.0, 6.0, 7.0, 10.0, 18.0, 5.0, 25.0, 300.0, 1500.0, 100.0, 3000.0,
                90, 150, null, null, "perennial", List.of("medium"), List.of("medium", "heavy"), List.of("well"),
                List.of("well", "excessive"), null, null);
        var annual = new DynamicCropRotationPlanner(List.of(crop("annual", "family-a", annualNeeds)), 1);
        var perennial = new DynamicCropRotationPlanner(List.of(crop("forage", "family-b", perennialNeeds)), 1);

        // Loam is medium (optimal); clay is heavy (tolerated); sandy loam is light (neither)
        assertEquals(1.0, reliabilityOn(annual, "Loam", null, null), 1e-9);
        assertEquals(0.8, reliabilityOn(annual, "Clay", null, null), 1e-9);
        assertEquals(0.5, reliabilityOn(annual, "Sandy loam", null, null), 1e-9);
        // Well drained is optimal, rapid is tolerated, poor is neither, imperfect is halfway between well and poor
        assertEquals(1.0, reliabilityOn(annual, "Loam", "W", null), 1e-9);
        assertEquals(0.8, reliabilityOn(annual, "Loam", "R", null), 1e-9);
        assertEquals(0.5, reliabilityOn(annual, "Loam", "P", null), 1e-9);
        assertEquals(0.75, reliabilityOn(annual, "Loam", "I", null), 1e-9);
        // Land classes 1 to 3 suit annual crops; 4 to 6 increasingly suit only perennial forage
        assertEquals(1.0, reliabilityOn(annual, "Loam", "W", "3"), 1e-9);
        assertEquals(0.9, reliabilityOn(annual, "Loam", "W", "4"), 1e-9);
        assertEquals(0.7, reliabilityOn(annual, "Loam", "W", "5"), 1e-9);
        assertEquals(0.5, reliabilityOn(annual, "Loam", "W", "6"), 1e-9);
        assertEquals(0.3, reliabilityOn(annual, "Loam", "W", "7"), 1e-9);
        assertEquals(1.0, reliabilityOn(perennial, "Loam", "W", "6"), 1e-9);
        assertEquals(0.3, reliabilityOn(perennial, "Loam", "W", "7"), 1e-9);

        var poorlyDrained = new FieldConditions(COOL_MONTHS, List.of(5, 6, 7, 8, 9), List.of(800.0), null, null, null, null,
                null, "P", false, false, null);
        List<String> reasons = annual.planField(
                new Field("field-1", 44.6, -79.4, 10, 6.5, "Loam", 4.0, 0.5, List.of(), poorlyDrained), SOIL_ONLY)
                .years().getFirst().reasons();
        assertTrue(reasons.contains("Less reliable here: the regional soil map shows poorly drained soils, "
                + "which does not suit it unless the field is tile-drained."));
    }

    /**
     * Verifies a seasonal outlook that leans dry changes the first plan year
     * only: the dry-year crop is planted first and the crop that suits the
     * long-term record afterwards.
     */
    @Test
    void firstYearOutlookShiftsOnlyTheFirstYear() {
        List<Crop> crops = List.of(
                crop("dryland", "family-a", needs(0, 40, -10, 50, 300, 500, 200, 700, null, null)),
                crop("thirsty", "family-b", needs(0, 40, -10, 50, 800, 1000, 500, 1300, null, null)));
        var planner = new DynamicCropRotationPlanner(crops, 5);
        var leansDry = new FieldConditions.RainfallChances(0.7, 0.2, 0.1);

        FieldPlan withoutOutlook = planner.planField(
                field(conditions(COOL_MONTHS, MIXED_RAINFALL, false, false, null)), RELIABILITY_ONLY);
        FieldPlan withOutlook = planner.planField(
                field(conditions(COOL_MONTHS, MIXED_RAINFALL, false, false, leansDry)), RELIABILITY_ONLY);

        assertTrue(withoutOutlook.years().stream().allMatch(year -> year.cropId().equals("thirsty")));
        assertEquals(0.5, withoutOutlook.years().getFirst().reliability(), 1e-9);
        assertEquals(List.of("dryland", "thirsty", "thirsty", "thirsty", "thirsty"),
                withOutlook.years().stream().map(YearPlan::cropId).toList());
        assertEquals(0.75, withOutlook.years().getFirst().reliability(), 1e-9);
        assertTrue(withOutlook.years().getFirst().reasons()
                .contains("The seasonal outlook leans dry for the coming growing season."));
        assertTrue(withOutlook.years().get(1).reasons().stream().noneMatch(reason -> reason.contains("outlook")));
    }

    /**
     * Verifies a crop whose tolerated temperature is never reached is not
     * planted, and that a field left with no plantable crop is rejected.
     */
    @Test
    void doesNotPlantACropOutsideItsToleratedClimate() {
        List<Crop> crops = List.of(
                crop("tropical", "family-a", needs(25, 35, 20, 45, 300, 1500, 100, 3000, null, null)),
                crop("b", "family-b", 100));
        var planner = new DynamicCropRotationPlanner(crops, 3);
        Field field = field(conditions(COOL_MONTHS, List.of(800.0), false, false, null));

        FieldPlan plan = planner.planField(field, RELIABILITY_ONLY);

        assertTrue(plan.years().stream().allMatch(year -> year.cropId().equals("b")));
        assertThrows(CropOptimizationValidationException.class,
                () -> planner.planField(field, Set.of("tropical"), RELIABILITY_ONLY));
    }

    /**
     * Verifies surplus nitrogen stops counting as a benefit where the soil is
     * rated as already holding a lot of nitrogen, and that the reason given
     * changes with it.
     */
    @Test
    void doesNotCountSurplusNitrogenWhereSoilNitrogenIsHigh() {
        List<Crop> crops = List.of(new Crop("legume", "Legume", "family-a", 150, 0, 0.5, 6, 5, 8, 100, 0));
        var planner = new DynamicCropRotationPlanner(crops, 1);

        YearPlan usual = planner.planField(field(FieldConditions.unknown()), SOIL_ONLY).years().getFirst();
        YearPlan highNitrogen = planner.planField(
                field(conditions(List.of(), List.of(), true, false, null)), SOIL_ONLY).years().getFirst();

        // The nitrogen term is a quarter of the soil score, and the surplus was worth the full 1.0
        assertEquals(0.25, usual.soilScore() - highNitrogen.soilScore(), 1e-9);
        assertTrue(usual.reasons().contains("Adds nitrogen to the soil for the following crop."));
        assertTrue(highNitrogen.reasons().contains("Takes its nitrogen from the air, so it needs little or no nitrogen fertilizer."));
    }

    /**
     * Verifies residue counts for more on a field whose soils are losing
     * organic carbon, enough to change which crop is planted.
     */
    @Test
    void residueCountsForMoreWhereSoilIsLosingCarbon() {
        List<Crop> crops = List.of(
                new Crop("residue", "Residue", "family-a", 0, 0, 0.65, 0, 5, 8, 100, 0),
                new Crop("cover", "Cover", "family-b", 0, 0, 0.3, 12, 5, 8, 100, 0));
        var planner = new DynamicCropRotationPlanner(crops, 1);

        YearPlan stableSoil = planner.planField(field(FieldConditions.unknown()), SOIL_ONLY).years().getFirst();
        YearPlan losingCarbon = planner.planField(
                field(conditions(List.of(), List.of(), false, true, null)), SOIL_ONLY).years().getFirst();

        assertEquals("cover", stableSoil.cropId());
        assertEquals("residue", losingCarbon.cropId());
        assertTrue(losingCarbon.reasons().stream()
                .anyMatch(reason -> reason.endsWith("because soils in this area are losing organic matter.")));
    }

    /**
     * Verifies ground cover is taken from the crop calendar when it covers the
     * crop, in place of the crop's estimated months of cover.
     */
    @Test
    void takesGroundCoverFromTheCropCalendar() {
        List<Crop> crops = List.of(new Crop("a", "A", "family-a", 0, 0, 0.0, 3, 5, 8, 100, 0));
        var planner = new DynamicCropRotationPlanner(crops, 1);
        var calendar = new FieldConditions(null, null, null, null, null, null,
                Map.of("a", new FieldConditions.CropSeason(121, 303, 292, false)), null, null, false, false, null);

        YearPlan estimated = planner.planField(field(FieldConditions.unknown()), SOIL_ONLY).years().getFirst();
        YearPlan fromCalendar = planner.planField(field(calendar), SOIL_ONLY).years().getFirst();

        // Cover is 15% of the soil score, scaled by the field's erosion risk of 0.5
        assertEquals(0.15 * 0.5 * 3 / 12.0, estimated.soilScore(), 1e-9);
        assertEquals(0.15 * 0.5 * 292 / 365.0, fromCalendar.soilScore(), 1e-9);
        assertTrue(fromCalendar.reasons().stream().anyMatch(reason -> reason.contains("about 292 days")));
    }

    /**
     * Verifies the water and heat-unit terms, which have no crop data yet, do
     * not change the plan or its scores even when values are supplied.
     */
    @Test
    void termsWithoutDataDoNotChangeTheScore() {
        List<Crop> without = List.of(
                crop("a", "family-a", needs(10, 18, 5, 25, 300, 1500, 100, 3000, null, null)),
                crop("b", "family-b", needs(12, 20, 5, 25, 300, 1500, 100, 3000, null, null)));
        List<Crop> with = List.of(
                crop("a", "family-a", needs(10, 18, 5, 25, 300, 1500, 100, 3000, 2000.0, 9999.0)),
                crop("b", "family-b", needs(12, 20, 5, 25, 300, 1500, 100, 3000, 100.0, 1.0)));
        var conditions = new FieldConditions(COOL_MONTHS, List.of(5, 6, 7, 8, 9), List.of(800.0), List.of(150.0),
                List.of(2800.0), 300.0, null, null, null, false, false, null);
        Weights weights = new Weights(0.7, 0.0, 0.3);

        FieldPlan expected = new DynamicCropRotationPlanner(without, 5).planField(field(conditions), weights);
        FieldPlan actual = new DynamicCropRotationPlanner(with, 5).planField(field(conditions), weights);

        for (int index = 0; index < 5; index++) {
            assertEquals(expected.years().get(index).cropId(), actual.years().get(index).cropId());
            assertEquals(expected.years().get(index).soilScore(), actual.years().get(index).soilScore(), 1e-12);
            assertEquals(expected.years().get(index).reliability(), actual.years().get(index).reliability(), 1e-12);
        }
        assertTrue(actual.years().getFirst().rationale().contains("not scored: water"));
        assertTrue(expected.years().getFirst().rationale().indexOf("not scored") < 0);
    }

    /**
     * Verifies the reasons describe rotation in plain language: a change of
     * family is credited, a repeated crop is cautioned, and nothing is said
     * about nitrogen when the crop neither fixes nor inherits any.
     */
    @Test
    void explainsRotationInPlainLanguage() {
        var planner = new DynamicCropRotationPlanner(THREE_FAMILIES, 1);

        List<String> rotated = planner.planField(field(6.5, List.of("a", "b")), SOIL_ONLY).years().getFirst().reasons();
        List<String> repeated = planner.planField(field(6.5, List.of("a")), Set.of("a"), SOIL_ONLY).years().getFirst().reasons();

        assertEquals(List.of("Follows a, a different crop family, which helps break pest and disease cycles."), rotated);
        assertEquals(List.of("Same crop as last year, so pest and disease risk is higher."), repeated);
    }

    /**
     * Plans one year on a field with the given soil texture, drainage and land
     * class and a climate that suits the crop, and returns the reliability.
     *
     * @param planner      planner holding a single crop
     * @param texture      topsoil texture class
     * @param drainageCode regional drainage code, or {@code null}
     * @param landClass    regional land capability class, or {@code null}
     * @return the reliability of the planned year
     */
    private static double reliabilityOn(DynamicCropRotationPlanner planner, String texture, String drainageCode, String landClass) {
        var conditions = new FieldConditions(COOL_MONTHS, List.of(5, 6, 7, 8, 9), List.of(800.0), null, null, null, null,
                landClass, drainageCode, false, false, null);
        Field field = new Field("field-1", 44.6, -79.4, 10, 6.5, texture, 4.0, 0.5, List.of(), conditions);
        return planner.planField(field, SOIL_ONLY).years().getFirst().reliability();
    }

    /**
     * Builds what a crop needs from climate, leaving soil preferences unknown.
     *
     * @param tempOptimalMin low end of the optimal temperature
     * @param tempOptimalMax high end of the optimal temperature
     * @param tempMin        lowest tolerated temperature
     * @param tempMax        highest tolerated temperature
     * @param rainOptimalMin low end of the optimal yearly rainfall
     * @param rainOptimalMax high end of the optimal yearly rainfall
     * @param rainMin        lowest tolerated yearly rainfall
     * @param rainMax        highest tolerated yearly rainfall
     * @param waterNeed      season water need, or {@code null}
     * @param heatUnits      heat units required, or {@code null}
     * @return the requirements
     */
    private static CropReference.Requirements needs(
            double tempOptimalMin, double tempOptimalMax, double tempMin, double tempMax,
            double rainOptimalMin, double rainOptimalMax, double rainMin, double rainMax,
            Double waterNeed, Double heatUnits) {
        return new CropReference.Requirements(5.0, 8.0, 6.0, 7.0, tempOptimalMin, tempOptimalMax, tempMin, tempMax,
                rainOptimalMin, rainOptimalMax, rainMin, rainMax, 90, 150, null, null, "annual",
                null, null, null, null, waterNeed, heatUnits);
    }

    /**
     * Builds a crop with neutral nitrogen and residue, no disease
     * susceptibility and the given climate requirements.
     *
     * @param id     crop id and name
     * @param family botanical family
     * @param needs  what the crop needs from climate and soil
     * @return the crop
     */
    private static Crop crop(String id, String family, CropReference.Requirements needs) {
        return new Crop(id, id, family, 0, 0, 0.5, 6, 5, 8, 100, 0, needs);
    }

    /**
     * Builds field conditions with a temperature and rainfall record and the
     * regional soil flags.
     *
     * @param monthlyMeanTempC  mean temperature per month, or an empty list
     * @param yearlyRainfallMm  rainfall per past year, or an empty list
     * @param nitrogenHigh      whether leftover soil nitrogen is rated high
     * @param carbonDeclining   whether soils are losing organic carbon
     * @param firstYearRainfall seasonal outlook for the first plan year, or {@code null}
     * @return the conditions
     */
    private static FieldConditions conditions(List<Double> monthlyMeanTempC, List<Double> yearlyRainfallMm,
                                              boolean nitrogenHigh, boolean carbonDeclining,
                                              FieldConditions.RainfallChances firstYearRainfall) {
        return new FieldConditions(monthlyMeanTempC, List.of(5, 6, 7, 8, 9), yearlyRainfallMm, null, null, null, null,
                null, null, nitrogenHigh, carbonDeclining, firstYearRainfall);
    }

    /**
     * Builds a 10 ha field with neutral pH, healthy organic matter, moderate
     * erosion risk, no history and the given conditions.
     *
     * @param conditions climate record and regional soil ratings
     * @return the field
     */
    private static Field field(FieldConditions conditions) {
        return new Field("field-1", 44.6, -79.4, 10, 6.5, "Loam", 4.0, 0.5, List.of(), conditions);
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
