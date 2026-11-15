package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.client.DataBackendException;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse.CropYear;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.service.DataBackendService;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for turning data backend responses into planner input.
 */
class PlannerInputNormalizerUnitTest {

    private static final Clock IN_2026 = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    private final DataBackendService dataBackend = mock(DataBackendService.class);

    private final PlannerInputNormalizer normalizer =
            new PlannerInputNormalizer(dataBackend, new ExampleCropRepository(), IN_2026);

    /**
     * Verifies soil, terrain, area and crop history are read from the data
     * backend models, with the farmer's current crop first and unmapped or
     * missing years kept in position as no crop.
     */
    @Test
    void buildsFieldFromDataBackendResponses() {
        NormalizedField normalized = normalizer.toField("field-1", "Corn", soil(6.82, 4.9), history(), summary("high"));

        Field field = normalized.field();
        assertEquals("field-1", field.id());
        assertEquals(6.82, field.ph());
        assertEquals("Sandy loam", field.texture());
        assertEquals(4.9, field.organicMatterPct());
        assertEquals(42.72, field.areaHa());
        assertEquals(44.6, field.lat());
        assertEquals(-79.4, field.lon());
        assertEquals(0.9, field.erosionRisk());
        // 2026 from the farmer, then 2025, 2024, 2023 (no map), 2022 (woodland), 2021 (rye: not in the catalog)
        assertEquals(List.of("corn", "soybeans", "corn", Field.NO_CROP, Field.NO_CROP, Field.NO_CROP), field.history());
        assertTrue(normalized.assumptions().isEmpty());
    }

    /**
     * Verifies each terrain rating maps to its erosion risk.
     */
    @Test
    void mapsErosionRatings() {
        assertEquals(0.2, normalizer.toField("f", "corn", null, null, summary("low")).field().erosionRisk());
        assertEquals(0.5, normalizer.toField("f", "corn", null, null, summary("Moderate")).field().erosionRisk());
        assertEquals(0.9, normalizer.toField("f", "corn", null, null, summary("high")).field().erosionRisk());
    }

    /**
     * Verifies missing responses and null values fall back to neutral defaults
     * and that every default is reported as an assumption.
     */
    @Test
    void usesDefaultsAndReportsAssumptionsWhenDataIsMissing() {
        NormalizedField nothing = normalizer.toField("field-1", "soybeans", null, null, null);
        NormalizedField nullValues = normalizer.toField("field-1", "soybeans", soil(null, null),
                new CropHistoryResponse(null, null, List.of(), List.of(), List.of(), 0, List.of(), null, null),
                new FieldSummaryResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        for (NormalizedField normalized : List.of(nothing, nullValues)) {
            Field field = normalized.field();
            assertEquals(PlannerInputNormalizer.DEFAULT_PH, field.ph());
            assertEquals(PlannerInputNormalizer.DEFAULT_ORGANIC_MATTER_PCT, field.organicMatterPct());
            assertEquals(PlannerInputNormalizer.DEFAULT_EROSION_RISK, field.erosionRisk());
            assertEquals(PlannerInputNormalizer.DEFAULT_AREA_HA, field.areaHa());
            assertEquals(List.of("soybeans"), field.history());
            assertEquals(5, normalized.assumptions().size());
        }
        assertEquals(PlannerInputNormalizer.UNKNOWN_TEXTURE, nothing.field().texture());
    }

    /**
     * Verifies a data backend failure for one lookup is treated as missing
     * data while the other lookups are still used.
     *
     * @throws Exception if the geometry fixture cannot be parsed
     */
    @Test
    void keepsPlanningWhenADataBackendLookupFails() throws Exception {
        when(dataBackend.soil(any())).thenThrow(new DataBackendException(502, "SoilGrids is unavailable"));
        when(dataBackend.cropHistory(any())).thenReturn(history());
        when(dataBackend.fieldSummary(any())).thenThrow(new DataBackendException(502, "SoilGrids is unavailable"));
        var request = new CropOptimizationRequest.FieldRequest(
                "field-1",
                JsonMapper.builder().build().readTree(
                        "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"),
                "Corn", List.of("Soybeans"), null);

        NormalizedField normalized = normalizer.normalize(request);

        assertEquals(PlannerInputNormalizer.DEFAULT_PH, normalized.field().ph());
        assertEquals("soybeans", normalized.field().history().get(1));
        assertEquals(4, normalized.assumptions().size());
    }

    /**
     * Builds topsoil estimates.
     *
     * @param ph            topsoil pH, or {@code null}
     * @param organicMatter organic matter in percent, or {@code null}
     * @return a soil response
     */
    private static FieldSoilResponse soil(Double ph, Double organicMatter) {
        return new FieldSoilResponse(
                null,
                new FieldSoilResponse.Properties(ph, 16.45, 52.31, 31.24, 28.7, 2.76, 1.48, 17.05),
                new FieldSoilResponse.Derived(ph == null ? null : "Sandy loam", organicMatter, "Neutral", 40),
                Map.of(), Map.of(), null, null);
    }

    /**
     * Builds a crop history with a gap in 2023, woodland in 2022 and a crop
     * outside the catalog in 2021.
     *
     * @return a crop-history response
     */
    private static CropHistoryResponse history() {
        List<CropYear> years = List.of(
                new CropYear(2021, "Rye", 137, true, "other_grains", "Other grains", 0.9, 0.95, List.of()),
                new CropYear(2022, "Forest (undifferentiated)", 200, false, "natural", "Woodland or wetland", 0.8, 0.1, List.of()),
                new CropYear(2024, "Corn for grain", 147, true, "corn", "Corn", 0.88, 0.99, List.of()),
                new CropYear(2025, "Soybeans", 158, true, "soybeans", "Soybeans", 0.97, 0.99, List.of()));
        return new CropHistoryResponse(null, null, years, List.of(), List.of(), 3, List.of(2023), null, null);
    }

    /**
     * Builds a field summary with area, centroid and a terrain erosion rating.
     *
     * @param erosionRating terrain rating
     * @return a field summary response
     */
    private static FieldSummaryResponse summary(String erosionRating) {
        return new FieldSummaryResponse(
                new FieldSummaryResponse.FieldInfo(new FieldSummaryResponse.Centroid(44.6, -79.4), 42.72),
                "daymet", null, null, null, null, null, null, null, null, null,
                new FieldSummaryResponse.TerrainInfo(250.0, 12.0, 3.1, 9.4, erosionRating, 0.1, 0.05),
                null, null);
    }
}
