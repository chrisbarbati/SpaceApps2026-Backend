package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.HealthResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Liveness endpoint, re-exposed from the data backend.
 */
@RestController
@RequestMapping("/health")
@Tag(name = "Health", description = "Service liveness")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public HealthController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Reports the data backend liveness status.
     *
     * @return the health response
     */
    @GetMapping
    @Operation(summary = "Liveness check", description = "Reports the data backend service status.")
    public HealthResponse health() {
        log.trace("Entering health()");
        log.info("Handling GET /health");
        try {
            return service.health();
        } finally {
            log.trace("Exiting health()");
        }
    }
}
