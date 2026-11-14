package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.JsonNode;

/**
 * Properties attached to a {@link FieldFeature}.
 *
 * <p>The identifier is always present. Indexed sources additionally expose a
 * {@code score} (0-100 mean model score, not a calibrated probability). Any
 * other source-specific properties are preserved in the catch-all map.</p>
 */
public class FieldProperties {

    private String id;

    private Integer score;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the field identifier
     */
    public String getId() {
        return id;
    }

    /**
     * @param id the field identifier
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * @return the model score, or {@code null} when the source does not expose one
     */
    public Integer getScore() {
        return score;
    }

    /**
     * @param score the model score
     */
    public void setScore(Integer score) {
        this.score = score;
    }

    /**
     * @return any additional source-specific properties
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
