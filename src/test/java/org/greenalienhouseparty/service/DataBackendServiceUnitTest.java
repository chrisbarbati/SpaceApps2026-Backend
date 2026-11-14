package org.greenalienhouseparty.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.greenalienhouseparty.client.DataBackendClient;
import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.CropHistoryResponse;
import org.greenalienhouseparty.dto.DailyPointResponse;
import org.greenalienhouseparty.dto.FieldAreaResponse;
import org.greenalienhouseparty.dto.FieldCoverageResponse;
import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.dto.HealthResponse;
import org.greenalienhouseparty.dto.ReadinessResponse;
import org.greenalienhouseparty.dto.SoilMoistureResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

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
     * Verifies that the readiness status and typed body are returned unchanged.
     */
    @Test
    void delegatesReadinessCheck() {
        ReadinessResponse expected = new ReadinessResponse(
                false, new ReadinessResponse.ReadinessChecks("unavailable", "database"));
        ResponseEntity<ReadinessResponse> response = ResponseEntity.status(503).body(expected);
        when(client.readiness()).thenReturn(response);

        assertSame(response, service.readiness());
        verify(client).readiness();
    }

    /**
     * Verifies that the SMAP layer operation delegates to the client.
     */
    @Test
    void delegatesSoilMoistureRequest() {
        SoilMoistureResponse expected = new SoilMoistureResponse("FeatureCollection", List.of(), null);
        when(client.soilMoisture()).thenReturn(expected);

        assertSame(expected, service.soilMoisture());
        verify(client).soilMoisture();
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

    /**
     * Verifies that field-specific crop history is delegated by field ID.
     */
    @Test
    void delegatesFieldCropHistoryRequest() {
        CropHistoryResponse expected = new CropHistoryResponse(
                "field-1", null, List.of(), List.of(), List.of(), 0, List.of(), "outside coverage");
        when(client.fieldCropHistory("field-1")).thenReturn(expected);

        assertSame(expected, service.fieldCropHistory("field-1"));
        verify(client).fieldCropHistory("field-1");
    }

    /**
     * Verifies that crop history for a submitted polygon is delegated as a
     * typed request.
     */
    @Test
    void delegatesCropHistoryRequest() {
        CropHistoryRequest request = new CropHistoryRequest();
        CropHistoryResponse expected = new CropHistoryResponse(
                null, null, List.of(), List.of(), List.of(), 0, List.of(), "outside coverage");
        when(client.cropHistory(request)).thenReturn(expected);

        assertSame(expected, service.cropHistory(request));
        verify(client).cropHistory(request);
    }
}
