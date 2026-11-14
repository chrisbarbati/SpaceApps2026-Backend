package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Request body of the data backend's {@code POST /field-summary} endpoint.
 *
 * <p>{@code polygon} is required and may be a GeoJSON Polygon, MultiPolygon or
 * Feature, or a plain ring of {@code [longitude, latitude]} positions. The
 * remaining fields are optional and, when {@code null}, are omitted from the
 * forwarded payload so the data backend applies its documented defaults.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldSummaryRequest {

    private JsonNode polygon;

    private PeriodRequest period;

    private SeasonRequest season;

    private Double weatherGridDeg;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the required field geometry
     */
    public JsonNode getPolygon() {
        return polygon;
    }

    /**
     * @param polygon the field geometry
     */
    public void setPolygon(JsonNode polygon) {
        this.polygon = polygon;
    }

    /**
     * @return the analysis period, or {@code null} to use the backend default
     */
    public PeriodRequest getPeriod() {
        return period;
    }

    /**
     * @param period the analysis period
     */
    public void setPeriod(PeriodRequest period) {
        this.period = period;
    }

    /**
     * @return the growing-season months, or {@code null} for the hemisphere default
     */
    public SeasonRequest getSeason() {
        return season;
    }

    /**
     * @param season the growing-season months
     */
    public void setSeason(SeasonRequest season) {
        this.season = season;
    }

    /**
     * @return the weather sampling grid in degrees, or {@code null} for the default
     */
    public Double getWeatherGridDeg() {
        return weatherGridDeg;
    }

    /**
     * @param weatherGridDeg the weather sampling grid in degrees
     */
    public void setWeatherGridDeg(Double weatherGridDeg) {
        this.weatherGridDeg = weatherGridDeg;
    }

    /**
     * @return any additional request properties forwarded verbatim
     */
    @JsonAnyGetter
    public Map<String, JsonNode> getExtra() {
        return extra;
    }

    /**
     * Captures any property not declared as an explicit field so unknown request
     * properties can be forwarded and rejected by the data backend.
     *
     * @param name  the property name
     * @param value the property value
     */
    @JsonAnySetter
    public void setExtra(String name, JsonNode value) {
        this.extra.put(name, value);
    }

    /**
     * Inclusive analysis period. Dates are accepted as {@code YYYY-MM-DD} or
     * {@code YYYYMMDD} strings.
     *
     * @param start the inclusive start date
     * @param end   the inclusive end date
     */
    public record PeriodRequest(String start, String end) {
    }

    /**
     * Consecutive growing-season months.
     *
     * @param months the consecutive months, in growing order
     */
    public record SeasonRequest(List<Integer> months) {
    }
}
