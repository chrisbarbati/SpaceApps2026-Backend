package org.greenalienhouseparty.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.greenalienhouseparty.config.RequestIdFilter;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for the Python data backend.
 *
 * <p>Every data endpoint exposed by this application is backed by a call to the
 * Python service. Responses are deserialized into typed entities here so that
 * business logic can be layered on top of the models in the service layer rather
 * than operating on raw JSON.</p>
 */
@Component
public class DataBackendClient {

    private static final Logger log = LoggerFactory.getLogger(DataBackendClient.class);

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    /**
     * Creates the client.
     *
     * @param objectMapper the configured JSON mapper, used to parse error bodies
     * @param baseUrl     the Python backend base URL (see {@code data.backend.url})
     */
    public DataBackendClient(
             ObjectMapper objectMapper,
            @Value("${data.backend.url:http://localhost:8000}") String baseUrl) {
        log.trace("Entering DataBackendClient constructor");
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIdFilter.REQUEST_ID_HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
        this.objectMapper = objectMapper;
        log.debug("Initialized data backend HTTP client");
        log.trace("Exiting DataBackendClient constructor");
    }

    /**
     * Calls the data backend liveness check.
     *
     * @return the health response
     */
    public HealthResponse health() {
        log.trace("Entering health()");
        log.debug("Calling Python data backend GET /health");
        try {
            return execute(() -> restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(HealthResponse.class));
        } finally {
            log.trace("Exiting health()");
        }
    }

    /**
     * Calls the readiness endpoint while preserving its 200 or 503 status and
     * typed readiness body.
     *
     * @return upstream readiness status and response body
     */
    public ResponseEntity<ReadinessResponse> readiness() {
        log.trace("Entering readiness()");
        log.debug("Calling Python data backend GET /ready");
        try {
            return execute(() -> restClient.get().uri("/ready").exchange((request, response) -> {
                int status = response.getStatusCode().value();
                String body;
                try {
                    body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.error("Unable to read the Python readiness response body");
                    throw new DataBackendException(HttpStatus.BAD_GATEWAY.value(), "Invalid readiness response");
                }
                if (status != HttpStatus.OK.value() && status != HttpStatus.SERVICE_UNAVAILABLE.value()) {
                    throw new DataBackendException(status, parseDetail(body));
                }
                try {
                    ReadinessResponse readiness = objectMapper.readValue(body, ReadinessResponse.class);
                    return ResponseEntity.status(response.getStatusCode()).body(readiness);
                } catch (Exception e) {
                    log.error("Unable to deserialize the Python readiness response: {}",
                            e.getClass().getSimpleName());
                    throw new DataBackendException(HttpStatus.BAD_GATEWAY.value(), "Invalid readiness response");
                }
            }));
        } finally {
            log.trace("Exiting readiness()");
        }
    }

    /**
     * Retrieves the cached or newly acquired NASA SMAP GeoJSON layer.
     *
     * @return native-grid soil-moisture cells and SMAP metadata
     */
    public SoilMoistureResponse soilMoisture() {
        log.trace("Entering soilMoisture()");
        log.debug("Calling Python data backend GET /soil-moisture");
        try {
            return execute(() -> restClient.get()
                    .uri("/soil-moisture")
                    .retrieve()
                    .body(SoilMoistureResponse.class));
        } finally {
            log.trace("Exiting soilMoisture()");
        }
    }

    /**
     * Calls the NASA POWER daily point endpoint.
     *
     * @param lat        the latitude to sample
     * @param lon        the longitude to sample
     * @param start      the inclusive start date, as {@code YYYYMMDD}
     * @param end        the inclusive end date, as {@code YYYYMMDD}
     * @param parameters the comma-separated POWER parameters
     * @return the daily point response
     */
    public DailyPointResponse dailyPoint(double lat, double lon, String start, String end, String parameters) {
        log.trace("Entering dailyPoint()");
        log.debug("Calling Python data backend GET /daily-point");
        try {
            return execute(() -> restClient.get()
                    .uri(builder -> builder.path("/daily-point")
                            .queryParam("lat", lat)
                            .queryParam("lon", lon)
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .queryParam("parameters", parameters)
                            .build())
                    .retrieve()
                    .body(DailyPointResponse.class));
        } finally {
            log.trace("Exiting dailyPoint()");
        }
    }

    /**
     * Calls the paginated field list endpoint.
     *
     * @param bbox   the optional {@code west,south,east,north} bounding box
     * @param limit  the maximum number of features per page
     * @param cursor the cursor from the previous page
     * @return a page of field outlines
     */
    public FieldsResponse listFields(String bbox, int limit, int cursor) {
        log.trace("Entering listFields()");
        log.debug("Calling Python data backend GET /fields");
        try {
            return execute(() -> restClient.get()
                    .uri(builder -> {
                        UriBuilder b = builder.path("/fields")
                                .queryParam("limit", limit)
                                .queryParam("cursor", cursor);
                        if (bbox != null) {
                            b = b.queryParam("bbox", bbox);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(FieldsResponse.class));
        } finally {
            log.trace("Exiting listFields()");
        }
    }

    /**
     * Calls the selected-field area endpoint.
     *
     * @param fieldId the field identifier
     * @return the estimated hectares and acres
     */
    public FieldAreaResponse fieldArea(String fieldId) {
        log.trace("Entering fieldArea()");
        log.debug("Calling Python data backend GET /fields/{field_id}");
        try {
            return execute(() -> restClient.get()
                    .uri(builder -> builder.path("/fields/{id}").build(fieldId))
                    .retrieve()
                    .body(FieldAreaResponse.class));
        } finally {
            log.trace("Exiting fieldArea()");
        }
    }

    /**
     * Calls the field coverage endpoint.
     *
     * @return the loaded field coverage and build status
     */
    public FieldCoverageResponse fieldCoverage() {
        log.trace("Entering fieldCoverage()");
        log.debug("Calling Python data backend GET /field-coverage");
        try {
            return execute(() -> restClient.get()
                    .uri("/field-coverage")
                    .retrieve()
                    .body(FieldCoverageResponse.class));
        } finally {
            log.trace("Exiting fieldCoverage()");
        }
    }

    /**
     * Calls the field environmental summary endpoint.
     *
     * @param request the field summary request
     * @return the environmental summary
     */
    public FieldSummaryResponse fieldSummary(FieldSummaryRequest request) {
        log.trace("Entering fieldSummary()");
        log.debug("Calling Python data backend POST /field-summary");
        try {
            return execute(() -> restClient.post()
                    .uri("/field-summary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(FieldSummaryResponse.class));
        } finally {
            log.trace("Exiting fieldSummary()");
        }
    }

    /**
     * Calls the field-specific crop history endpoint.
     *
     * @param fieldId the selected field identifier
     * @return year-by-year crop classifications for the selected field
     */
    public CropHistoryResponse fieldCropHistory(String fieldId) {
        log.trace("Entering fieldCropHistory()");
        log.debug("Calling Python data backend GET /fields/{field_id}/crop-history");
        try {
            return execute(() -> restClient.get()
                    .uri(builder -> builder.path("/fields/{id}/crop-history").build(fieldId))
                    .retrieve()
                    .body(CropHistoryResponse.class));
        } finally {
            log.trace("Exiting fieldCropHistory()");
        }
    }

    /**
     * Calls the crop history endpoint for a user-provided field outline.
     *
     * @param request the polygon request
     * @return year-by-year crop classifications for the outline
     */
    public CropHistoryResponse cropHistory(CropHistoryRequest request) {
        log.trace("Entering cropHistory()");
        log.debug("Calling Python data backend POST /crop-history");
        try {
            return execute(() -> restClient.post()
                    .uri("/crop-history")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(CropHistoryResponse.class));
        } finally {
            log.trace("Exiting cropHistory()");
        }
    }

    /**
     * Runs a client call and maps transport and upstream errors to a uniform
     * {@link DataBackendException}.
     *
     * @param call the client call to run
     * @param <T>  the response type
     * @return the deserialized response
     */
    private <T> T execute(Supplier<T> call) {
        log.trace("Entering execute()");
        try {
            T result = call.get();
            log.debug("Python data backend request completed successfully");
            return result;
        } catch (RestClientResponseException e) {
            log.error("Python data backend returned HTTP {}", e.getStatusCode().value());
            throw new DataBackendException(e.getStatusCode().value(), parseDetail(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            log.error("Python data backend request failed: {}", e.getClass().getSimpleName());
            throw new DataBackendException(
                    HttpStatus.BAD_GATEWAY.value(),
                    "Data backend unavailable: " + e.getMessage());
        } finally {
            log.trace("Exiting execute()");
        }
    }

    /**
     * Parses an upstream error body and extracts its {@code detail} value.
     *
     * @param body the raw error body
     * @return the {@code detail} value when present, the whole body otherwise,
     * or {@code null} when the body is empty or not JSON
     */
    private JsonNode parseDetail(String body) {
        log.trace("Entering parseDetail()");
        try {
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.isObject() && node.has("detail")) {
                return node.get("detail");
            }
            return node;
        } catch (Exception e) {
            log.error("Failed to parse the data backend error response: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            log.trace("Exiting parseDetail()");
        }
    }
}
