package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.CropCalendarRequest;
import org.greenalienhouseparty.dto.CropCalendarResponse;
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
 * Usual planting and maturity dates for crops around a field, re-exposed from
 * the data backend.
 */
@RestController
@Tag(name = "Crop calendar", description = "Usual planting and maturity dates from the GGCMI Phase 3 crop calendar")
public class CropCalendarController {

    private static final Logger log = LoggerFactory.getLogger(CropCalendarController.class);

    private static final String DESCRIPTION = "Returns, for each crop in the GGCMI Phase 3 crop calendar (NASA GISS "
            + "and partners), the usual planting and maturity day and date, growing-season length, the days the "
            + "ground is free of that crop, and whether the crop is planted in one year and matures in the next. "
            + "Crop keys: corn, soybeans, winter_wheat, spring_wheat, barley, rye, canola, potatoes, beans, peas, "
            + "sunflower, sugar_beet, sorghum, millet, rice_first_season, rice_second_season, cotton, groundnut, "
            + "cassava, sugarcane; oats, hay and fruit are not covered. Values are multi-year averages around the "
            + "year 2000 for a cell of about 50 km: a guide to the normal season, not a forecast or local variety "
            + "advice. Cells where a crop is not grown are filled in from neighbouring areas, so a date does not "
            + "mean the crop is grown there. The first request takes a few seconds.";

    private final DataBackendService service;

    /**
     * Creates the controller.
     *
     * @param service the data backend service
     */
    public CropCalendarController(DataBackendService service) {
        this.service = service;
    }

    /**
     * Retrieves the crop calendar around a stored field.
     *
     * @param fieldId field identifier from {@code GET /fields}
     * @param system  {@code rainfed} or {@code irrigated}, or {@code null} for rainfed
     * @param crops   comma-separated calendar crop keys, or {@code null} for every crop
     * @return usual planting and maturity dates around the field
     */
    @GetMapping("/fields/{field_id}/crop-calendar")
    @Operation(summary = "Usual planting and maturity dates around a field", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The crop calendar around the field."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "422", description = "Unknown system or crop key."),
            @ApiResponse(responseCode = "502", description = "The crop calendar files could not be downloaded or read."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public CropCalendarResponse fieldCropCalendar(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "rainfed (default) or irrigated") String system,
            @RequestParam(required = false)
            @Parameter(description = "Comma-separated calendar crop keys; all crops when omitted",
                    example = "corn,soybeans,winter_wheat") String crops) {
        log.trace("Entering fieldCropCalendar()");
        log.info("Handling GET /fields/{field_id}/crop-calendar");
        try {
            return service.fieldCropCalendar(fieldId, system, crops);
        } finally {
            log.trace("Exiting fieldCropCalendar()");
        }
    }

    /**
     * Retrieves the crop calendar around a GeoJSON outline supplied by the caller.
     *
     * @param request polygon and optional system and crops
     * @return usual planting and maturity dates around the outline
     */
    @PostMapping("/crop-calendar")
    @Operation(summary = "Usual planting and maturity dates around any outline", description = DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The crop calendar around the outline."),
            @ApiResponse(responseCode = "422", description = "Invalid polygon, unknown system or crop key, or unsupported request property."),
            @ApiResponse(responseCode = "502", description = "The crop calendar files could not be downloaded or read.")
    })
    public CropCalendarResponse cropCalendar(@RequestBody CropCalendarRequest request) {
        log.trace("Entering cropCalendar()");
        log.info("Handling POST /crop-calendar");
        try {
            return service.cropCalendar(request);
        } finally {
            log.trace("Exiting cropCalendar()");
        }
    }
}
