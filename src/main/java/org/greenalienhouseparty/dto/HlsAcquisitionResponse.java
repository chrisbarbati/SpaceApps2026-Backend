package org.greenalienhouseparty.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Native HLS measurements and acquisition progress returned by Python, without crop interpretation.
 * Pixel arrays, the field mask and their encoding are present only when requested with {@code pixels=true}.
 */
public record HlsAcquisitionResponse(String id, String jobId, String status, Period period, Progress progress,
        Grid grid, List<Observation> observations, List<Failure> failures, Source source, String error,
        Double firstObservationSeconds, Double elapsedSeconds, Integer count, Map<String, String> encoding) {
    /** Explicit inclusive acquisition dates. */
    public record Period(LocalDate start, LocalDate end) { }
    /** Number of attempted and discovered scene reads; total is null during discovery. */
    public record Progress(Integer processed, Integer total) { }

    /**
     * Native grid sampling support, including whether mixed boundary pixels were excluded, and the pixel
     * window (field plus a context margin) that observation pixels and the field mask describe.
     */
    public record Grid(Integer resolutionM, Integer fieldPixels, Integer samplePixels, Boolean interiorOnly,
            String tile, String crs, List<Double> transform, Integer width, Integer height, Integer contextM,
            String fieldMask, Double areaHa) { }

    /** One actual overpass; NDVI is null when spectral measurements were not usable or not read. */
    public record Observation(String sceneId, LocalDate date, Instant datetime, String sensor, String collection,
            Double coverageShare, Double clearShare, Double cloudShare, Double snowShare, Double waterShare,
            Double sampleClearShare, Integer validPixels, Double ndviMean, Double ndviP10, Double ndviP90,
            Double ndviP50, Double sceneCloudPct, Pixels pixels) { }

    /** Base64 display arrays over the grid window; see the response encoding for byte layouts. */
    public record Pixels(String rgb, String ndvi, String clear) { }
    /** A scene that failed acquisition; no substitute measurement is supplied. */
    public record Failure(String sceneId, String message) { }
    /** Product and provider provenance. */
    public record Source(String name, String provider, Integer resolutionM, String url, List<String> collections) { }
}
