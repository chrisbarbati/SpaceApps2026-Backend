package org.greenalienhouseparty.dto;

import java.util.List;

import org.greenalienhouseparty.planning.model.Crop;

/**
 * The crops the application can plan with, including the agronomic and
 * economic attributes the rotation planner uses, so the frontend can show them.
 *
 * @param crops       supported crops in display order; submit their {@code id} in crop-planning requests
 * @param exampleData whether the crop attributes are illustrative placeholders rather than sourced figures
 * @param note        user-facing explanation of where the attributes come from
 */
public record CropCatalogResponse(List<Crop> crops, boolean exampleData, String note) {
}
