package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropCatalogResponse;
import org.greenalienhouseparty.service.CropOptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Provides the fixed crop choices used by the application.
 */
@RestController
@Tag(name = "Crop Planning", description = "Crop choices and temporary crop-rotation plans")
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
     * Returns the fixed crop names supported by this initial implementation.
     *
     * @return supported crop choices
     */
    @GetMapping("/crops")
    @Operation(summary = "Available crop choices",
            description = "Returns the fixed crop list used for current-crop and growable-crop selection.")
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
