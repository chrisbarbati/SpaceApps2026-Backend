package org.greenalienhouseparty.planning.model;

import java.util.List;
import java.util.Map;

/**
 * What is known about a field beyond its topsoil: its climate record, the
 * usual growing season of each crop there, and regional soil ratings. The
 * rotation planner compares these with what each crop needs.
 *
 * <p>Every value may be missing. A missing list is empty, a missing map is
 * empty and a missing single value is {@code null}; the planner then skips the
 * check that needs it rather than guessing.</p>
 *
 * @param monthlyMeanTempC     mean temperature of each month in Celsius, January first (12 entries, each
 *                             possibly {@code null}), or an empty list when unknown
 * @param seasonMonths         months of the field's main growing season, 1 to 12
 * @param yearlyRainfallMm     total rainfall of each past year with enough data, in millimetres
 * @param yearlyFrostFreeDays  frost-free days of each past year with enough data
 * @param yearlyHeatUnits      crop heat units of each past year with enough data
 * @param seasonRainfallMm     typical growing-season rainfall in millimetres, or {@code null}
 * @param cropSeasons          usual planting and maturity of crops around the field, keyed by crop id
 * @param landClass            Canada Land Inventory capability class, {@code 1} (best) to {@code 7}, or {@code null}
 * @param drainageCode         dominant soil drainage: {@code R} rapid, {@code W} well, {@code I} imperfect,
 *                             {@code P} poor, or {@code null}
 * @param residualNitrogenHigh whether nitrogen left in the soil after harvest is rated high for the area
 * @param organicCarbonDeclining whether the area's soils are losing organic carbon
 * @param firstYearRainfall    seasonal outlook for the first plan year's growing season, or {@code null}
 *                             when there is no outlook or it shows no clear signal
 */
public record FieldConditions(
        List<Double> monthlyMeanTempC,
        List<Integer> seasonMonths,
        List<Double> yearlyRainfallMm,
        List<Double> yearlyFrostFreeDays,
        List<Double> yearlyHeatUnits,
        Double seasonRainfallMm,
        Map<String, CropSeason> cropSeasons,
        String landClass,
        String drainageCode,
        boolean residualNitrogenHigh,
        boolean organicCarbonDeclining,
        RainfallChances firstYearRainfall) {

    /**
     * Replaces missing collections with empty ones.
     */
    public FieldConditions {
        monthlyMeanTempC = monthlyMeanTempC == null ? List.of() : monthlyMeanTempC;
        seasonMonths = seasonMonths == null ? List.of() : seasonMonths;
        yearlyRainfallMm = yearlyRainfallMm == null ? List.of() : yearlyRainfallMm;
        yearlyFrostFreeDays = yearlyFrostFreeDays == null ? List.of() : yearlyFrostFreeDays;
        yearlyHeatUnits = yearlyHeatUnits == null ? List.of() : yearlyHeatUnits;
        cropSeasons = cropSeasons == null ? Map.of() : cropSeasons;
    }

    /**
     * Conditions for a field about which nothing more is known.
     *
     * @return conditions with every value missing
     */
    public static FieldConditions unknown() {
        return new FieldConditions(null, null, null, null, null, null, null, null, null, false, false, null);
    }

    /**
     * The usual season of one crop around the field, from the crop calendar.
     *
     * @param plantingDay       usual planting day of the year, 1 to 365
     * @param maturityDay       usual maturity day of the year, 1 to 365
     * @param growingSeasonDays days from planting to maturity
     * @param crossesNewYear    whether the crop is sown in one calendar year and matures in the next
     */
    public record CropSeason(int plantingDay, int maturityDay, int growingSeasonDays, boolean crossesNewYear) {
    }

    /**
     * Chances that growing-season rainfall is below, near or above normal, as
     * fractions that sum to 1.
     *
     * @param below chance of a drier season than normal
     * @param near  chance of a near-normal season
     * @param above chance of a wetter season than normal
     */
    public record RainfallChances(double below, double near, double above) {
    }
}
