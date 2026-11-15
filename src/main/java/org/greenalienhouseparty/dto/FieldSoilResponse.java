package org.greenalienhouseparty.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Topsoil estimates for a field from ISRIC SoilGrids 2.0 (250 m), as returned by
 * the data backend's {@code GET /fields/{field_id}/soil}.
 *
 * <p>These are machine-learning estimates for 250 m cells, averaged over the cells
 * the field touches; a farmer's soil test should take precedence. Values are
 * {@code null} over water, ice or built-up land.</p>
 *
 * @param id      the field identifier, absent for caller-provided outlines
 * @param topsoil thickness-weighted 0-30 cm means
 * @param derived plain-language classes and water holding derived from the topsoil
 * @param depths  the same properties for 0-5, 5-15 and 15-30 cm, keyed by depth label
 * @param units   unit of each property
 * @param source  data source
 * @param note    how the values were derived
 */
public record FieldSoilResponse(
        @JsonInclude(JsonInclude.Include.NON_NULL) String id,
        Properties topsoil,
        Derived derived,
        Map<String, Properties> depths,
        Map<String, String> units,
        Source source,
        String note) {

    /**
     * Soil properties for one depth range or the 0-30 cm topsoil.
     *
     * @param ph                  pH in water
     * @param clayPct             clay content, %
     * @param sandPct             sand content, %
     * @param siltPct             silt content, %
     * @param organicCarbonGPerKg soil organic carbon, g/kg
     * @param nitrogenGPerKg      total nitrogen, g/kg
     * @param bulkDensityGPerCm3  bulk density of the fine earth, g/cm3
     * @param cecCmolPerKg        cation exchange capacity, cmol(+)/kg
     */
    public record Properties(
            Double ph,
            Double clayPct,
            Double sandPct,
            Double siltPct,
            Double organicCarbonGPerKg,
            Double nitrogenGPerKg,
            Double bulkDensityGPerCm3,
            Double cecCmolPerKg) {
    }

    /**
     * Values derived from the topsoil for display and planning.
     *
     * @param textureClass     USDA soil texture class, e.g. "Loam"
     * @param organicMatterPct organic matter, % (organic carbon x 1.724)
     * @param phClass          USDA soil reaction class, e.g. "Slightly acid"
     * @param availableWaterMm plant-available water the top 30 cm can hold, mm (Saxton & Rawls 2006)
     */
    public record Derived(
            @Schema(description = "USDA soil texture class", example = "Loam") String textureClass,
            @Schema(description = "Organic matter, % (organic carbon x 1.724)") Double organicMatterPct,
            @Schema(description = "USDA soil reaction class", example = "Slightly acid") String phClass,
            @Schema(description = "Plant-available water the top 30 cm can hold, mm") Integer availableWaterMm) {
    }

    /**
     * Data source.
     *
     * @param name        source name
     * @param url         source website
     * @param resolutionM cell size in metres
     * @param license     data licence
     */
    public record Source(String name, String url, Integer resolutionM, String license) {
    }
}
