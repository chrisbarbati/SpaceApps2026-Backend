package org.greenalienhouseparty.dto;

import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Crop-planning context for one or more selected fields.
 *
 * @param fields field-specific crop choices and outline data
 */
public record CropOptimizationRequest(List<FieldRequest> fields) {

    /**
     * Crop-planning inputs for one selected field.
     *
     * @param fieldId       stable field identifier from the field API
     * @param geometry      selected GeoJSON field geometry for future planning logic
     * @param currentCrop   crop currently growing in the field
     * @param growableCrops crops the farmer says they can grow
     * @param readyDate     date the field is expected to be ready for planting, if known
     */
    public record FieldRequest(
            String fieldId,
            JsonNode geometry,
            String currentCrop,
            List<String> growableCrops,
            LocalDate readyDate) {
    }
}
