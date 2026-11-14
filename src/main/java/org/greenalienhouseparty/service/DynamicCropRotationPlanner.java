package org.greenalienhouseparty.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TODO: Document further
 *
 * First implementation of optimized crop rotation functionality
 */
public class DynamicCropRotationPlanner {

    // TODO: Extract the following records, and then generate them based on transformation of the NASA data to this format

    // TODO: Review whether the scoring values below make sense, or if there's possibly some way to get them empirically rather than our assumed values

    public record Crop(
            String id,
            String name,
            String family, // Basic identification
            double nFixKgHa,
            double nDemandKgHa,
            double residueScore,      // 0..1 carbon input
            int coverMonths,          // 0..12
            double phMin, double phMax,
            double netMarginPerHa,    // $/ha, can be negative for cover crops
            double diseaseSusceptibility // 0..1
    ) {}

    // TODO: Extract
    public record Field(
            String id, double lat, double lon, double areaHa,
            double ph, String texture, double organicMatterPct,
            double erosionRisk,       // 0..1
            List<String> history      // crop ids, most recent first
    ) {}

    // TODO: Extract
    public record Weights(double soil, double income) {} // sum to 1

    // TODO: Extract
    public record YearPlan(
            int year, String cropId, String cropName,
            double soilScore, double incomePerHa, String rationale) {}

    // TODO: Extract
    public record FieldPlan(
            String fieldId, List<YearPlan> years,
            double totalSoilScore, double totalIncome) {}

    // TODO: Extract
    public record Strategy(
            String name, Weights weights, List<FieldPlan> fieldPlans,
            double farmSoilIndex, double farmIncome) {}

    // TODO: Extract
    public record PlanResponse(
            List<Strategy> strategies, String recommended, Strategy baseline) {}

    /**
     * List of all potential crops that the farmer is able to plant
     */
    private final List<Crop> availableCrops;

    /**
     * How many years ahead to plan
     */
    private final int planningYears;

    /**
     * Highest margin that can potentially be earned per hectare of land.
     * <p>
     * Will be used to scale the profit scoring later. This maximum margin
     * will be used as 1.0, and the plans can then be scored against it on a
     * 0 to 1.0 scale.
     * </p>
     */
    private final double highestMarginPerHa;

    /**
     * Placeholder index meaning "no crop was grown", to be used for missing
     * historical data.
     */
    private final int noCropIndex;

    public DynamicCropRotationPlanner(List<Crop> availableCrops, int planningYears) { // Constructor
        this.availableCrops = availableCrops;
        this.planningYears = planningYears;
        this.noCropIndex = availableCrops.size();

        // Find and store the margin of the most profitable crop provided in the list
        double largestMargin = availableCrops.stream().mapToDouble(Crop::netMarginPerHa).max().orElse(1);

        // Prevent divide-by-zero later by setting a default value of 1
        this.highestMarginPerHa = largestMargin <= 0 ? 1 : largestMargin;
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
     * @param field
     * @param cropOneYearAgo
     * @param cropTwoYearsAgo
     * @param candidateCrop
     * @return
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
     * @param field
     * @param crop
     * @return
     */
    private boolean isCropAllowed(Field field, Crop crop) {
        return field.ph() >= crop.phMin() && field.ph() <= crop.phMax();
    }

    /**
     * Optimize for the best crop sequence for the field, given the provided weights.
     *
     * @param field
     * @param weights
     * @return
     */
    public FieldPlan planField(Field field, Weights weights) {

        // One state for every available crop. Add one for the "no crop" option
        int numberOfStates = availableCrops.size() + 1;

        // bestTotalScore[year][cropOneYearAgo][cropTwoYearsAgo] = best score found for reaching that situation

        /*
            The best total score table has three possible dimensions:
                Number of years + 1 TODO: Why is this +1?
                Possible crop states year 1
                Possible crop states year 2
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

                        // If the field characteristics prevent planting this crop, skip it.
                        if (!isCropAllowed(field, candidateCrop)) {
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
     * @param field
     * @param bestTotalScore
     * @param cameFromOlderCrop
     * @param weights
     * @return
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
            throw new IllegalStateException("No crop is allowed for field " + field.id());
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
        Crop cropOneYearAgo = pastCropIds.size() > 0 ? availableCrops.get(indexOfCrop(pastCropIds.get(0))) : null;

        // Crop from two years ago (if known)
        Crop cropTwoYearsAgo = pastCropIds.size() > 1 ? availableCrops.get(indexOfCrop(pastCropIds.get(1))) : null;

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
     * @param value
     * @param minimum
     * @param maximum
     * @return
     */
    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}