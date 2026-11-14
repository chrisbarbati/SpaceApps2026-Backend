package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.DailyPointResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * NASA POWER daily point endpoint, re-exposed from the data backend.
 */
@RestController
@RequestMapping("/daily-point")
@Tag(name = "Daily Point", description = "NASA POWER daily point data")
public class DailyPointController {

    private static final Logger log = LoggerFactory.getLogger(DailyPointController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public DailyPointController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves NASA POWER daily point data for a single coordinate.
     *
     * @param lat        the latitude to sample, between -90 and 90
     * @param lon        the longitude to sample, between -180 and 180
     * @param start      the inclusive start date, as {@code YYYYMMDD}
     * @param end        the inclusive end date, as {@code YYYYMMDD}
     * @param parameters the comma-separated POWER parameters
     * @return the daily point response
     */
    @GetMapping
    @Operation(summary = "NASA POWER daily point data",
            description = "Returns daily NASA POWER observations for a single point.")
    public DailyPointResponse dailyPoint(
            @RequestParam @Parameter(description = "Latitude, between -90 and 90") double lat,
            @RequestParam @Parameter(description = "Longitude, between -180 and 180") double lon,
            @RequestParam @Parameter(description = "Inclusive start date, YYYYMMDD") String start,
            @RequestParam @Parameter(description = "Inclusive end date, YYYYMMDD") String end,
            @RequestParam(defaultValue = "T2M,PRECTOTCORR")
            @Parameter(description = "Comma-separated POWER parameters") String parameters) {
        log.trace("Entering dailyPoint()");
        log.info("Handling GET /daily-point");
        try {
            return service.dailyPoint(lat, lon, start, end, parameters);
        } finally {
            log.trace("Exiting dailyPoint()");
        }
    }
}
