package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.dto.DailyPointResponse;
import org.greenalienhouseparty.dto.FieldAreaResponse;
import org.greenalienhouseparty.dto.FieldCoverageResponse;
import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.dto.HealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the service facade, verifying that each operation delegates
 * to the Python data backend client and returns its typed result unchanged.
 */
@ExtendWith(MockitoExtension.class)
class DataBackendServiceUnitTest {

    @Mock
    private DataBackendClient client;

    private DataBackendService service;

    /**
     * Creates the service with the mocked data backend client before each test.
     */
    @BeforeEach
    void setUp() {
        service = new DataBackendService(client);
    }

    /**
     * Verifies that the health operation delegates to the client.
     */
    @Test
    void delegatesHealthCheck() {
        HealthResponse expected = new HealthResponse("ok");
        when(client.health()).thenReturn(expected);

        assertSame(expected, service.health());
        verify(client).health();
    }

    /**
     * Verifies that daily-point query values are delegated and the typed result
     * is returned unchanged.
     */
    @Test
    void delegatesDailyPointRequest() {
        DailyPointResponse expected = new DailyPointResponse();
        when(client.dailyPoint(44.6, -79.4, "20240101", "20240107", "T2M")).thenReturn(expected);

        assertSame(expected, service.dailyPoint(44.6, -79.4, "20240101", "20240107", "T2M"));
        verify(client).dailyPoint(44.6, -79.4, "20240101", "20240107", "T2M");
    }

    /**
     * Verifies that field list pagination values are delegated.
     */
    @Test
    void delegatesFieldListRequest() {
        FieldsResponse expected = new FieldsResponse("FeatureCollection", List.of(), 0, false, null, null);
        when(client.listFields("0,0,1,1", 10, 2)).thenReturn(expected);

        assertSame(expected, service.listFields("0,0,1,1", 10, 2));
        verify(client).listFields("0,0,1,1", 10, 2);
    }

    /**
     * Verifies that a selected field's estimated area is delegated.
     */
    @Test
    void delegatesFieldAreaRequest() {
        FieldAreaResponse expected = new FieldAreaResponse("field-1", 12.3, 30.4);
        when(client.fieldArea("field-1")).thenReturn(expected);

        assertSame(expected, service.fieldArea("field-1"));
        verify(client).fieldArea("field-1");
    }

    /**
     * Verifies that coverage metadata is delegated.
     */
    @Test
    void delegatesFieldCoverageRequest() {
        FieldCoverageResponse expected = new FieldCoverageResponse();
        when(client.fieldCoverage()).thenReturn(expected);

        assertSame(expected, service.fieldCoverage());
        verify(client).fieldCoverage();
    }

    /**
     * Verifies that field summary requests are delegated as typed models.
     */
    @Test
    void delegatesFieldSummaryRequest() {
        FieldSummaryRequest request = new FieldSummaryRequest();
        FieldSummaryResponse expected = new FieldSummaryResponse(
                null, null, null, null, null, null, null, null, null, null, null, null);
        when(client.fieldSummary(request)).thenReturn(expected);

        assertSame(expected, service.fieldSummary(request));
        verify(client).fieldSummary(request);
    }
}
