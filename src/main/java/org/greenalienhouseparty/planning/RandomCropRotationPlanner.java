package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Random;
import java.util.random.RandomGenerator;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.CropOptimizationResponse.PlannedYear;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Temporary five-year planner that randomly selects from the farmer's growable
 * crops.
 *
 * <p>This implementation is for wiring and UI development only; it does not
 * evaluate soil, weather, yield, profitability, pests, or crop reliability.</p>
 */
@Component
@ConditionalOnProperty(name = CropRotationPlanner.STRATEGY_PROPERTY, havingValue = "random", matchIfMissing = true)
public class RandomCropRotationPlanner implements CropRotationPlanner {

    private static final Logger log = LoggerFactory.getLogger(RandomCropRotationPlanner.class);

    private static final int PLANNING_HORIZON_YEARS = PlanYears.PLANNING_HORIZON_YEARS;

    private static final String DISCLAIMER = "Temporary random plan only; it is not optimized for soil health, "
            + "weather, yield, income, or crop reliability, and may repeat crops. "
            + "Review any planting decision with local experts.";

    private final RandomGenerator random;

    private final Clock clock;

    /**
     * Creates the production placeholder planner with a default random source
     * and system clock.
     */
    public RandomCropRotationPlanner() {
        this(new Random(), Clock.systemDefaultZone());
    }

    /**
     * Creates a planner with explicit random and calendar sources for repeatable
     * testing.
     *
     * @param random random source used to choose a growable crop
     * @param clock  calendar source used to determine future plan years
     */
    RandomCropRotationPlanner(RandomGenerator random, Clock clock) {
        log.trace("Entering RandomCropRotationPlanner constructor");
        this.random = random;
        this.clock = clock;
        log.debug("Initialized temporary random crop planner");
        log.trace("Exiting RandomCropRotationPlanner constructor");
    }

    /**
     * Randomly assigns a growable crop to each of five future calendar years.
     * Repeated crops are permitted until rotation rules are designed.
     *
     * <p>TODO: Replace this random placeholder with the real algorithmic crop
     * rotation planner before presenting plans as recommendations. The current stub is just to
     * allow building of the frontend against the mock API response.</p>
     *
     * @param field field-specific crop-planning context
     * @return five randomly selected future crop assignments
     */
    @Override
    public CropOptimizationResponse.FieldPlan plan(CropOptimizationRequest.FieldRequest field) {
        log.trace("Entering plan()");
        log.debug("Generating a temporary random crop plan");
        try {
            int firstPlanYear = PlanYears.firstPlanYear(clock, field.readyDate());
            var years = new ArrayList<PlannedYear>(PLANNING_HORIZON_YEARS);
            for (int offset = 0; offset < PLANNING_HORIZON_YEARS; offset++) {
                String crop = field.growableCrops().get(random.nextInt(field.growableCrops().size()));
                years.add(new PlannedYear(firstPlanYear + offset, crop));
            }
            return new CropOptimizationResponse.FieldPlan(field.fieldId(), field.currentCrop(), years);
        } finally {
            log.trace("Exiting plan()");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "random-placeholder";
    }

    /**
     * {@inheritDoc}
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
}
