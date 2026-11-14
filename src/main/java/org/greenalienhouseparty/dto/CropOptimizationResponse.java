package org.greenalienhouseparty.dto;

import java.util.List;

/**
 * Five-year crop plans for the fields submitted to the optimizer.
 *
 * @param plans       one future rotation per selected field
 * @param planner     identifier for the planner implementation used
 * @param temporary   whether the result is a placeholder rather than a real recommendation
 * @param disclaimer  user-facing explanation of the temporary result
 */
public record CropOptimizationResponse(
        List<FieldPlan> plans,
        String planner,
        boolean temporary,
        String disclaimer) {

    /**
     * Future crop assignments for one field.
     *
     * @param fieldId     selected field identifier
     * @param currentCrop crop reported as currently growing
     * @param years       five future crop assignments
     */
    public record FieldPlan(String fieldId, String currentCrop, List<PlannedYear> years) {
    }

    /**
     * Crop assigned to one future calendar year.
     *
     * @param year calendar year of the assignment
     * @param crop selected crop name
     */
    public record PlannedYear(Integer year, String crop) {
    }
}
