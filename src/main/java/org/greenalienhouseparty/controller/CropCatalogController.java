package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropCatalogResponse;
import org.greenalienhouseparty.planning.CropOptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Provides the crop choices used by the application, with their planning attributes.
 */
@RestController
@Tag(name = "Crop Planning", description = "Crop choices and five-year crop-rotation plans")
public class CropCatalogController {

    private static final Logger log = LoggerFactory.getLogger(CropCatalogController.class);

    private final CropOptimizationService service;

    /**
     * Creates the controller.
     *
     * @param service crop catalog and planning service
     */
    public CropCatalogController(CropOptimizationService service) {
        this.service = service;
    }

    /**
     * Returns the supported crops with the agronomic and economic attributes
     * the rotation planner uses.
     *
     * @return supported crops
     */
    @GetMapping("/crops")
    @Operation(summary = "Available crop choices",
            description = "Returns every supported crop with its id, display name, botanical family, nitrogen fixed "
                    + "and demanded (kg/ha), residue score (0-1), months of ground cover, tolerated soil pH range, net "
                    + "margin ($/ha) and disease susceptibility (0-1). Submit the id in crop-optimization requests. "
                    + "When exampleData is true the figures are illustrative placeholders, not sourced data.")
    public CropCatalogResponse crops() {
        log.trace("Entering crops()");
        log.info("Handling GET /crops");
        try {
            return service.catalog();
        } finally {
            log.trace("Exiting crops()");
        }
    }
}
