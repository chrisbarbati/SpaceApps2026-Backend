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
import java.time.Year;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test of {@code POST /crop-optimization} with
 * {@code crop.planner.strategy=dynamic}, against a local stub of the Python
 * data backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "crop.planner.strategy=dynamic")
class DynamicPlannerApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static HttpServer dataBackend;

    @LocalServerPort
    private int port;

    /** Whether the stub answers the field summary; when not, it fails as the data backend does on an outage. */
    private static final AtomicBoolean summaryAvailable = new AtomicBoolean(true);

    /**
     * Starts a stub that answers the data backend's topsoil, crop history,
     * field summary and crop calendar, and Agriculture and Agri-Food Canada's
     * soil services, then points the application at it. The seasonal outlook
     * service is pointed at the stub too and gets no usable answer.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void configureDataBackend(DynamicPropertyRegistry registry) {
        try {
            dataBackend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            dataBackend.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String body;
                if (path.equals("/soil")) {
                    body = "{\"topsoil\":{\"ph\":6.8},\"derived\":{\"textureClass\":\"Loam\",\"organicMatterPct\":4.2}}";
                } else if (path.equals("/crop-history")) {
                    int lastYear = Year.now().getValue() - 1;
                    body = "{\"years\":[{\"year\":" + lastYear + ",\"crop\":\"Corn for grain\",\"isCrop\":true,"
                            + "\"group\":\"corn\",\"share\":0.9}]}";
                } else if (path.equals("/field-summary") && summaryAvailable.get()) {
                    body = "{\"field\":{\"centroid\":{\"lat\":44.35,\"lon\":-79.16},\"areaHa\":20.0},"
                            + "\"season\":{\"months\":[5,6,7,8,9]},"
                            + "\"monthly\":[{\"month\":5,\"tmean_c\":12.0},{\"month\":6,\"tmean_c\":17.0},"
                            + "{\"month\":7,\"tmean_c\":20.0},{\"month\":8,\"tmean_c\":19.0},{\"month\":9,\"tmean_c\":15.0}],"
                            + "\"yearly\":{\"years\":[{\"year\":2023,\"precip_mm\":1000.0,\"frost_free_days\":150.0},"
                            + "{\"year\":2024,\"precip_mm\":900.0,\"frost_free_days\":160.0}]},"
                            + "\"terrain\":{\"erosionRating\":\"moderate\"}}";
                } else if (path.equals("/crop-calendar")) {
                    body = "{\"crops\":[{\"crop\":\"soybeans\",\"plantingDay\":135,\"maturityDay\":273,"
                            + "\"growingSeasonDays\":138,\"crossesNewYear\":false},"
                            + "{\"crop\":\"winter_wheat\",\"plantingDay\":284,\"maturityDay\":208,"
                            + "\"growingSeasonDays\":289,\"crossesNewYear\":true}]}";
                } else if (path.startsWith("/aafc/")) {
                    String attributes = path.contains("cli_agr_cap_250k") ? "{\"CLASS_A\":\"2\",\"SUBCLAS_A1\":\"S\"}"
                            : path.contains("aafc_soc_drainage_fs") ? "{\"DRAINAGE_CODE\":\"W\",\"DRAINAGE_PCNT\":69}"
                            : path.contains("Residual_soil_nitrogen_2021")
                                    ? "{\"RSN_2021_VAL\":32.5,\"RSN_2021_CLASS_EN\":\"High\"}"
                            : null;
                    body = "{\"features\":[" + (attributes == null ? "" : "{\"attributes\":" + attributes + "}") + "]}";
                } else {
                    status = 502;
                    body = "{\"detail\":\"NASA POWER daily weather is unavailable or invalid\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(bytes);
                }
            });
            dataBackend.start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start the local data backend stub", e);
        }
        registry.add("data.backend.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort());
        registry.add("aafc.arcgis.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort() + "/aafc");
        registry.add("geomet.wms.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort() + "/geomet");
    }

    /**
     * Stops the data backend stub.
     */
    @AfterAll
    static void stopDataBackend() {
        if (dataBackend != null) {
            dataBackend.stop(0);
        }
    }

    /**
     * Verifies the dynamic planner serves the request with every data source
     * answering: crops submitted by id, a scored five-year plan from the
     * growable crops with reliability and plain-language reasons, the high
     * leftover soil nitrogen reflected in the legume's reason, and no
     * assumptions when the seasonal outlook alone is unavailable.
     *
     * @throws Exception if the request fails or the response cannot be decoded
     */
    @Test
    void returnsScoredPlanWithReliabilityAndReasons() throws Exception {
        summaryAvailable.set(true);

        JsonNode body = optimize();

        assertEquals("dynamic-soil-reliability", body.get("planner").asString());
        JsonNode plan = body.get("plans").get(0);
        assertEquals("field-1", plan.get("fieldId").asString());
        assertEquals("Corn", plan.get("currentCrop").asString());
        assertEquals(5, plan.get("years").size());
        assertEquals(Year.now().getValue() + 1, plan.get("years").get(0).get("year").asInt());
        boolean nitrogenFromTheAir = false;
        for (JsonNode year : plan.get("years")) {
            assertTrue(List.of("soybeans", "wheat", "alfalfa").contains(year.get("cropId").asString()));
            assertTrue(year.has("soilScore") && year.has("incomePerHa") && year.has("rationale"));
            double reliability = year.get("reliability").asDouble();
            assertTrue(reliability > 0 && reliability <= 1);
            assertTrue(year.get("reasons").size() > 0);
            for (JsonNode reason : year.get("reasons")) {
                nitrogenFromTheAir = nitrogenFromTheAir || reason.asString().startsWith("Takes its nitrogen from the air");
            }
        }
        assertTrue(nitrogenFromTheAir);
        assertTrue(plan.has("totalSoilScore"));
        assertEquals(0, plan.get("assumptions").size(), plan.get("assumptions").toString());
    }

    /**
     * Verifies a failed field summary is reported as assumptions rather than
     * an error: area and erosion risk are assumed, and the climate record and
     * the regional soil ratings that need the field's centre are missing.
     *
     * @throws Exception if the request fails or the response cannot be decoded
     */
    @Test
    void reportsAssumptionsWhenTheFieldSummaryFails() throws Exception {
        summaryAvailable.set(false);

        JsonNode plan = optimize().get("plans").get(0);

        assertEquals(5, plan.get("years").size());
        assertEquals(4, plan.get("assumptions").size(), plan.get("assumptions").toString());
        for (JsonNode year : plan.get("years")) {
            assertTrue(year.has("reliability") && year.has("reasons"));
        }
    }

    /**
     * Requests a plan for one field currently in corn that can grow soybeans,
     * wheat and alfalfa.
     *
     * @return the decoded response body
     * @throws Exception if the request fails or does not succeed
     */
    private JsonNode optimize() throws Exception {
        String requestBody = """
                {"fields":[{"fieldId":"field-1",
                  "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]},
                  "currentCrop":"corn","growableCrops":["soybeans","wheat","alfalfa"]}]}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/crop-optimization"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }
}
