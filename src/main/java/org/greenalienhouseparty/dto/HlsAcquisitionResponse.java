package org.greenalienhouseparty.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Native HLS measurements and acquisition progress returned by Python, without crop interpretation. */
public record HlsAcquisitionResponse(String id, String jobId, String status, Period period, Progress progress,
        Grid grid, List<Observation> observations, List<Failure> failures, Source source, String error,
        Double firstObservationSeconds, Double elapsedSeconds) {
    /** Explicit inclusive acquisition dates. */
    public record Period(LocalDate start, LocalDate end) { }
    /** Number of attempted and discovered scene reads; total is null during discovery. */
    public record Progress(Integer processed, Integer total) { }
    /** Native grid sampling support, including whether mixed boundary pixels were excluded. */
    public record Grid(Integer resolutionM, Integer fieldPixels, Integer samplePixels, Boolean interiorOnly) { }
    /** One actual overpass; NDVI is null when spectral measurements were not usable or not read. */
    public record Observation(String sceneId, LocalDate date, Instant datetime, String sensor, String collection,
            Double coverageShare, Double clearShare, Double cloudShare, Double snowShare, Double waterShare,
            Double sampleClearShare, Integer validPixels, Double ndviMean, Double ndviP10, Double ndviP90) { }
    /** A scene that failed acquisition; no substitute measurement is supplied. */
    public record Failure(String sceneId, String message) { }
    /** Product and provider provenance. */
    public record Source(String name, String provider, Integer resolutionM, String url, List<String> collections) { }
}
