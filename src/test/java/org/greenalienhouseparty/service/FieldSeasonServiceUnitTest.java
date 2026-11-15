package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.dto.HlsAcquisitionResponse;
import org.greenalienhouseparty.dto.HlsAcquisitionResponse.*;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

/** Verifies quality-aware presentation of actual HLS measurements and calendar periods. */
class FieldSeasonServiceUnitTest {
    private final DataBackendClient client = mock(DataBackendClient.class);
    private final FieldSeasonService service = new FieldSeasonService(client,
            Clock.fixed(Instant.parse("2026-11-15T01:00:00Z"), ZoneOffset.UTC));

    /** Creates an overpass with controlled land support and measured reflectance. */
    private Observation observation(String id, String day, double ndvi, double support, double snow, double water) {
        return new Observation(id, LocalDate.parse(day), Instant.parse(day + "T15:00:00Z"), "Landsat", "hls2-l30",
                1.0, support, 1 - support, snow, water, support, 20, ndvi, ndvi - .1, ndvi + .1);
    }

    /** Wraps observations in the acquisition contract. */
    private HlsAcquisitionResponse raw(List<Observation> observations) {
        return new HlsAcquisitionResponse("field", "job", "done",
                new Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 15)), new Progress(observations.size(), observations.size()),
                new Grid(30, 50, 25, true), observations, List.of(),
                new Source("NASA HLS", "provider", 30, "https://hls.gsfc.nasa.gov/", List.of("hls2-l30")), null, 1.0, 5.0);
    }

    /** Snow, water and weak support cannot drive peaks or create fabricated zero-valued NDVI. */
    @Test
    void unsupportedPassesRemainVisibleButDoNotEnterSummary() {
        var response = service.prepare("field", 2026, raw(List.of(
                observation("clear", "2026-06-01", .6, .9, 0, 0),
                observation("partial", "2026-06-02", .95, .5, 0, 0),
                observation("snow", "2026-06-03", .99, .9, .8, 0),
                observation("water", "2026-06-04", .98, .9, 0, .8),
                observation("cloud", "2026-06-05", .9, .1, 0, 0))));
        assertEquals(1, response.summary().clearObservations());
        assertEquals(1, response.summary().partialObservations());
        assertEquals(.6, response.summary().peakNdvi());
        assertEquals(List.of("clear", "partial", "snow", "water", "cloudy"), response.points().stream().map(p -> p.quality()).toList());
        assertNull(response.points().get(2).ndvi());
        assertNull(response.points().get(3).ndvi());
        assertNull(response.points().get(4).ndvi());
    }

    /** Daily selection follows support rather than whichever sensor happens to produce higher greenness. */
    @Test
    void dailyDeduplicationAndGapSegmentsDoNotInventContinuity() {
        var response = service.prepare("field", 2026, raw(List.of(
                observation("low-support-high-value", "2026-06-01", .95, .75, 0, 0),
                observation("best-support", "2026-06-01", .5, .99, 0, 0),
                observation("nearby", "2026-06-10", .6, .9, 0, 0),
                observation("gap", "2026-08-01", -.2, .9, 0, 0))));
        assertEquals(3, response.points().size());
        assertEquals("best-support", response.points().getFirst().sceneId());
        assertEquals(2, response.segments().size());
        assertEquals(2, response.segments().getFirst().size());
        assertTrue(response.gaps().stream().anyMatch(g -> g.days() == 52));
        assertEquals(-1.0, response.axisTicks().getFirst());
        assertEquals(-.2, response.summary().latestNdvi());
    }

    /** Java owns season boundaries, including the UTC day shared with Python acquisition. */
    @Test
    void backendChoosesCalendarPeriodAndRejectsFutureYears() {
        when(client.fieldSeason("field", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 15), false)).thenReturn(raw(List.of()));
        assertEquals(2026, service.fieldSeason("field", null, false).year());
        verify(client).fieldSeason("field", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 15), false);
        assertThrows(InvalidRequestException.class, () -> service.fieldSeason("field", 2027, false));
        assertThrows(InvalidRequestException.class, () -> service.fieldSeason("field", 2012, false));
    }
}
