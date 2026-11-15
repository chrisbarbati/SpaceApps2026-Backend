package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.DroughtIndicatorsRequest;
import org.greenalienhouseparty.dto.DroughtIndicatorsResponse;
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
 * Groundwater and soil moisture compared with history around a field, from
 * NASA's GRACE and GRACE-FO drought indicators, re-exposed from the data
 * backend.
 */
@RestController
@Tag(name = "Drought indicators", description = "NASA GRACE groundwater and soil moisture percentiles")
public class DroughtIndicatorsController {

    private static final Logger log = LoggerFactory.getLogger(DroughtIndicatorsController.class);

    private static final String DESCRIPTION = "Returns how wet or dry the groundwater, root-zone soil moisture (top "
            + "metre) and surface soil moisture are around the field, from NASA's GRACE and GRACE-FO drought "
            + "indicators (0.25 degrees, weekly). Each value is a percentile against the same time of year over "
            + "1948-2012: 50 is typical, 10 is drier than nine years in ten. latest gives the newest week with a "
            + "condition in words per layer; weekly gives the recent weeks; sameWeekByYear gives the same time of "
            + "year in earlier years, for the longer trend; conditions lists the percentile limits. Cells are "
            + "about 25 km, so this is the district's water, not one field, and the newest week is usually about "
            + "two months old (latest.ageDays). The first request can take about 20 seconds while files are "
            + "downloaded; later requests for any field return at once.";

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public DroughtIndicatorsController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves drought indicators around a stored field.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @param weeks   recent weeks to return, or {@code null} for the default of 12
     * @param years   earlier years to compare, or {@code null} for the default of 10
     * @return groundwater and soil moisture percentiles around the field
     */
    @GetMapping("/fields/{field_id}/drought-indicators")
    @Operation(summary = "Groundwater and soil moisture percentiles around a field", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Drought indicators around the field."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "422", description = "weeks outside 1 to 52 or years outside 0 to 23."),
            @ApiResponse(responseCode = "502", description = "NASA's catalogue or files could not be reached."),
            @ApiResponse(responseCode = "503", description = "Earthdata credentials are not configured on the data "
                    + "backend, or field boundary data is unavailable.")
    })
    public DroughtIndicatorsResponse fieldDroughtIndicators(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "Recent weeks to return, 1 to 52; defaults to 12") Integer weeks,
            @RequestParam(required = false)
            @Parameter(description = "Earlier years to compare at the same time of year, 0 to 23; defaults to 10")
            Integer years) {
        log.trace("Entering fieldDroughtIndicators()");
        log.info("Handling GET /fields/{field_id}/drought-indicators");
        try {
            return service.fieldDroughtIndicators(fieldId, weeks, years);
        } finally {
            log.trace("Exiting fieldDroughtIndicators()");
        }
    }

    /**
     * Retrieves drought indicators around a GeoJSON outline supplied by the caller.
     *
     * @param request polygon and optional weeks and years
     * @return groundwater and soil moisture percentiles around the outline
     */
    @PostMapping("/drought-indicators")
    @Operation(summary = "Groundwater and soil moisture percentiles around any outline", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Drought indicators around the outline."),
            @ApiResponse(responseCode = "422", description = "Invalid polygon, weeks or years out of range, or unsupported request property."),
            @ApiResponse(responseCode = "502", description = "NASA's catalogue or files could not be reached."),
            @ApiResponse(responseCode = "503", description = "Earthdata credentials are not configured on the data backend.")
    })
    public DroughtIndicatorsResponse droughtIndicators(@RequestBody DroughtIndicatorsRequest request) {
        log.trace("Entering droughtIndicators()");
        log.info("Handling POST /drought-indicators");
        try {
            return service.droughtIndicators(request);
        } finally {
            log.trace("Exiting droughtIndicators()");
        }
    }
}
