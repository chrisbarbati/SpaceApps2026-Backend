package org.greenalienhouseparty.landscape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.greenalienhouseparty.dto.SoilLandscapeResponse;
import org.greenalienhouseparty.dto.SoilLandscapeResponse.Indicator;
import org.greenalienhouseparty.exception.ExternalDataException;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for reading land capability, drainage and soil indicators from
 * AAFC feature attributes.
 */
class SoilLandscapeServiceUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CAPABILITY = "{\"CLASS_A\":\"2\",\"PERCENT_A\":\" \",\"SUBCLAS_A1\":\"S\","
            + "\"SUBCLAS_A2\":\" \",\"CLASS_B\":\" \",\"MAJOR1\":2}";

    private static final String DRAINAGE = "{\"SLC_V31_22_ID\":\"55201131\",\"DRAINAGE_CODE\":\"W\",\"DRAINAGE_PCNT\":69}";

    private static final String EROSION = "{\"SOIL_LANDSCAPE_ID\":552011,\"ERI_1981_VAL\":14.464,\"ERI_1981_CLASS\":3,"
            + "\"ERI_1981_CLASS_EN\":\"Moderate\",\"ERI_1981_CLASS_FR\":\"Moyen\",\"ERI_2021_VAL\":12.467,"
            + "\"ERI_2021_CLASS\":3,\"ERI_2021_CLASS_EN\":\"Moderate\",\"ERI_81_21_CHG_VAL\":-1.997,"
            + "\"ERI_81_21_CHG_CLASS\":-1,\"ERI_81_21_CHG_CLASS_EN\":\"Decrease\",\"Shape__Area\":123.4}";

    private static final String CARBON = "{\"SOC_2016_VAL\":-78.604,\"SOC_2016_CLASS_E\":\"Moderate decrease\","
            + "\"SOC_2021_VAL\":-72.708,\"SOC_2021_CLASS\":4,\"SOC_2021_CLASS_E\":\"Moderate decrease\"}";

    private static final String NITROGEN = "{\"RSN_2021_VAL\":32.54,\"RSN_2021_CLASS_EN\":\"          High\","
            + "\"RSN_81_21_CHG_VAL\":2.82,\"RSN_81_21_CHG_CLASS_EN\":\"   One class increased risk\"}";

    private final ArcGisFeatureClient client = mock(ArcGisFeatureClient.class);

    private final SoilLandscapeService service = new SoilLandscapeService(client, Duration.ofHours(24));

    /**
     * Stops the service's request threads.
     */
    @AfterEach
    void stop() {
        service.shutdown();
    }

    /**
     * Verifies the capability class, its meaning and its limitations are read,
     * and that blank classes and unclassified drainage give nothing.
     */
    @Test
    void readsLandCapabilityAndDrainage() {
        var capability = SoilLandscapeService.landCapability(attributes(CAPABILITY));
        assertEquals("2", capability.classCode());
        assertTrue(capability.description().startsWith("Moderate limitations"));
        assertEquals(List.of(new SoilLandscapeResponse.Limitation("S", "a combination of adverse soil characteristics")),
                capability.limitations());
        assertNull(SoilLandscapeService.landCapability(attributes("{\"CLASS_A\":\" \"}")));
        assertTrue(SoilLandscapeService.landCapability(attributes("{\"CLASS_A\":\"8\"}")).description()
                .startsWith("Not rated for agriculture"));

        var drainage = SoilLandscapeService.drainage(attributes(DRAINAGE));
        assertEquals("Well and moderately well drained", drainage.description());
        assertEquals(69, drainage.sharePct());
        assertNull(SoilLandscapeService.drainage(attributes("{\"DRAINAGE_CODE\":\"RK\",\"DRAINAGE_PCNT\":100}")));
    }

    /**
     * Verifies indicator history, newest value and change are read from the
     * layers' field naming, including the carbon layer's shorter class suffix
     * and the nitrogen layer's padded labels.
     */
    @Test
    void readsIndicatorHistoryAndChange() {
        Indicator erosion = SoilLandscapeService.indicator(SoilLandscapeService.INDICATORS.get(0), attributes(EROSION));
        assertEquals("erosionRisk", erosion.key());
        assertEquals(2021, erosion.latestYear());
        assertEquals(12.47, erosion.value());
        assertEquals("Moderate", erosion.rating());
        assertEquals(List.of(1981, 2021), erosion.history().stream().map(SoilLandscapeResponse.YearValue::year).toList());
        assertEquals(new SoilLandscapeResponse.Change(1981, 2021, -2.0, "Decrease"), erosion.change());

        Indicator carbon = SoilLandscapeService.indicator(SoilLandscapeService.INDICATORS.get(2), attributes(CARBON));
        assertEquals(-72.71, carbon.value());
        assertEquals("Moderate decrease", carbon.rating());
        assertNull(carbon.change());

        Indicator nitrogen = SoilLandscapeService.indicator(SoilLandscapeService.INDICATORS.get(3), attributes(NITROGEN));
        assertEquals("High", nitrogen.rating());
        assertEquals("One class increased risk", nitrogen.change().rating());

        assertNull(SoilLandscapeService.indicator(SoilLandscapeService.INDICATORS.get(0), attributes("{\"OBJECTID\":1}")));
    }

    /**
     * Verifies all six layers are queried once, the answer is cached, and a
     * layer with nothing at the point is simply left out.
     */
    @Test
    void combinesLayersAndCaches() {
        when(client.attributesAt(anyString(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        when(client.attributesAt(eq(SoilLandscapeService.CAPABILITY_SERVICE), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(attributes(CAPABILITY)));
        when(client.attributesAt(eq("Risk_soil_erosion_integrated_2021"), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(attributes(EROSION)));

        SoilLandscapeResponse response = service.landscape(44.3457, -79.1569);
        service.landscape(44.3457, -79.1569);

        assertEquals("Canada", response.coverage());
        assertEquals("2", response.landCapability().classCode());
        assertNull(response.drainage());
        assertEquals(List.of("erosionRisk"), response.indicators().stream().map(Indicator::key).toList());
        assertEquals(6, response.source().datasets().size());
        verify(client, times(6)).attributesAt(anyString(), anyDouble(), anyDouble());
    }

    /**
     * Verifies a point outside Canada returns no values with an explanation,
     * bad coordinates are rejected before any request, and an outage is passed on.
     */
    @Test
    void handlesNoCoverageBadCoordinatesAndOutages() {
        when(client.attributesAt(anyString(), anyDouble(), anyDouble())).thenReturn(Optional.empty());
        SoilLandscapeResponse outside = service.landscape(40.45, -88.95);
        assertNull(outside.coverage());
        assertTrue(outside.indicators().isEmpty());
        assertTrue(outside.note().startsWith("None of these layers covers this location"));

        var fresh = mock(ArcGisFeatureClient.class);
        var other = new SoilLandscapeService(fresh, Duration.ofHours(24));
        try {
            assertThrows(InvalidRequestException.class, () -> other.landscape(-91, 0));
            verifyNoInteractions(fresh);
            when(fresh.attributesAt(anyString(), anyDouble(), anyDouble()))
                    .thenThrow(new ExternalDataException(502, "Agriculture and Agri-Food Canada's soil service is unavailable"));
            assertThrows(ExternalDataException.class, () -> other.landscape(44.3, -79.1));
        } finally {
            other.shutdown();
        }
    }

    /**
     * Parses a JSON object into feature attributes.
     *
     * @param json attributes as JSON
     * @return attributes by field name
     */
    private static Map<String, JsonNode> attributes(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, JsonNode>>() {
        });
    }
}
