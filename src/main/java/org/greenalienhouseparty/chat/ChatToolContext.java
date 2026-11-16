package org.greenalienhouseparty.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.greenalienhouseparty.dto.ChatEvent;
import org.greenalienhouseparty.dto.ChatRequest;
import org.springframework.ai.chat.model.ToolContext;

/**
 * What the assistant's tools know about the message being answered, read from Spring AI's
 * {@link ToolContext}.
 *
 * <p>The tool context travels with the request to each tool call but is never sent to the model. It carries
 * the farmer's on-screen {@link ChatRequest.Context}, the {@link ChatEventSink} for progress and proposal
 * events, and the conversation identifier. Tools use it to fill in what the model leaves out, most
 * importantly the selected field when the farmer says "this field".</p>
 *
 * @param context        what the farmer is looking at; never {@code null}
 * @param events         where to send progress and proposal events, or {@code null} outside a chat
 * @param conversationId the conversation being answered, or {@code null} outside a chat
 */
public record ChatToolContext(ChatRequest.Context context, ChatEventSink events, String conversationId) {

    /** Tool context key of the farmer's on-screen context. */
    static final String CONTEXT_KEY = "chat.context";

    /** Tool context key of the event sink. */
    static final String EVENTS_KEY = "chat.events";

    /** Tool context key of the conversation identifier. */
    static final String CONVERSATION_KEY = "chat.conversationId";

    private static final ChatRequest.Context EMPTY = new ChatRequest.Context(null, List.of(), null, null, List.of());

    /**
     * Creates the context, replacing a missing on-screen context with an empty one.
     */
    public ChatToolContext {
        context = context == null ? EMPTY : context;
    }

    /**
     * Builds the map passed to {@code ChatClient...toolContext(...)}.
     *
     * @param context        what the farmer is looking at, or {@code null}
     * @param events         where tools send progress and proposal events
     * @param conversationId the conversation being answered
     * @return the tool context map
     */
    public static Map<String, Object> toMap(ChatRequest.Context context, ChatEventSink events, String conversationId) {
        return Map.of(
                CONTEXT_KEY, context == null ? EMPTY : context,
                EVENTS_KEY, events,
                CONVERSATION_KEY, conversationId);
    }

    /**
     * Reads the chat context from a tool call's context.
     *
     * @param toolContext the tool context Spring AI passed to the tool, or {@code null}
     * @return the chat context; empty when the tool is called outside a chat
     */
    public static ChatToolContext from(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        return new ChatToolContext(
                values.get(CONTEXT_KEY) instanceof ChatRequest.Context context ? context : null,
                values.get(EVENTS_KEY) instanceof ChatEventSink events ? events : null,
                values.get(CONVERSATION_KEY) instanceof String id ? id : null);
    }

    /**
     * Decides which field a tool call is about: the field the model named, otherwise the selected field,
     * otherwise the only field on screen.
     *
     * @param requested the field identifier the model passed, or {@code null}/blank
     * @return the field identifier to use
     * @throws IllegalArgumentException when no field can be determined; the message tells the model to ask
     */
    public String resolveFieldId(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        if (context.selectedFieldId() != null && !context.selectedFieldId().isBlank()) {
            return context.selectedFieldId().strip();
        }
        List<ChatRequest.FieldContext> fields = fields();
        if (fields.size() == 1 && fields.getFirst().fieldId() != null) {
            return fields.getFirst().fieldId();
        }
        throw new IllegalArgumentException(fields.isEmpty()
                ? "No field is selected in the app. Ask the farmer to select or drop a pin on a field first."
                : "Several fields are on screen and none is selected. Ask the farmer which field they mean, or "
                        + "pass one of these field ids: " + fields.stream().map(ChatRequest.FieldContext::fieldId).toList());
    }

    /**
     * Returns the fields on screen.
     *
     * @return the fields; never {@code null}
     */
    public List<ChatRequest.FieldContext> fields() {
        return context.fields() == null ? List.of() : context.fields();
    }

    /**
     * Finds what the app sent about one field.
     *
     * @param fieldId the field identifier
     * @return the field's on-screen details, when the app sent any
     */
    public Optional<ChatRequest.FieldContext> field(String fieldId) {
        return fields().stream().filter(field -> fieldId.equals(field.fieldId())).findFirst();
    }

    /**
     * Returns the crops the farmer said they can grow.
     *
     * @return crop ids or names; never {@code null}
     */
    public List<String> growableCrops() {
        return context.growableCrops() == null ? List.of() : context.growableCrops();
    }

    /**
     * Sends an event to the reply stream when the tool runs inside a chat.
     *
     * @param event the event
     */
    public void emit(ChatEvent event) {
        if (events != null) {
            events.emit(event);
        }
    }
}
