package org.greenalienhouseparty.chat.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.greenalienhouseparty.chat.ChatEventSink;
import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.dto.ChatRequest;
import org.greenalienhouseparty.dto.CropMarketResponse;
import org.greenalienhouseparty.dto.DailyPointProperties;
import org.greenalienhouseparty.dto.DailyPointResponse;
import org.greenalienhouseparty.dto.FieldDetailsResponse;
import org.greenalienhouseparty.dto.FieldFeature;
import org.greenalienhouseparty.dto.FieldProperties;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.landscape.SoilLandscapeService;
import org.greenalienhouseparty.market.CropMarketService;
import org.greenalienhouseparty.outlook.SeasonalOutlookService;
import org.greenalienhouseparty.planning.ReferenceCropRepository;
import org.greenalienhouseparty.service.DataBackendService;
import org.greenalienhouseparty.service.FieldDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Unit tests for the assistant's data, location, crop and reference tools and their helpers.
 */
class ChatToolsUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DataBackendService dataBackend = mock(DataBackendService.class);

    private final ToolResults results = new ToolResults(JSON);

    @Test
    void compactsResponsesByDroppingSeriesAndKeepingRecentItems() {
        Map<String, Object> value = Map.of(
                "series", List.of(1, 2, 3, 4, 5),
                "nested", List.of(Map.of("keep", 1, "drop", 2)),
                "summary", Map.of("peak", 0.8));

        JsonNode compact = results.compact(value, 2, "nested.drop");

        assertThat(compact.get("series").toString()).isEqualTo("[4,5]");
        assertThat(compact.get("seriesOmitted").asInt()).isEqualTo(3);
        assertThat(compact.get("nested").get(0).has("drop")).isFalse();
        assertThat(compact.get("nested").get(0).get("keep").asInt()).isEqualTo(1);
        assertThat(compact.get("summary").get("peak").asDouble()).isEqualTo(0.8);
        assertThat(results.compact(null, 2).isNull()).isTrue();
    }

    @Test
    void usesTheSelectedFieldAndAsksWhenNoneIsKnown() {
        ChatToolContext selected = ChatToolContext.from(context("field-1", List.of(), null));
        ChatToolContext onlyOne = ChatToolContext.from(context(null, List.of(field("field-2", null)), null));
        ChatToolContext several = ChatToolContext.from(context(null, List.of(field("a", null), field("b", null)), null));
        ChatToolContext outside = ChatToolContext.from(null);

        assertThat(selected.resolveFieldId(null)).isEqualTo("field-1");
        assertThat(selected.resolveFieldId(" other ")).isEqualTo("other");
        assertThat(onlyOne.resolveFieldId("")).isEqualTo("field-2");
        assertThatThrownBy(() -> several.resolveFieldId(null)).hasMessageContaining("[a, b]");
        assertThatThrownBy(() -> outside.resolveFieldId(null)).hasMessageContaining("No field is selected");
    }

    @Test
    void readsFieldOverviewsForTheSelectedFieldWithoutLongSeries() {
        FieldDetailsService details = mock(FieldDetailsService.class);
        when(details.details("field-1", null)).thenReturn(new FieldDetailsResponse("field-1", 12.5, 30.9,
                new FieldDetailsResponse.Location(44.6, -79.4, "climate"), null, null, null, null, null, null, null,
                null, null, null, Map.of("soil", new FieldDetailsResponse.Section(FieldDetailsResponse.Section.OK, null))));
        FieldDataTools tools = new FieldDataTools(details, dataBackend, results);

        JsonNode overview = tools.getFieldOverview(null, context("field-1", List.of(), null));

        assertThat(overview.get("id").asString()).isEqualTo("field-1");
        assertThat(overview.get("hectares").asDouble()).isEqualTo(12.5);
        assertThat(overview.get("sections").get("soil").get("status").asString()).isEqualTo("ok");
    }

    @Test
    void locatesQuestionsFromCoordinatesPinOrFieldOutline() {
        FieldLocator locator = new FieldLocator(dataBackend);
        JsonNode outline = JSON.readTree("{\"type\":\"Polygon\",\"coordinates\":[[[-80,44],[-78,44],[-78,46],[-80,46]]]}");

        assertThat(locator.locate(ChatToolContext.from(null), 10.0, 20.0).source()).isEqualTo("given coordinates");
        FieldLocator.Point pin = locator.locate(ChatToolContext.from(
                context(null, List.of(), new ChatRequest.Location(44.35, -79.16))), null, null);
        assertThat(pin.lat()).isEqualTo(44.35);
        FieldLocator.Point centre = locator.locate(ChatToolContext.from(
                context("f", List.of(field("f", outline)), null)), null, null);
        assertThat(centre.lat()).isEqualTo(45.0);
        assertThat(centre.lon()).isEqualTo(-79.0);
        assertThatThrownBy(() -> locator.locate(ChatToolContext.from(null), null, null))
                .hasMessageContaining("No location is known");
        assertThatThrownBy(() -> locator.locate(ChatToolContext.from(null), 95.0, 0.0))
                .hasMessageContaining("Latitude must be");
    }

    @Test
    void findsTheStoredOutlineOfAFieldTheAppDidNotSend() {
        FieldLocator locator = new FieldLocator(dataBackend);
        when(dataBackend.fieldSummary("f", null)).thenReturn(JSON.readValue(
                "{\"field\":{\"centroid\":{\"lat\":44.6,\"lon\":-79.4}}}", org.greenalienhouseparty.dto.FieldSummaryResponse.class));
        JsonNode geometry = JSON.readTree("{\"type\":\"Polygon\",\"coordinates\":[]}");
        when(dataBackend.listFields(anyString(), anyInt(), eq(0))).thenReturn(new FieldsResponse("FeatureCollection",
                List.of(new FieldFeature("Feature", "other", null, null, null), new FieldFeature("Feature", "f", null, geometry, null)),
                2, false, null, null));

        assertThat(locator.outline(ChatToolContext.from(null), "f")).isEqualTo(geometry);
        verify(dataBackend).listFields("-79.42000,44.58000,-79.38000,44.62000", 500, 0);
    }

    @Test
    void summarizesDailyWeatherPerParameter() {
        DailyPointProperties properties = new DailyPointProperties();
        Map<String, JsonNode> rain = new LinkedHashMap<>();
        rain.put("20260102", JsonNodeFactory.instance.numberNode(4.0));
        rain.put("20260101", JsonNodeFactory.instance.numberNode(2.0));
        rain.put("20260103", JsonNodeFactory.instance.numberNode(-999.0));
        properties.setParameter(Map.of("PRECTOTCORR", rain));
        DailyPointResponse response = new DailyPointResponse();
        response.setProperties(properties);
        when(dataBackend.dailyPoint(44.0, -79.0, "20260101", "20260103", "T2M,PRECTOTCORR")).thenReturn(response);
        LocationTools tools = new LocationTools(mock(SoilLandscapeService.class), mock(SeasonalOutlookService.class),
                dataBackend, new FieldLocator(dataBackend), results);

        JsonNode summary = tools.getDailyWeather("2026-01-01", "20260103", null, 44.0, -79.0, null)
                .get("parameters").get("PRECTOTCORR");

        assertThat(summary.get("days").asInt()).isEqualTo(2);
        assertThat(summary.get("missingDays").asInt()).isEqualTo(1);
        assertThat(summary.get("total").asDouble()).isEqualTo(6.0);
        assertThat(summary.get("mean").asDouble()).isEqualTo(3.0);
        assertThat(summary.get("lastDays").get(0).get("date").asString()).isEqualTo("20260101");
        assertThat(summary.get("lastDays").get(2).get("value").isNull()).isTrue();
        assertThatThrownBy(() -> tools.getDailyWeather("2026-01-01", "2027-06-01", null, 44.0, -79.0, null))
                .hasMessageContaining("at most");
        assertThatThrownBy(() -> tools.getDailyWeather("yesterday", "2026-01-01", null, 44.0, -79.0, null))
                .hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    void listsNearbyFieldsNearestFirst() {
        FieldProperties near = properties("near", List.of(-79.0, 44.0), 5.0);
        FieldProperties far = properties("far", List.of(-79.0, 44.005), 7.0);
        when(dataBackend.listFields(anyString(), eq(200), eq(0))).thenReturn(new FieldsResponse("FeatureCollection",
                List.of(new FieldFeature("Feature", "far", far, null, null), new FieldFeature("Feature", "near", near, null, null)),
                2, false, null, null));
        LocationTools tools = new LocationTools(mock(SoilLandscapeService.class), mock(SeasonalOutlookService.class),
                dataBackend, new FieldLocator(dataBackend), results);

        JsonNode found = tools.findFieldsNear(null, 44.0, -79.0, null);

        assertThat(found.get("fieldsFound").asInt()).isEqualTo(2);
        assertThat(found.get("fields").get(0).get("id").asString()).isEqualTo("near");
        assertThat(found.get("fields").get(1).get("distanceKm").asDouble()).isBetween(0.5, 0.6);
    }

    @Test
    void describesCropsAndTheirSoilRoles() {
        CropMarketService market = mock(CropMarketService.class);
        when(market.market(anyString())).thenReturn(new CropMarketResponse("QC", "Quebec", List.of(), null, null));
        CropTools tools = new CropTools(new ReferenceCropRepository(JSON), dataBackend, market, results);

        JsonNode crops = tools.listCrops().get("crops");
        JsonNode alfalfa = tools.getCropProfile("Alfalfa");

        assertThat(crops.findValuesAsString("id")).contains("corn", "soybeans", "alfalfa");
        assertThat(alfalfa.get("crop").get("id").asString()).isEqualTo("alfalfa");
        assertThat(alfalfa.get("soilRoles").get("fixesNitrogen").asBoolean()).isTrue();
        assertThat(alfalfa.get("soilRoles").get("roles").toString()).contains("nitrogen-fixing", "perennial", "deep-rooting");
        assertThatThrownBy(() -> tools.getCropProfile("rice")).hasMessageContaining("Unknown crop");

        tools.getMarketPrices(null, new ToolContext(ChatToolContext.toMap(
                new ChatRequest.Context(null, List.of(), null, "qc", List.of()), new ChatEventSink(), "c")));
        verify(market).market("QC");
        tools.getMarketPrices(null, new ToolContext(Map.of()));
        verify(market).market(CropTools.DEFAULT_PROVINCE);
    }

    @Test
    void explainsTermsFromTheGlossaryAndListsSources() {
        KnowledgeTools tools = new KnowledgeTools(JSON, Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC));

        assertThat(tools.explainTerm("ndvi").get("id").asString()).isEqualTo("ndvi");
        assertThat(tools.explainTerm("Evapotranspiration").get("id").asString()).isEqualTo("evapotranspiration");
        assertThat(tools.explainTerm("GRACE-FO").get("id").asString()).isEqualTo("groundwater");
        JsonNode unknown = tools.explainTerm("quantum farming");
        assertThat(unknown.get("found").asBoolean()).isFalse();
        assertThat(unknown.get("knownTerms").size()).isGreaterThan(10);

        assertThat(tools.getDataSources(null).size()).isGreaterThan(10);
        assertThat(tools.getDataSources("groundwater").findValuesAsString("id")).containsExactly("nasa-grace");

        JsonNode today = tools.getCurrentDate();
        assertThat(today.get("date").asString()).isEqualTo("2026-11-15");
        assertThat(today.get("season").asString()).isEqualTo("fall");
        assertThat(today.get("firstPlanYear").asInt()).isEqualTo(2027);
    }

    @Test
    void measuresDistances() {
        assertThat(LocationTools.distanceKm(44.0, -79.0, 45.0, -79.0)).isBetween(111.0, 111.4);
    }

    /**
     * Builds a tool context.
     */
    private static ToolContext context(String selected, List<ChatRequest.FieldContext> fields, ChatRequest.Location location) {
        return new ToolContext(ChatToolContext.toMap(new ChatRequest.Context(selected, fields, location, null, List.of()),
                new ChatEventSink(), "c-1"));
    }

    /**
     * Builds a field the app sent.
     */
    private static ChatRequest.FieldContext field(String id, JsonNode outline) {
        return new ChatRequest.FieldContext(id, null, null, outline);
    }

    /**
     * Builds stored field properties.
     */
    private static FieldProperties properties(String id, List<Double> centroid, double areaHa) {
        FieldProperties properties = new FieldProperties();
        properties.setId(id);
        properties.setCentroid(centroid);
        properties.setAreaHa(areaHa);
        return properties;
    }
}
