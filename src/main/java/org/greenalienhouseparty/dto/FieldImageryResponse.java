package org.greenalienhouseparty.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Satellite RGB images of a field or outline, as returned by the data backend's
 * {@code GET /fields/{field_id}/imagery} and {@code POST /imagery}.
 *
 * <p>Images are cropped to the outline's bounding box, not masked to the polygon,
 * and listed newest first. This application rewrites each {@link Image#imageUrl()}
 * to its own image route so the browser never contacts the imagery provider
 * directly; the provider address is kept in {@link Image#sourceImageUrl()}.</p>
 *
 * @param id      the field identifier, absent for caller-provided outlines
 * @param period  the searched acquisition period
 * @param bbox    WGS84 {@code [west, south, east, north]} of the image crop
 * @param images  matching scenes, newest first; empty when none match
 * @param hasMore whether more scenes match than were returned
 * @param dataset {@code hls} or {@code sentinel-2}
 * @param source  data source
 * @param note    how the images were selected and rendered
 */
public record FieldImageryResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Period period,
        @Schema(description = "WGS84 [west, south, east, north] of the image crop") List<Double> bbox,
        List<Image> images,
        @Schema(description = "More scenes match; narrow the dates to see older ones") Boolean hasMore,
        @Schema(description = "hls or sentinel-2", example = "hls") String dataset,
        Source source,
        String note) {

    /**
     * Returns a copy of this response with different images.
     *
     * @param replacement the images to use
     * @return a response identical apart from {@code images}
     */
    public FieldImageryResponse withImages(List<Image> replacement) {
        return new FieldImageryResponse(id, period, bbox, replacement, hasMore, dataset, source, note);
    }

    /**
     * The searched acquisition period.
     *
     * @param start first acquisition date, inclusive
     * @param end   last acquisition date, inclusive
     */
    public record Period(LocalDate start, LocalDate end) {
    }

    /**
     * One satellite scene rendered as an RGB image of the crop.
     *
     * @param sceneId            provider scene identifier
     * @param datetime           acquisition time
     * @param collection         provider collection, e.g. {@code hls2-l30}
     * @param platform           satellite, e.g. {@code landsat-8} or {@code sentinel-2b}
     * @param sceneCloudCoverPct cloud cover of the whole scene, %; the field itself may differ
     * @param imageUrl           this application's route for the PNG, or {@code null} when it cannot be served
     * @param sourceImageUrl     the provider's rendering address behind {@code imageUrl}
     * @param sceneBbox          WGS84 extent of the whole scene
     * @param sceneUrl           provider STAC metadata for the scene
     */
    public record Image(
            String sceneId,
            OffsetDateTime datetime,
            String collection,
            String platform,
            @Schema(description = "Cloud cover of the whole scene, %; the field itself may be clearer or cloudier")
            Double sceneCloudCoverPct,
            @Schema(description = "Route on this API that returns the PNG; prefix it with the API base path")
            String imageUrl,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "Provider rendering address behind imageUrl, for reference")
            String sourceImageUrl,
            @Schema(description = "WGS84 [west, south, east, north] of the whole scene") List<Double> sceneBbox,
            @Schema(description = "Provider STAC metadata for the scene") String sceneUrl) {

        /**
         * Returns a copy of this image served through a different address.
         *
         * @param url    the address clients should load, or {@code null}
         * @param source the provider rendering address
         * @return an image identical apart from its addresses
         */
        public Image withUrls(String url, String source) {
            return new Image(sceneId, datetime, collection, platform, sceneCloudCoverPct, url, source,
                    sceneBbox, sceneUrl);
        }
    }

    /**
     * Data source.
     *
     * @param name        source name
     * @param provider    service that hosts and renders the scenes
     * @param collections provider collections searched
     * @param resolutionM pixel size in metres
     * @param url         source website
     */
    public record Source(String name, String provider, List<String> collections, Integer resolutionM, String url) {
    }
}
