package org.greenalienhouseparty.planning;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;

/**
 * Produces a five-year crop plan from a field's current crop and growable crops.
 *
 * <p>One implementation is active at a time, chosen by the
 * {@code crop.planner.strategy} property: {@code random} (the default
 * placeholder), {@code dynamic} (the soil-health and reliability optimizer)
 * or {@code mock} (varied illustrative plans for demonstrations, not
 * calculated from field data).</p>
 */
public interface CropRotationPlanner {

    /** Property that selects the active planner. */
    String STRATEGY_PROPERTY = "crop.planner.strategy";

    /**
     * Creates five future yearly assignments for one field.
     *
     * @param field field-specific crop-planning context, with crops given as catalog display names
     * @return a field plan containing five future years
     */
    CropOptimizationResponse.FieldPlan plan(CropOptimizationRequest.FieldRequest field);

    /**
     * Names this planner in responses.
     *
     * @return short planner identifier
     */
    String id();

    /**
     * Says whether plans are placeholders rather than real recommendations.
     *
     * @return {@code true} for placeholder output
     */
    boolean temporary();

    /**
     * Explains to the user how far the plans can be trusted.
     *
     * @return user-facing disclaimer
     */
    String disclaimer();
}
