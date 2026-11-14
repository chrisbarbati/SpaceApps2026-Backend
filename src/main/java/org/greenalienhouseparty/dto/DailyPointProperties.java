package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.JsonNode;

/**
 * Properties block of a NASA POWER daily point response.
 *
 * <p>{@code parameter} maps each requested variable (for example {@code T2M} or
 * {@code PRECTOTCORR}) to a date-keyed series of observations. Values are kept
 * as {@link JsonNode} because observations may be numeric, the {@code -999}
 * missing-data sentinel, or {@code null}.</p>
 */
public class DailyPointProperties {

    private Map<String, Map<String, JsonNode>> parameter;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the requested variables mapped to their date-keyed observations
     */
    public Map<String, Map<String, JsonNode>> getParameter() {
        return parameter;
    }

    /**
     * @param parameter the variable series map
     */
    public void setParameter(Map<String, Map<String, JsonNode>> parameter) {
        this.parameter = parameter;
    }

    /**
     * @return any additional properties fields
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
