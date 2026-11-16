package org.greenalienhouseparty.chat.tools;

import java.util.Locale;
import java.util.Optional;

import org.greenalienhouseparty.chat.ChatToolContext;
import org.greenalienhouseparty.dto.ChatRequest;
import org.greenalienhouseparty.dto.FieldFeature;
import org.greenalienhouseparty.dto.FieldSummaryResponse;
import org.greenalienhouseparty.dto.FieldsResponse;
import org.greenalienhouseparty.service.DataBackendService;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Works out where a chat question is about, and finds field outlines, for the assistant's tools.
 *
 * <p>A location comes from, in order: coordinates the model passed, the pin or searched location the app
 * sent, the selected field's outline sent by the app, and finally the field's centre from the data backend.
 * An outline comes from the app when it sent one, and otherwise from the stored field boundaries around the
 * field's centre.</p>
 */
@Component
public class FieldLocator {

    /** Half-width in degrees of the box searched for a field's outline around its centre. */
    private static final double OUTLINE_SEARCH_DEGREES = 0.02;

    /** Most outlines read when searching for one field. */
    private static final int OUTLINE_SEARCH_LIMIT = 500;

    private final DataBackendService dataBackend;

    /**
     * Creates the locator.
     *
     * @param dataBackend access to the Python data backend
     */
    public FieldLocator(DataBackendService dataBackend) {
        this.dataBackend = dataBackend;
    }

    /**
     * A point on the map.
     *
     * @param lat    latitude in degrees
     * @param lon    longitude in degrees
     * @param source where the point came from, for the model's explanation
     */
    public record Point(double lat, double lon, String source) {
    }

    /**
     * Decides which point a location question is about.
     *
     * @param chat the chat context
     * @param lat  latitude the model passed, or {@code null}
     * @param lon  longitude the model passed, or {@code null}
     * @return the point to use
     * @throws IllegalArgumentException when no location is known; the message tells the model to ask
     */
    public Point locate(ChatToolContext chat, Double lat, Double lon) {
        if (lat != null && lon != null) {
            return validated(lat, lon, "given coordinates");
        }
        ChatRequest.Location location = chat.context().location();
        if (location != null && location.lat() != null && location.lon() != null) {
            return validated(location.lat(), location.lon(), "location selected in the app");
        }
        String fieldId;
        try {
            fieldId = chat.resolveFieldId(null);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("No location is known. Ask the farmer to drop a pin, search for a "
                    + "place or select a field, or pass lat and lon.");
        }
        Optional<Point> fromOutline = chat.field(fieldId).map(ChatRequest.FieldContext::geometry).flatMap(FieldLocator::centre);
        if (fromOutline.isPresent()) {
            return fromOutline.get();
        }
        return fieldCentre(fieldId);
    }

    /**
     * Finds a stored field's centre through the data backend's climate summary.
     *
     * @param fieldId field identifier
     * @return the field's centre
     * @throws IllegalArgumentException when the centre is not known
     */
    public Point fieldCentre(String fieldId) {
        FieldSummaryResponse summary = dataBackend.fieldSummary(fieldId, null);
        FieldSummaryResponse.Centroid centre = summary == null || summary.field() == null ? null : summary.field().centroid();
        if (centre == null || centre.lat() == null || centre.lon() == null) {
            throw new IllegalArgumentException("The centre of field " + fieldId + " is not known.");
        }
        return new Point(centre.lat(), centre.lon(), "centre of field " + fieldId);
    }

    /**
     * Finds a field's outline: the one the app sent, otherwise the stored boundary around the field's centre.
     *
     * @param chat    the chat context
     * @param fieldId field identifier
     * @return the GeoJSON outline
     * @throws IllegalArgumentException when the outline cannot be found
     */
    public JsonNode outline(ChatToolContext chat, String fieldId) {
        Optional<JsonNode> sent = chat.field(fieldId).map(ChatRequest.FieldContext::geometry)
                .filter(geometry -> geometry != null && geometry.isObject());
        if (sent.isPresent()) {
            return sent.get();
        }
        Point centre = fieldCentre(fieldId);
        FieldsResponse page = dataBackend.listFields(bbox(centre, OUTLINE_SEARCH_DEGREES), OUTLINE_SEARCH_LIMIT, 0);
        if (page != null && page.features() != null) {
            for (FieldFeature feature : page.features()) {
                boolean matches = fieldId.equals(feature.id())
                        || (feature.properties() != null && fieldId.equals(feature.properties().getId()));
                if (matches && feature.geometry() != null) {
                    return feature.geometry();
                }
            }
        }
        throw new IllegalArgumentException("The outline of field " + fieldId + " could not be found.");
    }

    /**
     * Builds a {@code west,south,east,north} box around a point.
     *
     * @param centre  the point
     * @param degrees half-width in degrees
     * @return the box in the format {@code GET /fields} expects
     */
    public static String bbox(Point centre, double degrees) {
        return String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f",
                centre.lon() - degrees, centre.lat() - degrees, centre.lon() + degrees, centre.lat() + degrees);
    }

    /**
     * Estimates an outline's centre as the mean of its outer ring's corners, which is close enough to look
     * up location-based data.
     *
     * @param geometry GeoJSON Polygon or MultiPolygon
     * @return the centre, when the outline can be read
     */
    static Optional<Point> centre(JsonNode geometry) {
        if (geometry == null || !geometry.isObject()) {
            return Optional.empty();
        }
        JsonNode coordinates = geometry.get("coordinates");
        String type = geometry.path("type").asString("");
        JsonNode ring = switch (type) {
            case "Polygon" -> coordinates == null ? null : coordinates.get(0);
            case "MultiPolygon" -> coordinates == null || coordinates.get(0) == null ? null : coordinates.get(0).get(0);
            default -> null;
        };
        if (ring == null || !ring.isArray() || ring.isEmpty()) {
            return Optional.empty();
        }
        double lonTotal = 0;
        double latTotal = 0;
        int corners = 0;
        for (JsonNode corner : ring) {
            if (corner.isArray() && corner.size() >= 2) {
                lonTotal += corner.get(0).asDouble();
                latTotal += corner.get(1).asDouble();
                corners++;
            }
        }
        return corners == 0 ? Optional.empty()
                : Optional.of(new Point(latTotal / corners, lonTotal / corners, "outline of the selected field"));
    }

    /**
     * Checks that coordinates are on the globe.
     *
     * @param lat    latitude
     * @param lon    longitude
     * @param source where they came from
     * @return the point
     * @throws IllegalArgumentException when out of range
     */
    private static Point validated(double lat, double lon, String source) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Latitude must be -90 to 90 and longitude -180 to 180.");
        }
        return new Point(lat, lon, source);
    }
}
