package org.greenalienhouseparty.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * GeoJSON soil-moisture layer returned by the Python data service's SMAP route.
 *
 * <p>Each feature is a native-grid cell. Its variable names can evolve with the
 * SMAP product schema, so the cell's numeric measurements are retained in a
 * typed map while row and column remain explicit properties.</p>
 *
 * @param type     GeoJSON collection type
 * @param features native-grid SMAP cell polygons and measurements
 * @param metadata product, units, timing, cache, and limitation information
 */
public record SoilMoistureResponse(String type, List<CellFeature> features, LayerMetadata metadata) {

    /**
     * GeoJSON feature representing one native-grid SMAP cell.
     *
     * @param type       GeoJSON feature type
     * @param id         stable cell identifier
     * @param geometry   cell polygon in WGS84 coordinates
     * @param properties grid indices and SMAP measurements
     */
    public record CellFeature(String type, String id, CellGeometry geometry, CellProperties properties) {
    }

    /**
     * Polygon geometry of a single SMAP cell.
     *
     * @param type        GeoJSON geometry type
     * @param coordinates polygon rings as longitude/latitude positions
     */
    public record CellGeometry(String type, List<List<List<Double>>> coordinates) {
    }

    /**
     * Grid location and measurements for one SMAP cell.
     *
     * <p>SMAP measurement names are retained as map keys. Values are numeric or
     * {@code null} when the source value is missing or invalid.</p>
     */
    public static class CellProperties {

        private Integer row;

        private Integer column;

        private final Map<String, JsonNode> measurements = new LinkedHashMap<>();

        /**
         * @return the row index in the source grid
         */
        public Integer getRow() {
            return row;
        }

        /**
         * @param row source grid row index
         */
        public void setRow(Integer row) {
            this.row = row;
        }

        /**
         * @return the column index in the source grid
         */
        public Integer getColumn() {
            return column;
        }

        /**
         * @param column source grid column index
         */
        public void setColumn(Integer column) {
            this.column = column;
        }

        /**
         * @return SMAP variable names and their numeric or null values
         */
        @JsonAnyGetter
        public Map<String, JsonNode> getMeasurements() {
            return measurements;
        }

        /**
         * Captures the dynamic SMAP variable properties without discarding
         * newer product fields.
         *
         * @param name  SMAP variable name
         * @param value numeric measurement or null source value
         */
        @JsonAnySetter
        public void setMeasurement(String name, JsonNode value) {
            measurements.put(name, value);
        }
    }

    /**
     * Provenance and interpretation metadata for the returned SMAP layer.
     *
     * @param source                    data source name
     * @param product                   NASA product identifier
     * @param version                   NASA product version
     * @param schemaVersion             cached layer schema version
     * @param variables                 units and descriptions by variable name
     * @param missingVariables          variables unavailable in the selected source granule
     * @param doi                       product DOI
     * @param url                       product information URL
     * @param spatialResolutionKm       nominal spatial resolution in kilometres
     * @param temporalResolutionHours   temporal resolution in hours
     * @param units                     legacy units field for core moisture values
     * @param depthCm                   documented depth ranges by core moisture variable
     * @param requestedBbox             requested west/south/east/north bounds
     * @param granule                   selected NASA granule identifier
     * @param timeStart                 start time of the selected interval
     * @param timeEnd                   end time of the selected interval
     * @param built                     cache layer creation time
     * @param limitations               usage and interpretation notes
     * @param soilConstants             matched static soil-constants provenance, if available
     */
    public record LayerMetadata(
            String source,
            String product,
            String version,
            @JsonProperty("schema_version") Integer schemaVersion,
            Map<String, VariableMetadata> variables,
            @JsonProperty("missing_variables") List<String> missingVariables,
            String doi,
            String url,
            @JsonProperty("spatial_resolution_km") Double spatialResolutionKm,
            @JsonProperty("temporal_resolution_hours") Integer temporalResolutionHours,
            String units,
            @JsonProperty("depth_cm") Map<String, List<Integer>> depthCm,
            @JsonProperty("requested_bbox") List<Double> requestedBbox,
            String granule,
            @JsonProperty("time_start") String timeStart,
            @JsonProperty("time_end") String timeEnd,
            String built,
            String limitations,
            @JsonProperty("soil_constants") SoilConstantsMetadata soilConstants) {
    }

    /**
     * Units, descriptive text, product association, and static/dynamic status
     * for one SMAP variable.
     *
     * @param units       units reported for the variable
     * @param description human-readable variable meaning
     * @param product     NASA product that supplies the variable
     * @param staticValue whether the variable is a static model input
     */
    public record VariableMetadata(String units, String description, String product,
            @JsonProperty("static") Boolean staticValue) {
    }

    /**
     * Provenance for static soil constants matched to the dynamic granule.
     *
     * @param product        constants product identifier
     * @param version        product version
     * @param scienceVersion matched science processing version
     * @param granule        constants granule name
     * @param url            product information URL
     * @param staticValue    indicates these values are static model inputs
     */
    public record SoilConstantsMetadata(String product, String version,
            @JsonProperty("science_version") String scienceVersion,
            String granule, String url,
            @JsonProperty("static") Boolean staticValue) {
    }
}
