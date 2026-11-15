package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.VegetationHistoryRequest;
import org.greenalienhouseparty.dto.VegetationHistoryResponse;
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
 * A field's long vegetation record from NASA MODIS, re-exposed from the data
 * backend.
 */
@RestController
@Tag(name = "Vegetation history", description = "MODIS NDVI every 16 days and yearly growth and water use")
public class VegetationHistoryController {

    private static final Logger log = LoggerFactory.getLogger(VegetationHistoryController.class);

    private static final String DESCRIPTION = "Returns NASA MODIS/Terra data for the field. ndvi.observations is NDVI "
            + "every 16 days at 250 m for the last 1 to 10 years (default 5); ndvi.byYear gives each year's peak, "
            + "green-up and brown-down dates; ndvi.calendar gives the usual NDVI for each 16-day period of the year "
            + "and how often the field had little green cover (NDVI under 0.3, or snow); ndvi.lowCoverWindows joins "
            + "those into the date ranges when the field usually has little green cover. yearly gives net primary "
            + "production, evapotranspiration and potential evapotranspiration for every year since 2001. Pixels "
            + "are 250 to 500 m, so small fields share values with their surroundings, and low cover includes "
            + "stubble and young crops, not only bare soil. The first request for a field takes about 30 seconds "
            + "for 5 years and 45 for 10, so show a loading state; repeats return at once.";

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public VegetationHistoryController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves the vegetation history of a stored field.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @param years   years of 16-day NDVI to read, or {@code null} for the default of 5
     * @return NDVI seasons, low-cover windows and yearly growth and water use
     */
    @GetMapping("/fields/{field_id}/vegetation-history")
    @Operation(summary = "Long vegetation record of a field", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The field's vegetation history."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "422", description = "years outside 1 to 10."),
            @ApiResponse(responseCode = "502", description = "The MODIS catalogue or files could not be reached."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public VegetationHistoryResponse fieldVegetationHistory(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "Years of 16-day NDVI to read, 1 to 10; defaults to 5") Integer years) {
        log.trace("Entering fieldVegetationHistory()");
        log.info("Handling GET /fields/{field_id}/vegetation-history");
        try {
            return service.fieldVegetationHistory(fieldId, years);
        } finally {
            log.trace("Exiting fieldVegetationHistory()");
        }
    }

    /**
     * Retrieves the vegetation history of a GeoJSON outline supplied by the caller.
     *
     * @param request polygon and optional years of NDVI
     * @return NDVI seasons, low-cover windows and yearly growth and water use
     */
    @PostMapping("/vegetation-history")
    @Operation(summary = "Long vegetation record of any outline", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The outline's vegetation history."),
            @ApiResponse(responseCode = "422", description = "Invalid polygon, years outside 1 to 10, or unsupported request property."),
            @ApiResponse(responseCode = "502", description = "The MODIS catalogue or files could not be reached.")
    })
    public VegetationHistoryResponse vegetationHistory(@RequestBody VegetationHistoryRequest request) {
        log.trace("Entering vegetationHistory()");
        log.info("Handling POST /vegetation-history");
        try {
            return service.vegetationHistory(request);
        } finally {
            log.trace("Exiting vegetationHistory()");
        }
    }
}
