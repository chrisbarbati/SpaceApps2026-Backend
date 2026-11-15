package org.greenalienhouseparty.planning;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;
import org.greenalienhouseparty.dto.CropOptimizationResponse.PlannedYear;
import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.model.Crop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demonstration planner that returns varied, plausible-looking plans without
 * calculating anything from field data.
 *
 * <p>Active when {@code crop.planner.strategy=mock}. It exists so the
 * application can be shown with plans that use several crops and a range of
 * scores and reasons. It makes no data backend calls. The rotation is built
 * from the farmer's growable crops with simple rules (change crop family each
 * year where the list allows, prefer crops not used yet, include a
 * nitrogen-fixing crop early), and the scores are illustrative numbers in the
 * same shape the real planner returns. The same field always gets the same
 * plan, so a demonstration can be repeated.</p>
 *
 * <p>Nothing this planner returns is a recommendation. Every plan says so in
 * its disclaimer and in its assumptions.</p>
 */
@Component
@ConditionalOnProperty(name = CropRotationPlanner.STRATEGY_PROPERTY, havingValue = "mock")
public class MockCropRotationPlanner implements CropRotationPlanner {

    private static final Logger log = LoggerFactory.getLogger(MockCropRotationPlanner.class);

    private static final String DISCLAIMER = "Demonstration plan only. The crops, scores and reasons are illustrative "
            + "and were not calculated from this field's soil, climate or crop history. Do not use it for planting "
            + "decisions.";

    private static final String ASSUMPTION = "Demonstration plan: the crops, scores and reasons are illustrative and "
            + "were not calculated from this field's data.";

    private static final String RATIONALE = "illustrative values for demonstration";

    private final CropRepository crops;

    private final Clock clock;

    /**
     * Creates the demonstration planner using the system clock.
     *
     * @param crops crop catalog
     */
    @Autowired
    public MockCropRotationPlanner(CropRepository crops) {
        this(crops, Clock.systemDefaultZone());
    }

    /**
     * Creates the demonstration planner with an explicit clock for repeatable testing.
     *
     * @param crops crop catalog
     * @param clock calendar source used to determine future plan years
     */
    MockCropRotationPlanner(CropRepository crops, Clock clock) {
        log.trace("Entering MockCropRotationPlanner constructor");
        this.crops = crops;
        this.clock = clock;
        log.debug("Initialized demonstration crop planner");
        log.trace("Exiting MockCropRotationPlanner constructor");
    }

    /**
     * Builds a five-year demonstration plan for one field. The plan depends
     * only on the field identifier, the current crop and the growable crops.
     *
     * @param field field-specific crop-planning context
     * @return five future crop assignments with illustrative scores and reasons
     * @throws CropOptimizationValidationException when a growable crop is unknown
     */
    @Override
    public CropOptimizationResponse.FieldPlan plan(CropOptimizationRequest.FieldRequest field) {
        log.trace("Entering plan()");
        log.debug("Generating a demonstration crop plan");
        try {
            List<Crop> growable = new ArrayList<>();
            for (String name : field.growableCrops()) {
                growable.add(crops.find(name).orElseThrow(() ->
                        new CropOptimizationValidationException("growableCrops contains an unsupported crop")));
            }

            // Seeded by the field identifier, so the same field always gets the same plan
            Random random = new Random(field.fieldId().hashCode());
            Map<String, Double> reliabilityByCrop = new HashMap<>();
            for (Crop crop : growable) {
                reliabilityByCrop.put(crop.id(), 0.72 + 0.25 * random.nextDouble());
            }
            double highestMargin = growable.stream().mapToDouble(Crop::netMarginPerHa).max().orElse(0);

            int firstPlanYear = PlanYears.firstPlanYear(clock, field.readyDate());
            List<PlannedYear> years = new ArrayList<>(PlanYears.PLANNING_HORIZON_YEARS);
            Set<String> used = new HashSet<>();
            boolean nitrogenFixerPlanted = false;
            Crop lastYear = crops.find(field.currentCrop()).orElse(null);
            Crop twoYearsAgo = null;
            double totalSoilScore = 0;
            for (int offset = 0; offset < PlanYears.PLANNING_HORIZON_YEARS; offset++) {
                Crop chosen = choose(growable, lastYear, twoYearsAgo, used, nitrogenFixerPlanted, random);
                boolean sameFamily = lastYear != null && lastYear.family().equals(chosen.family());

                double soilScore = round(0.02 + 0.2 * chosen.residueScore() + 0.1 * chosen.coverMonths() / 12.0
                        + (chosen.nFixKgHa() > 0 ? 0.08 : 0) - (sameFamily ? 0.2 : 0) + 0.04 * random.nextDouble(), 3);
                totalSoilScore += soilScore;
                years.add(new PlannedYear(
                        firstPlanYear + offset,
                        chosen.name(),
                        chosen.id(),
                        soilScore,
                        round(reliabilityByCrop.get(chosen.id()), 2),
                        round(chosen.netMarginPerHa(), 2),
                        RATIONALE,
                        reasons(chosen, lastYear, sameFamily, highestMargin, growable.size())));

                used.add(chosen.id());
                nitrogenFixerPlanted = nitrogenFixerPlanted || chosen.nFixKgHa() > 0;
                twoYearsAgo = lastYear;
                lastYear = chosen;
            }
            return new CropOptimizationResponse.FieldPlan(
                    field.fieldId(), field.currentCrop(), years, round(totalSoilScore, 3), null, List.of(ASSUMPTION));
        } finally {
            log.trace("Exiting plan()");
        }
    }

    /**
     * Picks the crop for one year: a different family from last year's crop
     * where the list allows, preferring crops not used yet, a crop other than
     * the one grown two years ago, and a nitrogen-fixing crop until one has
     * been planted. A small seeded random amount settles ties.
     *
     * @param growable             crops the farmer can grow
     * @param lastYear             crop grown the year before, or {@code null}
     * @param twoYearsAgo          crop grown two years before, or {@code null}
     * @param used                 ids of crops already in the plan
     * @param nitrogenFixerPlanted whether the plan already has a nitrogen-fixing crop
     * @param random               seeded random source
     * @return the crop for the year
     */
    private static Crop choose(List<Crop> growable, Crop lastYear, Crop twoYearsAgo, Set<String> used,
                               boolean nitrogenFixerPlanted, Random random) {
        Crop best = null;
        double bestPreference = Double.NEGATIVE_INFINITY;
        for (Crop crop : growable) {
            double preference = random.nextDouble();
            if (lastYear == null || !lastYear.family().equals(crop.family())) {
                preference += 4;
            }
            if (!used.contains(crop.id())) {
                preference += 2;
            }
            if (twoYearsAgo == null || !twoYearsAgo.id().equals(crop.id())) {
                preference += 1;
            }
            if (!nitrogenFixerPlanted && crop.nFixKgHa() > 0) {
                preference += 1;
            }
            if (preference > bestPreference) {
                bestPreference = preference;
                best = crop;
            }
        }
        return best;
    }

    /**
     * Writes plain-language reasons for a choice from the crop's catalog
     * figures, in the same style as the real planner.
     *
     * @param chosen        the crop for the year
     * @param lastYear      crop grown the year before, or {@code null}
     * @param sameFamily    whether last year's crop is of the same family
     * @param highestMargin highest net margin among the growable crops
     * @param growableCount number of growable crops
     * @return sentences for display: what the crop does for the field first, then cautions
     */
    private static List<String> reasons(Crop chosen, Crop lastYear, boolean sameFamily, double highestMargin, int growableCount) {
        List<String> benefits = new ArrayList<>();
        List<String> cautions = new ArrayList<>();
        boolean followsNitrogenFixer = lastYear != null && lastYear.nFixKgHa() > 0;

        if (chosen.nFixKgHa() > 0) {
            benefits.add("Adds nitrogen to the soil for the following crop.");
        } else if (followsNitrogenFixer && chosen.nDemandKgHa() > 0) {
            benefits.add("Uses nitrogen left in the soil by last year's " + lastYear.name().toLowerCase(Locale.ROOT)
                    + ", so it needs less fertilizer.");
        } else if (chosen.nDemandKgHa() >= 150) {
            cautions.add("Takes a lot of nitrogen from the soil, so plan for fertilizer or manure.");
        }

        if (sameFamily) {
            cautions.add(lastYear.id().equals(chosen.id())
                    ? "Same crop as last year, so pest and disease risk is higher."
                    : "Same crop family as last year's " + lastYear.name().toLowerCase(Locale.ROOT)
                            + ", so pest and disease risk is higher.");
        } else if (lastYear != null) {
            benefits.add("Follows " + lastYear.name().toLowerCase(Locale.ROOT)
                    + ", a different crop family, which helps break pest and disease cycles.");
        }

        if (chosen.coverMonths() >= 12) {
            benefits.add("Keeps the ground covered all year, which limits erosion.");
        } else if (chosen.coverMonths() >= 8) {
            benefits.add("Keeps the ground covered for most of the year, which limits erosion.");
        }
        if (chosen.residueScore() >= 0.6) {
            benefits.add("Leaves a lot of crop residue, which builds organic matter.");
        }
        if (growableCount > 1 && highestMargin > 0 && chosen.netMarginPerHa() >= highestMargin) {
            benefits.add("The highest-earning crop on your list.");
        }

        List<String> reasons = new ArrayList<>(benefits);
        reasons.addAll(cautions);
        return List.copyOf(reasons);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String id() {
        return "mock-demonstration";
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
