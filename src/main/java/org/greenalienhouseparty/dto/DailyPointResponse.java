package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.JsonNode;

/**
 * Raw NASA POWER daily point response envelope returned by the data backend's
 * {@code GET /daily-point} endpoint.
 *
 * <p>The well-known GeoJSON envelope is modelled explicitly ({@code type},
 * {@code geometry}, {@code properties}) while the remaining, source-specific
 * sections such as {@code header}, {@code messages} and {@code times} are
 * preserved verbatim through the catch-all map so no upstream data is dropped
 * during deserialization and re-serialization.</p>
 */
public class DailyPointResponse {

    private String type;

    private JsonNode geometry;

    private DailyPointProperties properties;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the GeoJSON type, for example {@code "Feature"}
     */
    public String getType() {
        return type;
    }

    /**
     * @param type the GeoJSON type
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * @return the GeoJSON geometry
     */
    public JsonNode getGeometry() {
        return geometry;
    }

    /**
     * @param geometry the GeoJSON geometry
     */
    public void setGeometry(JsonNode geometry) {
        this.geometry = geometry;
    }

    /**
     * @return the response properties block
     */
    public DailyPointProperties getProperties() {
        return properties;
    }

    /**
     * @param properties the response properties block
     */
    public void setProperties(DailyPointProperties properties) {
        this.properties = properties;
    }

    /**
     * @return any additional, source-specific top-level fields
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
