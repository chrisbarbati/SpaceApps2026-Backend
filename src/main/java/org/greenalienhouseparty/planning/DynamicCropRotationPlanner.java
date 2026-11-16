package org.greenalienhouseparty.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.CropSuitabilityAssessor.Fit;
import org.greenalienhouseparty.planning.CropSuitabilityAssessor.Reliability;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.CropReference;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldConditions;
import org.greenalienhouseparty.planning.model.FieldPlan;
import org.greenalienhouseparty.planning.model.Weights;
import org.greenalienhouseparty.planning.model.YearPlan;

/**
 * First implementation of optimized crop rotation functionality.
 *
 * <p>For one field, finds the sequence of crops over the planning horizon with
 * the best total score, by dynamic programming over the state "crop grown last
 * year, crop grown two years ago". Each year's score blends a soil score
 * (disease pressure from repeating a crop family, nitrogen balance, residue
 * and erosion cover), a reliability score (how well the field's climate record
 * and soil suit the crop) and income, using the caller's {@link Weights}.</p>
 *
 * <p>Where each input comes from, and how far it can be trusted:</p>
 * <ul>
 *   <li>Sourced: the field's climate record, crop calendar, soil and regional soil ratings, and each
 *       crop's tolerated pH, temperature, rainfall, cycle length, soil texture and drainage (FAO ECOCROP).</li>
 *   <li>Estimates, not sourced: each crop's nitrogen fixed and needed, residue, disease susceptibility and
 *       net margin. The terms that use them stay in the score for now and are marked in
 *       {@link #scoreCropChoice}.</li>
 *   <li>Not available yet: each crop's water need and heat-unit requirement. The terms that would use
 *       them are written in {@link #scoreCropChoice} but left out of the score.</li>
 * </ul>
 *
 * <p>The planner knows the whole crop catalog so that it can recognise crops in
 * a field's history, but only plants crops the farmer says they can grow.</p>
 *
 * TODO: Document further
 */
public class DynamicCropRotationPlanner {

    // TODO: Review whether the scoring values below make sense, or if there's possibly some way to get them empirically rather than our assumed values

    /**
     * Every crop the planner knows about. A field's history is read against this list; which of
     * these may actually be planted is decided per field in {@link #planField(Field, Set, Weights)}.
     */
    private final List<Crop> availableCrops;

    /**
     * How many years ahead to plan
     */
    private final int planningYears;

    /**
     * Placeholder index meaning "no crop was grown", to be used for missing
     * historical data.
     */
    private final int noCropIndex;

    /** Compares the field's climate and soil with what each crop needs. */
    private final CropSuitabilityAssessor suitability = new CropSuitabilityAssessor();

    /**
     * Creates a planner.
     *
     * @param availableCrops every crop the planner should know, including ones only seen in field history
     * @param planningYears  how many years ahead to plan
     */
    public DynamicCropRotationPlanner(List<Crop> availableCrops, int planningYears) { // Constructor
        this.availableCrops = availableCrops;
        this.planningYears = planningYears;
        this.noCropIndex = availableCrops.size();
    } // End of constructor

    /**
     * The two numbers (plus a text explanation) that describe how good one crop choice is.
     * <p>
     * Holder for results scoring of the year's performance
     * </p>
     * */
    private record YearScore(double soilScore, double reliability, double incomePerHa, String explanation, Components components) {}

    /**
     * The individual values behind a {@link YearScore}, kept so the choice can be explained in words.
     *
     * @param sameFamilyLastYear    whether the crop grown the year before is of the same family
     * @param sameFamilyTwoYearsAgo whether the crop grown two years before is of the same family
     * @param nitrogenScore         nitrogen score, -1 (drains the soil) to 1 (enriches it)
     * @param nitrogenLeftByLastCrop nitrogen carried over from last year's crop, in kg per hectare
     * @param nitrogenSurplusCapped whether surplus nitrogen was not counted because the soil already holds a lot
     * @param organicMatterNeed     how much the field needs residue, 1 (normal) to 1.5
     * @param coverFractionOfYear   share of the year the crop covers the ground, 0 to 1
     * @param reliability           how well the field's climate and soil suit the crop
     */
    private record Components(
            boolean sameFamilyLastYear,
            boolean sameFamilyTwoYearsAgo,
            double nitrogenScore,
            double nitrogenLeftByLastCrop,
            boolean nitrogenSurplusCapped,
            double organicMatterNeed,
            double coverFractionOfYear,
            Reliability reliability) {}

    /**
     * Determine the score of this crop choice in this field, factoring in the previous two years'
     * crop choices.
     * <p>
     * Sliding window of previous two years for historical context. Three years ago and greater is
     * not considered by this algorithm
     * </p>
     *
     * <p>
     * The NASA climate record, crop calendar and regional soil ratings now feed this calculation through
     * {@link Field#conditions()}: steps 2 to 5 below. Steps 6 and 7 are written but not yet counted.
     * </p>
     *
     * @param field           the field being planned
     * @param cropOneYearAgo  crop grown the year before, or {@code null} for none
     * @param cropTwoYearsAgo crop grown two years before, or {@code null} for none
     * @param candidateCrop   crop being considered for this year
     * @param planYear        position of this year in the plan, starting at 1; the seasonal outlook only applies to year 1
     * @return soil score, reliability, disease-adjusted income per hectare and an explanation
     */
    private YearScore scoreCropChoice(Field field, Crop cropOneYearAgo, Crop cropTwoYearsAgo, Crop candidateCrop, int planYear) { // Scoring function

        // Climate record, crop calendar and regional soil ratings for this field. Any value inside may be missing.
        FieldConditions conditions = field.conditions();

        // What the candidate needs from climate and soil (FAO ECOCROP), or null when not known
        CropReference.Requirements needs = candidateCrop.requirements();

        // 1. Calculate disease penalty

        /*
            Disease penalty is a negative score modifier used to account for the increased
            likelihood of disease. For one obvious example, the same crop grown two years in a row in
            the same field will render it susceptible in year two to pests and diseases from year one.
         */
        double diseasePenalty = 0;

        boolean sameFamilyLastYear = cropOneYearAgo != null && cropOneYearAgo.family().equals(candidateCrop.family());
        boolean sameFamilyTwoYearsAgo = cropTwoYearsAgo != null && cropTwoYearsAgo.family().equals(candidateCrop.family());

        /*
            If same crop was grown last year as current, we apply a hefty penalty.
         */
        if (sameFamilyLastYear) {
            diseasePenalty -= 1.0;
        }

        /*
            If same crop was grown two years ago, we apply a less severe penalty because the risk
            decreases over time.
            TODO: Is this true if the same crop was grown last year AND two years ago? Need to do some research but maybe an extra modifier if 1 year ago crop == 2 years ago crop == current crop
         */
        if (sameFamilyTwoYearsAgo) {
            diseasePenalty -= 0.5;
        }

        /*
            Scale the penalty against the relative 0 - 1 disease susceptibility of this crop. 0 being "Immune to disease"
            and 1 being "very vulnerable to disease"

            NOTE: The crop family is a fact, but diseaseSusceptibility is an estimate: we did not have reliable data
            for it. It stays in the calculation for now and may be removed once the data-backed terms prove out.
         */
        diseasePenalty *= candidateCrop.diseaseSusceptibility();

        // 2. Calculate Nitrogen scoring

        /*
            NOTE: nFixKgHa and nDemandKgHa are estimates: we did not have reliable data for them. This term stays
            in the calculation for now and may be removed once the data-backed terms prove out.
         */

        /*
            Determine the nitrogen left in the soil from last year's crop
         */
        double nitrogenLeftByLastCrop = (cropOneYearAgo == null) ? 0 : cropOneYearAgo.nFixKgHa();

        /*
            Calculate the nitrogen balance. Last year's leftover nitrogen, add in this crop's supply and subtract this crop's demand
         */
        double nitrogenBalanceKg = nitrogenLeftByLastCrop + candidateCrop.nFixKgHa() - candidateCrop.nDemandKgHa();

        /*
            Convert to a score on scale -1 to 1, where -1 means "Drains soil" and 1 means "Enriches soil"
         */
        double nitrogenScore = clamp(nitrogenBalanceKg / 100.0, -1, 1);

        /*
            Where the regional soil record (Agriculture and Agri-Food Canada) rates the nitrogen left in the soil
            after harvest as high, more nitrogen is not a benefit: it is at risk of being lost to water or air.
            So a surplus counts as neutral there, while a shortfall still counts against the crop.
         */
        boolean nitrogenSurplusCapped = conditions.residualNitrogenHigh() && nitrogenScore > 0;
        if (nitrogenSurplusCapped) {
            nitrogenScore = 0;
        }

        // 3. Organic matter score

        /*
            Base starting point is the organic matter score. Plants that leave more residue are better for the soil

            NOTE: residueScore is an estimate: we did not have reliable data for it. This term stays in the
            calculation for now and may be removed once the data-backed terms prove out.
         */
        double organicMatterScore = candidateCrop.residueScore();

        /*
            Residue is worth more on a field that needs it. The need starts at 1 and rises by a quarter when the
            field is already low in organic matter (topsoil estimate), and by another quarter when the area's soils
            are losing organic carbon (Agriculture and Agri-Food Canada). Multiplying, rather than subtracting the
            same amount from every crop, is what lets this change which crop is chosen.
         */
        double organicMatterNeed = 1.0;
        if (field.organicMatterPct() < 2.0) {
            organicMatterNeed += 0.25;
        }
        if (conditions.organicCarbonDeclining()) {
            organicMatterNeed += 0.25;
        }
        organicMatterScore *= organicMatterNeed;

        // 4. Erosion protection

        /*
            Determine what fraction of the year on a 0 to 1 scale this crop covers the ground. More ground cover helps prevent erosion

            Taken from the crop calendar's usual planting and maturity dates around this field when it covers the
            crop. A perennial covers the ground all year. Otherwise the crop's coverMonths is used, which is an
            estimate.
         */
        double coverFractionOfYear = candidateCrop.coverMonths() / 12.0;
        FieldConditions.CropSeason usualSeason = conditions.cropSeasons().get(candidateCrop.id());
        if (needs != null && needs.perennial()) {
            coverFractionOfYear = 1.0;
        } else if (usualSeason != null) {
            coverFractionOfYear = clamp(usualSeason.growingSeasonDays() / 365.0, 0, 1);
        }

        /*
            Scale the erosion risk based on the fraction of the year the field is covered
         */
        double erosionProtectionScore = coverFractionOfYear * field.erosionRisk();

        // TODO: Test to determine if these are suitable values
        double soilScore = 0.40 * diseasePenalty // Disease is weighted most heavily at 40%
                + 0.25 * nitrogenScore // Nitrogen balance counts for 25%
                + 0.20 * organicMatterScore // Organic matter counts for 20%
                + 0.15 * erosionProtectionScore; // Erosion protection counts for 15%

        // 5. Reliability: how well the field's climate and soil suit this crop

        /*
            Compares the field's temperature, rainfall record, season length, soil texture, drainage and land class
            with what the crop needs. Each comparison gives a fit from 0 to 1, and the reliability is the lowest of
            them: one unsuitable condition limits the crop however good the others are. A condition with no data
            counts as a fit of 1, so a missing value never rules a crop out.
         */
        Reliability reliability = suitability.assessReliability(field, candidateCrop, planYear);

        // 6. Water balance. NOT PART OF THE SCORE YET.

        /*
            NOTE: We would like to integrate this, but do not yet have good data on it.

            The field side is available: growing-season rainfall here, and later NASA POWER reference
            evapotranspiration, GRACE groundwater and MODIS evapotranspiration. The crop side is not: we have no
            sourced figure for how much water each crop needs over its season (seasonWaterNeedMm is empty for every
            crop; FAO-56 crop coefficients would give it). Until then this is calculated when the values exist, for
            checking, and left out of the soil score and the reliability.

            Scale: 0 when season rainfall covers the crop's need, down to -1 when there is none of it.
         */
        double waterBalanceScore = 0;
        Double seasonWaterNeedMm = needs == null ? null : needs.seasonWaterNeedMm();
        if (seasonWaterNeedMm != null && seasonWaterNeedMm > 0 && conditions.seasonRainfallMm() != null) {
            waterBalanceScore = clamp((conditions.seasonRainfallMm() - seasonWaterNeedMm) / seasonWaterNeedMm, -1, 0);
        }

        // 7. Heat units. NOT PART OF THE SCORE YET.

        /*
            NOTE: We would like to integrate this, but do not yet have good data on it.

            The field side is available: crop heat units for each past year. The crop side is not: we have no
            sourced figure for the heat units each crop needs to mature (heatUnitsRequired is empty for every crop;
            provincial crop-heat-unit ratings would give it). Until then this is calculated when the values exist,
            for checking, and left out of the reliability.

            Scale: the share of past years that reached the crop's requirement, 0 to 1.
         */
        double heatUnitScore = 1;
        Double heatUnitsRequired = needs == null ? null : needs.heatUnitsRequired();
        if (heatUnitsRequired != null && !conditions.yearlyHeatUnits().isEmpty()) {
            long yearsWithEnoughHeat = conditions.yearlyHeatUnits().stream().filter(units -> units >= heatUnitsRequired).count();
            heatUnitScore = (double) yearsWithEnoughHeat / conditions.yearlyHeatUnits().size();
        }

        // 8. Income

        /*
            NOTE: netMarginPerHa is an estimate: we did not have reliable data for it. Statistics Canada gives yield
            and price, but not costs, so a real margin cannot be worked out yet. This term stays in the calculation
            for now (the default weights give income no say) and may be removed or replaced later.
         */

        /*
            Factor the lost yield due to disease into the income estimate, up to a maximum of 30%
         */
        double incomeReductionFactor = 1 + 0.3 * Math.min(0, diseasePenalty);

        /*
            Disease-adjusted income expectation per hectare
         */
        double incomePerHa = candidateCrop.netMarginPerHa() * incomeReductionFactor;

        /*
            Make a formatted result score for use in logging and returned to the user.

            TODO: Unlikely we'll want to use this on the frontend. Better idea is to return the actual values for formatting
         */
        String explanation = String.format("disease %.2f, nitrogen %.2f, organic matter %.2f, cover %.2f, reliability %.2f%s",
                diseasePenalty, nitrogenScore, organicMatterScore, erosionProtectionScore, reliability.value(),
                reliability.limitedBy() == null || reliability.value() >= 0.995 ? "" : " (limited by " + reliability.limitedBy() + ")");

        // The two terms that are not part of the score yet are only reported when there was something to calculate
        if (seasonWaterNeedMm != null || heatUnitsRequired != null) {
            explanation += String.format("; not scored: water %.2f, heat units %.2f", waterBalanceScore, heatUnitScore);
        }

        Components components = new Components(sameFamilyLastYear, sameFamilyTwoYearsAgo, nitrogenScore,
                nitrogenLeftByLastCrop, nitrogenSurplusCapped, organicMatterNeed, coverFractionOfYear, reliability);

        return new YearScore(soilScore, reliability.value(), incomePerHa, explanation, components);
    }

    /**
     * Says in plain language why a crop was chosen for a year and what to watch for, from the same values the
     * score was built from. A sentence is only included when what it describes applied to this choice.
     *
     * @param field           the field being planned
     * @param cropOneYearAgo  crop grown the year before, or {@code null} for none
     * @param cropTwoYearsAgo crop grown two years before, or {@code null} for none
     * @param chosenCrop      the crop chosen for the year
     * @param planYear        position of the year in the plan, starting at 1
     * @param yearScore       the score of this choice
     * @return sentences for the farmer: what the crop does for the field first, then cautions
     */
    private List<String> describeChoice(Field field, Crop cropOneYearAgo, Crop cropTwoYearsAgo, Crop chosenCrop,
                                        int planYear, YearScore yearScore) {
        Components parts = yearScore.components();
        List<String> benefits = new ArrayList<>();
        List<String> cautions = new ArrayList<>();

        // Nitrogen
        if (chosenCrop.nFixKgHa() > 0) {
            benefits.add(parts.nitrogenSurplusCapped()
                    ? "Takes its nitrogen from the air, so it needs little or no nitrogen fertilizer."
                    : "Adds nitrogen to the soil for the following crop.");
        } else if (parts.nitrogenLeftByLastCrop() > 0 && chosenCrop.nDemandKgHa() > 0) {
            benefits.add("Uses nitrogen left in the soil by last year's " + lower(cropOneYearAgo.name())
                    + ", so it needs less fertilizer.");
        }
        if (chosenCrop.nFixKgHa() <= 0 && parts.nitrogenScore() <= -0.75) {
            cautions.add("Takes a lot of nitrogen from the soil, so plan for fertilizer or manure.");
        }

        // Rotation and disease
        if (parts.sameFamilyLastYear()) {
            boolean sameCrop = cropOneYearAgo.id().equals(chosenCrop.id());
            boolean perennial = chosenCrop.requirements() != null && chosenCrop.requirements().perennial();
            if (sameCrop && perennial) {
                cautions.add("Keeps last year's stand in place; pest and disease pressure builds the longer it stays.");
            } else if (sameCrop) {
                cautions.add("Same crop as last year, so pest and disease risk is higher.");
            } else {
                cautions.add("Same crop family as last year's " + lower(cropOneYearAgo.name()) + ", so pest and disease risk is higher.");
            }
        } else {
            if (cropOneYearAgo != null) {
                benefits.add("Follows " + lower(cropOneYearAgo.name())
                        + ", a different crop family, which helps break pest and disease cycles.");
            }
            if (parts.sameFamilyTwoYearsAgo()) {
                cautions.add("Same crop family as the " + lower(cropTwoYearsAgo.name())
                        + " grown two years earlier, so some pest and disease risk carries over.");
            }
        }

        // Ground cover
        if (parts.coverFractionOfYear() >= 0.6) {
            String cover = parts.coverFractionOfYear() >= 0.99
                    ? "Keeps the ground covered all year"
                    : "Keeps the ground covered for about " + Math.round(parts.coverFractionOfYear() * 365) + " days of the year";
            benefits.add(cover + (field.erosionRisk() >= 0.5 ? ", which protects this erosion-prone field." : ", which limits erosion."));
        }

        // Residue and organic matter
        if (chosenCrop.residueScore() >= 0.6) {
            boolean lowOrganicMatter = field.organicMatterPct() < 2.0;
            boolean losingCarbon = field.conditions().organicCarbonDeclining();
            String why = ".";
            if (lowOrganicMatter && losingCarbon) {
                why = "; that matters here because this soil is low in organic matter and soils in this area are losing it.";
            } else if (lowOrganicMatter) {
                why = "; that matters here because this soil is low in organic matter.";
            } else if (losingCarbon) {
                why = "; that matters here because soils in this area are losing organic matter.";
            }
            benefits.add("Leaves a lot of crop residue, which builds organic matter" + why);
        }

        // Reliability
        Reliability reliability = parts.reliability();
        if (reliability.known()) {
            if (reliability.value() >= 0.9) {
                Fit rainfall = reliability.rainfall();
                benefits.add(rainfall.known() && rainfall.years() > 0
                        ? "Well suited to this field's climate and soil: rainfall was within the range it tolerates in "
                                + rainfall.yearsTolerated() + " of the last " + rainfall.years() + " years."
                        : "Well suited to what is known of this field's climate and soil.");
            } else if (reliability.value() >= 0.6) {
                cautions.add("Suits this field, though " + suitability.describeLimit(field, reliability));
            } else {
                cautions.add("Less reliable here: " + suitability.describeLimit(field, reliability));
            }
        }

        // Seasonal outlook, first plan year only
        FieldConditions.RainfallChances outlook = field.conditions().firstYearRainfall();
        if (planYear == 1 && outlook != null && reliability.rainfall().known()) {
            if (outlook.below() >= 0.4 && outlook.below() > outlook.above()) {
                cautions.add("The seasonal outlook leans dry for the coming growing season.");
            } else if (outlook.above() >= 0.4 && outlook.above() > outlook.below()) {
                cautions.add("The seasonal outlook leans wet for the coming growing season.");
            }
        }

        List<String> reasons = new ArrayList<>(benefits);
        reasons.addAll(cautions);
        return List.copyOf(reasons);
    }

    /**
     * Lower-cases a crop or soil name for use inside a sentence.
     *
     * @param name display name
     * @return the name in lower case
     */
    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Optimize for the best crop sequence for the field, given the provided weights.
     *
     * <p>Every known crop is treated as growable. Use
     * {@link #planField(Field, Set, Weights)} to limit the plan to the crops a farmer can grow.</p>
     *
     * @param field   the field to plan
     * @param weights how much to value soil health, income and reliability against each other
     * @return the best crop sequence found
     */
    public FieldPlan planField(Field field, Weights weights) {
        Set<String> everyCropId = new LinkedHashSet<>();
        for (Crop crop : availableCrops) {
            everyCropId.add(crop.id());
        }
        return planField(field, everyCropId, weights);
    }

    /**
     * Optimize for the best crop sequence for the field, planting only the growable crops.
     *
     * <p>Crops in the field's history still count towards disease pressure and nitrogen carry-over
     * even when the farmer will not grow them again.</p>
     *
     * @param field           the field to plan
     * @param growableCropIds ids of the crops that may be planted
     * @param weights         how much to value soil health, income and reliability against each other
     * @return the best crop sequence found
     * @throws CropOptimizationValidationException when none of the growable crops suits the field
     */
    public FieldPlan planField(Field field, Set<String> growableCropIds, Weights weights) {

        /*
            Highest margin that can be earned per hectare from the growable crops. Used to scale income
            to a 0 to 1.0 score, with this margin as 1.0, so it is comparable to the soil score.
            Defaults to 1 to prevent divide-by-zero when no growable crop makes money.
         */
        double largestMargin = availableCrops.stream()
                .filter(crop -> growableCropIds.contains(crop.id()))
                .mapToDouble(Crop::netMarginPerHa).max().orElse(1);
        double highestMarginPerHa = largestMargin <= 0 ? 1 : largestMargin;

        /*
            Which crops may be planted here: the farmer can grow it and the field's characteristics do not rule it out.
            Worked out once, because it does not depend on the year or on what was grown before.
         */
        boolean[] mayBePlanted = new boolean[availableCrops.size()];
        for (int cropIndex = 0; cropIndex < availableCrops.size(); cropIndex++) {
            Crop crop = availableCrops.get(cropIndex);
            mayBePlanted[cropIndex] = growableCropIds.contains(crop.id()) && suitability.isCropAllowed(field, crop);
        }

        // One state for every available crop. Add one for the "no crop" option
        int numberOfStates = availableCrops.size() + 1;

        // bestTotalScore[year][cropOneYearAgo][cropTwoYearsAgo] = best score found for reaching that situation

        /*
            The best total score table has three dimensions:
                Number of years + 1: index 0 is the starting point before anything is planted (where the
                    field's own history sits), and index N is the situation after N planned years
                Possible crop states one year ago
                Possible crop states two years ago
         */
        double[][][] bestTotalScore = new double[planningYears + 1][numberOfStates][numberOfStates]; // Table of best score

        /*
            Remembers for each "best" score above, what crop did we come from
         */
        int[][][] cameFromOlderCrop = new int[planningYears + 1][numberOfStates][numberOfStates]; // Table of "where did we come from"

        /*
            By default, we will populate every situation as "not reachable yet"
         */
        for (double[][] yearTable : bestTotalScore) {
            for (double[] row : yearTable) {
                Arrays.fill(row, Double.NEGATIVE_INFINITY);
            }
        }

        // List of all past crop Ids grown in this field before, starting with the most recent
        List<String> pastCropIds = field.history();

        // Store last year's crop, defaulting to none if there was no crop last year
        int startingCropOneYearAgo = pastCropIds.size() > 0 ? indexOfCrop(pastCropIds.get(0)) : noCropIndex;

        // Store year before last's crop, defaulting to none if there was no crop last year
        int startingCropTwoYearsAgo = pastCropIds.size() > 1 ? indexOfCrop(pastCropIds.get(1)) : noCropIndex;

        // Starting point is zero
        bestTotalScore[0][startingCropOneYearAgo][startingCropTwoYearsAgo] = 0;

        // Starting at current year, move forward
        for (int year = 0; year < planningYears; year++) {
            // Iterate last year's crop options
            for (int cropOneYearAgo = 0; cropOneYearAgo < numberOfStates; cropOneYearAgo++) {
                // Iterate current year crop options
                for (int cropTwoYearsAgo = 0; cropTwoYearsAgo < numberOfStates; cropTwoYearsAgo++) {

                    // Best score so far in this situation
                    double scoreSoFar = bestTotalScore[year][cropOneYearAgo][cropTwoYearsAgo];

                    // If this situation was never reachable in the first place, skip it
                    if (scoreSoFar == Double.NEGATIVE_INFINITY) {
                        continue;
                    }

                    // Try planting each crop this year
                    for (int candidateIndex = 0; candidateIndex < availableCrops.size(); candidateIndex++) {

                        Crop candidateCrop = availableCrops.get(candidateIndex);

                        // If the farmer cannot grow this crop, or the field characteristics prevent planting it, skip it.
                        if (!mayBePlanted[candidateIndex]) {
                            continue;
                        }

                        // Calculate the score for this choice. The table's year 0 is the first plan year, so plan year is year + 1
                        YearScore yearScore = scoreCropChoice(field, cropAt(cropOneYearAgo), cropAt(cropTwoYearsAgo), candidateCrop, year + 1);

                        // Scale income 0 to 1 so it is comparable to the soil score, 1.0 being the maximum possible margin per hectare
                        double incomeScore = yearScore.incomePerHa() / highestMarginPerHa;

                        // Calculate the combined score for this year based on the provided weights
                        double combinedYearScore = weights.soil() * yearScore.soilScore()
                                + weights.reliability() * yearScore.reliability()
                                + weights.income() * incomeScore;

                        // Add to the previous year's score for a running total
                        double newTotalScore = scoreSoFar + combinedYearScore;

                        // After planting, "last year" becomes the candidate and "two years ago" becomes the old last-year crop

                        // Is this situation better than any path found so far?
                        if (newTotalScore > bestTotalScore[year + 1][candidateIndex][cropOneYearAgo]) {
                            // If so, save this as the new best total score
                            bestTotalScore[year + 1][candidateIndex][cropOneYearAgo] = newTotalScore;

                            // Remember the older crop so we can trace it back later
                            cameFromOlderCrop[year + 1][candidateIndex][cropOneYearAgo] = cropTwoYearsAgo;
                        }
                    }
                }
            }
        }

        // Once all possibilities are calculated, walk back through to recompute the best plan
        return rebuildBestPlan(field, bestTotalScore, cameFromOlderCrop, weights);
    }

    /**
     * Find the best final situation, then walk backwards to recover which crop was chosen each year.
     *
     * @param field             the field being planned
     * @param bestTotalScore    best score for reaching each situation
     * @param cameFromOlderCrop for each best score, the crop grown before the two in the state
     * @param weights           how much to value soil health, income and reliability against each other
     * @return the best plan with per-year scores
     * @throws CropOptimizationValidationException when no plan could be built
     */
    private FieldPlan rebuildBestPlan(Field field, double[][][] bestTotalScore, int[][][] cameFromOlderCrop, Weights weights) {

        // Will hold the final year's crop in the best path
        int bestLastCrop = -1;

        // Will hold the crop before that in the best path
        int bestSecondLastCrop = -1;

        // Best total score seen at the end of the plan, defaults to our minimum value
        double bestFinalScore = Double.NEGATIVE_INFINITY;

        // Check every possible final-year crop
        for (int lastCrop = 0; lastCrop < bestTotalScore[planningYears].length; lastCrop++) {
            // And every possible crop before it
            for (int secondLastCrop = 0; secondLastCrop < bestTotalScore[planningYears][lastCrop].length; secondLastCrop++) {
                // Total score for ending in this situation
                double finalScore = bestTotalScore[planningYears][lastCrop][secondLastCrop];

                // If this is the best so far, remember the score, remember the final year crop, remember the crop before it
                if (finalScore > bestFinalScore) {
                    bestFinalScore = finalScore;
                    bestLastCrop = lastCrop;
                    bestSecondLastCrop = secondLastCrop;
                }
            }
        }

        // Did we find a valid plan?
        if (bestLastCrop == -1) {
            throw new CropOptimizationValidationException("None of the growable crops suits field " + field.id()
                    + ": each is outside the soil pH it tolerates (estimated soil pH " + field.ph()
                    + ") or outside the temperature or rainfall it tolerates in this field's climate record");
        }


        // Will hold the chosen crop for years 1..N (stored at positions 0..N-1)
        int[] chosenCropIndexByYear = new int[planningYears];

        // Start at the final year's crop
        int currentCrop = bestLastCrop;

        // And the crop before it
        int previousCrop = bestSecondLastCrop;

        // Walk backwards from the last year to the first
        for (int year = planningYears; year >= 1; year--) {
            // Record the crop chosen for this year
            chosenCropIndexByYear[year - 1] = currentCrop;

            // Look up which crop preceded the previous one
            int olderCrop = cameFromOlderCrop[year][currentCrop][previousCrop];

            // Step back: the previous crop becomes the current one
            currentCrop = previousCrop;

            // And the older crop becomes the previous one
            previousCrop = olderCrop;
        }

        List<Crop> chosenCrops = new ArrayList<>(planningYears);
        for (int cropIndex : chosenCropIndexByYear) {
            chosenCrops.add(availableCrops.get(cropIndex));
        }
        return scoreSequence(field, chosenCrops);
    }

    /**
     * Scores a crop sequence chosen elsewhere, for example the farmer's existing rotation or one they want to
     * compare, with exactly the same soil, reliability and income terms the optimizer uses, and explains each
     * year in plain language.
     *
     * <p>Unlike {@link #planField(Field, Set, Weights)} this does not skip crops that do not suit the field,
     * so an unsuitable choice shows up as a low reliability and a caution instead of an error. Crops the
     * planner does not know are rejected.</p>
     *
     * @param field   the field the sequence is for
     * @param cropIds crop ids for years 1, 2, 3 and so on; may be shorter or longer than the planning horizon
     * @return the sequence with per-year scores and reasons, and the totals
     * @throws CropOptimizationValidationException when the sequence is empty or contains an unknown crop
     */
    public FieldPlan evaluateSequence(Field field, List<String> cropIds) {
        if (cropIds == null || cropIds.isEmpty()) {
            throw new CropOptimizationValidationException("A rotation must contain at least one crop");
        }
        List<Crop> sequence = new ArrayList<>(cropIds.size());
        for (String cropId : cropIds) {
            int index = indexOfCrop(cropId);
            if (index == noCropIndex) {
                throw new CropOptimizationValidationException("Unknown crop in rotation: " + cropId);
            }
            sequence.add(availableCrops.get(index));
        }
        return scoreSequence(field, sequence);
    }

    /**
     * Walks forward through a crop sequence from the field's own history, re-scoring and explaining each year.
     *
     * @param field       the field being planned
     * @param chosenCrops the crop for each plan year, in order
     * @return the plan with per-year scores and totals
     */
    private FieldPlan scoreSequence(Field field, List<Crop> chosenCrops) {
        // Crops grown here before the plan starts, most recent first
        List<String> pastCropIds = field.history();

        // Last year's crop (if known)
        Crop cropOneYearAgo = pastCropIds.size() > 0 ? cropAt(indexOfCrop(pastCropIds.get(0))) : null;

        // Crop from two years ago (if known)
        Crop cropTwoYearsAgo = pastCropIds.size() > 1 ? cropAt(indexOfCrop(pastCropIds.get(1))) : null;

        // Will collect one entry per year
        List<YearPlan> yearPlans = new ArrayList<>();

        // Running sum of yearly soil scores
        double totalSoilScore = 0;

        // Running sum of income for the whole field
        double totalIncome = 0;

        // Walk forward through the plan
        for (int year = 1; year <= chosenCrops.size(); year++) {
            // Look up this year's crop
            Crop chosenCrop = chosenCrops.get(year - 1);

            // Re-score it so we can report the details
            YearScore yearScore = scoreCropChoice(field, cropOneYearAgo, cropTwoYearsAgo, chosenCrop, year);

            // Say in plain language why this crop was chosen
            List<String> reasons = describeChoice(field, cropOneYearAgo, cropTwoYearsAgo, chosenCrop, year, yearScore);

            // Add to the plan
            yearPlans.add(new YearPlan(year, chosenCrop.id(), chosenCrop.name(), yearScore.soilScore(), yearScore.reliability(),
                    yearScore.incomePerHa(), yearScore.explanation(), reasons));

            // Add to the soil total
            totalSoilScore += yearScore.soilScore();

            // Convert per-hectare income to whole-field income and add it
            totalIncome += yearScore.incomePerHa() * field.areaHa();

            // Shift history: last year becomes two years ago
            cropTwoYearsAgo = cropOneYearAgo;

            // The crop we just planted becomes last year's crop
            cropOneYearAgo = chosenCrop;
        }

        return new FieldPlan(field.id(), yearPlans, totalSoilScore, totalIncome);
    }

    /**
     * Converts a crop index to a Crop, or null when the index means "no crop".
     * */
    private Crop cropAt(int cropIndex) {
        return cropIndex == noCropIndex ? null : availableCrops.get(cropIndex);
    }

    /**
     * Finds a crop's position in the list by its id; returns the "no crop" index if not found.
     * */
    private int indexOfCrop(String cropId) {
        for (int index = 0; index < availableCrops.size(); index++) {
            if (availableCrops.get(index).id().equals(cropId)) {
                return index;
            }
        }
        return noCropIndex;
    }

    /**
     * Clamps the provided value between specified minimum and maximum
     *
     * @param value   value to clamp
     * @param minimum lowest value returned
     * @param maximum highest value returned
     * @return the value, limited to the range
     */
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}