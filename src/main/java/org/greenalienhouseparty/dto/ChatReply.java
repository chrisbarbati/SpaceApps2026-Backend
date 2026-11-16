package org.greenalienhouseparty.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The assistant's complete reply, returned by {@code POST /chat} once the answer is finished.
 *
 * <p>Holds the same information as the events of {@code POST /chat/stream}, gathered into one object. Useful
 * for trying the assistant from Swagger and for callers that do not need to show progress.</p>
 *
 * @param conversationId identifier to send back as {@code conversationId} to continue the conversation
 * @param answer         the full answer text, in Markdown
 * @param model          model that wrote the answer, or {@code null} when none did
 * @param toolCalls      data lookups the assistant made, in order
 * @param proposals      rotation plans for the farmer to accept or reject
 * @param fallbacks      models that failed before the answering model took over
 * @param usage          tokens used, when the model reports them
 * @param error          why the reply could not be completed; {@code null} when it was
 */
@Schema(description = "The assistant's complete reply.")
public record ChatReply(
        String conversationId,
        String answer,
        String model,
        List<ToolCallSummary> toolCalls,
        List<ChatEvent.Proposal> proposals,
        List<ChatEvent.Fallback> fallbacks,
        @JsonInclude(JsonInclude.Include.NON_NULL) ChatEvent.Usage usage,
        @JsonInclude(JsonInclude.Include.NON_NULL) ChatEvent.Error error) {

    /**
     * One data lookup the assistant made.
     *
     * @param tool       tool name
     * @param label      what was done, in plain language
     * @param status     {@code ok} or {@code error}; {@code null} if the lookup never finished
     * @param durationMs how long it took
     * @param message    why it failed, when it did
     */
    @Schema(description = "A data lookup the assistant made.")
    public record ToolCallSummary(
            String tool,
            String label,
            String status,
            Long durationMs,
            @JsonInclude(JsonInclude.Include.NON_NULL) String message) {
    }

    /**
     * Gathers the events of one streamed reply into a complete reply.
     *
     * @param events the events in the order they were sent
     * @return the reply they describe
     */
    public static ChatReply from(List<ChatEvent> events) {
        String conversationId = null;
        String model = null;
        StringBuilder answer = new StringBuilder();
        List<ChatEvent.ToolCall> calls = new ArrayList<>();
        List<ChatEvent.ToolResult> results = new ArrayList<>();
        List<ChatEvent.Proposal> proposals = new ArrayList<>();
        List<ChatEvent.Fallback> fallbacks = new ArrayList<>();
        ChatEvent.Usage usage = null;
        ChatEvent.Error error = null;
        for (ChatEvent event : events) {
            switch (event) {
                case ChatEvent.Meta meta -> conversationId = meta.conversationId();
                case ChatEvent.Model current -> model = current.model();
                case ChatEvent.Fallback fallback -> fallbacks.add(fallback);
                case ChatEvent.Token token -> answer.append(token.text());
                case ChatEvent.ToolCall call -> calls.add(call);
                case ChatEvent.ToolResult result -> results.add(result);
                case ChatEvent.Proposal proposal -> proposals.add(proposal);
                case ChatEvent.Error failure -> error = failure;
                case ChatEvent.Done done -> {
                    conversationId = done.conversationId();
                    model = done.model();
                    usage = done.usage();
                }
            }
        }
        List<ToolCallSummary> toolCalls = calls.stream()
                .map(call -> results.stream()
                        .filter(result -> result.callId().equals(call.callId()))
                        .findFirst()
                        .map(result -> new ToolCallSummary(call.tool(), call.label(), result.status(),
                                result.durationMs(), result.message()))
                        .orElse(new ToolCallSummary(call.tool(), call.label(), null, null, null)))
                .toList();
        return new ChatReply(conversationId, answer.toString(), model, toolCalls, List.copyOf(proposals),
                List.copyOf(fallbacks), usage, error);
    }
}
