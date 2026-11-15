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

    /**
     * Starts a data backend stub that answers topsoil and crop history and
     * fails the field summary, then points the application at it.
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
     * Verifies the dynamic planner serves the request: crops submitted by id,
     * a scored five-year plan from the growable crops, and the failed field
     * summary reported as assumptions rather than an error.
     *
     * @throws Exception if the request fails or the response cannot be decoded
     */
    @Test
    void returnsScoredPlanAndReportsAssumptions() throws Exception {
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
        JsonNode body = JSON.readTree(response.body());
        assertEquals("dynamic-soil-health", body.get("planner").asString());
        JsonNode plan = body.get("plans").get(0);
        assertEquals("field-1", plan.get("fieldId").asString());
        assertEquals("Corn", plan.get("currentCrop").asString());
        assertEquals(5, plan.get("years").size());
        assertEquals(Year.now().getValue() + 1, plan.get("years").get(0).get("year").asInt());
        for (JsonNode year : plan.get("years")) {
            assertTrue(List.of("soybeans", "wheat", "alfalfa").contains(year.get("cropId").asString()));
            assertTrue(year.has("soilScore") && year.has("incomePerHa") && year.has("rationale"));
        }
        assertTrue(plan.has("totalSoilScore"));
        // The field summary failed: area and erosion risk were assumed.
        assertEquals(2, plan.get("assumptions").size());
    }
}
