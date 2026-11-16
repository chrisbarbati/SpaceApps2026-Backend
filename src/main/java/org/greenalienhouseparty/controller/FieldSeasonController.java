package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.dto.FieldSeasonResponse;
import org.greenalienhouseparty.service.FieldSeasonService;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Exposes Java-prepared HLS seasonal evidence without blocking on full-season acquisition. */
@RestController
@Tag(name = "Field season", description = "Incremental NASA HLS observations and quality-aware seasonal evidence")
public class FieldSeasonController {
    private final FieldSeasonService service;

    /** Creates the controller with the application preparation service. */
    public FieldSeasonController(FieldSeasonService service) {
        this.service = service;
    }

    /** Starts or polls a season; repeat while status is queued or running. */
    @GetMapping("/fields/{field_id}/season")
    @Operation(summary = "Satellite greenness through a field's season",
            description = "Python acquires native HLS QA and reflectance. Java chooses the calendar period, assigns quality, selects one observation per day and prepares line segments and gaps. This endpoint does not infer a crop or harvest date. Retry only terminal partial/failed acquisitions. "
                    + "With pixels=true, grid carries the 30 m pixel window and fieldMask, encoding describes the byte layouts, and each point that is at least half clear carries base64 pixels. "
                    + "Pass the previous observationCount as since: every point is still returned, but pixels only for observations acquired since then, so keep earlier pictures by sceneId.")
    public FieldSeasonResponse fieldSeason(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false) @Parameter(description = "Calendar year, 2013 through the current year; defaults to the current year") Integer year,
            @RequestParam(defaultValue = "false") @Parameter(description = "Retry failed scene acquisition") boolean retry,
            @RequestParam(defaultValue = "false") @Parameter(description = "Include display pixels (colour, NDVI, clear mask), the field mask and their encoding") boolean pixels,
            @RequestParam(defaultValue = "0") @Parameter(description = "observationCount from the previous poll; pixels are sent only for newer observations") int since) {
        return service.fieldSeason(fieldId, year, retry, pixels, since);
    }
}
