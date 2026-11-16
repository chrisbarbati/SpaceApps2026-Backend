package org.greenalienhouseparty.controller;

import org.greenalienhouseparty.chat.ChatService;
import org.greenalienhouseparty.dto.ChatEvent;
import org.greenalienhouseparty.dto.ChatReply;
import org.greenalienhouseparty.dto.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The farmer's chat assistant.
 *
 * <p>The assistant answers questions in plain language using the application's own data and calculations
 * as tools: field soil, climate and crop history, satellite greenness and drought indicators, regional soil
 * maps, the seasonal outlook, crop requirements and markets, crop suitability, rotation comparisons and
 * rotation proposals for the farmer to accept or reject. The app sends what the farmer is looking at (the
 * selected field and so on) with each message, so "this field" needs no explanation.</p>
 */
@RestController
@Tag(name = "Chat assistant", description = "Natural-language questions and recommendations about the farmer's fields")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final String STREAM_DESCRIPTION = """
            Answers a farmer's message as server-sent events. Each event's name is its type and its data is \
            JSON with a matching 'type' property:

            - meta: first event; conversationId to send with the next message to continue the conversation.
            - model: which AI model is answering (attempt 0 is the primary).
            - fallback: a model failed before writing anything; the next one is answering instead.
            - token: a piece of the answer (Markdown); append the pieces in order.
            - tool_call / tool_result: a data lookup started / finished, with a plain-language label to show \
            while it runs. Slow lookups (satellite history, drought) can take up to a minute.
            - proposal: a rotation plan for the farmer to accept or reject in the app. Nothing is saved or \
            applied by the backend.
            - error: the reply could not be completed; always last.
            - done: the reply is complete, with usage; always last.

            Send the selected field and anything else on screen in 'context'. A missing, blank or overlong message is a 422.""";

    private final ChatService service;

    /**
     * Creates the controller.
     *
     * @param service answers chat messages
     */
    public ChatController(ChatService service) {
        this.service = service;
    }

    /**
     * Answers a message as a stream of server-sent events.
     *
     * @param request the message and what the farmer is looking at
     * @return the reply events
     */
    @PostMapping(path = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask the assistant, streaming the answer", description = STREAM_DESCRIPTION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Server-sent events, ending with done or error.",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ChatEvent.class)))),
            @ApiResponse(responseCode = "422", description = "The message is missing or too long.")
    })
    public Flux<ServerSentEvent<ChatEvent>> stream(@RequestBody ChatRequest request) {
        log.trace("Entering stream()");
        log.info("Handling POST /chat/stream");
        try {
            return service.stream(request)
                    .map(event -> ServerSentEvent.<ChatEvent>builder(event).event(event.eventName()).build());
        } finally {
            log.trace("Exiting stream()");
        }
    }

    /**
     * Answers a message and returns the whole reply at once.
     *
     * @param request the message and what the farmer is looking at
     * @return the complete reply
     */
    @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ask the assistant and wait for the whole answer",
            description = "Same as /chat/stream, but returns the finished answer, the data lookups made, any "
                    + "rotation proposals and fallbacks in one response. Handy for trying the assistant from Swagger.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The complete reply; 'error' is set when it could not be completed."),
            @ApiResponse(responseCode = "422", description = "The message is missing or too long.")
    })
    public Mono<ChatReply> chat(@RequestBody ChatRequest request) {
        log.trace("Entering chat()");
        log.info("Handling POST /chat");
        try {
            return service.reply(request);
        } finally {
            log.trace("Exiting chat()");
        }
    }

    /**
     * Forgets a conversation's history.
     *
     * @param conversationId the conversation identifier from the {@code meta} event
     * @return 204 No Content
     */
    @DeleteMapping("/chat/{conversation_id}")
    @Operation(summary = "Forget a conversation",
            description = "Clears the assistant's memory of a conversation. Unknown ids are ignored.")
    @ApiResponse(responseCode = "204", description = "The conversation was forgotten.")
    public ResponseEntity<Void> forget(
            @PathVariable("conversation_id") @Parameter(description = "conversationId from the meta event") String conversationId) {
        log.trace("Entering forget()");
        log.info("Handling DELETE /chat/{conversation_id}");
        try {
            service.forget(conversationId);
            return ResponseEntity.noContent().build();
        } finally {
            log.trace("Exiting forget()");
        }
    }
}
