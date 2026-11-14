package org.greenalienhouseparty.dto;

import java.util.List;

/**
 * Paginated field outlines returned by the data backend's {@code GET /fields}
 * endpoint.
 *
 * <p>Follow {@link #nextCursor()} using the same bounding box while
 * {@link #hasMore()} is {@code true} to traverse the full result set.</p>
 *
 * @param type           the GeoJSON type, always {@code "FeatureCollection"}
 * @param features       the page of field features
 * @param numberReturned how many features are present in this page
 * @param hasMore        whether further pages remain
 * @param nextCursor     the cursor to request the next page, or {@code null}
 * @param queryBbox      the resolved bounding box, present only for indexed sources
 */
public record FieldsResponse(
        String type,
        List<FieldFeature> features,
        Integer numberReturned,
        Boolean hasMore,
        Integer nextCursor,
        List<Double> queryBbox) {
}
