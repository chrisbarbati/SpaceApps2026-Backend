package org.greenalienhouseparty.planning.model;

import java.util.List;

/**
 * One rotation strategy for the whole farm: the plans produced for every field
 * under one set of weights.
 *
 * <p>Not produced yet; kept for the strategy comparison (for example
 * soil-first, balanced and the existing rotation).</p>
 *
 * @param name          strategy name
 * @param weights       weights the plans were optimised for
 * @param fieldPlans    one plan per field
 * @param farmSoilIndex soil score across the farm
 * @param farmIncome    income across the farm, in dollars
 */
public record Strategy(
        String name,
        Weights weights,
        List<FieldPlan> fieldPlans,
        double farmSoilIndex,
        double farmIncome) {
}
