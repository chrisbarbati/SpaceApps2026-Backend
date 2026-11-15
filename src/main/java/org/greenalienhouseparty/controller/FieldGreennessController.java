package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldGreennessResponse;
import org.greenalienhouseparty.dto.GreennessRequest;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Greenness through a growing season for stored fields and user-provided
 * outlines, from Sentinel-2 imagery.
 */
@RestController
@Tag(name = "Crops", description = "Year-by-year field crop classifications")
public class FieldGreennessController {

    private static final Logger log = LoggerFactory.getLogger(FieldGreennessController.class);

    private static final String GREENNESS_DESCRIPTION = "Returns Sentinel-2 NDVI at 10 m for each clear overpass "
            + "(about every 2-5 days) from April 1 to November 15, with the season's peak, latest value and green-up "
            + "date. Bare soil is about 0.1-0.2 NDVI and a full crop canopy 0.6-0.9.";

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public FieldGreennessController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves Sentinel-2 NDVI through one growing season for a stored field.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @param year    growing season year, or {@code null} for the most recent season
     * @return clear-sky NDVI observations and a season summary
     */
    @GetMapping("/fields/{field_id}/greenness")
    @Operation(summary = "Greenness of a field through a season",
            description = GREENNESS_DESCRIPTION + " The first request for a field and season takes about 10 seconds; "
                    + "repeats return at once, so show a loading state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Clear-sky NDVI observations and a season summary."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "422", description = "Year outside 2017 to this year."),
            @ApiResponse(responseCode = "502", description = "Sentinel-2 imagery could not be reached."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public FieldGreennessResponse fieldGreenness(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "Growing season year, 2017 to this year; defaults to the most recent season")
            Integer year) {
        log.trace("Entering fieldGreenness()");
        log.info("Handling GET /fields/{field_id}/greenness");
        try {
            return service.fieldGreenness(fieldId, year);
        } finally {
            log.trace("Exiting fieldGreenness()");
        }
    }

    /**
     * Retrieves Sentinel-2 NDVI through one growing season for a GeoJSON outline
     * supplied by the caller, such as a field the farmer drew that is not in
     * {@code GET /fields}.
     *
     * @param request the outline to inspect and optional growing season year
     * @return clear-sky NDVI observations and a season summary, without a field ID
     */
    @PostMapping("/greenness")
    @Operation(summary = "Greenness of any outline through a season",
            description = GREENNESS_DESCRIPTION + " Accepts a GeoJSON Polygon, MultiPolygon or Feature, or a ring of "
                    + "[longitude, latitude] positions, with an optional year (2017 to this year; defaults to the most "
                    + "recent season). The first request for an outline and season takes about 10 seconds; repeats "
                    + "return at once, so show a loading state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Clear-sky NDVI observations and a season summary."),
            @ApiResponse(responseCode = "422", description = "Invalid polygon, year outside 2017 to this year, or "
                    + "unsupported request property."),
            @ApiResponse(responseCode = "502", description = "Sentinel-2 imagery could not be reached.")
    })
    public FieldGreennessResponse greenness(@RequestBody GreennessRequest request) {
        log.trace("Entering greenness()");
        log.info("Handling POST /greenness");
        try {
            return service.greenness(request);
        } finally {
            log.trace("Exiting greenness()");
        }
    }
}
