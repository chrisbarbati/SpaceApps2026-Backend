package org.greenalienhouseparty.client;

import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.greenalienhouseparty.config.RequestIdFilter;
import org.greenalienhouseparty.dto.CropHistoryRequest;
import org.greenalienhouseparty.dto.FieldSummaryRequest;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.SoilMoistureResponse;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies typed deserialization and error mapping for every Python data API
 * endpoint using a local HTTP test server.
 */
class DataBackendClientIntegrationTest {

    private static final String POLYGON = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Exercises every upstream endpoint and verifies that JSON becomes typed
     * Java models rather than remaining an opaque response body.
     *
     * @throws Exception if the local HTTP server or client fails
     */
    @Test
    void deserializesAllDataEndpoints() throws Exception {
        AtomicReference<String> sentSummaryRequest = new AtomicReference<>();
        AtomicReference<String> sentCropHistoryRequest = new AtomicReference<>();
        AtomicReference<String> receivedRequestId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            receivedRequestId.set(exchange.getRequestHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER));
            int status = 200;
            String response;
            if (path.equals("/health")) {
                response = "{\"status\":\"ok\"}";
            } else if (path.equals("/ready")) {
                response = "{\"ready\":true,\"checks\":{\"fields\":\"ok\",\"source\":\"database\"}}";
            } else if (path.equals("/daily-point")) {
                response = "{\"type\":\"Feature\",\"geometry\":null,"
                        + "\"properties\":{\"parameter\":{\"T2M\":{\"20240101\":12.5}}},"
                        + "\"header\":{\"title\":\"NASA POWER\"}}";
            } else if (path.equals("/soil-moisture")) {
                response = soilMoistureResponse();
            } else if (path.equals("/fields")) {
                response = "{\"type\":\"FeatureCollection\",\"features\":["
                        + "{\"type\":\"Feature\",\"id\":\"field-1\","
                        + "\"properties\":{\"id\":\"field-1\",\"score\":88},"
                        + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[]},"
                        + "\"bbox\":[0,0,1,1]}],\"numberReturned\":1,"
                        + "\"hasMore\":true,\"nextCursor\":42,\"queryBbox\":[0,0,1,1]}";
            } else if (path.equals("/fields/field-1")) {
                response = "{\"id\":\"field-1\",\"hectares\":12.3,\"acres\":30.4}";
            } else if (path.equals("/fields/field-1/crop-history")) {
                response = cropHistoryResponse("field-1");
            } else if (path.equals("/crop-history")) {
                try {
                    sentCropHistoryRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                response = cropHistoryResponse(null);
            } else if (path.equals("/field-coverage")) {
                response = "{\"region\":\"Ontario\",\"status\":\"complete\","
                        + "\"fields\":123,\"sourceUrl\":\"https://example.test/source\","
                        + "\"completedPartitions\":7}";
            } else if (path.equals("/field-summary")) {
                try {
                    sentSummaryRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                response = summaryResponse();
            } else {
                status = 404;
                response = "{}";
            }
            respond(exchange, status, response);
        });
        server.start();

        try {
            DataBackendClient client = client(server);
            MDC.put(RequestIdFilter.MDC_KEY, "client-test-42");
            try {
                assertEquals("ok", client.health().status());
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
            assertEquals("client-test-42", receivedRequestId.get());

            var readiness = client.readiness();
            assertEquals(200, readiness.getStatusCode().value());
            assertTrue(readiness.getBody().ready());

            var daily = client.dailyPoint(44.6, -79.4, "20240101", "20240107", "T2M");
            assertEquals(12.5, daily.getProperties().getParameter().get("T2M").get("20240101").asDouble());
            assertEquals("NASA POWER", daily.getExtra().get("header").get("title").asString());

            SoilMoistureResponse soilMoisture = client.soilMoisture();
            assertEquals("FeatureCollection", soilMoisture.type());
            assertEquals(9, soilMoisture.metadata().spatialResolutionKm());
            assertEquals("m3/m3", soilMoisture.metadata().variables().get("sm_surface").units());
            assertEquals(1, soilMoisture.features().getFirst().properties().getRow());
            assertTrue(soilMoisture.features().getFirst().properties().getMeasurements().get("sm_surface").isNull());
            assertEquals(0.4, soilMoisture.features().getFirst().properties().getMeasurements()
                    .get("sm_rootzone").asDouble());
            assertEquals("SPL4SMLM", soilMoisture.metadata().soilConstants().product());

            var fields = client.listFields("0,0,1,1", 20, 0);
            assertEquals(1, fields.numberReturned());
            assertTrue(fields.hasMore());
            assertEquals(42, fields.nextCursor());
            assertEquals(88, fields.features().getFirst().properties().getScore());
            assertEquals("Polygon", fields.features().getFirst().geometry().get("type").asString());

            assertEquals(12.3, client.fieldArea("field-1").hectares());

            var fieldCrops = client.fieldCropHistory("field-1");
            assertEquals("field-1", fieldCrops.id());
            assertEquals("Soybeans", fieldCrops.years().getFirst().crop());
            assertEquals("AAFC Annual Crop Inventory", fieldCrops.source().name());

            var coverage = client.fieldCoverage();
            assertEquals("Ontario", coverage.getRegion());
            assertEquals("https://example.test/source", coverage.getSourceUrl());
            assertEquals(7, coverage.getExtra().get("completedPartitions").asInt());

            FieldSummaryRequest request = new FieldSummaryRequest();
            request.setPolygon(JSON.readTree(POLYGON));
            request.setPeriod(new FieldSummaryRequest.PeriodRequest("2023-01-01", "2023-12-31"));
            request.setSeason(new FieldSummaryRequest.SeasonRequest(List.of(5, 6, 7, 8, 9)));
            request.setWeatherGridDeg(0.05);
            FieldSummaryResponse summary = client.fieldSummary(request);
            assertEquals(4321.5, summary.climate().gddAnnualMedian());
            assertEquals(125.0, summary.water().seasonEt0MmMedian());
            assertEquals("ok", summary.layers().weather().status());
            assertNull(summary.terrain());
            assertEquals("NASA POWER", summary.source().getFirst().getSource());
            assertEquals(50, summary.source().getFirst().getExtra().get("spatial_resolution_km").asInt());

            JsonNode sent = JSON.readTree(sentSummaryRequest.get());
            assertEquals("Polygon", sent.get("polygon").get("type").asString());
            assertEquals("2023-01-01", sent.get("period").get("start").asString());
            assertEquals(0.05, sent.get("weatherGridDeg").asDouble());

            CropHistoryRequest cropRequest = new CropHistoryRequest();
            cropRequest.setPolygon(JSON.readTree(POLYGON));
            var cropHistory = client.cropHistory(cropRequest);
            assertNull(cropHistory.id());
            assertEquals("Soybeans", cropHistory.rotation().getFirst());
            JsonNode sentCrop = JSON.readTree(sentCropHistoryRequest.get());
            assertEquals("Polygon", sentCrop.get("polygon").get("type").asString());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Confirms upstream error status and structured detail are preserved.
     *
     * @throws Exception if the local HTTP server cannot be started
     */
    @Test
    void preservesUpstreamStatusAndDetail() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 404, "{\"detail\":\"Field not found\"}"));
        server.start();
        try {
            DataBackendException exception = assertThrows(
                    DataBackendException.class, () -> client(server).fieldArea("missing"));
            assertEquals(404, exception.getStatusCode());
            assertNotNull(exception.getDetail());
            assertEquals("\"Field not found\"", exception.getDetail().toString());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Confirms a not-ready 503 is returned as a typed readiness result instead
     * of being converted to a generic error body.
     *
     * @throws Exception if the local HTTP server cannot be started
     */
    @Test
    void preservesReadiness503BodyAndStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> respond(exchange, 503,
                "{\"ready\":false,\"checks\":{\"fields\":\"unavailable\",\"source\":\"database\"}}"));
        server.start();
        try {
            var response = client(server).readiness();
            assertEquals(503, response.getStatusCode().value());
            assertEquals(false, response.getBody().ready());
            assertEquals("unavailable", response.getBody().checks().fields());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Confirms SMAP provider errors preserve their upstream HTTP status and
     * structured detail.
     *
     * @throws Exception if the local HTTP server cannot be started
     */
    @Test
    void preservesSoilMoistureProviderError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/soil-moisture", exchange -> respond(exchange, 502,
                "{\"detail\":\"NASA SMAP request failed; retry later\"}"));
        server.start();
        try {
            DataBackendException exception = assertThrows(
                    DataBackendException.class, () -> client(server).soilMoisture());
            assertEquals(502, exception.getStatusCode());
            assertEquals("\"NASA SMAP request failed; retry later\"", exception.getDetail().toString());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Creates a client pointed at the given local HTTP server.
     *
     * @param server the local upstream server
     * @return a client configured for that server
     */
    private DataBackendClient client(HttpServer server) {
        return new DataBackendClient(JSON, "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
    }

    /**
     * Returns a representative field summary with all response blocks.
     *
     * @return JSON for a Python field summary response
     */
    private static String summaryResponse() {
        return "{\"field\":{\"centroid\":{\"lat\":44.6,\"lon\":-79.4},\"areaHa\":1.2},"
                + "\"weatherCell\":{\"lat\":44.6,\"lon\":-79.4,\"gridDeg\":0.05},"
                + "\"period\":{\"start\":\"20230101\",\"end\":\"20231231\"},"
                + "\"season\":{\"months\":[5,6,7,8,9],\"crosses_year\":false,"
                + "\"base_temp_c\":10,\"label\":\"May-Sep\"},"
                + "\"climate\":{\"gdd_annual_median\":4321.5,\"precip_trend_mm_per_year\":2.1},"
                + "\"water\":{\"season_et0_mm_median\":125.0},"
                + "\"frost\":{\"last_spring_frost\":\"04-20\",\"first_fall_frost\":\"10-10\","
                + "\"last_spring_frost_doy\":110,\"first_fall_frost_doy\":283,"
                + "\"frost_free_season_days\":173,\"spring_frost_years\":5,"
                + "\"last_spring_frost_doy_by_year\":{\"2023\":110},\"calendar_shift_days\":0},"
                + "\"monthly\":[{\"month\":1,\"precip_mm\":20,\"heavy_rain_days\":1,"
                + "\"et0_mm\":12,\"frost_days\":4,\"tmean_c\":-3.1}],"
                + "\"coverage\":{\"first_date\":\"2023-01-01\",\"last_date\":\"2023-12-31\","
                + "\"calendar_years\":1,\"years_with_temperature\":1,\"years_with_water_balance\":1,"
                + "\"min_coverage\":0.9,\"missing_temperature_days\":0,\"missing_precip_days\":0,"
                + "\"duplicate_days_dropped\":0,\"impossible_temperature_days\":0,\"impossible_precip_days\":0},"
                + "\"terrain\":null,\"layers\":{\"weather\":{\"status\":\"ok\"},"
                + "\"terrain\":{\"status\":\"error\",\"message\":\"unavailable\"}},"
                + "\"source\":[{\"source\":\"NASA POWER\",\"spatial_resolution_km\":50}]}";
    }

    /**
     * Creates a representative crop-history response with the optional field ID.
     *
     * @param id field identifier to include, or {@code null} for an arbitrary outline
     * @return a crop-history response payload
     */
    private static String cropHistoryResponse(String id) {
        String idPart = id == null ? "" : "\"id\":\"" + id + "\",";
        return "{" + idPart
                + "\"source\":{\"name\":\"AAFC Annual Crop Inventory\","
                + "\"url\":\"https://example.test/aci\",\"license\":\"Open Government Licence - Canada\","
                + "\"resolutionM\":30},\"years\":[{\"year\":2025,\"crop\":\"Soybeans\","
                + "\"code\":158,\"isCrop\":true,\"share\":0.967,\"classes\":["
                + "{\"code\":158,\"name\":\"Soybeans\",\"isCrop\":true,\"share\":0.967}]}],"
                + "\"rotation\":[\"Soybeans\"],\"distinctCrops\":[\"Soybeans\"],"
                + "\"cropYears\":1,\"unavailableYears\":[],\"note\":\"Satellite classification\"}";
    }

    /**
     * Returns a representative single-cell SMAP response including nullable
     * measurements and variable provenance.
     *
     * @return a small soil-moisture FeatureCollection payload
     */
    private static String soilMoistureResponse() {
        return "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"id\":\"smap-1-1\","
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]},"
                + "\"properties\":{\"row\":1,\"column\":1,\"sm_surface\":null,"
                + "\"sm_rootzone\":0.4,\"soil_temp_layer1\":280.15}}],"
                + "\"metadata\":{\"source\":\"NASA SMAP L4\",\"product\":\"SPL4SMGP\","
                + "\"version\":\"008\",\"schema_version\":2,"
                + "\"variables\":{\"sm_surface\":{\"units\":\"m3/m3\","
                + "\"description\":\"Soil moisture, 0-5 cm\",\"product\":\"SPL4SMGP\",\"static\":false}},"
                + "\"missing_variables\":[\"soil_temp_layer6\"],\"doi\":\"10.5067/T5RUATAQREF8\","
                + "\"url\":\"https://example.test/smap\",\"spatial_resolution_km\":9,"
                + "\"temporal_resolution_hours\":3,\"units\":\"m3/m3\","
                + "\"depth_cm\":{\"sm_surface\":[0,5],\"sm_rootzone\":[0,100]},"
                + "\"requested_bbox\":[-79.7,44.35,-79.05,44.85],\"granule\":\"smap-granule\","
                + "\"time_start\":\"2026-11-14T00:00:00Z\",\"time_end\":\"2026-11-14T03:00:00Z\","
                + "\"built\":\"2026-11-14T04:00:00+00:00\",\"limitations\":\"Model-assimilated cells\","
                + "\"soil_constants\":{\"product\":\"SPL4SMLM\",\"version\":\"008\","
                + "\"science_version\":\"v8010\",\"granule\":\"constants.h5\","
                + "\"url\":\"https://example.test/constants\",\"static\":true}}}";
    }

    /**
     * Writes a JSON response from the local HTTP server.
     *
     * @param exchange the active test request
     * @param status   the HTTP response status
     * @param body     the JSON body
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
}
