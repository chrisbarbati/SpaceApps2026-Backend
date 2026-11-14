package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldCoverageResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Field coverage endpoint, re-exposed from the data backend.
 */
@RestController
@RequestMapping("/field-coverage")
@Tag(name = "Field Coverage", description = "Loaded field coverage and build status")
public class FieldCoverageController {

    private static final Logger log = LoggerFactory.getLogger(FieldCoverageController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public FieldCoverageController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Reports the loaded field coverage, source and build status.
     *
     * @return the coverage metadata
     */
    @GetMapping
    @Operation(summary = "Loaded field coverage, source and build status")
    public FieldCoverageResponse coverage() {
        log.trace("Entering coverage()");
        log.info("Handling GET /field-coverage");
        try {
            return service.fieldCoverage();
        } finally {
            log.trace("Exiting coverage()");
        }
    }
}
