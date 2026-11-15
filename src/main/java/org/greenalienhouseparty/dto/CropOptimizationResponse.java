package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Five-year crop plans for the fields submitted to the optimizer.
 *
 * @param plans       one future rotation per selected field
 * @param planner     identifier for the planner implementation used
 * @param temporary   whether the result is a placeholder rather than a real recommendation
 * @param disclaimer  user-facing explanation of how far the result can be trusted
 */
public record CropOptimizationResponse(
        List<FieldPlan> plans,
        String planner,
        boolean temporary,
        String disclaimer) {

    /**
     * Future crop assignments for one field.
     *
     * <p>The score and assumption fields are present only when the planner
     * computes them; the random placeholder leaves numeric scores out and supplies illustrative rationale.</p>
     *
     * @param fieldId        selected field identifier
     * @param currentCrop    crop reported as currently growing
     * @param years          five future crop assignments
     * @param totalSoilScore sum of the yearly soil scores; higher is better for the soil
     * @param totalIncome    estimated income over the plan for the whole field, in dollars
     * @param assumptions    plain-language notes on data that was missing and what was assumed instead
     * @param alternatives   distinct demo rotations, or null for planners that return one plan
     */
    public record FieldPlan(
            String fieldId,
            String currentCrop,
            List<PlannedYear> years,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double totalSoilScore,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double totalIncome,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> assumptions,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<RotationOption> alternatives) {

        /**
         * Preserves the single-plan constructor for the dynamic planner.
         *
         * @param fieldId selected field identifier
         * @param currentCrop farmer-reported current crop
         * @param years future crop assignments
         * @param totalSoilScore combined soil score
         * @param totalIncome combined estimated income
         * @param assumptions notes about missing inputs
         */
        public FieldPlan(String fieldId, String currentCrop, List<PlannedYear> years,
                Double totalSoilScore, Double totalIncome, List<String> assumptions) {
            this(fieldId, currentCrop, years, totalSoilScore, totalIncome, assumptions, null);
        }

        /**
         * Creates a plan without scores, as the random placeholder does.
         *
         * @param fieldId     selected field identifier
         * @param currentCrop crop reported as currently growing
         * @param years       five future crop assignments
         */
        public FieldPlan(String fieldId, String currentCrop, List<PlannedYear> years) {
            this(fieldId, currentCrop, years, null, null, null, null);
        }
    }

    /**
     * A distinct illustrative rotation with explanations for comparison.
     *
     * @param id stable option identifier within the field
     * @param title short display name
     * @param summary description of the actual generated sequence
     * @param years five crop assignments with example rationale
     * @param benefits hypothetical reasons for exploring the sequence
     * @param tradeoff example limitation to review before using it
     */
    public record RotationOption(String id, String title, String summary,
            List<PlannedYear> years, List<String> benefits, String tradeoff) {
    }

    /**
     * Crop assigned to one future calendar year.
     *
     * <p>The id, score and rationale fields are present only when the planner
     * computes them; the random placeholder leaves numeric scores out and supplies illustrative rationale.</p>
     *
     * @param year        calendar year of the assignment
     * @param crop        selected crop display name
     * @param cropId      selected crop identifier from {@code GET /crops}
     * @param soilScore   soil score of this choice; higher is better for the soil
     * @param incomePerHa estimated net margin in dollars per hectare
     * @param rationale   the score components in words
     */
    public record PlannedYear(
            Integer year,
            String crop,
            @JsonInclude(JsonInclude.Include.NON_NULL) String cropId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double soilScore,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double incomePerHa,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rationale) {

        /**
         * Creates an assignment without scores, as the random placeholder does.
         *
         * @param year calendar year of the assignment
         * @param crop selected crop display name
         */
        public PlannedYear(Integer year, String crop) {
            this(year, crop, null, null, null, null);
        }
    }
}
