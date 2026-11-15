package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.CropOptimizationResponse.PlannedYear;
import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.model.FieldPlan;
import org.greenalienhouseparty.planning.model.Weights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Serves crop-optimization requests with {@link DynamicCropRotationPlanner}.
 *
 * <p>Active when {@code crop.planner.strategy=dynamic}. For each field it looks
 * up the growable crops, builds the planner's field input from data backend
 * responses, runs the optimizer and reports the plan in the API's response
 * shape, including the scores and any assumptions made for missing data.</p>
 */
@Component
@ConditionalOnProperty(name = CropRotationPlanner.STRATEGY_PROPERTY, havingValue = "dynamic")
public class DynamicCropRotationPlannerAdapter implements CropRotationPlanner {

    private static final Logger log = LoggerFactory.getLogger(DynamicCropRotationPlannerAdapter.class);

    /** Soil health only, the default priority until farmers can state their own. */
    static final Weights DEFAULT_WEIGHTS = new Weights(1.0, 0.0);

    private static final String DISCLAIMER = "Plan optimized for soil health using example crop figures and "
            + "satellite and model estimates of soil, terrain and crop history. It does not yet consider weather, "
            + "water or markets. Treat it as a starting point and review any planting decision with local experts.";

    private final CropRepository crops;

    private final PlannerInputNormalizer normalizer;

    private final Clock clock;

    private final DynamicCropRotationPlanner planner;

    /**
     * Creates the adapter using the system clock.
     *
     * @param crops      crop catalog
     * @param normalizer builds planner input from data backend responses
     */
    @Autowired
    public DynamicCropRotationPlannerAdapter(CropRepository crops, PlannerInputNormalizer normalizer) {
        this(crops, normalizer, Clock.systemDefaultZone());
    }

    /**
     * Creates the adapter with an explicit clock for repeatable testing.
     *
     * @param crops      crop catalog
     * @param normalizer builds planner input from data backend responses
     * @param clock      calendar source used to determine future plan years
     */
    DynamicCropRotationPlannerAdapter(CropRepository crops, PlannerInputNormalizer normalizer, Clock clock) {
        log.trace("Entering DynamicCropRotationPlannerAdapter constructor");
        this.crops = crops;
        this.normalizer = normalizer;
        this.clock = clock;
        this.planner = new DynamicCropRotationPlanner(crops.findAll(), PlanYears.PLANNING_HORIZON_YEARS);
        log.debug("Initialized dynamic crop planner");
        log.trace("Exiting DynamicCropRotationPlannerAdapter constructor");
    }

    /**
     * Plans five years for one field, optimizing soil health.
     *
     * @param field field-specific crop-planning context
     * @return five future crop assignments with scores and assumptions
     * @throws CropOptimizationValidationException when a growable crop is unknown or none suits the field
     */
    @Override
    public CropOptimizationResponse.FieldPlan plan(CropOptimizationRequest.FieldRequest field) {
        log.trace("Entering plan()");
        log.debug("Generating an optimized crop plan");
        try {
            Set<String> growableCropIds = new LinkedHashSet<>();
            for (String crop : field.growableCrops()) {
                growableCropIds.add(crops.find(crop)
                        .orElseThrow(() -> new CropOptimizationValidationException(
                                "growableCrops contains an unsupported crop"))
                        .id());
            }
            NormalizedField input = normalizer.normalize(field);
            FieldPlan plan = planner.planField(input.field(), growableCropIds, DEFAULT_WEIGHTS);

            int firstPlanYear = PlanYears.firstPlanYear(clock, field.readyDate());
            List<PlannedYear> years = plan.years().stream()
                    .map(year -> new PlannedYear(
                            firstPlanYear + year.year() - 1,
                            year.cropName(),
                            year.cropId(),
                            round(year.soilScore(), 3),
                            round(year.incomePerHa(), 2),
                            year.rationale()))
                    .toList();
            return new CropOptimizationResponse.FieldPlan(
                    field.fieldId(),
                    field.currentCrop(),
                    years,
                    round(plan.totalSoilScore(), 3),
                    round(plan.totalIncome(), 2),
                    input.assumptions());
        } finally {
            log.trace("Exiting plan()");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "dynamic-soil-health";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Still {@code true}: the optimizer is real, but the crop figures it
     * works from are examples.</p>
     */
    @Override
    public boolean temporary() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String disclaimer() {
        return DISCLAIMER;
    }

    /**
     * Rounds a value for display.
     *
     * @param value    value to round
     * @param decimals decimal places to keep
     * @return the rounded value
     */
    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }
}
