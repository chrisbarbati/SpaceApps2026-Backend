package org.greenalienhouseparty.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.CropSuitabilityAssessor.Reliability;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.CropReference;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldConditions;
import org.greenalienhouseparty.planning.model.FieldPlan;
import org.greenalienhouseparty.planning.model.YearPlan;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CropSuitabilityAssessor}, now shared by the planner and the chat assistant, and for
 * {@link DynamicCropRotationPlanner#evaluateSequence}, which scores a farmer's own rotation.
 */
class CropSuitabilityAssessorUnitTest {

    private static final List<Double> WARM_MONTHS = List.of(-5.0, -3.0, 2.0, 8.0, 15.0, 20.0, 22.0, 21.0, 16.0, 9.0, 3.0, -2.0);

    private final CropSuitabilityAssessor assessor = new CropSuitabilityAssessor();

    @Test
    void ratesACropInsideItsRangesAsFullyReliable() {
        Reliability reliability = assessor.assessReliability(field(6.5, conditions(WARM_MONTHS, List.of(800.0, 850.0, 900.0), "W", "2")),
                crop("warm", 12, 26, 600, 1200), 0);

        assertThat(reliability.value()).isEqualTo(1.0);
        assertThat(reliability.known()).isTrue();
        assertThat(reliability.limitedBy()).isNull();
        assertThat(reliability.rainfall().yearsTolerated()).isEqualTo(3);
    }

    @Test
    void namesTheWeakestConditionAndDescribesItInWords() {
        Field dryField = field(6.5, conditions(WARM_MONTHS, List.of(300.0, 320.0, 350.0), null, null));
        Reliability reliability = assessor.assessReliability(dryField, crop("thirsty", 12, 26, 600, 1200), 0);

        assertThat(reliability.limitedBy()).isEqualTo("rainfall");
        assertThat(reliability.rainfall().direction()).isEqualTo(-1);
        assertThat(assessor.describeLimit(dryField, reliability)).isEqualTo("most years are drier than this crop prefers.");
    }

    @Test
    void treatsMissingDataAsUncheckedRatherThanUnsuitable() {
        Reliability reliability = assessor.assessReliability(field(6.5, FieldConditions.unknown()), crop("any", 12, 26, 600, 1200), 0);

        assertThat(reliability.value()).isEqualTo(1.0);
        assertThat(reliability.known()).isFalse();
        assertThat(reliability.temperature().known()).isFalse();
    }

    @Test
    void rulesOutCropsOutsideTheirToleratedSoilPh() {
        Crop crop = crop("acid-hater", 12, 26, 600, 1200);

        assertThat(assessor.acidityTolerated(field(4.5, FieldConditions.unknown()), crop)).isFalse();
        assertThat(assessor.isCropAllowed(field(4.5, FieldConditions.unknown()), crop)).isFalse();
        assertThat(assessor.isCropAllowed(field(6.5, FieldConditions.unknown()), crop)).isTrue();
    }

    @Test
    void lowersAnnualCropsOnPoorLandClassesButNotPerennials() {
        FieldConditions classSix = conditions(List.of(), List.of(), null, "6");

        assertThat(assessor.assessReliability(field(6.5, classSix), crop("annual", 12, 26, 600, 1200), 0).value())
                .isEqualTo(0.5);
        assertThat(assessor.assessReliability(field(6.5, classSix), perennial("forage"), 0).value()).isEqualTo(1.0);
    }

    @Test
    void scoresAFarmersOwnSequenceWithReasons() {
        List<Crop> crops = List.of(
                new Crop("corn", "Corn", "Poaceae", 0, 170, 0.8, 5, 5.5, 7.5, 450, 0.5),
                new Crop("soybeans", "Soybeans", "Fabaceae", 120, 100, 0.3, 4, 6, 7.5, 400, 0.6));
        DynamicCropRotationPlanner planner = new DynamicCropRotationPlanner(crops, 5);
        Field field = new Field("field-1", 44.6, -79.4, 10, 6.5, "Loam", 4.0, 0.5, List.of("corn"));

        FieldPlan monoculture = planner.evaluateSequence(field, List.of("corn", "corn", "corn"));
        FieldPlan rotation = planner.evaluateSequence(field, List.of("soybeans", "corn", "soybeans"));

        assertThat(monoculture.years()).extracting(YearPlan::cropId).containsExactly("corn", "corn", "corn");
        assertThat(rotation.totalSoilScore()).isGreaterThan(monoculture.totalSoilScore());
        assertThat(monoculture.years().getFirst().reasons()).contains("Same crop as last year, so pest and disease risk is higher.");
        assertThatThrownBy(() -> planner.evaluateSequence(field, List.of("corn", "rice")))
                .isInstanceOf(CropOptimizationValidationException.class)
                .hasMessageContaining("rice");
        assertThatThrownBy(() -> planner.evaluateSequence(field, List.of()))
                .isInstanceOf(CropOptimizationValidationException.class);
    }

    /**
     * Builds an annual crop with a pH range of 5.5 to 7.5 and the given climate ranges; tolerated limits are
     * 5 C and 300 mm outside the optimal ones.
     */
    private static Crop crop(String id, double tempOptimalMin, double tempOptimalMax, double rainOptimalMin,
                             double rainOptimalMax) {
        var needs = new CropReference.Requirements(5.5, 7.5, 6.0, 7.0, tempOptimalMin, tempOptimalMax,
                tempOptimalMin - 5, tempOptimalMax + 5, rainOptimalMin, rainOptimalMax, rainOptimalMin - 300,
                rainOptimalMax + 300, 90, 150, null, null, "annual", null, null, null, null, null, null);
        return new Crop(id, id, "family-" + id, 0, 0, 0.5, 6, 5.5, 7.5, 100, 0, needs);
    }

    /** Builds a perennial with no climate ranges. */
    private static Crop perennial(String id) {
        var needs = new CropReference.Requirements(5.5, 7.5, 6.0, 7.0, null, null, null, null, null, null, null, null,
                null, null, null, null, "perennial", null, null, null, null, null, null);
        return new Crop(id, id, "family-" + id, 0, 0, 0.5, 12, 5.5, 7.5, 100, 0, needs);
    }

    /** Builds conditions with a temperature and rainfall record and regional ratings. */
    private static FieldConditions conditions(List<Double> months, List<Double> rainfall, String drainage, String landClass) {
        return new FieldConditions(months, List.of(5, 6, 7, 8, 9), rainfall, null, null, null, null, landClass, drainage,
                false, false, null);
    }

    /** Builds a 10 ha loam field. */
    private static Field field(double ph, FieldConditions conditions) {
        return new Field("field-1", 44.6, -79.4, 10, ph, "Loam", 4.0, 0.5, List.of(), conditions);
    }
}
