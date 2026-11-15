package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Request body for querying groundwater and soil moisture percentiles around
 * a user-provided outline.
 *
 * <p>The polygon may be GeoJSON Polygon, MultiPolygon or Feature data, or a
 * simple ring of {@code [longitude, latitude]} positions. {@code weeks} and
 * {@code years} are optional; when {@code null} they are omitted so the Python
 * API uses 12 recent weeks and 10 earlier years. Unknown properties are
 * retained so the Python API can reject them using its validation contract.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DroughtIndicatorsRequest {

    private JsonNode polygon;

    private Integer weeks;

    private Integer years;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the requested outline
     */
    public JsonNode getPolygon() {
        return polygon;
    }

    /**
     * @param polygon GeoJSON outline or a coordinate ring
     */
    public void setPolygon(JsonNode polygon) {
        this.polygon = polygon;
    }

    /**
     * @return recent weeks to return, or {@code null} for the default
     */
    public Integer getWeeks() {
        return weeks;
    }

    /**
     * @param weeks recent weeks to return, 1 to 52
     */
    public void setWeeks(Integer weeks) {
        this.weeks = weeks;
    }

    /**
     * @return earlier years to compare at the same time of year, or {@code null} for the default
     */
    public Integer getYears() {
        return years;
    }

    /**
     * @param years earlier years to compare at the same time of year, 0 to 23
     */
    public void setYears(Integer years) {
        this.years = years;
    }

    /**
     * @return any unsupported properties received in the request
     */
    @JsonAnyGetter
    public Map<String, JsonNode> getExtra() {
        return extra;
    }

    /**
     * Captures request properties not declared as part of this model.
     *
     * @param name  the unknown property name
     * @param value the property value
     */
    @JsonAnySetter
    public void setExtra(String name, JsonNode value) {
        extra.put(name, value);
    }
}
