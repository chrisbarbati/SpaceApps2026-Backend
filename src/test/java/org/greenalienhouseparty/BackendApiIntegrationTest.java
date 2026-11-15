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
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private static final AtomicReference<String> receivedFieldsQuery = new AtomicReference<>();

    private static final List<String> batchRequestBodies = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean fieldDatabaseUp = new AtomicBoolean(true);

    private static final AtomicReference<String> greennessQuery = new AtomicReference<>();

    private static final List<String> soilBatchBodies = new CopyOnWriteArrayList<>();

    private static final AtomicReference<String> soilRequestBody = new AtomicReference<>();

    private static final AtomicReference<String> greennessRequestBody = new AtomicReference<>();

    private static final AtomicBoolean fieldDataReady = new AtomicBoolean(true);

    private static final AtomicBoolean soilMoistureAvailable = new AtomicBoolean(true);

    private static final AtomicReference<String> agroWeatherQuery = new AtomicReference<>();

    private static final AtomicReference<String> agroWeatherRequestBody = new AtomicReference<>();

    private static final AtomicBoolean statCanUp = new AtomicBoolean(true);

    private static final AtomicReference<String> droughtQuery = new AtomicReference<>();

    private static final AtomicReference<String> droughtRequestBody = new AtomicReference<>();

    private static final List<String> geoMetQueries = new CopyOnWriteArrayList<>();

    private static final AtomicReference<String> vegetationQuery = new AtomicReference<>();

    private static final AtomicReference<String> vegetationRequestBody = new AtomicReference<>();

    private static final AtomicReference<String> cropCalendarQuery = new AtomicReference<>();

    private static final AtomicReference<String> cropCalendarRequestBody = new AtomicReference<>();

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
        receivedFieldsQuery.set(null);
        soilMoistureAvailable.set(true);
        batchRequestBodies.clear();
        fieldDatabaseUp.set(true);
        greennessQuery.set(null);
        soilBatchBodies.clear();
        soilRequestBody.set(null);
        greennessRequestBody.set(null);
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
        registry.add("statcan.wds.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort() + "/statcan");
        registry.add("geomet.wms.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort() + "/geomet");
        registry.add("aafc.arcgis.url", () -> "http://127.0.0.1:" + dataBackend.getAddress().getPort() + "/aafc");
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

        HttpResponse<String> soilMoisture = get("/soil-moisture");
        assertEquals(200, soilMoisture.statusCode());
        assertEquals("FeatureCollection", json(soilMoisture).get("type").asString());
        assertEquals(0.4, json(soilMoisture).get("features").get(0).get("properties")
                .get("sm_rootzone").asDouble());
        assertEquals("m3/m3", json(soilMoisture).get("metadata").get("variables")
                .get("sm_surface").get("units").asString());

        HttpResponse<String> fields = get("/fields");
        assertEquals(200, fields.statusCode());
        assertEquals("FeatureCollection", json(fields).get("type").asString());
        assertEquals("field-1", json(fields).get("features").get(0).get("id").asString());
        JsonNode properties = json(fields).get("features").get(0).get("properties");
        assertEquals(42.72, properties.get("areaHa").asDouble());
        assertEquals(105.56, properties.get("areaAcres").asDouble());
        assertEquals(4109, properties.get("perimeterM").asInt());
        assertEquals(1205, properties.get("lengthM").asInt());
        assertEquals(416, properties.get("widthM").asInt());
        assertEquals(162, properties.get("orientationDeg").asInt());
        assertEquals(0.32, properties.get("compactness").asDouble());
        assertEquals(0, properties.get("holes").asInt());
        assertEquals(1, properties.get("parts").asInt());
        assertEquals(-79.156222, properties.get("centroid").get(0).asDouble());
        assertEquals(2025, properties.get("sourceYear").asInt());
        assertEquals(88, properties.get("score").asInt());
        assertEquals(13, properties.size());
        assertTrue(receivedFieldsQuery.get().contains("limit=50"));
        assertTrue(receivedFieldsQuery.get().contains("cursor=0"));

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
                + "\"start\":\"2023-01-01\",\"end\":\"2023-12-31\"},\"weatherSource\":\"auto\"}";
        HttpRequest summaryRequest = HttpRequest.newBuilder(URI.create(url("/field-summary")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        HttpResponse<String> summary = httpClient.send(summaryRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, summary.statusCode());
        assertEquals(4321.5, json(summary).get("climate").get("gdd_annual_median").asDouble());
        assertEquals("Polygon", JSON.readTree(summaryRequestBody.get()).get("polygon").get("type").asString());
        assertEquals("auto", JSON.readTree(summaryRequestBody.get()).get("weatherSource").asString());
        assertEquals("power", json(summary).get("weatherSource").asString());
        assertEquals(3533.2, json(summary).get("climate").get("chu_available_median").asDouble());
        JsonNode weatherLayer = json(summary).get("layers").get("weather");
        assertEquals("power", weatherLayer.get("source").asString());
        assertEquals("daymet", weatherLayer.get("fallbackFrom").asString());
        assertEquals("NASA Daymet is unavailable", weatherLayer.get("message").asString());
        JsonNode terrainLayer = json(summary).get("layers").get("terrain");
        assertTrue(!terrainLayer.has("source") && !terrainLayer.has("fallbackFrom"));
    }

    /**
     * Verifies that field summary requests without a weather source leave it out,
     * so the data backend applies its own default.
     *
     * @throws Exception if a response cannot be decoded
     */
    @Test
    void omitsUnsetWeatherSourceFromFieldSummaryRequest() throws Exception {
        HttpResponse<String> summary = post("/field-summary",
                "{\"polygon\":{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}}");

        assertEquals(200, summary.statusCode());
        assertTrue(!JSON.readTree(summaryRequestBody.get()).has("weatherSource"));
    }

    /**
     * Verifies topsoil and greenness for a caller-provided outline pass the
     * request through, return the data backend's estimates without a field ID,
     * and keep the data backend's validation errors.
     *
     * @throws Exception if a response cannot be decoded
     */
    @Test
    void returnsSoilAndGreennessForAnOutline() throws Exception {
        String outline = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";

        HttpResponse<String> soil = post("/soil", "{\"polygon\":" + outline + "}");
        assertEquals(200, soil.statusCode());
        assertTrue(!json(soil).has("id"));
        assertEquals("Sandy loam", json(soil).get("derived").get("textureClass").asString());
        assertEquals(40, json(soil).get("derived").get("availableWaterMm").asInt());
        assertEquals("Polygon", JSON.readTree(soilRequestBody.get()).get("polygon").get("type").asString());
        HttpResponse<String> invalidSoil = post("/soil", "{\"polygon\":" + outline + ",\"extra\":true}");
        assertEquals(422, invalidSoil.statusCode());
        assertEquals("Extra inputs are not permitted", json(invalidSoil).get("detail").asString());

        HttpResponse<String> greenness = post("/greenness", "{\"polygon\":" + outline + ",\"year\":2024}");
        assertEquals(200, greenness.statusCode());
        assertTrue(!json(greenness).has("id"));
        assertEquals(2024, json(greenness).get("year").asInt());
        assertEquals(0.77, json(greenness).get("summary").get("peakNdvi").asDouble());
        assertEquals(2024, JSON.readTree(greennessRequestBody.get()).get("year").asInt());
        assertEquals("Polygon", JSON.readTree(greennessRequestBody.get()).get("polygon").get("type").asString());
        post("/greenness", "{\"polygon\":" + outline + "}");
        assertTrue(!JSON.readTree(greennessRequestBody.get()).has("year"));
        HttpResponse<String> invalidGreenness = post("/greenness", "{\"polygon\":" + outline + ",\"extra\":true}");
        assertEquals(422, invalidGreenness.statusCode());

        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/soil"));
        assertTrue(paths.has("/greenness"));
    }

    /**
     * Verifies crop reference data and Statistics Canada market data are
     * served, and that a Statistics Canada outage and an unknown province are
     * reported with their own statuses.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsCropReferenceAndMarketData() throws Exception {
        JsonNode reference = json(get("/crops/reference"));
        assertEquals("FAO ECOCROP", reference.get("sources").get("requirements").get("name").asString());
        assertEquals("Zea mays", reference.get("crops").get(0).get("scientificName").asString());
        assertEquals(4.5, reference.get("crops").get(0).get("requirements").get("phMin").asDouble());
        assertEquals(170.0, reference.get("crops").get(0).get("estimates").get("nDemandKgHa").asDouble());

        statCanUp.set(false);
        HttpResponse<String> outage = get("/crops/market?province=QC");
        assertEquals(502, outage.statusCode());
        assertEquals("Statistics Canada is unavailable", json(outage).get("detail").asString());
        statCanUp.set(true);

        HttpResponse<String> market = get("/crops/market");
        assertEquals(200, market.statusCode());
        JsonNode body = json(market);
        assertEquals("ON", body.get("province").asString());
        assertEquals(9, body.get("crops").size());
        JsonNode corn = body.get("crops").get(0);
        assertEquals("corn", corn.get("cropId").asString());
        assertEquals(11000.0, corn.get("yieldKgPerHa").asDouble());
        assertEquals(2025, corn.get("yieldYear").asInt());
        assertEquals("2026-09", corn.get("priceMonth").asString());
        assertEquals(2750.0, corn.get("grossRevenuePerHa").asDouble());
        JsonNode grapes = body.get("crops").get(8);
        assertTrue(grapes.get("yieldKgPerHa").isNull());
        assertEquals(1, grapes.get("notes").size());

        assertEquals(422, get("/crops/market?province=ZZ").statusCode());
        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/crops/market"));
        assertTrue(paths.has("/crops/reference"));
    }

    /**
     * Verifies the crop calendar is re-exposed for a stored field and for an
     * outline, passing the optional system and crops through and keeping
     * nulls and upstream validation errors.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsCropCalendar() throws Exception {
        HttpResponse<String> field = get("/fields/field-1/crop-calendar?system=irrigated&crops=corn,winter_wheat");
        assertEquals(200, field.statusCode());
        assertEquals("system=irrigated&crops=corn,winter_wheat", cropCalendarQuery.get());
        JsonNode body = json(field);
        assertEquals("field-1", body.get("id").asString());
        assertEquals(44.25, body.get("cell").get("lat").asDouble());
        JsonNode corn = body.get("crops").get(0);
        assertEquals("corn", corn.get("crop").asString());
        assertEquals(121, corn.get("plantingDay").asInt());
        assertEquals("05-01", corn.get("plantingDate").asString());
        assertEquals(203, corn.get("offSeasonDays").asInt());
        assertTrue(!corn.get("crossesNewYear").asBoolean());
        assertTrue(body.get("crops").get(1).get("crossesNewYear").asBoolean());
        assertTrue(body.get("crops").get(2).get("plantingDay").isNull());
        assertEquals("10.5281/zenodo.5062513", body.get("source").get("doi").asString());
        get("/fields/field-1/crop-calendar");
        assertEquals("null", cropCalendarQuery.get());

        String outline = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
        HttpResponse<String> posted = post("/crop-calendar",
                "{\"polygon\":" + outline + ",\"system\":\"rainfed\",\"crops\":[\"corn\"]}");
        assertEquals(200, posted.statusCode());
        assertTrue(!json(posted).has("id"));
        assertEquals("corn", JSON.readTree(cropCalendarRequestBody.get()).get("crops").get(0).asString());
        post("/crop-calendar", "{\"polygon\":" + outline + "}");
        assertTrue(!JSON.readTree(cropCalendarRequestBody.get()).has("system"));
        assertTrue(!JSON.readTree(cropCalendarRequestBody.get()).has("crops"));
        HttpResponse<String> invalid = post("/crop-calendar", "{\"polygon\":" + outline + ",\"crops\":[\"oats\"]}");
        assertEquals(422, invalid.statusCode());
        assertEquals("unknown crops: oats", json(invalid).get("detail").asString());

        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/fields/{field_id}/crop-calendar"));
        assertTrue(paths.has("/crop-calendar"));
    }

    /**
     * Verifies vegetation history is re-exposed for a stored field and for an
     * outline, passing the optional years through and keeping nulls.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsVegetationHistory() throws Exception {
        HttpResponse<String> field = get("/fields/field-1/vegetation-history?years=3");
        assertEquals(200, field.statusCode());
        assertEquals("years=3", vegetationQuery.get());
        JsonNode body = json(field);
        assertEquals("field-1", body.get("id").asString());
        JsonNode ndvi = body.get("ndvi");
        assertEquals(0.3, ndvi.get("lowCoverThreshold").asDouble());
        assertTrue(ndvi.get("observations").get(0).get("ndvi").isNull());
        assertTrue(ndvi.get("observations").get(0).get("snow").asBoolean());
        assertEquals(0.804, ndvi.get("observations").get(1).get("ndvi").asDouble());
        assertEquals("2024-06-09", ndvi.get("byYear").get(0).get("greenUpDate").asString());
        assertTrue(ndvi.get("byYear").get(1).get("peakNdvi").isNull());
        assertEquals(1.0, ndvi.get("calendar").get(0).get("lowCoverShare").asDouble());
        assertEquals("12-03", ndvi.get("lowCoverWindows").get(0).get("startDate").asString());
        assertEquals(157, ndvi.get("lowCoverWindows").get(0).get("days").asInt());
        assertEquals(128.0, ndvi.get("typical").get("lowCoverDays").asDouble());
        assertEquals(0.557, body.get("yearly").get(0).get("nppKgCPerM2").asDouble());
        assertEquals(0.51, body.get("yearly").get(0).get("etShareOfPet").asDouble());
        assertEquals(560.6, body.get("yearlySummary").get("etMedianMm").asDouble());
        assertEquals("modis-13Q1-061", body.get("source").get(0).get("collection").asString());
        get("/fields/field-1/vegetation-history");
        assertEquals("null", vegetationQuery.get());

        String outline = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
        HttpResponse<String> posted = post("/vegetation-history", "{\"polygon\":" + outline + ",\"years\":2}");
        assertEquals(200, posted.statusCode());
        assertTrue(!json(posted).has("id"));
        assertEquals(2, JSON.readTree(vegetationRequestBody.get()).get("years").asInt());
        post("/vegetation-history", "{\"polygon\":" + outline + "}");
        assertTrue(!JSON.readTree(vegetationRequestBody.get()).has("years"));

        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/fields/{field_id}/vegetation-history"));
        assertTrue(paths.has("/vegetation-history"));
    }

    /**
     * Verifies the seasonal outlook is read from Environment Canada for the
     * newest run, that a point without an outlook returns no periods, and
     * that bad coordinates are rejected.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsSeasonalOutlook() throws Exception {
        geoMetQueries.clear();
        HttpResponse<String> outlook = get("/seasonal-outlook?lat=44.35&lon=-79.16");
        assertEquals(200, outlook.statusCode());
        JsonNode body = json(outlook);
        assertEquals("2026-11", body.get("issued").asString());
        assertEquals(3, body.get("periods").size());
        JsonNode first = body.get("periods").get(0);
        assertEquals("Nov 2026 - Jan 2027", first.get("label").asString());
        assertEquals(61.3, first.get("temperature").get("abovePct").asDouble());
        assertEquals(13.3, first.get("precipitation").get("belowPct").asDouble());
        assertEquals("above", first.get("temperature").get("mostLikely").asString());
        assertEquals("2027-07", body.get("periods").get(2).get("endMonth").asString());
        assertEquals(19, geoMetQueries.size());
        assertTrue(geoMetQueries.stream().anyMatch(q -> q.contains("layers=CanSIPS_100km_PrecipAccum-ProbBelowNormal-2m_PT3M")
                && q.contains("time=2027-05-01T00:00:00Z") && q.contains("dim_reference_time=2026-11-01T00:00:00Z")
                && q.contains("request=GetFeatureInfo") && q.contains("bbox=44.3000,-79.2100,44.4000,-79.1100")));

        JsonNode nowhere = json(get("/seasonal-outlook?lat=0&lon=0"));
        assertTrue(nowhere.get("issued").isNull());
        assertEquals(0, nowhere.get("periods").size());

        assertEquals(422, get("/seasonal-outlook?lat=95&lon=0").statusCode());
        assertEquals(422, get("/seasonal-outlook?lat=44.35").statusCode());
        assertTrue(json(get("/v3/api-docs")).get("paths").has("/seasonal-outlook"));
    }

    /**
     * Verifies drought indicators are re-exposed for a stored field and for
     * an outline, passing optional weeks and years through, keeping nulls and
     * preserving the missing-credentials status.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsDroughtIndicators() throws Exception {
        HttpResponse<String> field = get("/fields/field-1/drought-indicators?weeks=4&years=2");
        assertEquals(200, field.statusCode());
        assertEquals("weeks=4&years=2", droughtQuery.get());
        JsonNode body = json(field);
        assertEquals("field-1", body.get("id").asString());
        assertEquals(44.375, body.get("cell").get("lat").asDouble());
        assertEquals("2026-08-31", body.get("latest").get("weekStart").asString());
        assertEquals(75, body.get("latest").get("ageDays").asInt());
        assertEquals(8.5, body.get("latest").get("groundwater").get("percentile").asDouble());
        assertEquals("severely dry", body.get("latest").get("groundwater").get("condition").asString());
        assertTrue(body.get("latest").get("surface").get("percentile").isNull());
        assertEquals(96.1, body.get("weekly").get(0).get("rootZonePercentile").asDouble());
        assertEquals("2025-09-01", body.get("sameWeekByYear").get(0).get("weekStart").asString());
        assertEquals(2, body.get("conditions").get(0).get("upToPercentile").asInt());
        assertEquals("1948-2012", body.get("baseline").asString());
        get("/fields/field-1/drought-indicators");
        assertEquals("null", droughtQuery.get());

        HttpResponse<String> unconfigured = get("/fields/no-credentials/drought-indicators");
        assertEquals(503, unconfigured.statusCode());
        assertTrue(json(unconfigured).get("detail").asString().contains("EARTHDATA_USERNAME"));

        String outline = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
        HttpResponse<String> posted = post("/drought-indicators", "{\"polygon\":" + outline + ",\"weeks\":8,\"years\":0}");
        assertEquals(200, posted.statusCode());
        assertTrue(!json(posted).has("id"));
        assertEquals(8, JSON.readTree(droughtRequestBody.get()).get("weeks").asInt());
        assertEquals(0, JSON.readTree(droughtRequestBody.get()).get("years").asInt());
        post("/drought-indicators", "{\"polygon\":" + outline + "}");
        assertTrue(!JSON.readTree(droughtRequestBody.get()).has("weeks"));

        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/fields/{field_id}/drought-indicators"));
        assertTrue(paths.has("/drought-indicators"));
    }

    /**
     * Verifies land capability, drainage and soil indicators are read from
     * Agriculture and Agri-Food Canada, that a point outside Canada returns
     * no values, and that a service error and bad coordinates are reported.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsSoilLandscape() throws Exception {
        HttpResponse<String> landscape = get("/soil-landscape?lat=44.3457&lon=-79.1569");
        assertEquals(200, landscape.statusCode());
        JsonNode body = json(landscape);
        assertEquals("Canada", body.get("coverage").asString());
        assertEquals("2", body.get("landCapability").get("classCode").asString());
        assertEquals("S", body.get("landCapability").get("limitations").get(0).get("code").asString());
        assertEquals("Well and moderately well drained", body.get("drainage").get("description").asString());
        assertEquals(2, body.get("indicators").size());
        JsonNode erosion = body.get("indicators").get(0);
        assertEquals("erosionRisk", erosion.get("key").asString());
        assertEquals(12.47, erosion.get("value").asDouble());
        assertEquals("Moderate", erosion.get("rating").asString());
        assertEquals(1981, erosion.get("change").get("fromYear").asInt());
        assertEquals(2, erosion.get("history").size());
        assertEquals("soilCoverDays", body.get("indicators").get(1).get("key").asString());
        assertTrue(body.get("indicators").get(1).get("change").isNull());

        JsonNode outside = json(get("/soil-landscape?lat=40.45&lon=-88.95"));
        assertTrue(outside.get("coverage").isNull());
        assertTrue(outside.get("landCapability").isNull());
        assertEquals(0, outside.get("indicators").size());

        HttpResponse<String> failed = get("/soil-landscape?lat=46.0&lon=-60.0");
        assertEquals(502, failed.statusCode());
        assertTrue(json(failed).get("detail").asString().contains("soil service is unavailable"));
        assertEquals(422, get("/soil-landscape?lat=44.3&lon=200").statusCode());
        assertTrue(json(get("/v3/api-docs")).get("paths").has("/soil-landscape"));
    }

    /**
     * Verifies agro-weather is re-exposed for a stored field and for an
     * outline, passing the optional years through and keeping nulls.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void returnsAgroWeather() throws Exception {
        HttpResponse<String> field = get("/fields/field-1/agro-weather?years=10");
        assertEquals(200, field.statusCode());
        assertEquals("years=10", agroWeatherQuery.get());
        JsonNode body = json(field);
        assertEquals("field-1", body.get("id").asString());
        assertEquals("dry", body.get("soilWetness").get("latest").get("status").asString());
        assertEquals(0, body.get("soilWetness").get("latest").get("percentile").asInt());
        assertEquals(0.455, body.get("soilWetness").get("seasonPercentiles").get("rootZone").get("p50").asDouble());
        assertEquals(537.9, body.get("et0").get("seasonPercentilesMm").get("p50").asDouble());
        assertEquals(2025, body.get("seasonByYear").get(0).get("year").asInt());
        assertTrue(body.get("seasonByYear").get(0).get("et0Mm").isNull());
        assertEquals(241.1, body.get("location").get("elevationM").asDouble());
        get("/fields/field-1/agro-weather");
        assertEquals("null", agroWeatherQuery.get());

        String outline = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}";
        HttpResponse<String> posted = post("/agro-weather", "{\"polygon\":" + outline + ",\"years\":12}");
        assertEquals(200, posted.statusCode());
        assertTrue(!json(posted).has("id"));
        assertEquals(12, JSON.readTree(agroWeatherRequestBody.get()).get("years").asInt());
        post("/agro-weather", "{\"polygon\":" + outline + "}");
        assertTrue(!JSON.readTree(agroWeatherRequestBody.get()).has("years"));

        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/fields/{field_id}/agro-weather"));
        assertTrue(paths.has("/agro-weather"));
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
        assertTrue(openApi.get("paths").has("/crops"));
        assertTrue(openApi.get("paths").has("/crop-optimization"));
        assertTrue(openApi.get("paths").has("/soil-moisture"));
    }

    /**
     * Submits the crop-optimization request example published in OpenAPI to the
     * running Java application and confirms the real planner returns a plan.
     *
     * @throws Exception if the OpenAPI example or optimization response is invalid
     */
    @Test
    void openApiCropOptimizationExampleReturnsARealPlan() throws Exception {
        JsonNode openApi = JSON.readTree(get("/v3/api-docs").body());
        JsonNode example = openApi.get("paths")
                .get("/crop-optimization")
                .get("post")
                .get("requestBody")
                .get("content")
                .get("application/json")
                .get("examples")
                .get("valid-field-plan")
                .get("value");
        String requestBody = example.isString() ? example.asString() : example.toString();

        HttpResponse<String> response = post("/crop-optimization", requestBody);

        assertEquals(200, response.statusCode());
        JsonNode result = json(response);
        assertEquals("random-placeholder", result.get("planner").asString());
        assertTrue(result.get("temporary").asBoolean());
        assertEquals(1, result.get("plans").size());
        assertEquals("ftw-2025-17TPK_0_0-13020", result.get("plans").get(0).get("fieldId").asString());
        assertEquals(5, result.get("plans").get(0).get("years").size());
    }

    /**
     * Verifies that an unavailable SMAP cache or upstream is returned with the
     * same 503 status and detail as Python.
     *
     * @throws Exception if the error response cannot be decoded
     */
    @Test
    void preservesSoilMoistureUnavailableStatus() throws Exception {
        soilMoistureAvailable.set(false);

        HttpResponse<String> response = get("/soil-moisture");

        assertEquals(503, response.statusCode());
        assertEquals("SMAP cache unavailable", json(response).get("detail").asString());
    }

    /**
     * Verifies the fixed crop catalog and Java-owned five-year planning endpoint.
     *
     * @throws Exception if a request or response fails
     */
    @Test
    void exposesCropCatalogAndTemporaryFiveYearPlans() throws Exception {
        HttpResponse<String> catalog = get("/crops");
        assertEquals(200, catalog.statusCode());
        JsonNode corn = json(catalog).get("crops").get(0);
        assertEquals("corn", corn.get("id").asString());
        assertEquals("Corn", corn.get("name").asString());
        assertEquals("Poaceae", corn.get("family").asString());
        assertTrue(corn.has("nDemandKgHa") && corn.has("netMarginPerHa") && corn.has("diseaseSusceptibility"));
        assertTrue(json(catalog).get("exampleData").asBoolean());
        assertTrue(json(catalog).get("crops").size() >= 1);

        LocalDate readyDate = LocalDate.now().plusYears(2);
        String requestBody = """
                {"fields":[
                  {"fieldId":"field-1","geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]},"currentCrop":"Corn","growableCrops":["Soybeans","Wheat"]},
                  {"fieldId":"field-2","geometry":{"type":"Polygon","coordinates":[[[1,1],[2,1],[2,2],[1,2],[1,1]]]},"currentCrop":"soybeans","growableCrops":["oats"],"readyDate":"%s"}
                ]}
                """.formatted(readyDate);

        HttpResponse<String> response = post("/crop-optimization", requestBody);
        assertEquals(200, response.statusCode());
        JsonNode body = json(response);
        assertEquals("random-placeholder", body.get("planner").asString());
        assertTrue(body.get("temporary").asBoolean());
        assertEquals(2, body.get("plans").size());

        JsonNode firstPlan = body.get("plans").get(0);
        assertEquals("field-1", firstPlan.get("fieldId").asString());
        assertEquals(5, firstPlan.get("years").size());
        assertTrue(firstPlan.get("years").get(0).get("year").asInt() >= Year.now().getValue() + 1);
        for (JsonNode year : firstPlan.get("years")) {
            assertTrue(List.of("Soybeans", "Wheat").contains(year.get("crop").asString()));
        }

        JsonNode secondPlan = body.get("plans").get(1);
        assertEquals("field-2", secondPlan.get("fieldId").asString());
        assertEquals(readyDate.getYear(), secondPlan.get("years").get(0).get("year").asInt());
        for (JsonNode year : secondPlan.get("years")) {
            assertEquals("Oats", year.get("crop").asString());
        }
        assertTrue(body.get("disclaimer").asString().contains("not optimized"));
    }

    /**
     * Verifies invalid crop-planning choices return a validation response.
     *
     * @throws Exception if the response cannot be decoded
     */
    @Test
    void rejectsUnsupportedCropChoices() throws Exception {
        String requestBody = """
                {"fields":[{"fieldId":"field-1","geometry":{"type":"Polygon","coordinates":[]},
                "currentCrop":"Corn","growableCrops":["Coffee"]}]}
                """;

        HttpResponse<String> response = post("/crop-optimization", requestBody);

        assertEquals(422, response.statusCode());
        assertTrue(json(response).get("detail").asString().contains("unsupported crop"));
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
     * Verifies multi-field crop history through the public API: request order,
     * de-duplication, per-field errors, and documentation.
     *
     * @throws Exception if a response cannot be decoded
     */
    @Test
    void returnsCropHistoryForSeveralFields() throws Exception {
        HttpResponse<String> response = post("/fields/crop-history",
                "{\"ids\":[\"field-2\",\"missing\",\" field-1 \",\"field-2\"]}");

        assertEquals(200, response.statusCode());
        JsonNode body = json(response);
        assertEquals(3, body.get("requested").asInt());
        assertEquals(2, body.get("succeeded").asInt());
        assertEquals(1, body.get("failed").asInt());
        JsonNode results = body.get("results");
        assertEquals("field-2", results.get(0).get("id").asString());
        assertEquals("ok", results.get(0).get("status").asString());
        assertEquals("Soybeans", results.get(0).get("history").get("rotation").get(0).asString());
        JsonNode summary = results.get(0).get("history").get("summary");
        assertEquals("A 3-year rotation: corn, corn, then soybeans.", summary.get("pattern").get("text").asString());
        assertEquals("Corn", summary.get("expectedThisYear").get("crop").asString());
        assertEquals("high", summary.get("expectedThisYear").get("confidence").asString());
        assertEquals(2025, summary.get("lastSeason").get("year").asInt());
        assertEquals(15, summary.get("yearsOfData").asInt());
        assertTrue(!summary.get("flags").get("possiblyMerged").asBoolean());
        assertEquals("soybeans", results.get(0).get("history").get("years").get(0).get("group").asString());
        assertEquals("Soybeans", results.get(0).get("history").get("years").get(0).get("groupLabel").asString());
        assertEquals(0.996, results.get(0).get("history").get("years").get(0).get("cropShare").asDouble());
        assertTrue(!results.get(0).has("error"));
        assertTrue(!results.get(0).has("ok"));
        assertEquals("missing", results.get(1).get("id").asString());
        assertEquals(404, results.get(1).get("error").get("code").asInt());
        assertTrue(!results.get(1).has("history"));
        assertEquals("field-1", results.get(2).get("id").asString());
        assertEquals(1, batchRequestBodies.size());
        assertEquals("[\"field-2\",\"missing\",\"field-1\"]", JSON.readTree(batchRequestBodies.get(0)).get("ids").toString());

        JsonNode openApi = json(get("/v3/api-docs"));
        assertTrue(openApi.get("paths").has("/fields/crop-history"));
    }

    /**
     * Verifies that invalid selections are rejected without calling the data
     * backend, and that a data backend outage keeps its status.
     *
     * @throws Exception if a response cannot be decoded
     */
    @Test
    void rejectsInvalidSelectionsAndPropagatesOutages() throws Exception {
        for (String body : List.of("{\"ids\":[]}", "{}", "{\"ids\":[\" \"]}", "{\"ids\":\"field-1\"}")) {
            HttpResponse<String> invalid = post("/fields/crop-history", body);
            assertEquals(422, invalid.statusCode(), body);
            assertTrue(json(invalid).has("detail"));
        }
        assertTrue(batchRequestBodies.isEmpty());

        fieldDatabaseUp.set(false);
        HttpResponse<String> outage = post("/fields/crop-history", "{\"ids\":[\"field-1\"]}");
        assertEquals(503, outage.statusCode());
        assertEquals("Field boundary database is unavailable or invalid", json(outage).get("detail").asString());
    }

    /**
     * Verifies single-field and multi-field soil pass through unchanged, including
     * per-field errors, and that invalid selections stop before the data backend.
     *
     * @throws Exception if a response cannot be decoded
     */
    @Test
    void returnsFieldSoil() throws Exception {
        HttpResponse<String> single = get("/fields/field-1/soil");
        assertEquals(200, single.statusCode());
        JsonNode soil = json(single);
        assertEquals("field-1", soil.get("id").asString());
        assertEquals(6.82, soil.get("topsoil").get("ph").asDouble());
        assertEquals("Sandy loam", soil.get("derived").get("textureClass").asString());
        assertEquals(40, soil.get("derived").get("availableWaterMm").asInt());
        assertEquals(16.45, soil.get("depths").get("15-30cm").get("clayPct").asDouble());
        assertEquals("mm in 0-30 cm", soil.get("units").get("availableWaterMm").asString());
        assertEquals(404, get("/fields/missing/soil").statusCode());

        HttpResponse<String> batch = post("/fields/soil", "{\"ids\":[\"field-1\",\"missing\",\"field-1\"]}");
        assertEquals(200, batch.statusCode());
        JsonNode body = json(batch);
        assertEquals(1, body.get("failed").asInt());
        assertEquals("Sandy loam", body.get("results").get(0).get("soil").get("derived").get("textureClass").asString());
        assertEquals(52.31, body.get("results").get(0).get("soil").get("topsoil").get("sandPct").asDouble());
        assertTrue(!body.get("results").get(0).has("ok"));
        assertEquals(404, body.get("results").get(1).get("error").get("code").asInt());
        assertEquals("[\"field-1\",\"missing\"]", JSON.readTree(soilBatchBodies.get(0)).get("ids").toString());

        assertEquals(422, post("/fields/soil", "{\"ids\":[]}").statusCode());
        assertEquals(1, soilBatchBodies.size());
        JsonNode paths = json(get("/v3/api-docs")).get("paths");
        assertTrue(paths.has("/fields/{field_id}/soil"));
        assertTrue(paths.has("/fields/soil"));
    }

    /**
     * Verifies field greenness passes the optional year through and returns the
     * season unchanged, and that errors keep their status.
     *
     * @throws Exception if a response cannot be decoded
     */
    @Test
    void returnsFieldGreenness() throws Exception {
        HttpResponse<String> response = get("/fields/field-1/greenness?year=2024");

        assertEquals(200, response.statusCode());
        assertEquals("year=2024", greennessQuery.get());
        JsonNode body = json(response);
        assertEquals("field-1", body.get("id").asString());
        assertEquals("2024-04-01", body.get("period").get("start").asString());
        assertEquals("2024-07-14", body.get("observations").get(0).get("date").asString());
        assertEquals(0.77, body.get("observations").get(0).get("ndvi").asDouble());
        assertEquals(0.84, body.get("observations").get(0).get("ndviP90").asDouble());
        assertEquals("2024-07-14", body.get("summary").get("peakDate").asString());
        assertTrue(body.get("summary").get("greenUpDate").isNull());
        assertEquals("sentinel-2-c1-l2a", body.get("source").get("collection").asString());

        get("/fields/field-1/greenness");
        assertEquals("null", greennessQuery.get());

        assertEquals(404, get("/fields/missing/greenness").statusCode());
        HttpResponse<String> providerFailure = get("/fields/provider-error/greenness");
        assertEquals(502, providerFailure.statusCode());
        assertEquals("Earth Search (Sentinel-2 catalogue) is unavailable", json(providerFailure).get("detail").asString());
        assertEquals(422, get("/fields/field-1/greenness?year=soon").statusCode());
        assertTrue(json(get("/v3/api-docs")).get("paths").has("/fields/{field_id}/greenness"));
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
        } else if (path.equals("/soil-moisture")) {
            if (soilMoistureAvailable.get()) {
                response = soilMoistureResponse();
            } else {
                status = 503;
                response = "{\"detail\":\"SMAP cache unavailable\"}";
            }
        } else if (path.equals("/fields")) {
            receivedFieldsQuery.set(exchange.getRequestURI().getRawQuery());
            response = "{\"type\":\"FeatureCollection\",\"features\":["
                    + "{\"type\":\"Feature\",\"id\":\"field-1\","
                    + "\"properties\":{\"areaHa\":42.72,\"areaAcres\":105.56,\"perimeterM\":4109,"
                    + "\"lengthM\":1205,\"widthM\":416,\"orientationDeg\":162,\"compactness\":0.32,"
                    + "\"holes\":0,\"parts\":1,\"centroid\":[-79.156222,44.345239],\"sourceYear\":2025,"
                    + "\"id\":\"field-1\",\"score\":88},"
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
        } else if (path.equals("/fields/field-1/soil")) {
            response = "{\"id\":\"field-1\"," + soilJson() + "}";
        } else if (path.equals("/fields/soil")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            soilBatchBodies.add(body);
            response = "{\"results\":[{\"id\":\"field-1\",\"status\":\"ok\",\"soil\":{" + soilJson() + "}},"
                    + "{\"id\":\"missing\",\"status\":\"error\",\"error\":{\"code\":404,\"detail\":\"Field not found\"}}],"
                    + "\"requested\":2,\"succeeded\":1,\"failed\":1}";
        } else if (path.startsWith("/aafc/")) {
            String query = exchange.getRequestURI().getQuery();
            String attributes;
            if (query.contains("geometry=-88.95")) {
                attributes = null;
            } else if (query.contains("geometry=-60.0")) {
                attributes = "error";
            } else if (path.contains("cli_agr_cap_250k")) {
                attributes = "{\"CLASS_A\":\"2\",\"SUBCLAS_A1\":\"S\",\"SUBCLAS_A2\":\" \"}";
            } else if (path.contains("aafc_soc_drainage_fs")) {
                attributes = "{\"DRAINAGE_CODE\":\"W\",\"DRAINAGE_PCNT\":69}";
            } else if (path.contains("Risk_soil_erosion_integrated_2021")) {
                attributes = "{\"ERI_1981_VAL\":14.464,\"ERI_1981_CLASS_EN\":\"Moderate\",\"ERI_2021_VAL\":12.467,"
                        + "\"ERI_2021_CLASS_EN\":\"Moderate\",\"ERI_81_21_CHG_VAL\":-1.997,"
                        + "\"ERI_81_21_CHG_CLASS_EN\":\"Decrease\"}";
            } else if (path.contains("Soil_cover_days_2021")) {
                attributes = "{\"SCD_2021_VAL\":287.4205,\"SCD_2021_CLASS_EN\":\"Moderate\"}";
            } else {
                attributes = null;
            }
            response = "error".equals(attributes)
                    ? "{\"error\":{\"code\":400,\"message\":\"Invalid query\"}}"
                    : "{\"objectIdFieldName\":\"OBJECTID\",\"fields\":[],\"features\":["
                            + (attributes == null ? "" : "{\"attributes\":" + attributes + "}") + "]}";
        } else if (path.equals("/geomet")) {
            String query = exchange.getRequestURI().getQuery();
            geoMetQueries.add(query);
            if (query.contains("bbox=-0.0500")) {
                response = "{\"type\":\"FeatureCollection\",\"features\":[]}";
            } else {
                String time = query.contains("time=2027-02") ? "2027-02-01T00:00:00Z"
                        : query.contains("time=2027-05") ? "2027-05-01T00:00:00Z" : "2026-11-01T00:00:00Z";
                double value = query.contains("ProbAbove") ? 61.25 : query.contains("ProbNear") ? 25.5 : 13.25;
                response = "{\"type\":\"FeatureCollection\",\"layer\":\"x\",\"features\":[{\"type\":\"Feature\","
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[-79.0,44.5]},\"properties\":{\"value\":"
                        + value + ",\"class\":\"60 - 70%\",\"time\":\"" + time
                        + "\",\"dim_reference_time\":\"2026-11-01T00:00:00Z\"}}]}";
            }
        } else if (path.equals("/statcan/getDataFromCubePidCoordAndLatestNPeriods")) {
            if (!statCanUp.get()) {
                status = 503;
                response = "{\"message\":\"down\"}";
            } else {
                StringBuilder results = new StringBuilder();
                for (JsonNode series : JSON.readTree(exchange.getRequestBody().readAllBytes())) {
                    int productId = series.get("productId").asInt();
                    String coordinate = series.get("coordinate").asString();
                    String points = productId == 32100359
                            ? "{\"refPer\":\"2025-01-01\",\"value\":11000.0,\"decimals\":0},"
                                    + "{\"refPer\":\"2026-01-01\",\"value\":null,\"decimals\":0}"
                            : "{\"refPer\":\"2026-09-01\",\"value\":250.0,\"decimals\":2}";
                    if (!results.isEmpty()) {
                        results.append(',');
                    }
                    results.append("{\"status\":\"SUCCESS\",\"object\":{\"responseStatusCode\":0,\"productId\":")
                            .append(productId).append(",\"coordinate\":\"").append(coordinate)
                            .append("\",\"vectorId\":1,\"vectorDataPoint\":[").append(points).append("]}}");
                }
                response = "[" + results + "]";
            }
        } else if (path.equals("/fields/field-1/crop-calendar")) {
            cropCalendarQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));
            response = "{\"id\":\"field-1\"," + cropCalendarJson() + "}";
        } else if (path.equals("/crop-calendar")) {
            cropCalendarRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (cropCalendarRequestBody.get().contains("\"oats\"")) {
                status = 422;
                response = "{\"detail\":\"unknown crops: oats\"}";
            } else {
                response = "{" + cropCalendarJson() + "}";
            }
        } else if (path.equals("/fields/field-1/vegetation-history")) {
            vegetationQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));
            response = "{\"id\":\"field-1\"," + vegetationHistoryJson() + "}";
        } else if (path.equals("/vegetation-history")) {
            vegetationRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            response = "{" + vegetationHistoryJson() + "}";
        } else if (path.equals("/fields/field-1/drought-indicators")) {
            droughtQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));
            response = "{\"id\":\"field-1\"," + droughtJson() + "}";
        } else if (path.equals("/fields/no-credentials/drought-indicators")) {
            status = 503;
            response = "{\"detail\":\"Configure EARTHDATA_USERNAME and EARTHDATA_PASSWORD on the API host to fetch GRACE data\"}";
        } else if (path.equals("/drought-indicators")) {
            droughtRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            response = "{" + droughtJson() + "}";
        } else if (path.equals("/fields/field-1/agro-weather")) {
            agroWeatherQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));
            response = "{\"id\":\"field-1\"," + agroWeatherJson() + "}";
        } else if (path.equals("/agro-weather")) {
            agroWeatherRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            response = "{" + agroWeatherJson() + "}";
        } else if (path.equals("/fields/field-1/greenness")) {
            greennessQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));
            response = "{\"id\":\"field-1\"," + greennessJson() + "}";
        } else if (path.equals("/greenness")) {
            greennessRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (greennessRequestBody.get().contains("\"extra\"")) {
                status = 422;
                response = "{\"detail\":\"Extra inputs are not permitted\"}";
            } else {
                response = "{" + greennessJson() + "}";
            }
        } else if (path.equals("/soil")) {
            soilRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (soilRequestBody.get().contains("\"extra\"")) {
                status = 422;
                response = "{\"detail\":\"Extra inputs are not permitted\"}";
            } else {
                response = "{" + soilJson() + "}";
            }
        } else if (path.equals("/fields/provider-error/greenness")) {
            status = 502;
            response = "{\"detail\":\"Earth Search (Sentinel-2 catalogue) is unavailable\"}";
        } else if (path.equals("/fields/crop-history")) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            batchRequestBodies.add(body);
            if (!fieldDatabaseUp.get()) {
                status = 503;
                response = "{\"detail\":\"Field boundary database is unavailable or invalid\"}";
            } else {
                StringBuilder results = new StringBuilder();
                int ok = 0;
                for (JsonNode id : JSON.readTree(body).get("ids")) {
                    String fieldId = id.asString();
                    if (!results.isEmpty()) {
                        results.append(',');
                    }
                    if (fieldId.equals("missing")) {
                        results.append("{\"id\":\"missing\",\"status\":\"error\","
                                + "\"error\":{\"code\":404,\"detail\":\"Field not found\"}}");
                    } else {
                        ok++;
                        results.append("{\"id\":\"").append(fieldId).append("\",\"status\":\"ok\",\"history\":")
                                .append(cropHistoryResponse(null)).append('}');
                    }
                }
                int total = JSON.readTree(body).get("ids").size();
                response = "{\"results\":[" + results + "],\"requested\":" + total
                        + ",\"succeeded\":" + ok + ",\"failed\":" + (total - ok) + "}";
            }
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
                    + "\"weatherSource\":\"power\","
                    + "\"weatherCell\":{\"lat\":44.6,\"lon\":-79.4,\"gridDeg\":0.05},"
                    + "\"period\":{\"start\":\"20230101\",\"end\":\"20231231\"},"
                    + "\"season\":{\"months\":[5,6,7,8,9],\"crosses_year\":false,"
                    + "\"base_temp_c\":10,\"label\":\"May-Sep\"},"
                    + "\"climate\":{\"gdd_annual_median\":4321.5,\"chu_available_median\":3533.2},"
                    + "\"water\":{\"season_et0_mm_median\":125},\"terrain\":null,"
                    + "\"layers\":{\"weather\":{\"status\":\"ok\",\"source\":\"power\",\"fallbackFrom\":\"daymet\","
                    + "\"message\":\"NASA Daymet is unavailable\"},"
                    + "\"terrain\":{\"status\":\"error\"}},\"source\":[]}";
        } else {
            status = 404;
            response = "{\"detail\":\"Not found\"}";
        }
        respond(exchange, status, response);
    }

    /**
     * Creates a representative crop calendar body, without the surrounding braces.
     *
     * @return crop calendar JSON members
     */
    private static String cropCalendarJson() {
        return "\"location\":{\"lat\":44.345697,\"lon\":-79.156938},"
                + "\"cell\":{\"lat\":44.25,\"lon\":-79.25,\"sizeDeg\":0.5},\"system\":\"rainfed\","
                + "\"crops\":[{\"crop\":\"corn\",\"label\":\"Corn\",\"plantingDay\":121,\"plantingDate\":\"05-01\","
                + "\"maturityDay\":283,\"maturityDate\":\"10-10\",\"growingSeasonDays\":162,\"offSeasonDays\":203,"
                + "\"crossesNewYear\":false,\"dataSource\":\"MIRCA2000\"},"
                + "{\"crop\":\"winter_wheat\",\"label\":\"Winter wheat\",\"plantingDay\":284,\"plantingDate\":\"10-11\","
                + "\"maturityDay\":208,\"maturityDate\":\"07-27\",\"growingSeasonDays\":289,\"offSeasonDays\":76,"
                + "\"crossesNewYear\":true,\"dataSource\":\"SAGE\"},"
                + "{\"crop\":\"cassava\",\"label\":\"Cassava\",\"plantingDay\":null,\"plantingDate\":null,"
                + "\"maturityDay\":null,\"maturityDate\":null,\"growingSeasonDays\":null,\"offSeasonDays\":null,"
                + "\"crossesNewYear\":null,\"dataSource\":null}],"
                + "\"source\":{\"name\":\"GGCMI Phase 3 crop calendar\",\"url\":\"https://zenodo.org/records/5062513\","
                + "\"doi\":\"10.5281/zenodo.5062513\",\"version\":\"1.01\",\"resolutionDeg\":0.5,"
                + "\"license\":\"CC BY 4.0\",\"citation\":\"Jaegermeyr et al. (2021)\"},\"note\":\"Usual dates\"";
    }

    /**
     * Creates a representative vegetation history body, without the surrounding braces.
     *
     * @return vegetation history JSON members
     */
    private static String vegetationHistoryJson() {
        return "\"location\":{\"lat\":44.345697,\"lon\":-79.156938},"
                + "\"ndvi\":{\"period\":{\"start\":\"2024-01-01\",\"end\":\"2026-11-14\"},\"lowCoverThreshold\":0.3,"
                + "\"observations\":[{\"date\":\"2024-01-01\",\"ndvi\":null,\"snow\":true,\"usableShare\":0.0},"
                + "{\"date\":\"2024-07-11\",\"ndvi\":0.804,\"snow\":false,\"usableShare\":1.0}],"
                + "\"byYear\":[{\"year\":2024,\"composites\":20,\"peakNdvi\":0.824,\"peakDate\":\"2024-07-27\","
                + "\"greenUpDate\":\"2024-06-09\",\"brownDownDate\":\"2024-09-29\",\"greenDays\":128,\"lowCoverDays\":96},"
                + "{\"year\":2025,\"composites\":11,\"peakNdvi\":null,\"peakDate\":null,\"greenUpDate\":null,"
                + "\"brownDownDate\":null,\"greenDays\":null,\"lowCoverDays\":112}],"
                + "\"calendar\":[{\"startDay\":1,\"startDate\":\"01-01\",\"medianNdvi\":null,\"lowCoverShare\":1.0,"
                + "\"snowShare\":1.0,\"years\":3}],"
                + "\"lowCoverWindows\":[{\"startDate\":\"12-03\",\"endDate\":\"05-08\",\"days\":157}],"
                + "\"typical\":{\"peakNdvi\":0.823,\"greenDays\":112.0,\"lowCoverDays\":128.0}},"
                + "\"yearly\":[{\"year\":2001,\"nppKgCPerM2\":0.557,\"etMm\":527.7,\"petMm\":1024.8,\"etShareOfPet\":0.51}],"
                + "\"yearlySummary\":{\"nppMedianKgCPerM2\":0.571,\"nppTrendKgCPerM2PerYear\":0.00016,"
                + "\"etMedianMm\":560.6,\"petMedianMm\":990.9,\"etShareOfPetMedian\":0.57},"
                + "\"filesChecked\":125,\"filesUnreadable\":0,"
                + "\"source\":[{\"name\":\"NASA MODIS/Terra Vegetation Indices 16-Day (MOD13Q1 v6.1)\","
                + "\"collection\":\"modis-13Q1-061\",\"resolutionM\":250,\"from\":2000}],"
                + "\"provider\":{\"name\":\"Microsoft Planetary Computer\",\"url\":\"https://example.test/pc\"},"
                + "\"note\":\"MODIS pixels are 250 m\"";
    }

    /**
     * Creates a representative drought indicators body, without the surrounding braces.
     *
     * @return drought indicators JSON members
     */
    private static String droughtJson() {
        return "\"location\":{\"lat\":44.345697,\"lon\":-79.156938},"
                + "\"cell\":{\"lat\":44.375,\"lon\":-79.125,\"sizeDeg\":0.25},"
                + "\"latest\":{\"weekStart\":\"2026-08-31\",\"ageDays\":75,"
                + "\"groundwater\":{\"percentile\":8.5,\"condition\":\"severely dry\"},"
                + "\"rootZone\":{\"percentile\":40.0,\"condition\":\"normal\"},"
                + "\"surface\":{\"percentile\":null,\"condition\":null}},"
                + "\"weekly\":[{\"weekStart\":\"2026-08-24\",\"groundwaterPercentile\":9.0,\"rootZonePercentile\":96.1,"
                + "\"surfacePercentile\":94.6}],"
                + "\"sameWeekByYear\":[{\"weekStart\":\"2025-09-01\",\"groundwaterPercentile\":54.1,"
                + "\"rootZonePercentile\":56.1,\"surfacePercentile\":null}],"
                + "\"conditions\":[{\"upToPercentile\":2,\"condition\":\"exceptionally dry\"}],\"baseline\":\"1948-2012\","
                + "\"source\":{\"name\":\"NASA GRACE and GRACE-FO Data Assimilation drought indicators\","
                + "\"product\":\"GRACEDADM_CLSM025GL_7D\",\"url\":\"https://example.test/grace\",\"resolutionDeg\":0.25,"
                + "\"frequency\":\"weekly\",\"institution\":\"NASA Goddard Space Flight Center\"},"
                + "\"note\":\"Percentiles compare each layer\"";
    }

    /**
     * Creates a representative agro-weather body, without the surrounding braces.
     *
     * @return agro-weather JSON members
     */
    private static String agroWeatherJson() {
        return "\"location\":{\"lat\":44.345697,\"lon\":-79.156938,\"elevationM\":241.1},"
                + "\"period\":{\"start\":\"2006-01-01\",\"end\":\"2026-11-14\"},\"season\":{\"months\":[5,6,7,8,9]},"
                + "\"soilWetness\":{\"latest\":{\"date\":\"2026-11-11\",\"rootZone\":0.21,\"surface\":0.35,"
                + "\"rootZone30DayMean\":0.207,\"percentile\":0,\"comparedYears\":20,\"status\":\"dry\"},"
                + "\"monthly\":[{\"month\":1,\"rootZone\":0.52,\"surface\":0.6}],"
                + "\"seasonPercentiles\":{\"rootZone\":{\"p10\":0.428,\"p50\":0.455,\"p90\":0.497,\"years\":21},"
                + "\"surface\":null}},"
                + "\"et0\":{\"method\":\"FAO-56 Penman-Monteith\",\"seasonPercentilesMm\":{\"p10\":489.3,\"p50\":537.9,"
                + "\"p90\":570.7,\"years\":21},\"monthlyMmPerDay\":[{\"month\":1,\"et0\":0.4}]},"
                + "\"seasonMeans\":{\"solarMjM2Day\":19.5,\"windMs\":1.36,\"humidityPct\":68.6},"
                + "\"seasonByYear\":[{\"year\":2025,\"rootZone\":0.424,\"surface\":0.406,\"et0Mm\":null,"
                + "\"solarMjM2Day\":19.52,\"windMs\":1.35,\"humidityPct\":65.4}],"
                + "\"units\":{\"et0Mm\":\"mm per season\"},"
                + "\"source\":{\"name\":\"NASA POWER (MERRA-2, CERES)\",\"url\":\"https://example.test/power\","
                + "\"resolutionKm\":50,\"variables\":[\"GWETROOT\"]},\"note\":\"Regional values\"";
    }

    /**
     * Creates representative soil properties, without the surrounding braces.
     *
     * @return soil JSON members
     */
    private static String soilJson() {
        String props = "{\"ph\":6.82,\"clayPct\":16.45,\"sandPct\":52.31,\"siltPct\":31.24,"
                + "\"organicCarbonGPerKg\":28.7,\"nitrogenGPerKg\":2.76,\"bulkDensityGPerCm3\":1.48,\"cecCmolPerKg\":17.05}";
        return "\"topsoil\":" + props + ","
                + "\"derived\":{\"textureClass\":\"Sandy loam\",\"organicMatterPct\":4.9,\"phClass\":\"Neutral\","
                + "\"availableWaterMm\":40},"
                + "\"depths\":{\"0-5cm\":" + props + ",\"5-15cm\":" + props + ",\"15-30cm\":" + props + "},"
                + "\"units\":{\"ph\":\"pH\",\"availableWaterMm\":\"mm in 0-30 cm\"},"
                + "\"source\":{\"name\":\"ISRIC SoilGrids 2.0\",\"url\":\"https://soilgrids.org\","
                + "\"resolutionM\":250,\"license\":\"CC BY 4.0\"},\"note\":\"Estimates, not a soil test\"";
    }

    /**
     * Creates a representative greenness season, without the surrounding braces.
     *
     * @return greenness JSON members
     */
    private static String greennessJson() {
        return "\"year\":2024,"
                + "\"period\":{\"start\":\"2024-04-01\",\"end\":\"2024-11-15\"},"
                + "\"observations\":[{\"date\":\"2024-07-14\",\"ndvi\":0.77,\"ndviP10\":0.66,"
                + "\"ndviP90\":0.84,\"clearShare\":1.0}],"
                + "\"summary\":{\"peakNdvi\":0.77,\"peakDate\":\"2024-07-14\",\"latestNdvi\":0.77,"
                + "\"latestDate\":\"2024-07-14\",\"greenUpDate\":null},"
                + "\"scenesChecked\":22,\"scenesUnreadable\":0,"
                + "\"source\":{\"name\":\"Copernicus Sentinel-2 L2A via Earth Search\","
                + "\"collection\":\"sentinel-2-c1-l2a\",\"url\":\"https://example.test/stac\","
                + "\"resolutionM\":10,\"license\":\"Copernicus Sentinel data, free and open\"},"
                + "\"note\":\"Mean NDVI over the clear part of the field\"";
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
                + "\"isCrop\":true,\"group\":\"soybeans\",\"groupLabel\":\"Soybeans\",\"share\":0.967,\"cropShare\":0.996,\"classes\":[{\"code\":158,"
                + "\"name\":\"Soybeans\",\"isCrop\":true,\"share\":0.967}]}],"
                + "\"rotation\":[\"Soybeans\"],\"distinctCrops\":[\"Soybeans\"],"
                + "\"cropYears\":1,\"unavailableYears\":[],"
                + "\"summary\":{\"yearsOfData\":15,\"firstYear\":2011,"
                + "\"lastSeason\":{\"year\":2025,\"crop\":\"Soybeans\",\"group\":\"soybeans\",\"groupLabel\":\"Soybeans\"},"
                + "\"pattern\":{\"kind\":\"rotation\",\"periodYears\":3,\"groups\":[\"corn\",\"soybeans\"],"
                + "\"text\":\"A 3-year rotation: corn, corn, then soybeans.\"},"
                + "\"expectedThisYear\":{\"year\":2026,\"group\":\"corn\",\"crop\":\"Corn\",\"confidence\":\"high\","
                + "\"reason\":\"Soybeans in 2025, so the pattern points to corn this year.\"},"
                + "\"flags\":{\"possiblyMerged\":false,\"mostlyNotCropland\":false},"
                + "\"notes\":[\"The newest crop map is 2025 (last season); 2026 is not mapped yet.\"]},"
                + "\"note\":\"Satellite classification\"}";
    }

    /**
     * Returns a representative SMAP response used by the local data API stub.
     *
     * @return a one-cell soil-moisture GeoJSON response
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
