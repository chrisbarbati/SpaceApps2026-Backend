package org.greenalienhouseparty.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * One event in the assistant's streamed reply from {@code POST /chat/stream}.
 *
 * <p>Each event is sent as a server-sent event whose {@code event:} name is {@link #eventName()} and whose
 * {@code data:} is this object as JSON, including a {@code type} property with the same name. A reply always
 * starts with {@link Meta} and ends with {@link Done} or {@link Error}. In between, {@link Token} events carry
 * the answer text as it is written, {@link ToolCall} and {@link ToolResult} report data lookups so the app can
 * show progress, {@link Proposal} carries a rotation plan for the farmer to accept or reject, and
 * {@link Model} and {@link Fallback} say which AI model is answering.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChatEvent.Meta.class, name = ChatEvent.META),
        @JsonSubTypes.Type(value = ChatEvent.Model.class, name = ChatEvent.MODEL),
        @JsonSubTypes.Type(value = ChatEvent.Fallback.class, name = ChatEvent.FALLBACK),
        @JsonSubTypes.Type(value = ChatEvent.Token.class, name = ChatEvent.TOKEN),
        @JsonSubTypes.Type(value = ChatEvent.ToolCall.class, name = ChatEvent.TOOL_CALL),
        @JsonSubTypes.Type(value = ChatEvent.ToolResult.class, name = ChatEvent.TOOL_RESULT),
        @JsonSubTypes.Type(value = ChatEvent.Proposal.class, name = ChatEvent.PROPOSAL),
        @JsonSubTypes.Type(value = ChatEvent.Error.class, name = ChatEvent.ERROR),
        @JsonSubTypes.Type(value = ChatEvent.Done.class, name = ChatEvent.DONE)
})
@Schema(description = "One event in a streamed chat reply; the type property names the event.",
        oneOf = {ChatEvent.Meta.class, ChatEvent.Model.class, ChatEvent.Fallback.class, ChatEvent.Token.class,
                ChatEvent.ToolCall.class, ChatEvent.ToolResult.class, ChatEvent.Proposal.class,
                ChatEvent.Error.class, ChatEvent.Done.class})
public sealed interface ChatEvent {

    /** Event name of {@link Meta}. */
    String META = "meta";

    /** Event name of {@link Model}. */
    String MODEL = "model";

    /** Event name of {@link Fallback}. */
    String FALLBACK = "fallback";

    /** Event name of {@link Token}. */
    String TOKEN = "token";

    /** Event name of {@link ToolCall}. */
    String TOOL_CALL = "tool_call";

    /** Event name of {@link ToolResult}. */
    String TOOL_RESULT = "tool_result";

    /** Event name of {@link Proposal}. */
    String PROPOSAL = "proposal";

    /** Event name of {@link Error}. */
    String ERROR = "error";

    /** Event name of {@link Done}. */
    String DONE = "done";

    /**
     * Returns the server-sent event name for this event.
     *
     * @return one of the event name constants
     */
    @JsonIgnore
    default String eventName() {
        return switch (this) {
            case Meta ignored -> META;
            case Model ignored -> MODEL;
            case Fallback ignored -> FALLBACK;
            case Token ignored -> TOKEN;
            case ToolCall ignored -> TOOL_CALL;
            case ToolResult ignored -> TOOL_RESULT;
            case Proposal ignored -> PROPOSAL;
            case Error ignored -> ERROR;
            case Done ignored -> DONE;
        };
    }

    /**
     * Says whether the farmer can see this event as part of the answer. Once such an event has been sent,
     * the reply can no longer be retried on a fallback model without the farmer seeing it twice.
     *
     * @return {@code true} for answer text, data lookups and proposals
     */
    @JsonIgnore
    default boolean visibleOutput() {
        return this instanceof Token || this instanceof ToolCall || this instanceof ToolResult || this instanceof Proposal;
    }

    /**
     * First event of every reply.
     *
     * @param conversationId identifier to send back as {@code conversationId} to continue the conversation
     */
    @Schema(description = "First event: the conversation identifier to continue this conversation.")
    record Meta(String conversationId) implements ChatEvent {
    }

    /**
     * Says which AI model is answering. Sent before the first attempt and again after each fallback.
     *
     * @param model   the model's name
     * @param attempt 0 for the primary model, 1 for the first fallback and so on
     */
    @Schema(description = "Which AI model is answering.")
    record Model(String model, int attempt) implements ChatEvent {
    }

    /**
     * Says that a model failed before writing anything and the next one is being tried.
     *
     * @param failedModel the model that failed
     * @param reason      short reason, for example {@code rate limited}
     * @param nextModel   the model that will answer instead
     */
    @Schema(description = "A model failed before answering; the next model is being tried.")
    record Fallback(String failedModel, String reason, String nextModel) implements ChatEvent {
    }

    /**
     * A piece of the answer text, to be appended to what came before. The text is Markdown.
     *
     * @param text the next piece of the answer
     */
    @Schema(description = "A piece of the answer text (Markdown), appended to the previous pieces.")
    record Token(String text) implements ChatEvent {
    }

    /**
     * The assistant started a data lookup.
     *
     * @param callId    identifier pairing this event with its {@link ToolResult}
     * @param tool      tool name
     * @param label     what is being done, in plain language, for example "Checking soil data"
     * @param arguments the arguments the model chose, as JSON
     */
    @Schema(description = "The assistant started a data lookup.")
    record ToolCall(String callId, String tool, String label, JsonNode arguments) implements ChatEvent {
    }

    /**
     * A data lookup finished.
     *
     * @param callId     identifier of the matching {@link ToolCall}
     * @param tool       tool name
     * @param status     {@code ok} or {@code error}
     * @param durationMs how long the lookup took
     * @param message    why it failed; {@code null} when it succeeded
     */
    @Schema(description = "A data lookup finished.")
    record ToolResult(
            String callId,
            String tool,
            @Schema(allowableValues = {"ok", "error"}) String status,
            long durationMs,
            @JsonInclude(JsonInclude.Include.NON_NULL) String message) implements ChatEvent {

        /** Status of a lookup that succeeded. */
        public static final String OK = "ok";

        /** Status of a lookup that failed. */
        public static final String FAILED = "error";
    }

    /**
     * A crop rotation plan the assistant recommends, for the farmer to accept or reject in the app. Nothing
     * is saved or changed until the farmer accepts it, and accepting it is handled by the app.
     *
     * @param proposalId  identifier of this proposal
     * @param fieldId     field the plan is for
     * @param title       short title
     * @param summary     one-sentence summary of the plan
     * @param plan        the plan in the same shape as one {@code POST /crop-optimization} field plan
     * @param planner     planner that produced it
     * @param temporary   whether the plan is placeholder or demonstration output
     * @param disclaimer  how far the plan can be trusted
     * @param reviewNotes points a farmer or local expert should check before accepting
     */
    @Schema(description = "A rotation plan for the farmer to accept or reject. Nothing is saved until accepted.")
    record Proposal(
            String proposalId,
            String fieldId,
            String title,
            String summary,
            CropOptimizationResponse.FieldPlan plan,
            String planner,
            boolean temporary,
            String disclaimer,
            List<String> reviewNotes) implements ChatEvent {
    }

    /**
     * The reply could not be completed. Always the last event.
     *
     * @param code    machine-readable reason, for example {@code model_unavailable}
     * @param message explanation for the farmer
     */
    @Schema(description = "The reply could not be completed. Always the last event.")
    record Error(String code, String message) implements ChatEvent {
    }

    /**
     * The reply is complete. Always the last event of a successful reply.
     *
     * @param conversationId identifier to continue the conversation
     * @param model          model that wrote the answer
     * @param finishReason   why the model stopped, as reported by the model, for example {@code STOP}
     * @param usage          tokens used, when the model reports them
     */
    @Schema(description = "The reply is complete.")
    record Done(
            String conversationId,
            String model,
            @JsonInclude(JsonInclude.Include.NON_NULL) String finishReason,
            @JsonInclude(JsonInclude.Include.NON_NULL) Usage usage) implements ChatEvent {
    }

    /**
     * Tokens used to write a reply.
     *
     * @param promptTokens     tokens read
     * @param completionTokens tokens written
     * @param totalTokens      both together
     */
    @Schema(description = "Tokens used to write a reply.")
    record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
