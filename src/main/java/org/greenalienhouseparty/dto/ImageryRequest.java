package org.greenalienhouseparty.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * Request body for finding satellite RGB images of a user-provided outline.
 *
 * <p>The polygon may be GeoJSON Polygon, MultiPolygon or Feature data, or a
 * simple ring of {@code [longitude, latitude]} positions. Every search option is
 * optional; unset options are omitted so the Python API applies its defaults
 * (HLS, the past year, 30% cloud, 6 images). Unknown properties are retained so
 * the Python API can reject them using its validation contract.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageryRequest {

    private JsonNode polygon;

    private String dataset;

    private LocalDate start;

    private LocalDate end;

    private Double maxCloud;

    private Integer limit;

    private final Map<String, JsonNode> extra = new LinkedHashMap<>();

    /**
     * @return the requested outline
     */
    @Schema(description = "GeoJSON Polygon, MultiPolygon or Feature, or a ring of [longitude, latitude] positions; "
            + "must fit within 1 by 1 degree")
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
     * @return {@code hls} or {@code sentinel-2}, or {@code null} for the default (HLS)
     */
    @Schema(description = "hls (NASA HLS v2, 30 m) or sentinel-2 (native 10 m RGB); defaults to hls",
            allowableValues = {"hls", "sentinel-2"})
    public String getDataset() {
        return dataset;
    }

    /**
     * @param dataset {@code hls} or {@code sentinel-2}
     */
    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    /**
     * @return the first acquisition date, or {@code null} for one year before {@link #getEnd()}
     */
    @Schema(description = "First acquisition date; defaults to one year before end")
    public LocalDate getStart() {
        return start;
    }

    /**
     * @param start the first acquisition date
     */
    public void setStart(LocalDate start) {
        this.start = start;
    }

    /**
     * @return the last acquisition date, or {@code null} for today
     */
    @Schema(description = "Last acquisition date; defaults to today")
    public LocalDate getEnd() {
        return end;
    }

    /**
     * @param end the last acquisition date
     */
    public void setEnd(LocalDate end) {
        this.end = end;
    }

    /**
     * @return the maximum whole-scene cloud percentage, or {@code null} for the default (30)
     */
    @Schema(description = "Maximum whole-scene cloud percentage, 0 to 100; defaults to 30")
    public Double getMaxCloud() {
        return maxCloud;
    }

    /**
     * @param maxCloud the maximum whole-scene cloud percentage
     */
    public void setMaxCloud(Double maxCloud) {
        this.maxCloud = maxCloud;
    }

    /**
     * @return the maximum number of scenes, or {@code null} for the default (6)
     */
    @Schema(description = "Maximum number of scenes to return, 1 to 20; defaults to 6")
    public Integer getLimit() {
        return limit;
    }

    /**
     * @param limit the maximum number of scenes
     */
    public void setLimit(Integer limit) {
        this.limit = limit;
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
