package org.greenalienhouseparty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test of {@code POST /crop-optimization} with
 * {@code crop.planner.strategy=mock}. The demonstration planner makes no data
 * backend calls, so the data backend address points nowhere.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"crop.planner.strategy=mock", "data.backend.url=http://127.0.0.1:9"})
class MockPlannerApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    /**
     * Verifies the property selects the demonstration planner, and that it
     * answers without the data backend with a varied five-year plan that is
     * labelled as a demonstration.
     *
     * @throws Exception if the request fails or the response cannot be decoded
     */
    @Test
    void servesLabelledDemonstrationPlans() throws Exception {
        String requestBody = """
                {"fields":[{"fieldId":"field-1",
                  "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]},
                  "currentCrop":"corn","growableCrops":["corn","soybeans","wheat","canola","alfalfa"]}]}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/crop-optimization"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("mock-demonstration", body.get("planner").asString());
        assertTrue(body.get("temporary").asBoolean());
        assertTrue(body.get("disclaimer").asString().startsWith("Demonstration plan only."));
        JsonNode plan = body.get("plans").get(0);
        assertEquals(5, plan.get("years").size());
        Set<String> cropIds = new HashSet<>();
        for (JsonNode year : plan.get("years")) {
            cropIds.add(year.get("cropId").asString());
            assertTrue(year.has("soilScore") && year.has("reliability") && year.get("reasons").size() > 0);
        }
        assertTrue(cropIds.size() >= 4);
        assertTrue(plan.get("assumptions").get(0).asString().startsWith("Demonstration plan:"));
        assertTrue(!plan.has("totalIncome"));
    }
}
