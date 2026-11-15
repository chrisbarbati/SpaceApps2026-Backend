package org.greenalienhouseparty.planning.model;

import java.util.List;

/**
 * The planned crop sequence for one field.
 *
 * @param fieldId        field identifier
 * @param years          one entry per planned year, in order
 * @param totalSoilScore sum of the yearly soil scores
 * @param totalIncome    income over the whole plan for the whole field, in dollars
 */
public record FieldPlan(
        String fieldId,
        List<YearPlan> years,
        double totalSoilScore,
        double totalIncome) {
}
