package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How wet or dry the groundwater and soil are around a field or drawn outline
 * compared with history, from NASA's GRACE and GRACE-FO drought indicators.
 *
 * <p>Each value is a percentile against the same time of year over the
 * baseline period: 50 is typical and low is dry. Cells are about 25 km, so
 * this describes the district's water rather than one field, and the newest
 * week is usually about two months old.</p>
 *
 * @param id             field identifier, present only for the field-specific route
 * @param location       the field's centre
 * @param cell           the grid cell read
 * @param latest         the newest week, with a plain-language condition per layer
 * @param weekly         recent weeks, oldest first
 * @param sameWeekByYear the same time of year in earlier years, oldest first
 * @param conditions     the percentile limits behind each condition
 * @param baseline       the years the percentiles are measured against
 * @param source         source dataset metadata
 * @param note           interpretation and limitations
 */
public record DroughtIndicatorsResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Location location,
        Cell cell,
        Latest latest,
        List<Week> weekly,
        List<Week> sameWeekByYear,
        List<ConditionLimit> conditions,
        String baseline,
        Source source,
        String note) {

    /**
     * The field's centre.
     *
     * @param lat latitude
     * @param lon longitude
     */
    public record Location(Double lat, Double lon) {
    }

    /**
     * The grid cell the values come from.
     *
     * @param lat     latitude of the cell centre
     * @param lon     longitude of the cell centre
     * @param sizeDeg cell size in degrees
     */
    public record Cell(Double lat, Double lon, Double sizeDeg) {
    }

    /**
     * The newest published week.
     *
     * @param weekStart   first day of the week, {@code YYYY-MM-DD}
     * @param ageDays     days between that day and today
     * @param groundwater groundwater storage
     * @param rootZone    soil moisture in the top metre
     * @param surface     soil moisture in the top few centimetres
     */
    public record Latest(String weekStart, Integer ageDays, Layer groundwater, Layer rootZone, Layer surface) {
    }

    /**
     * One layer's state; both values are {@code null} over ocean and ice.
     *
     * @param percentile percentile against the same time of year in the baseline, 0 to 100
     * @param condition  the percentile in words, from {@code exceptionally dry} to {@code much wetter than normal}
     */
    public record Layer(Double percentile, String condition) {
    }

    /**
     * One week's percentiles.
     *
     * @param weekStart             first day of the week, {@code YYYY-MM-DD}
     * @param groundwaterPercentile groundwater storage percentile
     * @param rootZonePercentile    root-zone soil moisture percentile
     * @param surfacePercentile     surface soil moisture percentile
     */
    public record Week(
            String weekStart,
            Double groundwaterPercentile,
            Double rootZonePercentile,
            Double surfacePercentile) {
    }

    /**
     * The upper limit of one condition.
     *
     * @param upToPercentile highest percentile that still counts as this condition
     * @param condition      the condition in words
     */
    public record ConditionLimit(Integer upToPercentile, String condition) {
    }

    /**
     * Provenance of the indicators.
     *
     * @param name          dataset name
     * @param product       NASA product short name
     * @param url           dataset information URL
     * @param resolutionDeg grid size in degrees
     * @param frequency     how often it is published
     * @param institution   who produces it
     */
    public record Source(
            String name,
            String product,
            String url,
            Double resolutionDeg,
            String frequency,
            String institution) {
    }
}
