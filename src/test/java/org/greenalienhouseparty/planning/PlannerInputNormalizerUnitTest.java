package org.greenalienhouseparty.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.greenalienhouseparty.client.DataBackendException;
import org.greenalienhouseparty.dto.CropCalendarResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse.CropYear;
import org.greenalienhouseparty.dto.CropOptimizationRequest;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.greenalienhouseparty.planning.model.Field;
import org.greenalienhouseparty.planning.model.FieldConditions;
import org.greenalienhouseparty.service.DataBackendService;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for turning data backend responses into planner input.
 */
class PlannerInputNormalizerUnitTest {

    private static final Clock IN_2026 = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    private final DataBackendService dataBackend = mock(DataBackendService.class);

    private final SoilLandscapeService soilLandscape = mock(SoilLandscapeService.class);

    private final SeasonalOutlookService seasonalOutlook = mock(SeasonalOutlookService.class);

    private final PlannerInputNormalizer normalizer =
            new PlannerInputNormalizer(dataBackend, new ExampleCropRepository(), soilLandscape, seasonalOutlook, IN_2026);

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
        // No climate record, crop calendar or regional soil ratings were given
        assertEquals(3, normalized.assumptions().size());
    }

    /**
     * Verifies the climate record, crop seasons, regional soil ratings and the
     * first plan year's rainfall chances are gathered, the two erosion ratings
     * are averaged, and nothing is reported as assumed when everything is known
     * apart from the calendar's autumn-sown barley.
     */
    @Test
    void gathersClimateCalendarAndRegionalSoilRatings() {
        NormalizedField normalized = normalizer.toField("field-1", "Corn", soil(6.82, 4.9), history(),
                summaryWithClimate("high"), calendar(), landscape("Canada"), outlook(), 2027, null);

        Field field = normalized.field();
        FieldConditions conditions = field.conditions();
        // Terrain "high" is 0.9 and the regional "Moderate" is 0.5
        assertEquals(0.7, field.erosionRisk(), 1e-9);
        assertEquals(12, conditions.monthlyMeanTempC().size());
        assertEquals(19.5, conditions.monthlyMeanTempC().get(6));
        assertNull(conditions.monthlyMeanTempC().get(0));
        assertEquals(List.of(5, 6, 7, 8, 9), conditions.seasonMonths());
        // 2026 has no rainfall total yet, so only two years are kept
        assertEquals(List.of(1010.0, 880.0), conditions.yearlyRainfallMm());
        assertEquals(List.of(150.0, 162.0, 140.0), conditions.yearlyFrostFreeDays());
        assertEquals(List.of(2900.0, 3100.0), conditions.yearlyHeatUnits());
        assertEquals(410.0, conditions.seasonRainfallMm());
        assertEquals(Set.of("corn", "wheat", "barley"), conditions.cropSeasons().keySet());
        assertEquals(new FieldConditions.CropSeason(121, 283, 162, false), conditions.cropSeasons().get("corn"));
        assertTrue(conditions.cropSeasons().get("wheat").crossesNewYear());
        assertEquals("2", conditions.landClass());
        assertEquals("W", conditions.drainageCode());
        assertTrue(conditions.residualNitrogenHigh());
        assertTrue(conditions.organicCarbonDeclining());
        // May to July 2027 is the outlook period inside the first plan year's season
        assertEquals(0.5, conditions.firstYearRainfall().below(), 1e-9);
        assertEquals(0.3, conditions.firstYearRainfall().near(), 1e-9);
        assertEquals(0.2, conditions.firstYearRainfall().above(), 1e-9);
        assertEquals(1, normalized.assumptions().size());
        assertTrue(normalized.assumptions().getFirst().contains("barley as sown in autumn"));
    }

    /**
     * Verifies the autumn-sown note is left out for a crop the farmer does not
     * grow, a field outside the regional soil maps is reported, an outlook for
     * a different year is ignored and a regional rating stands in for a
     * missing terrain rating.
     */
    @Test
    void limitsNotesAndRegionalDataToWhatApplies() {
        NormalizedField outsideCanada = normalizer.toField("field-1", "Corn", soil(6.82, 4.9), history(),
                summaryWithClimate("high"), calendar(), landscape(null), outlook(), 2028, Set.of("corn"));
        NormalizedField noTerrain = normalizer.toField("field-1", "Corn", soil(6.82, 4.9), history(),
                summaryWithClimate(null), calendar(), landscape("Canada"), null, 2027, Set.of("corn"));

        assertEquals(0.9, outsideCanada.field().erosionRisk());
        assertNull(outsideCanada.field().conditions().landClass());
        assertFalse(outsideCanada.field().conditions().residualNitrogenHigh());
        assertNull(outsideCanada.field().conditions().firstYearRainfall());
        assertEquals(1, outsideCanada.assumptions().size());
        assertTrue(outsideCanada.assumptions().getFirst().contains("regional soil ratings"));
        assertEquals(0.5, noTerrain.field().erosionRisk());
        assertNull(noTerrain.field().conditions().firstYearRainfall());
        assertTrue(noTerrain.assumptions().isEmpty());
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
            assertEquals(FieldConditions.unknown(), field.conditions());
            // Five defaults, plus no climate record, no crop calendar and no regional soil ratings
            assertEquals(8, normalized.assumptions().size());
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
        // pH, organic matter, area and terrain, plus climate, crop calendar and regional soil ratings
        assertEquals(7, normalized.assumptions().size());
        // Without the summary there is no field centre to look the regional services up at
        verify(soilLandscape, never()).landscape(anyDouble(), anyDouble());
        verify(seasonalOutlook, never()).outlook(anyDouble(), anyDouble());
    }

    /**
     * Verifies the crop calendar, regional soil ratings and seasonal outlook
     * are looked up for the field, and that an outside service failing is
     * treated as missing data.
     *
     * @throws Exception if the geometry fixture cannot be parsed
     */
    @Test
    void looksUpRegionalServicesAtTheFieldCentreAndSurvivesTheirFailure() throws Exception {
        when(dataBackend.soil(any())).thenReturn(soil(6.82, 4.9));
        when(dataBackend.cropHistory(any())).thenReturn(history());
        when(dataBackend.fieldSummary(any())).thenReturn(summaryWithClimate("high"));
        when(dataBackend.cropCalendar(any())).thenReturn(calendar());
        when(soilLandscape.landscape(44.6, -79.4)).thenThrow(new ExternalDataException(502, "The soil service is unavailable"));
        when(seasonalOutlook.outlook(44.6, -79.4)).thenReturn(outlook());
        var request = new CropOptimizationRequest.FieldRequest(
                "field-1",
                JsonMapper.builder().build().readTree(
                        "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"),
                "Corn", List.of("Soybeans", "Wheat"), null);

        NormalizedField normalized = normalizer.normalize(request);

        FieldConditions conditions = normalized.field().conditions();
        assertEquals(0.9, normalized.field().erosionRisk());
        assertTrue(conditions.cropSeasons().containsKey("wheat"));
        assertNull(conditions.landClass());
        // The clock reads 2026, so the first plan year is 2027
        assertEquals(0.5, conditions.firstYearRainfall().below(), 1e-9);
        assertEquals(1, normalized.assumptions().size());
        assertTrue(normalized.assumptions().getFirst().contains("regional soil ratings"));
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
     * Builds a field summary that also carries a climate record: July's mean
     * temperature, a May to September season, and three years of which the
     * last has no rainfall or heat-unit total yet.
     *
     * @param erosionRating terrain rating, or {@code null} for no terrain
     * @return a field summary response
     */
    private static FieldSummaryResponse summaryWithClimate(String erosionRating) {
        var climate = new FieldSummaryResponse.ClimateInfo(null, null, null, 410.0, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        var monthly = List.of(new FieldSummaryResponse.MonthlyRow(7, 80.0, 1.0, 120.0, 0.0, 19.5));
        var yearly = new FieldSummaryResponse.YearlyInfo(List.of(
                yearRow(2024, 2900.0, 1010.0, 150.0),
                yearRow(2025, 3100.0, 880.0, 162.0),
                yearRow(2026, null, null, 140.0)), Map.of());
        return new FieldSummaryResponse(
                new FieldSummaryResponse.FieldInfo(new FieldSummaryResponse.Centroid(44.6, -79.4), 42.72),
                "daymet", null, null,
                new FieldSummaryResponse.SeasonInfo(List.of(5, 6, 7, 8, 9), false, 10.0, "May-Sep"),
                climate, null, null, monthly, yearly, null,
                erosionRating == null ? null : new FieldSummaryResponse.TerrainInfo(250.0, 12.0, 3.1, 9.4, erosionRating, 0.1, 0.05),
                null, null);
    }

    /**
     * Builds one year of the climate record.
     *
     * @param year          calendar year
     * @param heatUnits     crop heat units, or {@code null}
     * @param rainfallMm    total rainfall, or {@code null}
     * @param frostFreeDays frost-free days, or {@code null}
     * @return the year row
     */
    private static FieldSummaryResponse.YearRow yearRow(Integer year, Double heatUnits, Double rainfallMm, Double frostFreeDays) {
        return new FieldSummaryResponse.YearRow(year, heatUnits, null, null, null, rainfallMm, null, null, null,
                frostFreeDays, null, null, null, null, null);
    }

    /**
     * Builds a crop calendar with corn, winter wheat, barley given as
     * autumn-sown, and a crop with no dates.
     *
     * @return a crop calendar response
     */
    private static CropCalendarResponse calendar() {
        return new CropCalendarResponse(null, null, null, "rainfed", List.of(
                new CropCalendarResponse.CropSeason("corn", "Corn", 121, "05-01", 283, "10-10", 162, 203, false, "MIRCA2000"),
                new CropCalendarResponse.CropSeason("winter_wheat", "Winter wheat", 284, "10-11", 208, "07-27", 289, 76, true, "SAGE"),
                new CropCalendarResponse.CropSeason("barley", "Barley", 305, "11-01", 144, "05-24", 204, 161, true, "SAGE"),
                new CropCalendarResponse.CropSeason("potatoes", "Potatoes", null, null, null, null, null, null, null, null)),
                null, null);
    }

    /**
     * Builds regional soil ratings: land class 2, well drained, moderate
     * erosion risk, declining soil carbon and high leftover nitrogen.
     *
     * @param coverage area the ratings cover, or {@code null} for a field outside it
     * @return a soil landscape response
     */
    private static SoilLandscapeResponse landscape(String coverage) {
        return new SoilLandscapeResponse(null, coverage,
                new SoilLandscapeResponse.LandCapability("2", "Moderate limitations", List.of(), "1:250,000"),
                new SoilLandscapeResponse.Drainage("W", "Well and moderately well drained", 69),
                List.of(indicator("erosionRisk", 12.47, "Moderate"),
                        indicator("organicCarbonChange", -72.71, "Moderate decrease"),
                        indicator("residualSoilNitrogen", 32.5, "High")),
                null, null);
    }

    /**
     * Builds one regional soil indicator.
     *
     * @param key    indicator key
     * @param value  latest value
     * @param rating latest rating
     * @return the indicator
     */
    private static SoilLandscapeResponse.Indicator indicator(String key, Double value, String rating) {
        return new SoilLandscapeResponse.Indicator(key, key, null, null, 2021, value, rating, null, List.of());
    }

    /**
     * Builds a seasonal outlook issued in November 2026 whose third period,
     * May to July 2027, leans dry, while the first shows no clear signal.
     *
     * @return a seasonal outlook response
     */
    private static SeasonalOutlookResponse outlook() {
        var noSignal = new SeasonalOutlookResponse.Chances(34.0, 33.0, 33.0, "no clear signal");
        var leansDry = new SeasonalOutlookResponse.Chances(20.0, 30.0, 50.0, "below");
        return new SeasonalOutlookResponse(null, "2026-11", List.of(
                new SeasonalOutlookResponse.Period("2026-11", "2027-01", "Nov 2026 - Jan 2027", noSignal, noSignal),
                new SeasonalOutlookResponse.Period("2027-05", "2027-07", "May 2027 - Jul 2027", noSignal, leansDry)),
                null, null);
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
