package org.greenalienhouseparty.chat.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The assistant's reference tools: plain-language explanations of scientific terms, where the app's data
 * comes from and how far it can be trusted, and today's date.
 *
 * <p>Explanations and source descriptions are written once in {@code chat/glossary.json} and
 * {@code chat/data-sources.json} so the assistant explains terms the same way every time instead of
 * improvising.</p>
 */
@Component
public class KnowledgeTools {

    static final String GLOSSARY = "chat/glossary.json";

    static final String DATA_SOURCES = "chat/data-sources.json";

    private final ArrayNode terms;

    private final ArrayNode sources;

    private final Clock clock;

    private final ObjectMapper objectMapper;

    /**
     * Creates the tools using the system clock.
     *
     * @param objectMapper reads the reference files
     */
    @Autowired
    public KnowledgeTools(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemDefaultZone());
    }

    /**
     * Creates the tools with an explicit clock for repeatable testing.
     *
     * @param objectMapper reads the reference files
     * @param clock        source of today's date
     */
    KnowledgeTools(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.terms = (ArrayNode) read(objectMapper, GLOSSARY).get("terms");
        this.sources = (ArrayNode) read(objectMapper, DATA_SOURCES).get("sources");
    }

    /**
     * Explains a scientific or farming term in plain language.
     *
     * @param term the term, for example "NDVI" or "evapotranspiration"
     * @return the explanation and why it matters, or the list of known terms
     */
    @Tool(name = "explainTerm", description = """
            Plain-language explanation of a scientific or farming term used in this app, and why it matters \
            to a farmer: for example NDVI, evapotranspiration, ET0, soil moisture anomaly, water balance, \
            GRACE groundwater, frost risk, heat units, land capability, drainage, organic matter, nitrogen \
            fixation, cover crops, deep-rooting crops, crop rotation, erosion, seasonal outlook, and how \
            satellite data differs from ground measurements. Prefer this over your own definitions.""")
    public JsonNode explainTerm(@ToolParam(description = "The term to explain") String term) {
        String wanted = normalize(term);
        JsonNode best = null;
        for (JsonNode entry : terms) {
            if (matches(entry, wanted)) {
                best = entry;
                break;
            }
        }
        if (best == null) {
            for (JsonNode entry : terms) {
                if (!wanted.isEmpty() && normalize(entry.path("term").asString("")).contains(wanted)) {
                    best = entry;
                    break;
                }
            }
        }
        if (best != null) {
            return best;
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("found", false);
        ArrayNode known = result.putArray("knownTerms");
        terms.forEach(entry -> known.add(entry.path("term").asString("")));
        result.put("note", "Not in the app's glossary. If you explain it yourself, keep it simple and say it is a "
                + "general explanation.");
        return result;
    }

    /**
     * Describes the app's data sources, optionally only those used for one topic.
     *
     * @param topic a topic such as "soil" or "drought", or {@code null} for every source
     * @return what each source measures, its resolution, what it is used for and its limitations
     */
    @Tool(name = "getDataSources", description = """
            Where this app's data comes from: for each source (NASA POWER, SRTM, MODIS, GRACE, Sentinel-2/HLS, \
            SoilGrids, AAFC crop maps and soil landscapes, crop calendar, Environment Canada outlook, Statistics \
            Canada, FAO ECOCROP and the app's own estimates) what it measures, its resolution, what it is used \
            for and its limitations. Use it to cite sources and explain how much to trust a number.""")
    public JsonNode getDataSources(
            @ToolParam(description = "Optional topic such as 'soil', 'rainfall', 'drought', 'crop history'",
                    required = false) String topic) {
        if (topic == null || topic.isBlank()) {
            return sources;
        }
        String wanted = normalize(topic);
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode source : sources) {
            if (normalize(source.toString()).contains(wanted)) {
                matching.add(source);
            }
        }
        return matching.isEmpty() ? sources : objectMapper.valueToTree(matching);
    }

    /**
     * Returns today's date and season.
     *
     * @return the date, weekday, month, northern-hemisphere season and first year a new plan would cover
     */
    @Tool(name = "getCurrentDate", description = """
            Today's date, weekday, month and season (northern hemisphere), and the first year a new rotation \
            plan would cover. Use it for anything that depends on timing, such as whether it is too late to \
            plant or when the next season starts.""")
    public JsonNode getCurrentDate() {
        LocalDate today = LocalDate.now(clock);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("date", today.toString());
        result.put("weekday", today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        result.put("month", today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        result.put("season", season(today.getMonthValue()));
        result.put("firstPlanYear", today.getYear() + 1);
        return result;
    }

    /**
     * Names the northern-hemisphere season of a month.
     *
     * @param month 1 to 12
     * @return winter, spring, summer or fall
     */
    static String season(int month) {
        return switch (month) {
            case 12, 1, 2 -> "winter";
            case 3, 4, 5 -> "spring";
            case 6, 7, 8 -> "summer";
            default -> "fall";
        };
    }

    /**
     * Checks a glossary entry's id and aliases against a term.
     *
     * @param entry  glossary entry
     * @param wanted normalized term
     * @return whether it matches
     */
    private static boolean matches(JsonNode entry, String wanted) {
        if (normalize(entry.path("id").asString("")).equals(wanted)) {
            return true;
        }
        for (JsonNode alias : entry.path("aliases")) {
            if (normalize(alias.asString("")).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lower-cases a term and turns punctuation into spaces.
     *
     * @param text the text
     * @return the normalized text
     */
    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /**
     * Reads a JSON resource from the class path.
     *
     * @param objectMapper JSON reader
     * @param path         resource path
     * @return the parsed JSON
     */
    private static JsonNode read(ObjectMapper objectMapper, String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + path, exception);
        }
    }
}
