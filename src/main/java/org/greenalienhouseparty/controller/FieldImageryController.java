package org.greenalienhouseparty.controller;

import java.time.Duration;
import java.time.LocalDate;

import org.greenalienhouseparty.client.RenderedImage;
import org.greenalienhouseparty.dto.FieldImageryResponse;
import org.greenalienhouseparty.dto.ImageryRequest;
import org.greenalienhouseparty.service.ImageryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Satellite RGB images of stored fields and user-provided outlines from NASA HLS
 * v2 (30 m) or Sentinel-2 (10 m), and the route that serves each image.
 */
@RestController
@Tag(name = "Imagery", description = "NASA HLS v2 (30 m) or Sentinel-2 (10 m) RGB field images with dates and cloud cover")
public class FieldImageryController {

    private static final Logger log = LoggerFactory.getLogger(FieldImageryController.class);

    private static final String IMAGERY_DESCRIPTION = "Lists true-colour satellite images of the field, newest first, "
            + "with acquisition time and whole-scene cloud cover. Default dataset=hls (NASA Harmonized Landsat "
            + "Sentinel-2, 30 m pixels); dataset=sentinel-2 gives native 10 m pixels. Images are cropped to the field's "
            + "bounding box, not masked to its outline, and capped at 1024 pixels. Cloud cover is for the whole scene, "
            + "so the field may still be cloudy. Each images[].imageUrl is a route on this API (prefix it with the API "
            + "base path) that returns the PNG; it is null if the image cannot be served. Dates default to the past "
            + "year, must be from 2013-04-11 (HLS) or 2015-06-27 (Sentinel-2) through today, and span at most 366 days. "
            + "An empty list means no matching scenes; hasMore means narrowing the dates will reveal older ones.";

    /** Past satellite scenes do not change, so browsers may reuse a rendered image for a day. */
    private static final CacheControl IMAGE_CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final ImageryService service;

    /**
     * Creates the controller.
     *
     * @param service the imagery service
     */
    public FieldImageryController(ImageryService service) {
        this.service = service;
    }

    /**
     * Lists satellite images of a stored field.
     *
     * @param fieldId  field identifier from {@code GET /fields}
     * @param dataset  {@code hls} or {@code sentinel-2}, or {@code null} for HLS
     * @param start    first acquisition date, or {@code null}
     * @param end      last acquisition date, or {@code null}
     * @param maxCloud maximum whole-scene cloud percentage, or {@code null}
     * @param limit    maximum number of scenes, or {@code null}
     * @return matching scenes with image routes on this API
     */
    @GetMapping("/fields/{field_id}/imagery")
    @Operation(summary = "Satellite images of a field", description = IMAGERY_DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching scenes, newest first."),
            @ApiResponse(responseCode = "404", description = "No field has this ID in the loaded data."),
            @ApiResponse(responseCode = "422", description = "Invalid dates or search options."),
            @ApiResponse(responseCode = "502", description = "Imagery catalogue unavailable or invalid."),
            @ApiResponse(responseCode = "503", description = "Field boundary data is unavailable.")
    })
    public FieldImageryResponse fieldImagery(
            @PathVariable("field_id") @Parameter(description = "Field identifier from /fields") String fieldId,
            @RequestParam(required = false)
            @Parameter(description = "hls (NASA HLS v2, 30 m) or sentinel-2 (native 10 m RGB); defaults to hls",
                    schema = @Schema(allowableValues = {"hls", "sentinel-2"}))
            String dataset,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "First acquisition date; defaults to one year before end")
            LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "Last acquisition date; defaults to today")
            LocalDate end,
            @RequestParam(required = false)
            @Parameter(description = "Maximum whole-scene cloud percentage, 0 to 100; defaults to 30")
            Double maxCloud,
            @RequestParam(required = false)
            @Parameter(description = "Maximum number of scenes, 1 to 20; defaults to 6")
            Integer limit) {
        log.trace("Entering fieldImagery()");
        log.info("Handling GET /fields/{field_id}/imagery");
        try {
            return service.fieldImagery(fieldId, dataset, start, end, maxCloud, limit);
        } finally {
            log.trace("Exiting fieldImagery()");
        }
    }

    /**
     * Lists satellite images of a GeoJSON outline supplied by the caller, such as
     * a field the farmer drew that is not in {@code GET /fields}.
     *
     * @param request the outline and optional search options
     * @return matching scenes with image routes on this API, without a field ID
     */
    @PostMapping("/imagery")
    @Operation(summary = "Satellite images of any outline",
            description = IMAGERY_DESCRIPTION + " Accepts a GeoJSON Polygon, MultiPolygon or Feature, or a ring of "
                    + "[longitude, latitude] positions, that fits within 1 by 1 degree.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching scenes, newest first."),
            @ApiResponse(responseCode = "422", description = "Invalid outline, dates, search options or unsupported "
                    + "request property."),
            @ApiResponse(responseCode = "502", description = "Imagery catalogue unavailable or invalid.")
    })
    public FieldImageryResponse imagery(@RequestBody ImageryRequest request) {
        log.trace("Entering imagery()");
        log.info("Handling POST /imagery");
        try {
            return service.imagery(request);
        } finally {
            log.trace("Exiting imagery()");
        }
    }

    /**
     * Returns one satellite image listed by the imagery routes.
     *
     * @param src the provider rendering address, as encoded in {@code imageUrl}
     * @return the image, cacheable for a day
     */
    @GetMapping(ImageryService.IMAGE_ROUTE)
    @Operation(summary = "One satellite image",
            description = "Returns the PNG for an images[].imageUrl from the imagery routes; use that URL as given "
                    + "rather than building one. Rendering a new image can take several seconds.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The rendered image.",
                    content = @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "422", description = "src is missing or is not an image address returned by "
                    + "the imagery routes."),
            @ApiResponse(responseCode = "502", description = "The imagery provider could not render the image.")
    })
    public ResponseEntity<byte[]> image(
            @RequestParam @Parameter(description = "Provider image address, as encoded in imageUrl") String src) {
        log.trace("Entering image()");
        log.info("Handling GET {}", ImageryService.IMAGE_ROUTE);
        try {
            RenderedImage image = service.render(src);
            return ResponseEntity.ok()
                    .contentType(image.contentType())
                    .cacheControl(IMAGE_CACHE)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(image.bytes());
        } finally {
            log.trace("Exiting image()");
        }
    }
}
