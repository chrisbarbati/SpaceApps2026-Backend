package org.greenalienhouseparty.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Everything the application knows about one stored field, returned by
 * {@code GET /fields/{field_id}}.
 *
 * <p>{@code id}, {@code hectares} and {@code acres} are the field's estimated
 * size, as this route has always returned. Each other property is one data
 * source, in the same shape as that source's own route. A section is
 * {@code null} when it was not requested or could not be fetched;
 * {@link #sections()} says which, and why.</p>
 *
 * @param id                field identifier
 * @param hectares          estimated area in hectares
 * @param acres             estimated area in acres
 * @param location          the field centre used for location-based sources, or {@code null} when it is not known
 * @param soil              topsoil and deeper soil estimates ({@code GET /fields/{field_id}/soil})
 * @param cropHistory       mapped crops by year, interpreted ({@code GET /fields/{field_id}/crop-history})
 * @param climate           climate, water balance, frost and terrain ({@code GET /fields/{field_id}/summary})
 * @param cropCalendar      usual planting and maturity dates ({@code GET /fields/{field_id}/crop-calendar})
 * @param agroWeather       soil wetness, radiation and reference evapotranspiration
 *                          ({@code GET /fields/{field_id}/agro-weather})
 * @param soilLandscape     land capability, drainage and regional soil indicators, Canada only
 *                          ({@code GET /soil-landscape})
 * @param seasonalOutlook   chances of above, near or below normal temperature and precipitation
 *                          ({@code GET /seasonal-outlook})
 * @param greenness         Sentinel-2 greenness through one season, only when requested
 *                          ({@code GET /fields/{field_id}/greenness})
 * @param vegetationHistory MODIS vegetation history, only when requested
 *                          ({@code GET /fields/{field_id}/vegetation-history})
 * @param droughtIndicators GRACE groundwater and soil moisture percentiles, only when requested
 *                          ({@code GET /fields/{field_id}/drought-indicators})
 * @param sections          status of every section above, keyed by its property name
 */
public record FieldDetailsResponse(
        String id,
        Double hectares,
        Double acres,
        Location location,
        FieldSoilResponse soil,
        CropHistoryResponse cropHistory,
        FieldSummaryResponse climate,
        CropCalendarResponse cropCalendar,
        AgroWeatherResponse agroWeather,
        SoilLandscapeResponse soilLandscape,
        SeasonalOutlookResponse seasonalOutlook,
        FieldGreennessResponse greenness,
        VegetationHistoryResponse vegetationHistory,
        DroughtIndicatorsResponse droughtIndicators,
        Map<String, Section> sections) {

    /**
     * The field centre used to look up location-based sources.
     *
     * @param lat    latitude in degrees
     * @param lon    longitude in degrees
     * @param source which section the centre was taken from, for example {@code climate}
     */
    public record Location(Double lat, Double lon, String source) {
    }

    /**
     * Whether one section was returned, and if not, why.
     *
     * @param status {@code ok}, {@code unavailable} (requested but could not be fetched) or {@code not_requested}
     * @param detail why the section is unavailable, or how to request it; {@code null} when ok
     */
    public record Section(
            @Schema(allowableValues = {"ok", "unavailable", "not_requested"}) String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String detail) {

        /** Status of a section that was fetched. */
        public static final String OK = "ok";

        /** Status of a section that was requested but could not be fetched. */
        public static final String UNAVAILABLE = "unavailable";

        /** Status of an optional section that was not requested. */
        public static final String NOT_REQUESTED = "not_requested";
    }
}
