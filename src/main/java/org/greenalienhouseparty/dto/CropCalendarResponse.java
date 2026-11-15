package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Usual planting and maturity dates for crops around a field or drawn
 * outline, from the GGCMI Phase 3 crop calendar (NASA GISS and partners).
 *
 * <p>Values are multi-year averages around the year 2000 for a cell of about
 * 50 km. They describe the normal season; they are not a forecast, and cells
 * where a crop is not grown are filled in from neighbouring areas.</p>
 *
 * @param id       field identifier, present only for the field-specific route
 * @param location the field's centre
 * @param cell     the calendar grid cell read
 * @param system   {@code rainfed} or {@code irrigated}
 * @param crops    one entry per calendar crop
 * @param source   source dataset metadata
 * @param note     interpretation and limitations
 */
public record CropCalendarResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Location location,
        Cell cell,
        @Schema(allowableValues = {"rainfed", "irrigated"}) String system,
        List<CropSeason> crops,
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
     * The calendar grid cell the values come from.
     *
     * @param lat     latitude of the cell centre
     * @param lon     longitude of the cell centre
     * @param sizeDeg cell size in degrees
     */
    public record Cell(Double lat, Double lon, Double sizeDeg) {
    }

    /**
     * The usual season of one crop. Every value is {@code null} where the
     * calendar has none for the cell.
     *
     * @param crop              calendar crop key, for example {@code corn} or {@code winter_wheat}
     * @param label             crop name in plain words
     * @param plantingDay       usual planting day, counted from 1 January
     * @param plantingDate      usual planting date as {@code MM-DD}
     * @param maturityDay       usual maturity day, counted from 1 January
     * @param maturityDate      usual maturity date as {@code MM-DD}
     * @param growingSeasonDays days from planting to maturity
     * @param offSeasonDays     days of the year the ground is free of this crop
     * @param crossesNewYear    whether the crop is planted in one year and matures in the next
     * @param dataSource        the dataset the calendar used for this cell
     */
    public record CropSeason(
            String crop,
            String label,
            Integer plantingDay,
            String plantingDate,
            Integer maturityDay,
            String maturityDate,
            Integer growingSeasonDays,
            Integer offSeasonDays,
            Boolean crossesNewYear,
            String dataSource) {
    }

    /**
     * Provenance of the calendar.
     *
     * @param name          dataset name
     * @param url           dataset record URL
     * @param doi           dataset DOI
     * @param version       dataset version
     * @param resolutionDeg grid size in degrees
     * @param license       source licence
     * @param citation      authors and year to cite
     */
    public record Source(
            String name,
            String url,
            String doi,
            String version,
            Double resolutionDeg,
            String license,
            String citation) {
    }
}
