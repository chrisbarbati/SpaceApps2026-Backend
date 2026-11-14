package org.greenalienhouseparty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.greenalienhouseparty.config.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests that exercise the public HTTP API through Spring MVC and
 * the data backend client against a local Python API stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final AtomicReference<String> summaryRequestBody = new AtomicReference<>();

    private static final AtomicReference<String> cropHistoryRequestBody = new AtomicReference<>();

    private static final AtomicReference<String> receivedRequestId = new AtomicReference<>();

    private static final AtomicBoolean fieldDataReady = new AtomicBoolean(true);

    private static HttpServer dataBackend;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Resets mutable local-upstream fixture state before each integration test.
     */
    @BeforeEach
    void resetStubState() {
        fieldDataReady.set(true);
        summaryRequestBody.set(null);
        cropHistoryRequestBody.set(null);
        receivedRequestId.set(null);
    }

    /**
     * Starts a local data API stub and points the Spring application at it.
     *
     * @param registry the test property registry
     */
    @DynamicPropertySource
    static void configureDataBackend(DynamicPropertyRegistry registry) {
        try {
            dataBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            dataBackend.createContext("/", BackendApiIntegrationTest::handleDataBackendRequest);
            dataBackend.start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the local data backend stub", e);
        }
        registry.add("data.backend.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort());
    }

    /**
     * Stops the local data API stub after Spring has completed the integration
     * tests.
     */
    @AfterAll
    static void stopDataBackend() {
        if (dataBackend != null) {
            dataBackend.stop(0);
        }
    }

    /**
     * Verifies the public data routes pass through the service/client layers and
     * return the corresponding structured JSON responses.
     *
     * @throws Exception if any response cannot be decoded
     */
    @Test
    void exposesAllDataBackendRoutes() throws Exception {
        HttpResponse<String> health = get("/health", "frontend-trace-42");
        assertEquals(200, health.statusCode());
        assertEquals("ok", json(health).get("status").asString());
        assertEquals("frontend-trace-42", health.headers().firstValue(RequestIdFilter.REQUEST_ID_HEADER).orElseThrow());
        assertEquals("frontend-trace-42", receivedRequestId.get());

        HttpResponse<String> readiness = get("/ready");
        assertEquals(200, readiness.statusCode());
        assertTrue(json(readiness).get("ready").asBoolean());
        fieldDataReady.set(false);
        HttpResponse<String> notReady = get("/ready");
        assertEquals(503, notReady.statusCode());
        assertEquals(false, json(notReady).get("ready").asBoolean());
        assertEquals("unavailable", json(notReady).get("checks").get("fields").asString());
        fieldDataReady.set(true);

        HttpResponse<String> daily = get(
                "/daily-point?lat=44.6&lon=-79.4&start=20240101&end=20240107&parameters=T2M");
        assertEquals(200, daily.statusCode());
        assertEquals(12.5, json(daily).get("properties").get("parameter").get("T2M").get("20240101").asDouble());

        HttpResponse<String> fields = get("/fields?limit=5&cursor=0");
        assertEquals(200, fields.statusCode());
        assertEquals("FeatureCollection", json(fields).get("type").asString());
        assertEquals("field-1", json(fields).get("features").get(0).get("id").asString());

        HttpResponse<String> area = get("/fields/field-1");
        assertEquals(200, area.statusCode());
        assertEquals(12.3, json(area).get("hectares").asDouble());

        HttpResponse<String> fieldCropHistory = get("/fields/field-1/crop-history");
        assertEquals(200, fieldCropHistory.statusCode());
        assertEquals("field-1", json(fieldCropHistory).get("id").asString());
        assertEquals("Soybeans", json(fieldCropHistory).get("years").get(0).get("crop").asString());

        HttpResponse<String> coverage = get("/field-coverage");
        assertEquals(200, coverage.statusCode());
        assertEquals("Orillia", json(coverage).get("region").asString());
        assertTrue(json(coverage).has("fieldCount"));

        String cropRequestBody = "{\"polygon\":{\"type\":\"Polygon\",\"coordinates\":["
                + "[[0,0],[1,0],[1,1],[0,1],[0,0]]]}}";
        HttpResponse<String> cropHistory = post("/crop-history", cropRequestBody);
        assertEquals(200, cropHistory.statusCode());
        assertTrue(!json(cropHistory).has("id"));
        assertTrue(json(cropHistory).get("source").get("name").asString().contains("AAFC"));
        assertEquals("Polygon", JSON.readTree(cropHistoryRequestBody.get()).get("polygon").get("type").asString());

        String requestBody = "{\"polygon\":{\"type\":\"Polygon\",\"coordinates\":["
                + "[[0,0],[1,0],[1,1],[0,1],[0,0]]]},\"period\":{"
                + "\"start\":\"2023-01-01\",\"end\":\"2023-12-31\"}}";
        HttpRequest summaryRequest = HttpRequest.newBuilder(URI.create(url("/field-summary")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> summary = httpClient.send(summaryRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, summary.statusCode());
        assertEquals(4321.5, json(summary).get("climate").get("gdd_annual_median").asDouble());
        assertEquals("Polygon", JSON.readTree(summaryRequestBody.get()).get("polygon").get("type").asString());
    }

    /**
     * Verifies the new data backend routes are included in the generated
     * OpenAPI document.
     *
     * @throws Exception if the OpenAPI response cannot be decoded
     */
    @Test
    void documentsNewDataBackendRoutes() throws Exception {
        JsonNode openApi = JSON.readTree(get("/v3/api-docs").body());
        assertTrue(openApi.get("paths").has("/ready"));
        assertTrue(openApi.get("paths").has("/fields/{field_id}/crop-history"));
        assertTrue(openApi.get("paths").has("/crop-history"));
    }

    /**
     * Verifies unsafe request IDs are replaced and the replacement is
     * propagated to the Python API.
     *
     * @throws Exception if the request fails
     */
    @Test
    void replacesUnsafeRequestIdAndPropagatesGeneratedId() throws Exception {
        HttpResponse<String> response = get("/health", "invalid request id");
        String responseId = response.headers().firstValue(RequestIdFilter.REQUEST_ID_HEADER).orElseThrow();

        assertEquals(200, response.statusCode());
        assertTrue(responseId.matches("[0-9a-f]{32}"));
        assertEquals(responseId, receivedRequestId.get());
    }

    /**
     * Verifies the crop-history routes preserve field-not-found, validation,
     * and provider-failure statuses and details.
     *
     * @throws Exception if an error response cannot be decoded
     */
    @Test
    void preservesCropHistoryErrors() throws Exception {
        HttpResponse<String> missingField = get("/fields/missing/crop-history");
        assertEquals(404, missingField.statusCode());
        assertEquals("Not found", json(missingField).get("detail").asString());

        String invalidRequest = "{\"polygon\":{\"type\":\"Polygon\",\"coordinates\":["
                + "[[0,0],[1,0],[1,1],[0,1],[0,0]]]},\"extra\":1}";
        HttpResponse<String> invalid = post("/crop-history", invalidRequest);
        assertEquals(422, invalid.statusCode());
        assertEquals("Extra inputs are not permitted", json(invalid).get("detail").asString());

        HttpResponse<String> providerFailure = get("/fields/provider-error/crop-history");
        assertEquals(502, providerFailure.statusCode());
        assertEquals("AAFC Annual Crop Inventory is unavailable", json(providerFailure).get("detail").asString());
    }

    /**
     * Verifies that upstream HTTP failures retain their status and detail in
     * the public Java API response.
     *
     * @throws Exception if the error response cannot be decoded
     */
    @Test
    void propagatesUpstreamErrorStatusAndDetail() throws Exception {
        HttpResponse<String> response = get("/fields/missing");

        assertEquals(404, response.statusCode());
        assertEquals("Field not found", json(response).get("detail").asString());
    }

    /**
     * Verifies that missing required query parameters use the data API's 422
     * validation status.
     *
     * @throws Exception if the error response cannot be decoded
     */
    @Test
    void mapsMissingQueryParametersToUnprocessableEntity() throws Exception {
        HttpResponse<String> response = get("/daily-point");

        assertEquals(422, response.statusCode());
        assertTrue(json(response).has("detail"));
    }

    /**
     * Responds to the Spring application's calls with representative Python
     * API payloads.
     *
     * @param exchange the incoming upstream request
     * @throws IOException if the request or response stream cannot be accessed
     */
    private static void handleDataBackendRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        receivedRequestId.set(exchange.getRequestHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
        int status = 200;
        String response;
        if (path.equals("/health")) {
            response = "{\"status\":\"ok\"}";
        } else if (path.equals("/ready")) {
            boolean ready = fieldDataReady.get();
            status = ready ? 200 : 503;
            response = "{\"ready\":" + ready + ",\"checks\":{\"fields\":\""
                    + (ready ? "ok" : "unavailable") + "\",\"source\":\"database\"}}";
        } else if (path.equals("/daily-point")) {
            response = "{\"type\":\"Feature\",\"geometry\":null,"
                    + "\"properties\":{\"parameter\":{\"T2M\":{\"20240101\":12.5}}}}";
        } else if (path.equals("/fields")) {
            response = "{\"type\":\"FeatureCollection\",\"features\":["
                    + "{\"type\":\"Feature\",\"id\":\"field-1\","
                    + "\"properties\":{\"id\":\"field-1\",\"score\":88},"
                    + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[]},"
                    + "\"bbox\":[0,0,1,1]}],\"numberReturned\":1,"
                    + "\"hasMore\":false,\"nextCursor\":null}";
        } else if (path.equals("/fields/field-1")) {
            response = "{\"id\":\"field-1\",\"hectares\":12.3,\"acres\":30.4}";
        } else if (path.equals("/fields/field-1/crop-history")) {
            response = cropHistoryResponse("field-1");
        } else if (path.equals("/fields/provider-error/crop-history")) {
            status = 502;
            response = "{\"detail\":\"AAFC Annual Crop Inventory is unavailable\"}";
        } else if (path.equals("/crop-history")) {
            cropHistoryRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (cropHistoryRequestBody.get().contains("\"extra\"")) {
                status = 422;
                response = "{\"detail\":\"Extra inputs are not permitted\"}";
            } else {
                response = cropHistoryResponse(null);
            }
        } else if (path.equals("/fields/missing")) {
            status = 404;
            response = "{\"detail\":\"Field not found\"}";
        } else if (path.equals("/field-coverage")) {
            response = "{\"region\":\"Orillia\",\"status\":\"sample\","
                    + "\"fields\":300,\"fieldCount\":300}";
        } else if (path.equals("/field-summary")) {
            summaryRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            response = "{\"field\":{\"centroid\":{\"lat\":44.6,\"lon\":-79.4},\"areaHa\":1.2},"
                    + "\"weatherCell\":{\"lat\":44.6,\"lon\":-79.4,\"gridDeg\":0.05},"
                    + "\"period\":{\"start\":\"20230101\",\"end\":\"20231231\"},"
                    + "\"season\":{\"months\":[5,6,7,8,9],\"crosses_year\":false,"
                    + "\"base_temp_c\":10,\"label\":\"May-Sep\"},"
                    + "\"climate\":{\"gdd_annual_median\":4321.5},"
                    + "\"water\":{\"season_et0_mm_median\":125},\"terrain\":null,"
                    + "\"layers\":{\"weather\":{\"status\":\"ok\"},"
                    + "\"terrain\":{\"status\":\"error\"}},\"source\":[]}";
        } else {
            status = 404;
            response = "{\"detail\":\"Not found\"}";
        }
        respond(exchange, status, response);
    }

    /**
     * Sends a JSON response from the local data API stub.
     *
     * @param exchange the incoming request
     * @param status   the HTTP response status
     * @param body     the response body
     * @throws IOException if the response cannot be written
     */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    /**
     * Creates representative crop history, optionally with a stored field ID.
     *
     * @param id the field ID, or {@code null} for a caller-provided outline
     * @return a crop history JSON body
     */
    private static String cropHistoryResponse(String id) {
        String idPart = id == null ? "" : "\"id\":\"" + id + "\",";
        return "{" + idPart
                + "\"source\":{\"name\":\"AAFC Annual Crop Inventory\",\"url\":\"https://example.test/aci\","
                + "\"license\":\"Open Government Licence - Canada\",\"resolutionM\":30},"
                + "\"years\":[{\"year\":2025,\"crop\":\"Soybeans\",\"code\":158,"
                + "\"isCrop\":true,\"share\":0.967,\"classes\":[{\"code\":158,"
                + "\"name\":\"Soybeans\",\"isCrop\":true,\"share\":0.967}]}],"
                + "\"rotation\":[\"Soybeans\"],\"distinctCrops\":[\"Soybeans\"],"
                + "\"cropYears\":1,\"unavailableYears\":[],\"note\":\"Satellite classification\"}";
    }

    /**
     * Builds an absolute URL for a Java backend route.
     *
     * @param path the route path and optional query string
     * @return the absolute route URL
     */
    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /**
     * Sends a GET request to the embedded Java application.
     *
     * @param path the route path and optional query string
     * @return the HTTP response
     * @throws IOException          if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path))).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a GET request with a caller-supplied request ID.
     *
     * @param path      the route path and optional query string
     * @param requestId request ID header value
     * @return the HTTP response
     * @throws IOException          if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private HttpResponse<String> get(String path, String requestId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path)))
                .header(RequestIdFilter.REQUEST_ID_HEADER, requestId)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a JSON POST request to the embedded Java application.
     *
     * @param path the route path
     * @param body JSON request body
     * @return the HTTP response
     * @throws IOException          if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Parses an HTTP response body as JSON for assertions.
     *
     * @param response the HTTP response
     * @return its JSON body
     * @throws IOException if the body is not valid JSON
     */
    private JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }
}
