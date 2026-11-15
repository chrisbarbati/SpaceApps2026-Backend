package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Random;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.random.RandomGenerator;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.CropOptimizationResponse.PlannedYear;
import org.greenalienhouseparty.dto.CropOptimizationResponse.RotationOption;
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
            + "Explanations and tradeoffs are illustrative demo content, not findings about your field. "
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
     * Creates up to three distinct five-year rotations with hypothetical explanations.
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
            List<String> choices = field.growableCrops();
            int[] offsets = new int[PLANNING_HORIZON_YEARS];
            for (int year = 0; year < offsets.length; year++) {
                int next = random.nextInt(year == 0 || choices.size() == 1 ? choices.size() : choices.size() - 1);
                if (year > 0 && choices.size() > 1 && next >= offsets[year - 1]) next++;
                offsets[year] = next;
            }
            var alternatives = new ArrayList<RotationOption>();
            int optionCount = choices.size() == 1 ? 1 : 3;
            for (int option = 0; option < optionCount; option++) {
                var years = new ArrayList<PlannedYear>();
                String previousCrop = field.currentCrop();
                for (int year = 0; year < PLANNING_HORIZON_YEARS; year++) {
                    // Distinct offsets guarantee different sequences without collision retries.
                    int shift = choices.size() == 2 && option == 2 ? (year == 1 ? 1 : 0) : option;
                    String crop = choices.get((offsets[year] + shift) % choices.size());
                    years.add(new PlannedYear(firstPlanYear + year, crop, null, null, null,
                            exampleRationale(crop, previousCrop)));
                    previousCrop = crop;
                }
                long distinctCrops = years.stream().map(PlannedYear::crop).distinct().count();
                long changes = IntStream.range(1, years.size())
                        .filter(i -> !years.get(i).crop().equals(years.get(i - 1).crop())).count();
                String summary = "Starts with " + years.getFirst().crop() + " after " + field.currentCrop()
                        + "; explores " + distinctCrops + " different crops across five seasons.";
                List<String> benefits = exampleBenefits(years, changes);
                String tradeoff = "Example tradeoff: check how " + years.getFirst().crop()
                        + " and " + years.get(1).crop() + " fit your equipment, planting windows and buyers.";
                alternatives.add(new RotationOption("rotation-" + (option + 1), "Rotation " + (option + 1),
                        summary, List.copyOf(years), benefits, tradeoff));
            }
            return new CropOptimizationResponse.FieldPlan(field.fieldId(), field.currentCrop(),
                    alternatives.getFirst().years(), null, null, null, List.copyOf(alternatives));
        } finally {
            log.trace("Exiting plan()");
        }
    }

    /**
     * Builds short hypothetical benefits tied to the crops actually present in an option.
     *
     * @param years generated crop sequence
     * @param changes number of adjacent crop changes
     * @return example benefits for the comparison view
     */
    private List<String> exampleBenefits(List<PlannedYear> years, long changes) {
        List<String> legumes = years.stream().map(PlannedYear::crop)
                .filter(crop -> List.of("soybeans", "alfalfa").contains(crop.toLowerCase(Locale.ROOT)))
                .distinct().toList();
        List<String> cereals = years.stream().map(PlannedYear::crop)
                .filter(crop -> List.of("corn", "wheat", "barley", "oats").contains(crop.toLowerCase(Locale.ROOT)))
                .distinct().toList();
        String soilExample = !legumes.isEmpty()
                ? "Legume phase: this example uses " + String.join(", ", legumes) + " to explore nitrogen cycling."
                : !cereals.isEmpty()
                        ? "Residue phase: this example uses " + String.join(", ", cereals) + " to explore soil cover."
                        : "Crop variety: this example explores different crop types and field operations.";
        String sequenceExample = changes == 4
                ? "A different crop each year illustrates a break from repeated crop management."
                : changes + " of four year-to-year transitions change crop; repeated years keep some management familiar.";
        return List.of(soilExample, sequenceExample);
    }

    /**
     * Supplies explicitly illustrative reasoning that follows the actual crop sequence.
     *
     * @param crop crop chosen for this year
     * @param previousCrop crop preceding this year
     * @return hypothetical explanation for the demo, without a suitability claim
     */
    private String exampleRationale(String crop, String previousCrop) {
        if (crop.equalsIgnoreCase(previousCrop)) {
            return "Repeats " + crop + ". This example keeps crop management familiar, but does not illustrate a crop break.";
        }
        String role = switch (crop.toLowerCase(Locale.ROOT)) {
            case "soybeans", "alfalfa" -> "a legume phase to explore nitrogen cycling before another crop";
            case "corn", "wheat", "barley", "oats" -> "a cereal phase to explore residue and ground-cover management";
            case "canola", "potatoes" -> "a broadleaf phase to explore a change from cereal crop management";
            case "pasture" -> "a forage phase to explore longer ground cover";
            default -> "a different crop phase to explore variety in field management";
        };
        return "After " + previousCrop + ", this example uses " + role + ".";
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
