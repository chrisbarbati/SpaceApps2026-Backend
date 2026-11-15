package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.greenalienhouseparty.client.DataBackendException;
import org.greenalienhouseparty.config.RequestIdFilter;
import org.greenalienhouseparty.dto.AgroWeatherResponse;
import org.greenalienhouseparty.dto.CropCalendarResponse;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.DroughtIndicatorsResponse;
import org.greenalienhouseparty.dto.FieldAreaResponse;
import org.greenalienhouseparty.dto.FieldDetailsResponse;
import org.greenalienhouseparty.dto.FieldGreennessResponse;
import org.greenalienhouseparty.dto.FieldSoilResponse;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.SeasonalOutlookResponse;
import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.dto.VegetationHistoryResponse;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for gathering everything known about one field.
 */
class FieldDetailsServiceUnitTest {

    private final DataBackendService dataBackend = mock(DataBackendService.class);

    private final SoilLandscapeService soilLandscape = mock(SoilLandscapeService.class);

    private final SeasonalOutlookService seasonalOutlook = mock(SeasonalOutlookService.class);

    private final FieldDetailsService service = new FieldDetailsService(dataBackend, soilLandscape, seasonalOutlook);

    private final FieldSoilResponse soil = mock(FieldSoilResponse.class);

    private final CropHistoryResponse cropHistory = mock(CropHistoryResponse.class);

    private final SoilLandscapeResponse landscape = mock(SoilLandscapeResponse.class);

    private final SeasonalOutlookResponse outlook = mock(SeasonalOutlookResponse.class);

    /**
     * Stops the service's request threads and clears the logging context.
     */
    @AfterEach
    void tearDown() {
        service.shutdown();
        MDC.clear();
    }

    /**
     * Verifies every default section is returned with an ok status, the
     * location-based sections are looked up at the climate summary's centre,
     * and the optional sections are not fetched.
     */
    @Test
    void returnsEveryDefaultSection() {
        answerDefaults(summaryAt(44.3, -79.1));

        FieldDetailsResponse details = service.details("field-1", null);

        assertEquals("field-1", details.id());
        assertEquals(12.3, details.hectares());
        assertEquals(30.4, details.acres());
        assertEquals(new FieldDetailsResponse.Location(44.3, -79.1, "climate"), details.location());
        assertSame(soil, details.soil());
        assertSame(cropHistory, details.cropHistory());
        assertSame(landscape, details.soilLandscape());
        assertSame(outlook, details.seasonalOutlook());
        assertEquals(List.of("soil", "cropHistory", "climate", "cropCalendar", "agroWeather", "soilLandscape",
                "seasonalOutlook", "greenness", "vegetationHistory", "droughtIndicators"),
                List.copyOf(details.sections().keySet()));
        for (String name : List.of("soil", "cropHistory", "climate", "cropCalendar", "agroWeather", "soilLandscape",
                "seasonalOutlook")) {
            assertEquals(FieldDetailsResponse.Section.OK, details.sections().get(name).status(), name);
        }
        for (String name : List.of("greenness", "vegetationHistory", "droughtIndicators")) {
            assertEquals(FieldDetailsResponse.Section.NOT_REQUESTED, details.sections().get(name).status(), name);
        }
        verify(soilLandscape).landscape(44.3, -79.1);
        verify(seasonalOutlook).outlook(44.3, -79.1);
        verify(dataBackend, never()).fieldVegetationHistory(any(), any());
        verify(dataBackend, never()).fieldDroughtIndicators(any(), any(), any());
        verify(dataBackend, never()).fieldGreenness(any(), any());
    }

    /**
     * Verifies the optional sections are fetched when requested by keyword or
     * with all.
     */
    @Test
    void fetchesOptionalSectionsWhenRequested() {
        answerDefaults(summaryAt(44.3, -79.1));
        var greenness = mock(FieldGreennessResponse.class);
        var vegetation = mock(VegetationHistoryResponse.class);
        var drought = mock(DroughtIndicatorsResponse.class);
        when(dataBackend.fieldGreenness("field-1", null)).thenReturn(greenness);
        when(dataBackend.fieldVegetationHistory("field-1", null)).thenReturn(vegetation);
        when(dataBackend.fieldDroughtIndicators("field-1", null, null)).thenReturn(drought);

        FieldDetailsResponse some = service.details("field-1", " Vegetation ,,drought");
        FieldDetailsResponse all = service.details("field-1", "all");

        assertSame(vegetation, some.vegetationHistory());
        assertSame(drought, some.droughtIndicators());
        assertNull(some.greenness());
        assertEquals(FieldDetailsResponse.Section.NOT_REQUESTED, some.sections().get("greenness").status());
        assertSame(greenness, all.greenness());
        assertSame(vegetation, all.vegetationHistory());
        assertSame(drought, all.droughtIndicators());
    }

    /**
     * Verifies failing sources are reported as unavailable with the reason
     * while the rest of the field is returned, and that the centre falls back
     * to the crop calendar and then to agro-weather.
     */
    @Test
    void reportsFailuresAndFallsBackForTheFieldCentre() {
        answerDefaults(null);
        when(dataBackend.fieldSummary("field-1", null))
                .thenThrow(new DataBackendException(502, "NASA POWER daily weather is unavailable or invalid"));
        when(dataBackend.fieldSoil("field-1")).thenThrow(new IllegalStateException("bug"));
        when(soilLandscape.landscape(anyDouble(), anyDouble()))
                .thenThrow(new ExternalDataException(502, "Agriculture and Agri-Food Canada's soil service is unavailable"));

        FieldDetailsResponse details = service.details("field-1", null);

        assertNull(details.climate());
        assertNull(details.soil());
        assertSame(cropHistory, details.cropHistory());
        assertEquals("The data backend answered HTTP 502: NASA POWER daily weather is unavailable or invalid",
                details.sections().get("climate").detail());
        assertEquals("Unexpected error: IllegalStateException", details.sections().get("soil").detail());
        assertEquals(FieldDetailsResponse.Section.UNAVAILABLE, details.sections().get("soilLandscape").status());
        assertEquals("Agriculture and Agri-Food Canada's soil service is unavailable",
                details.sections().get("soilLandscape").detail());
        assertEquals(new FieldDetailsResponse.Location(44.25, -79.25, "cropCalendar"), details.location());
        assertSame(outlook, details.seasonalOutlook());

        assertEquals("agroWeather", FieldDetailsService.location(null, null, agroWeatherAt(44.4, -79.4)).source());
        assertNull(FieldDetailsService.location(null, null, null));
    }

    /**
     * Verifies that without a field centre the location-based sections are
     * reported as unavailable and not looked up.
     */
    @Test
    void skipsLocationSectionsWithoutACentre() {
        when(dataBackend.fieldArea("field-1")).thenReturn(new FieldAreaResponse("field-1", 12.3, 30.4));
        when(dataBackend.fieldSummary("field-1", null)).thenThrow(new DataBackendException(503, "down"));
        when(dataBackend.fieldCropCalendar("field-1", null, null)).thenThrow(new DataBackendException(503, "down"));
        when(dataBackend.fieldAgroWeather("field-1", null)).thenThrow(new DataBackendException(503, "down"));

        FieldDetailsResponse details = service.details("field-1", null);

        assertNull(details.location());
        assertTrue(details.sections().get("seasonalOutlook").detail().startsWith("The field centre is not known"));
        verify(soilLandscape, never()).landscape(anyDouble(), anyDouble());
        verify(seasonalOutlook, never()).outlook(anyDouble(), anyDouble());
    }

    /**
     * Verifies an unknown field is still reported as the data backend's 404
     * and an unknown include keyword is rejected before any lookup.
     */
    @Test
    void rejectsUnknownFieldsAndIncludes() {
        when(dataBackend.fieldArea("missing")).thenThrow(new DataBackendException(404, "Field not found"));

        DataBackendException missing = assertThrows(DataBackendException.class, () -> service.details("missing", null));
        assertEquals(404, missing.getStatusCode());
        assertThrows(InvalidRequestException.class, () -> service.details("field-1", "imagery"));
        verify(dataBackend, never()).fieldArea("field-1");
        assertEquals(Set.of(), FieldDetailsService.parseInclude(" , "));
        assertEquals(Set.of("greenness", "vegetation", "drought"), FieldDetailsService.parseInclude("ALL"));
    }

    /**
     * Verifies the caller's request ID is in the logging context of every
     * lookup, so it is sent on to the data backend from the request threads.
     */
    @Test
    void carriesTheRequestIdToEveryLookup() {
        answerDefaults(summaryAt(44.3, -79.1));
        AtomicReference<String> seenBySoil = new AtomicReference<>();
        AtomicReference<String> seenByOutlook = new AtomicReference<>();
        when(dataBackend.fieldSoil("field-1")).thenAnswer(call -> {
            seenBySoil.set(MDC.get(RequestIdFilter.MDC_KEY));
            return soil;
        });
        when(seasonalOutlook.outlook(44.3, -79.1)).thenAnswer(call -> {
            seenByOutlook.set(MDC.get(RequestIdFilter.MDC_KEY));
            return outlook;
        });
        MDC.put(RequestIdFilter.MDC_KEY, "trace-7");

        service.details("field-1", null);

        assertEquals("trace-7", seenBySoil.get());
        assertEquals("trace-7", seenByOutlook.get());
    }

    /**
     * Answers every default lookup for field-1.
     *
     * @param summary the climate summary to return, or {@code null}
     */
    private void answerDefaults(FieldSummaryResponse summary) {
        when(dataBackend.fieldArea("field-1")).thenReturn(new FieldAreaResponse("field-1", 12.3, 30.4));
        when(dataBackend.fieldSoil("field-1")).thenReturn(soil);
        when(dataBackend.fieldCropHistory("field-1")).thenReturn(cropHistory);
        when(dataBackend.fieldSummary("field-1", null)).thenReturn(summary);
        when(dataBackend.fieldCropCalendar("field-1", null, null)).thenReturn(new CropCalendarResponse(
                "field-1", new CropCalendarResponse.Location(44.25, -79.25), null, "rainfed", List.of(), null, null));
        when(dataBackend.fieldAgroWeather("field-1", null)).thenReturn(agroWeatherAt(44.4, -79.4));
        when(soilLandscape.landscape(anyDouble(), anyDouble())).thenReturn(landscape);
        when(seasonalOutlook.outlook(anyDouble(), anyDouble())).thenReturn(outlook);
    }

    /**
     * Builds a climate summary whose field centre is the given point.
     *
     * @param lat latitude
     * @param lon longitude
     * @return the summary
     */
    private static FieldSummaryResponse summaryAt(double lat, double lon) {
        return new FieldSummaryResponse("field-1",
                new FieldSummaryResponse.FieldInfo(new FieldSummaryResponse.Centroid(lat, lon), 12.3),
                "daymet", null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Builds an agro-weather response located at the given point.
     *
     * @param lat latitude
     * @param lon longitude
     * @return the response
     */
    private static AgroWeatherResponse agroWeatherAt(double lat, double lon) {
        return new AgroWeatherResponse("field-1", new AgroWeatherResponse.Location(lat, lon, 240.0),
                null, null, null, null, null, null, null, null, null);
    }
}
