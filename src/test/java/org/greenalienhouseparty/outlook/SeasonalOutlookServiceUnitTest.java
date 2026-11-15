package org.greenalienhouseparty.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse.Chances;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.outlook.GeoMetClient.PointValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for building the seasonal outlook from CanSIPS point values.
 */
class SeasonalOutlookServiceUnitTest {

    private static final String RUN = "2026-11-01T00:00:00Z";

    private final GeoMetClient client = mock(GeoMetClient.class);

    private final SeasonalOutlookService service = new SeasonalOutlookService(client, Duration.ofHours(6));

    /**
     * Stops the service's request threads.
     */
    @AfterEach
    void stop() {
        service.shutdown();
    }

    /**
     * Verifies the newest run is discovered first, three consecutive periods
     * are read for that run, and the most likely category is named.
     */
    @Test
    void readsThreePeriodsOfTheNewestRun() {
        answerWith(RUN);

        SeasonalOutlookResponse response = service.outlook(44.35, -79.16);

        assertEquals("2026-11", response.issued());
        assertEquals(List.of("2026-11", "2027-02", "2027-05"),
                response.periods().stream().map(SeasonalOutlookResponse.Period::startMonth).toList());
        assertEquals("2027-01", response.periods().getFirst().endMonth());
        assertEquals("Nov 2026 - Jan 2027", response.periods().getFirst().label());
        assertEquals(new Chances(70.0, 20.0, 10.0, "above"), response.periods().getFirst().temperature());
        assertEquals(new Chances(30.0, 36.0, 34.0, "no clear signal"), response.periods().getFirst().precipitation());
        verify(client).valueAt(eq(SeasonalOutlookService.layer("AirTemp", "Above")), eq(44.35), eq(-79.16),
                isNull(), isNull());
        verify(client).valueAt(SeasonalOutlookService.layer("PrecipAccum", "Below"), 44.35, -79.16,
                "2027-05-01T00:00:00Z", RUN);
        verify(client, times(19)).valueAt(anyString(), anyDouble(), anyDouble(), any(), any());
    }

    /**
     * Verifies nearby points share a cached answer but keep their own
     * coordinates, and that a distant point is fetched separately.
     */
    @Test
    void cachesPerGridCell() {
        answerWith(RUN);

        service.outlook(44.35, -79.16);
        SeasonalOutlookResponse nearby = service.outlook(44.40, -79.10);

        assertEquals(44.40, nearby.location().lat());
        verify(client, times(19)).valueAt(anyString(), anyDouble(), anyDouble(), any(), any());
        service.outlook(50.0, -100.0);
        verify(client, times(38)).valueAt(anyString(), anyDouble(), anyDouble(), any(), any());
    }

    /**
     * Verifies a point with no CanSIPS value returns no periods rather than failing.
     */
    @Test
    void returnsNoPeriodsWhereThereIsNoOutlook() {
        when(client.valueAt(anyString(), anyDouble(), anyDouble(), any(), any())).thenReturn(Optional.empty());

        SeasonalOutlookResponse response = service.outlook(0, 0);

        assertNull(response.issued());
        assertTrue(response.periods().isEmpty());
        assertTrue(response.note().startsWith("No seasonal outlook"));
    }

    /**
     * Verifies out-of-range coordinates are rejected before any request and
     * that a GeoMet outage is passed on, including from a later request.
     */
    @Test
    void validatesCoordinatesAndPassesOnOutages() {
        assertThrows(InvalidRequestException.class, () -> service.outlook(91, 0));
        assertThrows(InvalidRequestException.class, () -> service.outlook(0, 181));
        assertThrows(InvalidRequestException.class, () -> service.outlook(Double.NaN, 0));
        verifyNoInteractions(client);

        when(client.valueAt(anyString(), anyDouble(), anyDouble(), isNull(), isNull()))
                .thenReturn(Optional.of(new PointValue(70, RUN, RUN)));
        when(client.valueAt(anyString(), anyDouble(), anyDouble(), anyString(), anyString()))
                .thenThrow(new ExternalDataException(502, "Environment Canada's forecast service is unavailable"));
        assertThrows(ExternalDataException.class, () -> service.outlook(44.35, -79.16));
    }

    /**
     * Verifies how the most likely category is chosen and that missing
     * values leave it unset.
     */
    @Test
    void namesTheMostLikelyCategory() {
        assertEquals("below", SeasonalOutlookService.chances(List.of(10.0, 30.0, 60.0)).mostLikely());
        assertEquals("near", SeasonalOutlookService.chances(List.of(25.0, 45.04, 30.0)).mostLikely());
        assertEquals(45.0, SeasonalOutlookService.chances(List.of(25.0, 45.04, 30.0)).nearPct());
        assertEquals("no clear signal", SeasonalOutlookService.chances(List.of(39.9, 30.0, 30.1)).mostLikely());
        assertNull(SeasonalOutlookService.chances(Arrays.asList(50.0, null, 20.0)).mostLikely());
    }

    /**
     * Makes the mocked client answer: temperature strongly above normal and
     * precipitation without a clear signal.
     *
     * @param run the model run reported by every answer
     */
    private void answerWith(String run) {
        when(client.valueAt(anyString(), anyDouble(), anyDouble(), any(), any())).thenAnswer(call -> {
            String layer = call.getArgument(0);
            String time = call.getArgument(3);
            boolean temperature = layer.contains("AirTemp");
            double value = layer.contains("Above") ? (temperature ? 70 : 30)
                    : layer.contains("Near") ? (temperature ? 20 : 36) : (temperature ? 10 : 34);
            return Optional.of(new PointValue(value, time == null ? run : time, run));
        });
    }
}
