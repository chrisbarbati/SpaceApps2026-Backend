package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.JsonNode;

/**
 * Loaded field coverage, source and build status returned by the data backend's
 * {@code GET /field-coverage} endpoint.
 *
 * <p>The shape of this payload varies with the backing source: the preview and
 * custom GeoJSON sources expose a small fixed set of fields, while the indexed
 * database exposes metadata such as acquisition progress. Known fields are
 * modelled explicitly; the rest are preserved verbatim.</p>
 */
public class FieldCoverageResponse {

    private String region;

    private String status;

    private Integer fields;

    private String description;

    private String source;

    private String sourceUrl;

    private String license;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the loaded region label
     */
    public String getRegion() {
        return region;
    }

    /**
     * @param region the loaded region label
     */
    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * @return the build or sample status
     */
    public String getStatus() {
        return status;
    }

    /**
     * @param status the build or sample status
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * @return the number of loaded fields, or {@code null} when unknown
     */
    public Integer getFields() {
        return fields;
    }

    /**
     * @param fields the number of loaded fields
     */
    public void setFields(Integer fields) {
        this.fields = fields;
    }

    /**
     * @return a human-readable description of the coverage
     */
    public String getDescription() {
        return description;
    }

    /**
     * @param description a human-readable description of the coverage
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return the source dataset name, when available
     */
    public String getSource() {
        return source;
    }

    /**
     * @param source the source dataset name
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * @return the source dataset URL, when available
     */
    public String getSourceUrl() {
        return sourceUrl;
    }

    /**
     * @param sourceUrl the source dataset URL
     */
    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    /**
     * @return the source licence identifier, when available
     */
    public String getLicense() {
        return license;
    }

    /**
     * @param license the source licence identifier
     */
    public void setLicense(String license) {
        this.license = license;
    }

    /**
     * @return any additional source-specific metadata
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
