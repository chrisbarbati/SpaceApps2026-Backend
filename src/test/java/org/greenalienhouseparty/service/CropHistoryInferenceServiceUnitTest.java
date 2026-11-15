package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse.CropYear;
import org.greenalienhouseparty.dto.FieldCropHistoryBatchRequest;
import org.greenalienhouseparty.dto.FieldCropHistoryBatchResponse;
import org.junit.jupiter.api.Test;

/** Verifies application inference independently of acquisition, wall time and UI behavior. */
class CropHistoryInferenceServiceUnitTest {
    private final CropHistoryInferenceService inference = new CropHistoryInferenceService(
            Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC));
    private static final Map<Character, String> NAMES = Map.of(
            'C', "Corn for grain", 'S', "Soybeans", 'W', "Winter wheat", 'P', "Pasture/forages", 'F', "Mixedwood");

    /** Builds source observations with no application grouping or summary. */
    private static CropHistoryResponse history(String sequence, int start) {
        List<CropYear> years = new ArrayList<>();
        for (int index = 0; index < sequence.length(); index++) {
            char crop = sequence.charAt(index);
            years.add(new CropYear(start + index, NAMES.get(crop), index, crop != 'F', null, null,
                    0.95, crop == 'F' ? 0.0 : 1.0, List.of()));
        }
        return raw(years);
    }

    /** Wraps acquired observations in the Python response contract. */
    private static CropHistoryResponse raw(List<CropYear> years) {
        return new CropHistoryResponse("field", null, years, List.of(), List.of(), 0, List.of(), null, "Source classifications");
    }

    /** The screenshot's recent wheat-corn-soybean cycle must suggest soybeans, not last year's corn. */
    @Test
    void recentThreeYearCycleSuggestsSoybeans() {
        var summary = inference.interpret(history("WCSWC", 2021)).summary();
        assertEquals("Corn", summary.latestMapped().groupLabel());
        assertEquals("Soybeans", summary.currentCrop().crop());
        assertEquals("estimated", summary.currentCrop().status());
        assertEquals("medium", summary.currentCrop().confidence());
        assertEquals(3, summary.pattern().periodYears());
        assertTrue(summary.currentCrop().reason().contains("possible recent"));
    }

    /** A recent exact cycle can emerge after an irregular older history. */
    @Test
    void recentCycleIsNotHiddenByOlderIrregularYears() {
        var summary = inference.interpret(history("PSPCWCSWC", 2017)).summary();
        assertEquals("Soybeans", summary.currentCrop().crop());
        assertEquals("medium", summary.currentCrop().confidence());
    }

    /** Established repeated cycles retain strong pattern support. */
    @Test
    void establishedRotationsAndStableCropsAreInterpreted() {
        for (var example : Map.of("CSCSCSCSC", "Soybeans", "CCSCCSCCS", "Corn", "PPPPPPPPP", "Pasture").entrySet()) {
            var current = inference.interpret(history(example.getKey(), 2017)).summary().currentCrop();
            assertEquals(example.getValue(), current.crop());
            assertEquals("high", current.confidence());
        }
    }

    /** A lone prior observation never becomes a current-season suggestion. */
    @Test
    void missingPatternHasNoLastCropFallback() {
        var summary = inference.interpret(history("C", 2025)).summary();
        assertEquals("Corn", summary.latestMapped().groupLabel());
        assertEquals("unavailable", summary.currentCrop().status());
        assertNull(summary.currentCrop().crop());
        assertNull(summary.expectedThisYear());
    }

    /** Missing calendar years break support instead of being compressed into a rotation. */
    @Test
    void gapsStayVisibleAndDoNotCreateFalseAlternation() {
        List<CropYear> years = new ArrayList<>(history("CSCSC", 2021).years());
        years.remove(3);
        var summary = inference.interpret(raw(years)).summary();
        assertEquals("unavailable", summary.currentCrop().status());
        assertNull(summary.recentYears().get(3).observation());
        assertEquals(2024, summary.recentYears().get(3).year());
    }

    /** Low-share observations cannot bridge an otherwise apparent rotation. */
    @Test
    void weakLatestObservationStopsInference() {
        List<CropYear> years = new ArrayList<>(history("CSCSCSCSC", 2017).years());
        var last = years.removeLast();
        years.add(new CropYear(last.year(), last.crop(), last.code(), true, null, null, 0.4, 1.0, List.of()));
        assertNull(inference.interpret(raw(years)).summary().currentCrop().crop());
    }

    /** A changed latest crop, stale inventory or non-crop land produces no assumed crop. */
    @Test
    void changedStaleAndNonCropHistoryRemainUnavailable() {
        for (var raw : List.of(history("PPPPPPPPC", 2017), history("CSCSCSCSC", 2010), history("FFFFFFFFF", 2017))) {
            var summary = inference.interpret(raw).summary();
            assertEquals("unavailable", summary.currentCrop().status());
            assertNull(summary.currentCrop().crop());
        }
    }

    /** Current-season classification is identified as mapped evidence, not a rotation guess. */
    @Test
    void currentYearMappingTakesPrecedenceOverRotation() {
        var summary = inference.interpret(history("WCSWCC", 2021)).summary();
        assertEquals("mapped", summary.currentCrop().status());
        assertEquals("Corn", summary.currentCrop().crop());
        assertNull(summary.currentCrop().confidence());
        assertNull(summary.expectedThisYear());
        assertEquals(2026, summary.latestMapped().year());
    }

    /** Duplicate years do not provide repeated independent support. */
    @Test
    void duplicateLatestYearIsAmbiguous() {
        List<CropYear> years = new ArrayList<>(history("CSCSCSCSC", 2017).years());
        years.add(years.getLast());
        assertNull(inference.interpret(raw(years)).summary().currentCrop().crop());
    }

    /** Provider names are normalized in Java and upstream group labels are ignored. */
    @Test
    void normalizesBothCountriesWithoutTrustingUpstreamGroups() {
        var row = new CropYear(2026, "Winter wheat", 145, true, "corn", "Corn", 0.95, 1.0, List.of());
        var response = inference.interpret(raw(List.of(row)));
        assertEquals("wheat", response.years().getFirst().group());
        assertEquals("Wheat", response.summary().currentCrop().crop());
    }

    /** Existing Python summaries cannot bypass Java's interpretation during a rolling update. */
    @Test
    void discardsUpstreamInferenceAndBuildsAnExplicitEmptyResult() {
        var suppliedSummary = inference.interpret(history("PPPPPPPPP", 2017)).summary();
        var empty = raw(List.of());
        var raw = new CropHistoryResponse(empty.id(), empty.source(), empty.years(), empty.rotation(), empty.distinctCrops(),
                empty.cropYears(), empty.unavailableYears(), suppliedSummary, empty.note());
        var result = inference.interpret(raw).summary();
        assertEquals(2026, result.reviewYear());
        assertEquals("unavailable", result.currentCrop().status());
        assertNull(result.currentCrop().crop());
        assertNull(result.latestMapped());
    }

    /** All three public crop-history paths use the same application interpreter; batch errors stay intact. */
    @Test
    void interpretsFieldOutlineAndBatchRoutes() {
        var client = mock(DataBackendClient.class);
        var service = new DataBackendService(client, inference);
        var raw = history("WCSWC", 2021);
        var request = new CropHistoryRequest();
        var error = new FieldCropHistoryBatchResponse.Result("missing", "error", null,
                new FieldCropHistoryBatchResponse.Error(404, "Field not found"));
        when(client.fieldCropHistory("field")).thenReturn(raw);
        when(client.cropHistory(request)).thenReturn(raw);
        when(client.fieldCropHistories(List.of("field", "missing"))).thenReturn(new FieldCropHistoryBatchResponse(
                List.of(new FieldCropHistoryBatchResponse.Result("field", "ok", raw, null), error), 2, 1, 1));
        assertEquals("Soybeans", service.fieldCropHistory("field").summary().currentCrop().crop());
        assertEquals("Soybeans", service.cropHistory(request).summary().currentCrop().crop());
        var batch = service.fieldCropHistories(new FieldCropHistoryBatchRequest(List.of("field", "missing")));
        assertEquals("Soybeans", batch.results().getFirst().history().summary().currentCrop().crop());
        assertSame(error, batch.results().getLast());
        assertEquals(1, batch.failed());
    }
}
