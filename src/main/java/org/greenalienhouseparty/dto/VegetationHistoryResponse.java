package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A field's long vegetation record from NASA MODIS: greenness (NDVI) every 16
 * days for recent years, and yearly plant growth and water use since 2001.
 *
 * <p>MODIS pixels are 250 m (NDVI) and 500 m (yearly values), so a small field
 * shares its values with neighbouring land. NDVI is about 0.1-0.2 for bare
 * soil and 0.6-0.9 for a full canopy.</p>
 *
 * @param id               field identifier, present only for the field-specific route
 * @param location         the field's centre
 * @param ndvi             16-day greenness, seasons and usual low-cover periods
 * @param yearly           growth and water use for each year since 2001
 * @param yearlySummary    medians and trend of the yearly values
 * @param filesChecked     satellite files looked at
 * @param filesUnreadable  files that could not be read
 * @param source           the NASA products used
 * @param provider         who hosts the files that were read
 * @param note             interpretation and limitations
 */
public record VegetationHistoryResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Location location,
        Ndvi ndvi,
        List<YearlyRow> yearly,
        YearlySummary yearlySummary,
        Integer filesChecked,
        Integer filesUnreadable,
        List<Source> source,
        Provider provider,
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
     * Greenness every 16 days and what it says about the field's seasons.
     *
     * @param period            first and last day read
     * @param lowCoverThreshold NDVI below which the field counts as having little green cover
     * @param observations      one entry per usable 16-day composite, oldest first
     * @param byYear            peak, green-up and brown-down for each year
     * @param calendar          the usual NDVI and low-cover frequency for each 16-day period of the year
     * @param lowCoverWindows   date ranges that usually have little green cover, longest first
     * @param typical           medians across the years read
     */
    public record Ndvi(
            Period period,
            Double lowCoverThreshold,
            List<Observation> observations,
            List<Season> byYear,
            List<CalendarPeriod> calendar,
            List<LowCoverWindow> lowCoverWindows,
            Typical typical) {
    }

    /**
     * A period as ISO dates.
     *
     * @param start first day, {@code YYYY-MM-DD}
     * @param end   last day, {@code YYYY-MM-DD}
     */
    public record Period(String start, String end) {
    }

    /**
     * One 16-day composite over the field.
     *
     * @param date        first day of the 16 days, {@code YYYY-MM-DD}
     * @param ndvi        mean NDVI of the usable pixels; {@code null} when the field was snow-covered
     * @param snow        whether the field was snow-covered
     * @param usableShare share of the field's pixels that were usable, 0 to 1
     */
    public record Observation(String date, Double ndvi, Boolean snow, Double usableShare) {
    }

    /**
     * One year's growing season as seen in NDVI. Peak and dates are
     * {@code null} for a year with too few composites, and the green-up
     * values are {@code null} when the year has no clear season.
     *
     * @param year          calendar year
     * @param composites    usable or snow composites in the year
     * @param peakNdvi      highest NDVI of the year
     * @param peakDate      date of the peak
     * @param greenUpDate   first composite at least halfway from the year's low to its peak
     * @param brownDownDate last composite at least halfway from the year's low to its peak
     * @param greenDays     days from green-up to brown-down
     * @param lowCoverDays  days with NDVI under the threshold or snow
     */
    public record Season(
            Integer year,
            Integer composites,
            Double peakNdvi,
            String peakDate,
            String greenUpDate,
            String brownDownDate,
            Integer greenDays,
            Integer lowCoverDays) {
    }

    /**
     * One 16-day period of the calendar year, across the years read.
     *
     * @param startDay      first day of the period, counted from 1 January
     * @param startDate     the same as {@code MM-DD}
     * @param medianNdvi    median NDVI in this period; {@code null} when always snow-covered
     * @param lowCoverShare share of years with little green cover or snow in this period, 0 to 1
     * @param snowShare     share of years with snow in this period, 0 to 1
     * @param years         number of years with a composite in this period
     */
    public record CalendarPeriod(
            Integer startDay,
            String startDate,
            Double medianNdvi,
            Double lowCoverShare,
            Double snowShare,
            Integer years) {
    }

    /**
     * A stretch of the year that usually has little green cover.
     *
     * @param startDate first day as {@code MM-DD}
     * @param endDate   last day as {@code MM-DD}; earlier than the start when the window crosses the new year
     * @param days      length in days
     */
    public record LowCoverWindow(String startDate, String endDate, Integer days) {
    }

    /**
     * Medians across the years read.
     *
     * @param peakNdvi     usual peak NDVI
     * @param greenDays    usual days from green-up to brown-down
     * @param lowCoverDays usual days per year with little green cover or snow
     */
    public record Typical(Double peakNdvi, Double greenDays, Double lowCoverDays) {
    }

    /**
     * One year's growth and water use; a value is {@code null} when the product has none.
     *
     * @param year         calendar year
     * @param nppKgCPerM2  net primary production, kilograms of carbon per square metre
     * @param etMm         evapotranspiration (water actually used), millimetres
     * @param petMm        potential evapotranspiration, millimetres
     * @param etShareOfPet evapotranspiration as a share of potential; lower means more water stress
     */
    public record YearlyRow(Integer year, Double nppKgCPerM2, Double etMm, Double petMm, Double etShareOfPet) {
    }

    /**
     * Medians and trend of the yearly values.
     *
     * @param nppMedianKgCPerM2       median net primary production
     * @param nppTrendKgCPerM2PerYear change in net primary production per year; {@code null} with under five years
     * @param etMedianMm              median evapotranspiration
     * @param petMedianMm             median potential evapotranspiration
     * @param etShareOfPetMedian      median share of potential evapotranspiration used
     */
    public record YearlySummary(
            Double nppMedianKgCPerM2,
            Double nppTrendKgCPerM2PerYear,
            Double etMedianMm,
            Double petMedianMm,
            Double etShareOfPetMedian) {
    }

    /**
     * One NASA product used.
     *
     * @param name        product name
     * @param collection  catalogue collection read
     * @param resolutionM pixel size in metres
     * @param from        first year available
     */
    public record Source(String name, String collection, Integer resolutionM, Integer from) {
    }

    /**
     * Who hosts the files that were read.
     *
     * @param name host name
     * @param url  host URL
     */
    public record Provider(String name, String url) {
    }
}
