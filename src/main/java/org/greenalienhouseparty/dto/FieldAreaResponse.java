package org.greenalienhouseparty.dto;

/**
 * Estimated area of a single selected field, as returned by the data backend's
 * {@code GET /fields/{fieldId}} endpoint.
 *
 * @param id       the field identifier
 * @param hectares estimated area in hectares, rounded to two decimals
 * @param acres    estimated area in acres, rounded to two decimals
 */
public record FieldAreaResponse(String id, Double hectares, Double acres) {
}
