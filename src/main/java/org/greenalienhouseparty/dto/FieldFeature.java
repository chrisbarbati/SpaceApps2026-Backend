package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * A single predicted agricultural field outline, as carried inside a
 * {@link FieldsResponse} feature collection.
 *
 * @param type       the GeoJSON type, always {@code "Feature"}
 * @param id         the field identifier
 * @param properties the field properties (identifier and optional model score)
 * @param geometry   the GeoJSON geometry (Polygon or MultiPolygon)
 * @param bbox       the bounding box as {@code [west, south, east, north]}
 */
public record FieldFeature(
        String type,
        String id,
        FieldProperties properties,
        @JsonProperty("geometry") JsonNode geometry,
        List<Double> bbox) {
}
