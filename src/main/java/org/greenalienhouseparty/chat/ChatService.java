package org.greenalienhouseparty.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.greenalienhouseparty.dto.ChatEvent;
import org.greenalienhouseparty.dto.ChatReply;
import org.greenalienhouseparty.dto.ChatRequest;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Answers a farmer's chat message as a stream of {@link ChatEvent}s.
 *
 * <p>For each message it:</p>
 * <ol>
 *   <li>Writes the instructions with the farmer's on-screen context ({@link ChatPromptBuilder}) and adds the
 *       conversation so far from {@link ChatMemory}.</li>
 *   <li>Streams the answer from the first model in {@link ChatModelRouter}. Spring AI's tool-calling advisor
 *       runs any tools the model asks for; the tools receive the on-screen context and a
 *       {@link ChatEventSink} through the tool context and report progress and proposals there.</li>
 *   <li>Merges the answer text and the tool events into one stream, in the order they happen.</li>
 *   <li>If a model fails with a temporary error before anything visible was sent, tries the next model
 *       (see {@link ChatFailures}). After that point a failure ends the reply with an error event, because the
 *       farmer would otherwise see a second, different answer.</li>
 *   <li>On success, saves the farmer's message and the answer to memory, with a note of any proposals so a
 *       follow-up such as "why corn in year 2?" can refer to them.</li>
 * </ol>
 *
 * <p>Memory is updated here rather than by Spring AI's memory advisor so that a failed attempt, or one
 * replaced by a fallback, never leaves a half-finished turn in the conversation.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Longest message accepted, in characters. */
    static final int MAX_MESSAGE_LENGTH = 4000;

    private final ChatModelRouter router;

    private final ChatMemory memory;

    private final ChatPromptBuilder prompts;

    /**
     * Creates the service.
     *
     * @param router  the models to use, in order
     * @param memory  conversation memory
     * @param prompts writes the instructions for each message
     */
    public ChatService(ChatModelRouter router, ChatMemory memory, ChatPromptBuilder prompts) {
        this.router = router;
        this.memory = memory;
        this.prompts = prompts;
    }

    /**
     * Answers a message as a stream of events. The stream starts with {@link ChatEvent.Meta} and ends with
     * {@link ChatEvent.Done} or {@link ChatEvent.Error}; it never ends with an error signal.
     *
     * @param request the message and on-screen context
     * @return the reply events
     * @throws InvalidRequestException when the message is missing or too long
     */
    public Flux<ChatEvent> stream(ChatRequest request) {
        validate(request);
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId().strip();
        String message = request.message().strip();
        ChatEventSink sink = new ChatEventSink();
        Turn turn = new Turn();

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompts.systemPrompt(request.context())));
        messages.addAll(memory.get(conversationId));
        messages.add(new UserMessage(message));
        Map<String, Object> toolContext = ChatToolContext.toMap(request.context(), sink, conversationId);

        Flux<ChatEvent> answer = Flux.concat(
                        Mono.just(new ChatEvent.Meta(conversationId)),
                        attempt(0, messages, toolContext, sink, turn),
                        Mono.fromSupplier(() -> finish(conversationId, message, sink, turn)))
                .onErrorResume(error -> {
                    log.error("Chat reply failed", error);
                    return Mono.just(ChatFailures.toEvent(error));
                })
                .doFinally(signal -> sink.complete());

        return Flux.merge(answer, sink.asFlux());
    }

    /**
     * Answers a message and gathers the whole reply.
     *
     * @param request the message and on-screen context
     * @return the complete reply
     * @throws InvalidRequestException when the message is missing or too long
     */
    public Mono<ChatReply> reply(ChatRequest request) {
        return stream(request).collectList().map(ChatReply::from);
    }

    /**
     * Forgets a conversation.
     *
     * @param conversationId the conversation identifier
     */
    public void forget(String conversationId) {
        memory.clear(conversationId);
    }

    /**
     * Streams one model's answer, falling back to the next model when it fails before anything visible was
     * sent.
     *
     * @param index       position of the model in the router
     * @param messages    instructions, conversation and the new message
     * @param toolContext context passed to tools
     * @param sink        side channel for tool events
     * @param turn        what has been written so far
     * @return the model's events
     */
    private Flux<ChatEvent> attempt(int index, List<Message> messages, Map<String, Object> toolContext,
                                    ChatEventSink sink, Turn turn) {
        List<ChatModelRouter.Candidate> candidates = router.candidates();
        ChatModelRouter.Candidate candidate = candidates.get(index);
        turn.model = candidate.name();

        Flux<ChatEvent> tokens = Flux.defer(() -> candidate.client().prompt()
                        .messages(messages)
                        .toolContext(toolContext)
                        .stream()
                        .chatResponse())
                .concatMap(response -> Flux.fromIterable(read(response, turn)))
                .doOnNext(event -> sink.markVisibleOutput());

        return Flux.concat(Mono.just(new ChatEvent.Model(candidate.name(), index)), tokens)
                .onErrorResume(error -> {
                    Optional<String> reason = ChatFailures.retryableReason(error);
                    boolean canFallback = reason.isPresent() && !sink.hasVisibleOutput() && index + 1 < candidates.size();
                    if (!canFallback) {
                        return Flux.error(error);
                    }
                    String next = candidates.get(index + 1).name();
                    log.warn("Chat model {} failed ({}); falling back to {}", candidate.name(), reason.get(), next);
                    return Flux.concat(
                            Mono.just(new ChatEvent.Fallback(candidate.name(), reason.get(), next)),
                            attempt(index + 1, messages, toolContext, sink, turn));
                });
    }

    /**
     * Reads one streamed chunk: its text becomes a token event; usage, finish reason and model are kept.
     *
     * @param response the chunk
     * @param turn     what has been written so far
     * @return the token event, if the chunk has text
     */
    private static List<ChatEvent> read(ChatResponse response, Turn turn) {
        if (response == null) {
            return List.of();
        }
        if (response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                turn.usage.set(new ChatEvent.Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens()));
            }
        }
        Generation generation = response.getResult();
        if (generation == null) {
            return List.of();
        }
        if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
            turn.finishReason = generation.getMetadata().getFinishReason();
        }
        String text = generation.getOutput() == null ? null : generation.getOutput().getText();
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        turn.answer.append(text);
        return List.of(new ChatEvent.Token(text));
    }

    /**
     * Saves the finished turn to memory and builds the final event.
     *
     * @param conversationId the conversation
     * @param message        the farmer's message
     * @param sink           the side channel, for proposals made
     * @param turn           what was written
     * @return the final event
     */
    private ChatEvent finish(String conversationId, String message, ChatEventSink sink, Turn turn) {
        StringBuilder remembered = new StringBuilder(turn.answer);
        for (ChatEvent.Proposal proposal : sink.proposals()) {
            remembered.append("\n\n[Proposed to the farmer for approval: proposal ").append(proposal.proposalId())
                    .append(" for field ").append(proposal.fieldId()).append(": ").append(proposal.summary()).append(']');
        }
        if (!remembered.isEmpty()) {
            memory.add(conversationId, List.of(new UserMessage(message), new AssistantMessage(remembered.toString())));
        }
        return new ChatEvent.Done(conversationId, turn.model, turn.finishReason, turn.usage.get());
    }

    /**
     * Checks the message before anything is sent to a model.
     *
     * @param request the request
     * @throws InvalidRequestException when the message is missing or too long
     */
    private static void validate(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new InvalidRequestException("message is required");
        }
        if (request.message().length() > MAX_MESSAGE_LENGTH) {
            throw new InvalidRequestException("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
    }

    /**
     * What has been written for one message so far.
     */
    private static final class Turn {

        /** The answer text. */
        private final StringBuilder answer = new StringBuilder();

        /** Tokens used, from the latest chunk that reported them. */
        private final AtomicReference<ChatEvent.Usage> usage = new AtomicReference<>();

        /** The model currently answering. */
        private volatile String model;

        /** Why the model stopped, from the latest chunk that said. */
        private volatile String finishReason;
    }
}
