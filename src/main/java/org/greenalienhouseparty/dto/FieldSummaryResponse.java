package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

import tools.jackson.databind.JsonNode;

/**
 * Environmental summary returned by the data backend's {@code POST /field-summary}
 * endpoint.
 *
 * <p>The payload combines field geometry, the sampled weather cell, climate and
 * water-balance indicators, frost dates, monthly climatology, data coverage,
 * terrain statistics and source metadata. The {@code terrain} block is
 * {@code null} when the terrain provider failed while weather succeeded.</p>
 *
 * @param field      the field centroid and area
 * @param weatherSource the weather dataset used, {@code daymet} or {@code power}
 * @param weatherCell the weather sampling coordinate
 * @param period     the analysis period
 * @param season     the growing-season definition
 * @param climate    climate indicators and trends
 * @param water      water-balance indicators
 * @param frost      frost dates and frost-free season
 * @param monthly    monthly climatology, one entry per calendar month
 * @param yearly     year-by-year indicators and their spread across the period
 * @param coverage   data coverage and cleaning counts
 * @param terrain    terrain statistics, or {@code null} on terrain failure
 * @param layers     per-layer status
 * @param source     source and provenance metadata
 */
public record FieldSummaryResponse(
        FieldInfo field,
        @Schema(allowableValues = {"daymet", "power"}) String weatherSource,
        WeatherCell weatherCell,
        PeriodInfo period,
        SeasonInfo season,
        ClimateInfo climate,
        WaterInfo water,
        FrostInfo frost,
        List<MonthlyRow> monthly,
        YearlyInfo yearly,
        CoverageInfo coverage,
        TerrainInfo terrain,
        LayersInfo layers,
        List<SourceInfo> source) {

    /**
     * Field centroid and estimated area.
     *
     * @param centroid the field centroid
     * @param areaHa   the estimated area in hectares
     */
    public record FieldInfo(Centroid centroid, Double areaHa) {
    }

    /**
     * A latitude/longitude coordinate.
     *
     * @param lat latitude
     * @param lon longitude
     */
    public record Centroid(Double lat, Double lon) {
    }

    /**
     * The weather sampling coordinate and grid resolution.
     *
     * @param lat     latitude
     * @param lon     longitude
     * @param gridDeg the sampling grid in degrees
     */
    public record WeatherCell(Double lat, Double lon, Double gridDeg) {
    }

    /**
     * The analysis period as {@code YYYYMMDD} strings.
     *
     * @param start the inclusive start date
     * @param end   the inclusive end date
     */
    public record PeriodInfo(String start, String end) {
    }

    /**
     * The growing-season definition used for seasonal statistics.
     *
     * @param months      the consecutive growing months
     * @param crossesYear whether the season wraps past December
     * @param baseTempC   the base temperature used for growing-degree days
     * @param label       a human-readable season label
     */
    public record SeasonInfo(
            List<Integer> months,
            @JsonProperty("crosses_year") Boolean crossesYear,
            @JsonProperty("base_temp_c") Double baseTempC,
            String label) {
    }

    /**
     * Climate indicators and trends. All values are nullable; a statistic is
     * {@code null} when no year qualifies under the coverage threshold.
     *
     * @param gddAnnualMedian            median annual growing-degree days
     * @param gddSeasonMedian            median seasonal growing-degree days
     * @param chuAvailableMedian         median Ontario crop heat units available each year, the scale used
     *                                   to rate corn and soybean varieties; {@code null} south of the equator
     * @param growingSeasonPrecipMmMedian median growing-season precipitation
     * @param annualPrecipMmMedian       median annual precipitation
     * @param medianDrySpellDays         median longest dry spell
     * @param frostFreeDaysMedian        median frost-free days
     * @param heatDays30Median           median days at or above 30 C
     * @param heavyRainDaysMedian        median heavy-rain days
     * @param max1dayPrecipMmMedian      median maximum one-day precipitation
     * @param precipTrendMmPerYear       precipitation trend, millimetres per year
     * @param seasonPrecipTrendMmPerYear seasonal precipitation trend
     * @param heatDays30Trend            heat-day count trend
     * @param drySpellTrendDaysPerYear   dry-spell length trend, days per year
     * @param heavyRainDaysTrend         heavy-rain day count trend
     * @param precipShiftPct             recent-versus-early precipitation change
     * @param heatShiftPct               recent-versus-early heat change
     * @param drySpellShiftPct           recent-versus-early dry-spell change
     */
    public record ClimateInfo(
            @JsonProperty("gdd_annual_median") Double gddAnnualMedian,
            @JsonProperty("gdd_season_median") Double gddSeasonMedian,
            @JsonProperty("chu_available_median") Double chuAvailableMedian,
            @JsonProperty("growing_season_precip_mm_median") Double growingSeasonPrecipMmMedian,
            @JsonProperty("annual_precip_mm_median") Double annualPrecipMmMedian,
            @JsonProperty("median_dry_spell_days") Double medianDrySpellDays,
            @JsonProperty("frost_free_days_median") Double frostFreeDaysMedian,
            @JsonProperty("heat_days_30_median") Double heatDays30Median,
            @JsonProperty("heavy_rain_days_median") Double heavyRainDaysMedian,
            @JsonProperty("max_1day_precip_mm_median") Double max1dayPrecipMmMedian,
            @JsonProperty("precip_trend_mm_per_year") Double precipTrendMmPerYear,
            @JsonProperty("season_precip_trend_mm_per_year") Double seasonPrecipTrendMmPerYear,
            @JsonProperty("heat_days_30_trend") Double heatDays30Trend,
            @JsonProperty("dry_spell_trend_days_per_year") Double drySpellTrendDaysPerYear,
            @JsonProperty("heavy_rain_days_trend") Double heavyRainDaysTrend,
            @JsonProperty("precip_shift_pct") Double precipShiftPct,
            @JsonProperty("heat_shift_pct") Double heatShiftPct,
            @JsonProperty("dry_spell_shift_pct") Double drySpellShiftPct) {
    }

    /**
     * Water-balance indicators. All values are nullable.
     *
     * @param seasonEt0MmMedian     median seasonal reference evapotranspiration
     * @param waterDeficitMmMedian  median seasonal water deficit
     * @param aridityIndexMedian    median aridity index
     * @param waterStressIndex      median water-stress index
     * @param waterStressShiftPct   recent-versus-early water-stress change
     */
    public record WaterInfo(
            @JsonProperty("season_et0_mm_median") Double seasonEt0MmMedian,
            @JsonProperty("water_deficit_mm_median") Double waterDeficitMmMedian,
            @JsonProperty("aridity_index_median") Double aridityIndexMedian,
            @JsonProperty("water_stress_index") Double waterStressIndex,
            @JsonProperty("water_stress_shift_pct") Double waterStressShiftPct) {
    }

    /**
     * Typical frost dates and frost-free season.
     *
     * @param lastSpringFrost          typical last spring frost as {@code MM-DD}
     * @param firstFallFrost           typical first fall frost as {@code MM-DD}
     * @param lastSpringFrostDoy       last spring frost day of year
     * @param firstFallFrostDoy        first fall frost day of year
     * @param frostFreeSeasonDays      frost-free season length in days
     * @param springFrostYears         number of years contributing a spring frost
     * @param lastSpringFrostDoyByYear last spring frost day of year per year
     * @param calendarShiftDays        calendar shift applied for the hemisphere
     */
    public record FrostInfo(
            @JsonProperty("last_spring_frost") String lastSpringFrost,
            @JsonProperty("first_fall_frost") String firstFallFrost,
            @JsonProperty("last_spring_frost_doy") Double lastSpringFrostDoy,
            @JsonProperty("first_fall_frost_doy") Double firstFallFrostDoy,
            @JsonProperty("frost_free_season_days") Double frostFreeSeasonDays,
            @JsonProperty("spring_frost_years") Integer springFrostYears,
            @JsonProperty("last_spring_frost_doy_by_year") Map<String, Integer> lastSpringFrostDoyByYear,
            @JsonProperty("calendar_shift_days") Integer calendarShiftDays) {
    }

    /**
     * One calendar month of climatological averages. Values are {@code null}
     * when the month does not qualify under the coverage threshold.
     *
     * @param month        the month number, 1-12
     * @param precipMm     average precipitation
     * @param heavyRainDays average heavy-rain day count
     * @param et0Mm        average reference evapotranspiration
     * @param frostDays    average frost-day count
     * @param tmeanC       average mean temperature
     */
    public record MonthlyRow(
            Integer month,
            @JsonProperty("precip_mm") Double precipMm,
            @JsonProperty("heavy_rain_days") Double heavyRainDays,
            @JsonProperty("et0_mm") Double et0Mm,
            @JsonProperty("frost_days") Double frostDays,
            @JsonProperty("tmean_c") Double tmeanC) {
    }

    /**
     * Data coverage and cleaning counts.
     *
     * @param firstDate                 first observation date
     * @param lastDate                  last observation date
     * @param calendarYears             number of calendar years present
     * @param yearsWithTemperature      years qualifying for temperature statistics
     * @param yearsWithWaterBalance     years qualifying for water-balance statistics
     * @param minCoverage               the minimum coverage fraction applied
     * @param missingTemperatureDays    days missing temperature observations
     * @param missingPrecipDays         days missing precipitation observations
     * @param duplicateDaysDropped      duplicate days removed during cleaning
     * @param impossibleTemperatureDays impossible temperature values removed
     * @param impossiblePrecipDays      impossible precipitation values removed
     */
    public record CoverageInfo(
            @JsonProperty("first_date") String firstDate,
            @JsonProperty("last_date") String lastDate,
            @JsonProperty("calendar_years") Integer calendarYears,
            @JsonProperty("years_with_temperature") Integer yearsWithTemperature,
            @JsonProperty("years_with_water_balance") Integer yearsWithWaterBalance,
            @JsonProperty("min_coverage") Double minCoverage,
            @JsonProperty("missing_temperature_days") Integer missingTemperatureDays,
            @JsonProperty("missing_precip_days") Integer missingPrecipDays,
            @JsonProperty("duplicate_days_dropped") Integer duplicateDaysDropped,
            @JsonProperty("impossible_temperature_days") Integer impossibleTemperatureDays,
            @JsonProperty("impossible_precip_days") Integer impossiblePrecipDays) {
    }

    /**
     * Terrain statistics derived from SRTM elevation sampling.
     *
     * @param elevationMMean mean elevation in metres
     * @param reliefM        relief in metres
     * @param slopePctMean   mean slope in percent
     * @param slopePctP90    90th-percentile slope in percent
     * @param erosionRating  erosion risk rating
     * @param steepShare     share of sampled points rated steep
     * @param lowSpotShare   share of sampled points rated as low spots
     */
    public record TerrainInfo(
            @JsonProperty("elevation_m_mean") Double elevationMMean,
            @JsonProperty("relief_m") Double reliefM,
            @JsonProperty("slope_pct_mean") Double slopePctMean,
            @JsonProperty("slope_pct_p90") Double slopePctP90,
            String erosionRating,
            Double steepShare,
            Double lowSpotShare) {
    }

    /**
     * Per-layer statuses for the weather and terrain providers.
     *
     * @param weather the weather layer status
     * @param terrain the terrain layer status
     */
    public record LayersInfo(LayerStatus weather, LayerStatus terrain) {
    }

    /**
     * Status of a single data layer.
     *
     * @param status       the layer status, for example {@code "ok"} or {@code "error"}
     * @param message      the error message when the status is {@code "error"}, or why the weather layer
     *                     fell back to another dataset
     * @param source       the dataset that answered, {@code daymet} or {@code power}; weather layer only
     * @param fallbackFrom the dataset that failed before {@code source} was used; weather layer only, and
     *                     only after a fallback
     */
    public record LayerStatus(
            String status,
            String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) String source,
            @JsonInclude(JsonInclude.Include.NON_NULL) String fallbackFrom) {
    }

    /**
     * A single source or provenance entry. The entries are heterogeneous, so the
     * common {@code source} name is modelled explicitly and the remaining fields
     * are preserved verbatim.
     */
    public static class SourceInfo {

        private String source;

        private final Map<String, JsonNode> extra = new LinkedHashMap<>();

        /**
         * @return the source name
         */
        public String getSource() {
            return source;
        }

        /**
         * @param source the source name
         */
        public void setSource(String source) {
            this.source = source;
        }

        /**
         * @return any additional source-specific fields
         */
        @JsonAnyGetter
        public Map<String, JsonNode> getExtra() {
            return extra;
        }

        /**
         * Captures any property not declared as an explicit field.
         *
         * @param name  the property name
         * @param value the property value
         */
        @JsonAnySetter
        public void setExtra(String name, JsonNode value) {
            this.extra.put(name, value);
        }
    }

    /**
     * Year-by-year indicators, for judging how reliably a field meets a crop's
     * needs rather than relying on the median alone.
     *
     * @param years       one row per year; season values belong to the season's start year
     * @param percentiles spread of each indicator across valid years, keyed by the indicator's
     *                    name as it appears in a year row (for example {@code chu_available});
     *                    an entry is {@code null} when no year qualifies
     */
    public record YearlyInfo(List<YearRow> years, Map<String, Percentiles> percentiles) {
    }

    /**
     * Indicators for one year. A value is {@code null} when the year lacks
     * enough valid days.
     *
     * @param year             calendar year, or the season's start year for season values
     * @param chuAvailable     Ontario crop heat units available
     * @param gddSeason        growing-degree days in the growing season
     * @param gddAnnual        growing-degree days in the calendar year
     * @param seasonPrecipMm   growing-season precipitation in millimetres
     * @param precipMm         annual precipitation in millimetres
     * @param seasonEt0Mm      growing-season reference evapotranspiration in millimetres
     * @param waterDeficitMm   growing-season water deficit in millimetres
     * @param waterStressIndex water deficit as a share of evapotranspiration, 0 to 1
     * @param frostFreeDays    longest run of frost-free days
     * @param frostDays        days with a minimum at or below 0 C
     * @param heatDays30       days with a maximum at or above 30 C
     * @param drySpellDays     longest dry spell in days
     * @param heavyRainDays    heavy-rain days
     * @param max1dayPrecipMm  largest one-day precipitation in millimetres
     */
    public record YearRow(
            Integer year,
            @JsonProperty("chu_available") Double chuAvailable,
            @JsonProperty("gdd_season") Double gddSeason,
            @JsonProperty("gdd_annual") Double gddAnnual,
            @JsonProperty("season_precip_mm") Double seasonPrecipMm,
            @JsonProperty("precip_mm") Double precipMm,
            @JsonProperty("season_et0_mm") Double seasonEt0Mm,
            @JsonProperty("water_deficit_mm") Double waterDeficitMm,
            @JsonProperty("water_stress_index") Double waterStressIndex,
            @JsonProperty("frost_free_days") Double frostFreeDays,
            @JsonProperty("frost_days") Double frostDays,
            @JsonProperty("heat_days_30") Double heatDays30,
            @JsonProperty("dry_spell_days") Double drySpellDays,
            @JsonProperty("heavy_rain_days") Double heavyRainDays,
            @JsonProperty("max_1day_precip_mm") Double max1dayPrecipMm) {
    }

    /**
     * Spread of one indicator across the valid years.
     *
     * @param p10   value exceeded in nine years out of ten
     * @param p50   median
     * @param p90   value exceeded in one year out of ten
     * @param years number of valid years used
     */
    public record Percentiles(Double p10, Double p50, Double p90, Integer years) {
    }
}
