package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Request body for querying usual planting and maturity dates around a
 * user-provided outline.
 *
 * <p>The polygon may be GeoJSON Polygon, MultiPolygon or Feature data, or a
 * simple ring of {@code [longitude, latitude]} positions. {@code system} and
 * {@code crops} are optional; when {@code null} they are omitted so the Python
 * API uses rainfed farming and every calendar crop. Unknown properties are
 * retained so the Python API can reject them using its validation contract.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CropCalendarRequest {

    private JsonNode polygon;

    private String system;

    private List<String> crops;

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
     * @return {@code rainfed} or {@code irrigated}, or {@code null} for rainfed
     */
    public String getSystem() {
        return system;
    }

    /**
     * @param system {@code rainfed} or {@code irrigated}
     */
    public void setSystem(String system) {
        this.system = system;
    }

    /**
     * @return calendar crop keys wanted, or {@code null} for every crop
     */
    public List<String> getCrops() {
        return crops;
    }

    /**
     * @param crops calendar crop keys wanted, for example {@code corn} and {@code winter_wheat}
     */
    public void setCrops(List<String> crops) {
        this.crops = crops;
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
