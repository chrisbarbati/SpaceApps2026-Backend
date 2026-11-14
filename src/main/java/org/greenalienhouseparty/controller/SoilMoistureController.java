package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.SoilMoistureResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Re-exposes the Python data service's NASA SMAP soil-moisture GeoJSON layer.
 */
@RestController
@Tag(name = "Environment", description = "Climate, soil moisture and terrain layers")
public class SoilMoistureController {

    private static final Logger log = LoggerFactory.getLogger(SoilMoistureController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service data backend service used to retrieve the typed layer
     */
    public SoilMoistureController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Returns the cached SMAP V008 layer or acquires the default layer through
     * the Python service when it is missing or stale.
     *
     * @return native 9 km GeoJSON cells with SMAP variable and provenance metadata
     */
    @GetMapping("/soil-moisture")
    @Operation(summary = "SMAP soil moisture map layer",
            description = "Returns native 9 km SMAP V008 cells as GeoJSON. Feature measurement properties retain "
                    + "null for unavailable values, while metadata describes each variable's units and provenance. "
                    + "An initial acquisition can require Earthdata credentials and take longer; this is not "
                    + "field-scale soil-test data.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cached or newly acquired SMAP GeoJSON layer."),
            @ApiResponse(responseCode = "502", description = "NASA SMAP request failed; retry later."),
            @ApiResponse(responseCode = "503", description = "Layer, credentials, dependencies, or cache unavailable.")
    })
    public SoilMoistureResponse soilMoisture() {
        log.trace("Entering soilMoisture()");
        log.info("Handling GET /soil-moisture");
        try {
            return service.soilMoisture();
        } finally {
            log.trace("Exiting soilMoisture()");
        }
    }
}
