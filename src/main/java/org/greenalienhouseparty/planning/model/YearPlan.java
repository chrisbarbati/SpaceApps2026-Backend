package org.greenalienhouseparty.planning.model;

import java.util.List;

/**
 * The crop chosen for one year of a field plan and how it scored.
 *
 * @param year        position in the plan, starting at 1 for the first planned year
 * @param cropId      chosen crop identifier
 * @param cropName    chosen crop display name
 * @param soilScore   soil score of this choice; higher is better for the soil
 * @param reliability how well the field's climate and soil suit the crop, 0 (unsuited) to 1 (well suited)
 * @param incomePerHa disease-adjusted net margin in dollars per hectare
 * @param rationale   the score components as numbers, for checking the calculation
 * @param reasons     plain-language sentences saying why the crop was chosen and what to watch for
 */
public record YearPlan(
        int year,
        String cropId,
        String cropName,
        double soilScore,
        double reliability,
        double incomePerHa,
        String rationale,
        List<String> reasons) {
}
