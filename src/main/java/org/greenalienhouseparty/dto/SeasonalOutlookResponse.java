package org.greenalienhouseparty.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Seasonal outlook at a location: the chance that each coming three-month
 * period is warmer or cooler, and wetter or drier, than normal.
 *
 * <p>Probabilities come from the Canadian Seasonal to Inter-annual Prediction
 * System (CanSIPS) and describe a cell of about 100 km. They are odds, not a
 * forecast of weather on any day.</p>
 *
 * @param location the point asked about
 * @param issued   month of the model run, {@code YYYY-MM}; {@code null} when no outlook is available here
 * @param periods  consecutive three-month periods, nearest first
 * @param source   where the outlook comes from
 * @param note     how to read the outlook
 */
public record SeasonalOutlookResponse(
        Location location,
        String issued,
        List<Period> periods,
        Source source,
        String note) {

    /**
     * The point asked about.
     *
     * @param lat latitude
     * @param lon longitude
     */
    public record Location(Double lat, Double lon) {
    }

    /**
     * The outlook for one three-month period.
     *
     * @param startMonth    first month, {@code YYYY-MM}
     * @param endMonth      last month, {@code YYYY-MM}
     * @param label         the period in words, for example {@code Nov 2026 - Jan 2027}
     * @param temperature   chances for temperature
     * @param precipitation chances for precipitation
     */
    public record Period(
            String startMonth,
            String endMonth,
            String label,
            Chances temperature,
            Chances precipitation) {
    }

    /**
     * The chance of each third of the historical range. With no information
     * each would be about 33%. Values are {@code null} when the service has
     * none for the point.
     *
     * @param abovePct   chance of above normal, in percent
     * @param nearPct    chance of near normal, in percent
     * @param belowPct   chance of below normal, in percent
     * @param mostLikely {@code above}, {@code near} or {@code below} when one chance reaches 40%,
     *                   otherwise {@code no clear signal}; {@code null} without values
     */
    public record Chances(
            Double abovePct,
            Double nearPct,
            Double belowPct,
            @Schema(allowableValues = {"above", "near", "below", "no clear signal"}) String mostLikely) {
    }

    /**
     * Provenance of the outlook.
     *
     * @param name          forecast system
     * @param provider      publisher
     * @param url           service URL
     * @param resolutionKm  grid size in kilometres
     * @param license       licence the data is published under
     */
    public record Source(String name, String provider, String url, Integer resolutionKm, String license) {
    }
}
