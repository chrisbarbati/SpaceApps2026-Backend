package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import tools.jackson.databind.JsonNode;

/**
 * Properties attached to a {@link FieldFeature}.
 *
 * <p>The identifier is always present. Indexed sources additionally expose a
 * {@code score} (0-100 mean model score, not a calibrated probability). Quick
 * field facts computed from the outline (area, perimeter, dimensions,
 * orientation, compactness, holes, parts, centre and source year) travel with
 * every outline so a client can show them when a field is clicked, without
 * another request. Any other source-specific properties are preserved in the
 * catch-all map.</p>
 */
public class FieldProperties {

    private String id;

    private Integer score;

    @Schema(description = "Estimated area in hectares (outline minus holes), not surveyed.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double areaHa;

    @Schema(description = "Estimated area in acres.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double areaAcres;

    @Schema(description = "Boundary length in metres, including holes.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer perimeterM;

    @Schema(description = "Length of the field's long side in metres, from its smallest enclosing rectangle.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer lengthM;

    @Schema(description = "Width of the field in metres, from its smallest enclosing rectangle.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer widthM;

    @Schema(description = "Bearing of the long side in degrees clockwise from north, 0-179 (0 north-south, 90 east-west).")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer orientationDeg;

    @Schema(description = "4 pi area / perimeter squared, 0-1 (circle 1, square 0.79); low means irregular or narrow.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double compactness;

    @Schema(description = "Areas inside the outline that are not field, such as wet spots, trees or buildings.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer holes;

    @Schema(description = "Separate pieces making up the field.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer parts;

    @Schema(description = "Field centre as [longitude, latitude].")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Double> centroid;

    @Schema(description = "Edition year of the field boundary data.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer sourceYear;

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
     * @return the estimated area in hectares (outline minus holes), not surveyed, or {@code null} when not provided
     */
    public Double getAreaHa() {
        return areaHa;
    }

    /**
     * @param areaHa the estimated area in hectares (outline minus holes), not surveyed
     */
    public void setAreaHa(Double areaHa) {
        this.areaHa = areaHa;
    }

    /**
     * @return the estimated area in acres, or {@code null} when not provided
     */
    public Double getAreaAcres() {
        return areaAcres;
    }

    /**
     * @param areaAcres the estimated area in acres
     */
    public void setAreaAcres(Double areaAcres) {
        this.areaAcres = areaAcres;
    }

    /**
     * @return the boundary length in metres, including holes, or {@code null} when not provided
     */
    public Integer getPerimeterM() {
        return perimeterM;
    }

    /**
     * @param perimeterM the boundary length in metres, including holes
     */
    public void setPerimeterM(Integer perimeterM) {
        this.perimeterM = perimeterM;
    }

    /**
     * @return the length of the field's long side in metres, from its smallest enclosing rectangle, or {@code null} when not provided
     */
    public Integer getLengthM() {
        return lengthM;
    }

    /**
     * @param lengthM the length of the field's long side in metres, from its smallest enclosing rectangle
     */
    public void setLengthM(Integer lengthM) {
        this.lengthM = lengthM;
    }

    /**
     * @return the width of the field in metres, from its smallest enclosing rectangle, or {@code null} when not provided
     */
    public Integer getWidthM() {
        return widthM;
    }

    /**
     * @param widthM the width of the field in metres, from its smallest enclosing rectangle
     */
    public void setWidthM(Integer widthM) {
        this.widthM = widthM;
    }

    /**
     * @return the bearing of the long side in degrees clockwise from north, 0-179 (0 north-south, 90 east-west), or {@code null} when not provided
     */
    public Integer getOrientationDeg() {
        return orientationDeg;
    }

    /**
     * @param orientationDeg the bearing of the long side in degrees clockwise from north, 0-179 (0 north-south, 90 east-west)
     */
    public void setOrientationDeg(Integer orientationDeg) {
        this.orientationDeg = orientationDeg;
    }

    /**
     * @return the 4 pi area / perimeter squared, 0-1 (circle 1, square 0.79); low means irregular or narrow, or {@code null} when not provided
     */
    public Double getCompactness() {
        return compactness;
    }

    /**
     * @param compactness the 4 pi area / perimeter squared, 0-1 (circle 1, square 0.79); low means irregular or narrow
     */
    public void setCompactness(Double compactness) {
        this.compactness = compactness;
    }

    /**
     * @return the areas inside the outline that are not field, such as wet spots, trees or buildings, or {@code null} when not provided
     */
    public Integer getHoles() {
        return holes;
    }

    /**
     * @param holes the areas inside the outline that are not field, such as wet spots, trees or buildings
     */
    public void setHoles(Integer holes) {
        this.holes = holes;
    }

    /**
     * @return the separate pieces making up the field, or {@code null} when not provided
     */
    public Integer getParts() {
        return parts;
    }

    /**
     * @param parts the separate pieces making up the field
     */
    public void setParts(Integer parts) {
        this.parts = parts;
    }

    /**
     * @return the field centre as [longitude, latitude], or {@code null} when not provided
     */
    public List<Double> getCentroid() {
        return centroid;
    }

    /**
     * @param centroid the field centre as [longitude, latitude]
     */
    public void setCentroid(List<Double> centroid) {
        this.centroid = centroid;
    }

    /**
     * @return the edition year of the field boundary data, or {@code null} when not provided
     */
    public Integer getSourceYear() {
        return sourceYear;
    }

    /**
     * @param sourceYear the edition year of the field boundary data
     */
    public void setSourceYear(Integer sourceYear) {
        this.sourceYear = sourceYear;
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
