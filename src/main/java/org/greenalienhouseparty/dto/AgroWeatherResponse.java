package org.greenalienhouseparty.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Soil wetness, solar radiation, wind, humidity and Penman-Monteith reference
 * evapotranspiration around a field or drawn outline, from NASA POWER.
 *
 * <p>Values describe the roughly 50 km cell around the field and are modelled,
 * not measured in the field. Soil wetness runs from 0 (dry) to 1 (saturated).</p>
 *
 * @param id           field identifier, present only for the field-specific route
 * @param location     the sampled point and its grid-cell elevation
 * @param period       first and last day read
 * @param season       growing-season months used for season values
 * @param soilWetness  latest, monthly and season wetness
 * @param et0          reference evapotranspiration
 * @param seasonMeans  typical growing-season radiation, wind and humidity
 * @param seasonByYear growing-season values for each year
 * @param units        unit of each value, keyed by its name
 * @param source       source dataset metadata
 * @param note         interpretation and limitations
 */
public record AgroWeatherResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Location location,
        Period period,
        Season season,
        SoilWetness soilWetness,
        Et0 et0,
        SeasonMeans seasonMeans,
        List<SeasonYear> seasonByYear,
        Map<String, String> units,
        Source source,
        String note) {

    /**
     * The sampled point.
     *
     * @param lat        latitude
     * @param lon        longitude
     * @param elevationM elevation of the weather grid cell in metres
     */
    public record Location(Double lat, Double lon, Double elevationM) {
    }

    /**
     * The period read, as ISO dates.
     *
     * @param start first day, {@code YYYY-MM-DD}
     * @param end   last day with data, {@code YYYY-MM-DD}
     */
    public record Period(String start, String end) {
    }

    /**
     * The growing season used.
     *
     * @param months consecutive growing months
     */
    public record Season(List<Integer> months) {
    }

    /**
     * Soil wetness now, by calendar month and across growing seasons.
     *
     * @param latest            the newest values compared with earlier years, or {@code null} without data
     * @param monthly           usual wetness for each calendar month
     * @param seasonPercentiles spread of the growing-season mean across years, keyed {@code rootZone}
     *                          and {@code surface}
     */
    public record SoilWetness(Latest latest, List<MonthlyWetness> monthly, Map<String, Percentiles> seasonPercentiles) {
    }

    /**
     * The newest soil wetness and how it compares with the same 30 days in earlier years.
     *
     * @param date              date of the newest value, {@code YYYY-MM-DD}
     * @param rootZone          newest root-zone wetness, 0 to 1
     * @param surface           newest surface wetness, 0 to 1
     * @param rootZone30DayMean mean root-zone wetness over the latest 30 days
     * @param percentile        share of earlier years that were as dry or drier in the same 30 days, 0 to 100;
     *                          {@code null} with fewer than five earlier years
     * @param comparedYears     number of earlier years compared
     * @param status            {@code dry}, {@code normal} or {@code wet}; {@code null} without a percentile
     */
    public record Latest(
            String date,
            Double rootZone,
            Double surface,
            Double rootZone30DayMean,
            Integer percentile,
            Integer comparedYears,
            String status) {
    }

    /**
     * Usual soil wetness in one calendar month.
     *
     * @param month    calendar month, 1 to 12
     * @param rootZone mean root-zone wetness
     * @param surface  mean surface wetness
     */
    public record MonthlyWetness(Integer month, Double rootZone, Double surface) {
    }

    /**
     * Spread of a value across years.
     *
     * @param p10   10th percentile
     * @param p50   median
     * @param p90   90th percentile
     * @param years number of years used
     */
    public record Percentiles(Double p10, Double p50, Double p90, Integer years) {
    }

    /**
     * Reference evapotranspiration.
     *
     * @param method              how it was computed
     * @param seasonPercentilesMm spread of the growing-season total in millimetres across years
     * @param monthlyMmPerDay     usual daily value for each calendar month
     */
    public record Et0(String method, Percentiles seasonPercentilesMm, List<MonthlyEt0> monthlyMmPerDay) {
    }

    /**
     * Usual reference evapotranspiration in one calendar month.
     *
     * @param month calendar month, 1 to 12
     * @param et0   millimetres per day
     */
    public record MonthlyEt0(Integer month, Double et0) {
    }

    /**
     * Typical growing-season conditions (median across years).
     *
     * @param solarMjM2Day solar radiation in MJ per square metre per day
     * @param windMs       wind speed at 2 m in metres per second
     * @param humidityPct  relative humidity in percent
     */
    public record SeasonMeans(Double solarMjM2Day, Double windMs, Double humidityPct) {
    }

    /**
     * Growing-season values for one year; {@code null} when the season lacks enough days.
     *
     * @param year         the season's start year
     * @param rootZone     mean root-zone wetness
     * @param surface      mean surface wetness
     * @param et0Mm        reference evapotranspiration total in millimetres
     * @param solarMjM2Day mean solar radiation
     * @param windMs       mean wind speed
     * @param humidityPct  mean relative humidity
     */
    public record SeasonYear(
            Integer year,
            Double rootZone,
            Double surface,
            Double et0Mm,
            Double solarMjM2Day,
            Double windMs,
            Double humidityPct) {
    }

    /**
     * Provenance of the values.
     *
     * @param name         dataset name
     * @param url          API URL
     * @param resolutionKm nominal grid size in kilometres
     * @param variables    NASA POWER variable codes read
     */
    public record Source(String name, String url, Integer resolutionKm, List<String> variables) {
    }
}
