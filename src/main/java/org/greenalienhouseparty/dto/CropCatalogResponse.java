package org.greenalienhouseparty.dto;

import java.util.List;

/**
 * Fixed crop choices currently supported by the application.
 *
 * @param crops display names accepted by crop-planning requests
 */
public record CropCatalogResponse(List<String> crops) {
}
