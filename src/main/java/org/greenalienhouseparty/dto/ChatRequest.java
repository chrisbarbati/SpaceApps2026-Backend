package org.greenalienhouseparty.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * A farmer's chat message to the assistant, sent to {@code POST /chat/stream} or {@code POST /chat}.
 *
 * <p>The frontend sends what is on screen as a structured {@link Context} rather than pasting it into the
 * message. The backend writes the context into the model's instructions and makes it available to the
 * assistant's tools, so a question such as "What can you tell me about this field?" is answered for the
 * selected field without the model having to know or guess its identifier.</p>
 *
 * @param conversationId identifier of an earlier conversation to continue, or {@code null} to start a new one;
 *                       the identifier to use next time is returned in the first event of the reply
 * @param message        the farmer's question or request, in their own words
 * @param context        what the farmer is looking at, or {@code null} when nothing is selected
 */
@Schema(description = "A chat message plus what the farmer is looking at in the app.")
public record ChatRequest(
        @Schema(description = "Conversation to continue; omit to start a new one", example = "8d0c6f8e-2f7a-4b4e-9b8e-3f9e3a1c2d10")
        String conversationId,
        @Schema(description = "The farmer's question or request", example = "What can you tell me about this field?",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String message,
        @Schema(description = "What the farmer is looking at; used by the assistant's tools")
        Context context) {

    /**
     * What the farmer is looking at in the app when they send the message.
     *
     * @param selectedFieldId the field the farmer has selected, used when a question says "this field"
     * @param fields          every field the farmer has pinned or selected, with what the app knows about each
     * @param location        a point the farmer dropped or searched for, when no field covers it
     * @param provinceCode    two-letter Canadian province code for market data, for example {@code ON}
     * @param growableCrops   crop ids or names the farmer has said they can grow
     */
    @Schema(description = "What the farmer is looking at in the app.")
    public record Context(
            @Schema(description = "Selected field; used when the farmer says \"this field\"", example = "ON-12345")
            String selectedFieldId,
            @Schema(description = "Fields the farmer has pinned or selected")
            List<FieldContext> fields,
            @Schema(description = "A dropped pin or searched location")
            Location location,
            @Schema(description = "Two-letter province code for market prices", example = "ON")
            String provinceCode,
            @Schema(description = "Crop ids or names the farmer can grow", example = "[\"corn\", \"soybeans\", \"wheat\"]")
            List<String> growableCrops) {
    }

    /**
     * One field the farmer has pinned or selected.
     *
     * @param fieldId     stored field identifier from {@code GET /fields}
     * @param name        the farmer's own name for the field, if any
     * @param currentCrop crop growing in the field now, as chosen in the app
     * @param geometry    GeoJSON Polygon or MultiPolygon outline, when the app has it; needed to propose a
     *                    rotation plan, and looked up from the field data when omitted
     */
    @Schema(description = "A field the farmer has pinned or selected.")
    public record FieldContext(
            @Schema(description = "Stored field identifier from /fields", example = "ON-12345") String fieldId,
            @Schema(description = "The farmer's name for the field", example = "North 40") String name,
            @Schema(description = "Crop growing in the field now", example = "corn") String currentCrop,
            @Schema(description = "GeoJSON Polygon or MultiPolygon outline") JsonNode geometry) {
    }

    /**
     * A point on the map.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     */
    @Schema(description = "A point on the map.")
    public record Location(
            @Schema(example = "44.35") Double lat,
            @Schema(example = "-79.16") Double lon) {
    }
}
