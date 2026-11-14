package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.ReadinessResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Readiness endpoint, re-exposed from the Python data backend.
 */
@RestController
@RequestMapping("/ready")
@Tag(name = "Service", description = "Liveness and readiness probes")
public class ReadinessController {

    private static final Logger log = LoggerFactory.getLogger(ReadinessController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public ReadinessController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Reports whether the configured field data is available for requests.
     *
     * @return readiness details with the status code returned by the data backend
     */
    @GetMapping
    @Operation(summary = "Readiness check",
            description = "Returns 200 when field data is readable and 503 when field data is unavailable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Field routes can answer."),
            @ApiResponse(responseCode = "503", description = "Field data is unavailable; field routes will fail.")
    })
    public ResponseEntity<ReadinessResponse> readiness() {
        log.trace("Entering readiness()");
        log.info("Handling GET /ready");
        try {
            return service.readiness();
        } finally {
            log.trace("Exiting readiness()");
        }
    }
}
