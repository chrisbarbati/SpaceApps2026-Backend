package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * Request body for querying topsoil estimates by a user-provided outline.
 *
 * <p>The polygon may be GeoJSON Polygon, MultiPolygon or Feature data, or a
 * simple ring of {@code [longitude, latitude]} positions. Unknown properties
 * are retained so the Python API can reject them using its validation contract.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SoilRequest {

    private JsonNode polygon;

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
