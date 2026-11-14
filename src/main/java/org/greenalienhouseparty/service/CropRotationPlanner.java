package org.greenalienhouseparty.service;

import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.CropOptimizationResponse;

/**
 * Produces a five-year crop plan from a field's current crop and growable crops.
 *
 * <p>The interface isolates the initial random placeholder from the future
 * agronomic planning implementation.</p>
 */
public interface CropRotationPlanner {

    /**
     * Creates five future yearly assignments for one field.
     *
     * @param field field-specific crop-planning context
     * @return a field plan containing five future years
     */
    CropOptimizationResponse.FieldPlan plan(CropOptimizationRequest.FieldRequest field);
}
