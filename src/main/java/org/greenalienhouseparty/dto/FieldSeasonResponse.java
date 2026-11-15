package org.greenalienhouseparty.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Java-prepared seasonal evidence, with display quality and explicit gaps; it does not identify a crop. */
public record FieldSeasonResponse(String id, int year, String jobId, String status, String message,
        HlsAcquisitionResponse.Period period, HlsAcquisitionResponse.Progress progress,
        HlsAcquisitionResponse.Grid grid, List<Point> points, List<List<Point>> segments, List<Gap> gaps,
        Summary summary, List<Double> axisTicks, List<String> notes, HlsAcquisitionResponse.Source source,
        int failedScenes, String error) {
    /** One daily observation, chosen by measurement support rather than greenness magnitude. */
    public record Point(LocalDate date, String sceneId, String sensor,
            @Schema(allowableValues = {"clear", "partial", "cloudy", "snow", "water", "unobserved"}) String quality,
            String description, Double ndvi, Double p10, Double p90, double clearShare,
            double cloudShare, double snowShare, double waterShare, double coverageShare, int validPixels) { }
    /** A time interval without sufficiently clear observations; no interpolation is implied. */
    public record Gap(LocalDate start, LocalDate end, long days) { }
    /** Observed peak and latest clear measurement; unavailable values are null. */
    public record Summary(int clearObservations, int partialObservations, int otherObservations,
            Double peakNdvi, LocalDate peakDate, Double latestNdvi, LocalDate latestDate, long longestGapDays) { }
}
