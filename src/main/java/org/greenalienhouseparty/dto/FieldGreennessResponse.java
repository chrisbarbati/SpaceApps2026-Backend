package org.greenalienhouseparty.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How green a field was through one growing season, from Sentinel-2 NDVI at 10 m,
 * as returned by the data backend's {@code GET /fields/{field_id}/greenness}.
 *
 * <p>NDVI is about 0.1-0.2 for bare soil and 0.6-0.9 for a full crop canopy.</p>
 *
 * @param id               the field identifier
 * @param year             the growing season year
 * @param period           the dates searched for overpasses
 * @param observations     one entry per clear overpass, oldest first
 * @param summary          peak, latest and green-up for the season
 * @param scenesChecked    satellite scenes examined
 * @param scenesUnreadable scenes that could not be read
 * @param source           imagery source
 * @param note             how the values were derived
 */
public record FieldGreennessResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Integer year,
        Period period,
        List<Observation> observations,
        Summary summary,
        Integer scenesChecked,
        Integer scenesUnreadable,
        Source source,
        String note) {

    /**
     * Dates searched: April 1 to November 15 north of the equator, up to today for the current year.
     *
     * @param start first date searched
     * @param end   last date searched
     */
    public record Period(LocalDate start, LocalDate end) {
    }

    /**
     * One clear Sentinel-2 overpass.
     *
     * @param date       overpass date
     * @param ndvi       mean NDVI over the clear part of the field
     * @param ndviP10    10th percentile NDVI across the field's pixels
     * @param ndviP90    90th percentile NDVI; a wide spread means an uneven field
     * @param clearShare share of the field that was clear (at least 0.5)
     */
    public record Observation(
            LocalDate date,
            Double ndvi,
            @Schema(description = "10th percentile NDVI across the field's pixels") Double ndviP10,
            @Schema(description = "90th percentile NDVI across the field's pixels") Double ndviP90,
            @Schema(description = "Share of the field that was clear, 0.5-1") Double clearShare) {
    }

    /**
     * Season summary. Values are {@code null} when there were no clear overpasses.
     *
     * @param peakNdvi    highest mean NDVI of the season
     * @param peakDate    date of the peak
     * @param latestNdvi  most recent mean NDVI
     * @param latestDate  date of the most recent clear overpass
     * @param greenUpDate first overpass halfway from the spring low to the peak; {@code null} for flat seasons
     */
    public record Summary(
            Double peakNdvi,
            LocalDate peakDate,
            Double latestNdvi,
            LocalDate latestDate,
            @Schema(description = "First overpass halfway from the spring low to the peak; null when the season varies "
                    + "by less than 0.2 NDVI, for example permanent grass") LocalDate greenUpDate) {
    }

    /**
     * Imagery source.
     *
     * @param name        source name
     * @param collection  Earth Search collection used for this season
     * @param url         catalogue URL
     * @param resolutionM pixel size in metres
     * @param license     data licence
     */
    public record Source(String name, String collection, String url, Integer resolutionM, String license) {
    }
}
