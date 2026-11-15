package org.greenalienhouseparty.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.greenalienhouseparty.exception.CropOptimizationValidationException;
import org.greenalienhouseparty.planning.model.Crop;
import org.greenalienhouseparty.planning.model.Field;
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
 * and erosion cover) with income, using the caller's {@link Weights}.</p>
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
    private record YearScore(double soilScore, double incomePerHa, String explanation) {}

    /**
     * Determine the score of this crop choice in this field, factoring in the previous two years'
     * crop choices.
     * <p>
     * Sliding window of previous two years for historical context. Three years ago and greater is
     * not considered by this algorithm
     * </p>
     *
     * TODO: Review the available NASA data to determine if we want to include additional considerations in this calculation. eg. Weather
     *
     * @param field           the field being planned
     * @param cropOneYearAgo  crop grown the year before, or {@code null} for none
     * @param cropTwoYearsAgo crop grown two years before, or {@code null} for none
     * @param candidateCrop   crop being considered for this year
     * @return soil score, disease-adjusted income per hectare and an explanation
     */
    private YearScore scoreCropChoice(Field field, Crop cropOneYearAgo, Crop cropTwoYearsAgo, Crop candidateCrop) { // Scoring function

        // 1. Calculate disease penalty

        /*
            Disease penalty is a negative score modifier used to account for the increased
            likelihood of disease. For one obvious example, the same crop grown two years in a row in
            the same field will render it susceptible in year two to pests and diseases from year one.
         */
        double diseasePenalty = 0;

        /*
            If same crop was grown last year as current, we apply a hefty penalty.
         */
        if (cropOneYearAgo != null && cropOneYearAgo.family().equals(candidateCrop.family())) {
            diseasePenalty -= 1.0;
        }

        /*
            If same crop was grown two years ago, we apply a less severe penalty because the risk
            decreases over time.
            TODO: Is this true if the same crop was grown last year AND two years ago? Need to do some research but maybe an extra modifier if 1 year ago crop == 2 years ago crop == current crop
         */
        if (cropTwoYearsAgo != null && cropTwoYearsAgo.family().equals(candidateCrop.family())) {
            diseasePenalty -= 0.5;
        }

        /*
            Scale the penalty against the relative 0 - 1 disease susceptibility of this crop. 0 being "Immune to disease"
            and 1 being "very vulnerable to disease"
         */
        diseasePenalty *= candidateCrop.diseaseSusceptibility();

        // 2. Calculate Nitrogen scoring

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

        // 3. Organic matter score

        /*
            Base starting point is the organic matter score. Plants that leave more residue are better for the soil
         */
        double organicMatterScore = candidateCrop.residueScore();

        /*
            If the field is already low in organic matter, we will apply a small penalty since it will need extra residue
         */
        if (field.organicMatterPct() < 2.0) {
            organicMatterScore -= 0.2;
        }

        // 4. Erosion protection

        /*
            Determine what fraction of the year on a 0 to 1 scale this crop covers the ground. More ground cover helps prevent erosion
         */
        double coverFractionOfYear = candidateCrop.coverMonths() / 12.0;

        /*
            Scale the erosion risk based on the fraction of the year the field is covered
         */
        double erosionProtectionScore = coverFractionOfYear * field.erosionRisk();

        // TODO: Test to determine if these are suitable values
        double soilScore = 0.40 * diseasePenalty // Disease is weighted most heavily at 40%
                + 0.25 * nitrogenScore // Nitrogen balance counts for 25%
                + 0.20 * organicMatterScore // Organic matter counts for 20%
                + 0.15 * erosionProtectionScore; // Erosion protection counts for 15%

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
        String explanation = String.format("disease %.2f, nitrogen %.2f, organic matter %.2f, cover %.2f",
                diseasePenalty, nitrogenScore, organicMatterScore, erosionProtectionScore);

        return new YearScore(soilScore, incomePerHa, explanation);
    }

    /**
     * Determine whether the crop is allowed in this field. At this time this is just based on whether the acidity of the field is in the
     * acceptable range for this plant, but could be extended later (maybe farmer blocks out specific fields for specific crops).
     *
     * @param field the field being planned
     * @param crop  the crop being considered
     * @return whether the crop may be planted in this field
     */
    private boolean isCropAllowed(Field field, Crop crop) {
        return field.ph() >= crop.phMin() && field.ph() <= crop.phMax();
    }

    /**
     * Optimize for the best crop sequence for the field, given the provided weights.
     *
     * <p>Every known crop is treated as growable. Use
     * {@link #planField(Field, Set, Weights)} to limit the plan to the crops a farmer can grow.</p>
     *
     * @param field   the field to plan
     * @param weights how much to value soil health against income
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
     * @param weights         how much to value soil health against income
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
                        if (!growableCropIds.contains(candidateCrop.id()) || !isCropAllowed(field, candidateCrop)) {
                            continue;
                        }

                        // Calculate the score for this choice
                        YearScore yearScore = scoreCropChoice(field, cropAt(cropOneYearAgo), cropAt(cropTwoYearsAgo), candidateCrop);

                        // Scale income 0 to 1 so it is comparable to the soil score, 1.0 being the maximum possible margin per hectare
                        double incomeScore = yearScore.incomePerHa() / highestMarginPerHa;

                        // Calculate the combined score for this year based on the provided weights
                        double combinedYearScore = weights.soil() * yearScore.soilScore() + weights.income() * incomeScore;

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
     * @param weights           how much to value soil health against income
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
                    + " (estimated soil pH " + field.ph() + ")");
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
        for (int year = 1; year <= planningYears; year++) {
            // Look up this year's crop
            Crop chosenCrop = availableCrops.get(chosenCropIndexByYear[year - 1]);

            // Re-score it so we can report the details
            YearScore yearScore = scoreCropChoice(field, cropOneYearAgo, cropTwoYearsAgo, chosenCrop);

            // Add to the plan
            yearPlans.add(new YearPlan(year, chosenCrop.id(), chosenCrop.name(), yearScore.soilScore(), yearScore.incomePerHa(), yearScore.explanation()));

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