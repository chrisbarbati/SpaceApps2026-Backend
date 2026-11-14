package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldSoilBatchRequest;
import org.greenalienhouseparty.dto.FieldSoilBatchResponse;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Topsoil estimates for stored fields, from ISRIC SoilGrids 2.0.
 */
@RestController
@Tag(name = "Soil", description = "Topsoil texture, pH, organic matter and water holding for fields")
public class FieldSoilController {

    private static final Logger log = LoggerFactory.getLogger(FieldSoilController.class);

    private static final String SOIL_DESCRIPTION = "Topsoil estimates from ISRIC SoilGrids 2.0 at 250 m, averaged over "
            + "every cell the field touches: 0-30 cm pH, clay/sand/silt, organic carbon, nitrogen, bulk density and CEC, "
            + "with the USDA texture class, organic matter %, pH class and plant-available water in the top 30 cm. "
            + "These are estimates, not a soil test; values are null over water, ice or built-up land.";

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public FieldSoilController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves topsoil estimates for a stored field.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @return topsoil estimates with derived classes
     */
    @GetMapping("/fields/{field_id}/soil")
    @Operation(summary = "Topsoil of a field",
            description = SOIL_DESCRIPTION + " A new field takes about 2 seconds; repeats return at once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Topsoil estimates for the field."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "502", description = "SoilGrids could not be reached or read."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public FieldSoilResponse fieldSoil(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId) {
        log.trace("Entering fieldSoil()");
        log.info("Handling GET /fields/{field_id}/soil");
        try {
            return service.fieldSoil(fieldId);
        } finally {
            log.trace("Exiting fieldSoil()");
        }
    }

    /**
     * Retrieves topsoil estimates for several stored fields, such as a map
     * selection, in one request.
     *
     * @param request the field IDs
     * @return one result per unique requested field, in request order
     */
    @PostMapping("/fields/soil")
    @Operation(summary = "Topsoil of several fields",
            description = SOIL_DESCRIPTION + " Up to 300 fields per request, in the order given, with duplicates "
                    + "answered once. Each result has status ok with the same soil as GET /fields/{field_id}/soil, or "
                    + "status error with a code (404 unknown field, 502 SoilGrids failure) and detail; a failed field "
                    + "never fails the others, so check failed. Nearby fields are read together: about 2 seconds for "
                    + "100 new fields, and cached fields return at once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One result per requested field."),
            @ApiResponse(responseCode = "422", description = "No IDs, a blank ID, or more than 300 IDs."),
            @ApiResponse(responseCode = "502", description = "The data backend could not be reached."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public FieldSoilBatchResponse fieldSoils(@RequestBody FieldSoilBatchRequest request) {
        log.trace("Entering fieldSoils()");
        log.info("Handling POST /fields/soil");
        try {
            return service.fieldSoils(request);
        } finally {
            log.trace("Exiting fieldSoils()");
        }
    }
}
