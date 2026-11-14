package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Crop-history endpoints for stored fields and user-provided outlines.
 */
@RestController
@Tag(name = "Crops", description = "Year-by-year field crop classifications")
public class CropHistoryController {

    private static final Logger log = LoggerFactory.getLogger(CropHistoryController.class);

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public CropHistoryController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves crop history for an indexed field without requiring its geometry
     * in the request.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @return year-by-year crop classifications and source metadata
     */
    @GetMapping("/fields/{field_id}/crop-history")
    @Operation(summary = "Crop history of a field",
            description = "Returns dominant crop-map classes and their shares for a field selected from /fields.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Year-by-year crop classes for the field."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "502", description = "A crop map provider could not be reached."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public CropHistoryResponse fieldCropHistory(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId) {
        log.trace("Entering fieldCropHistory()");
        log.info("Handling GET /fields/{field_id}/crop-history");
        try {
            return service.fieldCropHistory(fieldId);
        } finally {
            log.trace("Exiting fieldCropHistory()");
        }
    }

    /**
     * Retrieves crop history for a GeoJSON outline supplied by the caller.
     *
     * @param request polygon to inspect
     * @return year-by-year crop classifications and source metadata
     */
    @PostMapping("/crop-history")
    @Operation(summary = "Crop history of any outline",
            description = "Returns annual crop-map classes for a GeoJSON Polygon, MultiPolygon, Feature, or coordinate ring.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Year-by-year crop classes for the outline."),
            @ApiResponse(responseCode = "422", description = "Invalid polygon or unsupported request property."),
            @ApiResponse(responseCode = "502", description = "A crop map provider could not be reached.")
    })
    public CropHistoryResponse cropHistory(@RequestBody CropHistoryRequest request) {
        log.trace("Entering cropHistory()");
        log.info("Handling POST /crop-history");
        try {
            return service.cropHistory(request);
        } finally {
            log.trace("Exiting cropHistory()");
        }
    }
}
