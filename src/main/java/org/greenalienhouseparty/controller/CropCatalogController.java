package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropCatalogResponse;
import org.greenalienhouseparty.planning.CropOptimizationService;
import org.greenalienhouseparty.planning.CropReferenceCatalog;
import org.greenalienhouseparty.planning.ReferenceCropRepository;
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

    private final ReferenceCropRepository reference;

    /**
     * Creates the controller.
     *
     * @param service   crop catalog and planning service
     * @param reference crop reference data with provenance
     */
    public CropCatalogController(CropOptimizationService service, ReferenceCropRepository reference) {
        this.service = service;
        this.reference = reference;
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

    /**
     * Returns the full crop reference data: environmental requirements from
     * FAO ECOCROP and the values that are still estimates, with their sources.
     *
     * @return reference data per crop and the provenance of each value group
     */
    @GetMapping("/crops/reference")
    @Operation(summary = "Crop requirements and where each value comes from",
            description = "Returns, per crop, the requirements from FAO ECOCROP (optimal and tolerated soil pH, "
                    + "growing temperature and annual rainfall, growing cycle in days, killing temperature, rooting "
                    + "depth) and the estimates no open dataset provides (nitrogen, residue, cover, net margin, "
                    + "disease susceptibility). sources says where each group comes from; estimates are placeholders "
                    + "that need local review.")
    public CropReferenceCatalog reference() {
        log.trace("Entering reference()");
        log.info("Handling GET /crops/reference");
        try {
            return reference.reference();
        } finally {
            log.trace("Exiting reference()");
        }
    }
}
