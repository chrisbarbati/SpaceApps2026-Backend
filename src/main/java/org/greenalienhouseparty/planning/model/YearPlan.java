package org.greenalienhouseparty.planning.model;

/**
 * The crop chosen for one year of a field plan and how it scored.
 *
 * @param year        position in the plan, starting at 1 for the first planned year
 * @param cropId      chosen crop identifier
 * @param cropName    chosen crop display name
 * @param soilScore   soil score of this choice; higher is better for the soil
 * @param incomePerHa disease-adjusted net margin in dollars per hectare
 * @param rationale   the score components in words
 */
public record YearPlan(
        int year,
        String cropId,
        String cropName,
        double soilScore,
        double incomePerHa,
        String rationale) {
}
