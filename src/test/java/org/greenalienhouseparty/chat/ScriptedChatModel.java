package org.greenalienhouseparty.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import reactor.core.publisher.Flux;

/**
 * A chat model for tests that answers each request with the next scripted reply, and records the prompts it
 * was sent. Each script step receives the prompt and returns the streamed chunks.
 */
final class ScriptedChatModel implements ChatModel {

    private final List<Function<Prompt, Flux<ChatResponse>>> steps;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    /**
     * Creates the model.
     *
     * @param steps one reply per request, in order
     */
    @SafeVarargs
    ScriptedChatModel(Function<Prompt, Flux<ChatResponse>>... steps) {
        this.steps = new ArrayList<>(Arrays.asList(steps));
    }

    /**
     * Returns the prompts received so far.
     *
     * @return the prompts in order
     */
    List<Prompt> prompts() {
        return prompts;
    }

    /**
     * Options that allow tool callbacks to be attached, as a real tool-capable model's do.
     *
     * @return empty tool-calling options
     */
    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    /**
     * Options that allow tool callbacks to be attached; Spring AI 2.0's chat client starts from these.
     *
     * @return empty tool-calling options
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model("scripted").build();
    }

    /**
     * Answers with the next step, aggregated.
     *
     * @param prompt the prompt
     * @return the last chunk of the next step
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        return stream(prompt).blockLast();
    }

    /**
     * Streams the next step.
     *
     * @param prompt the prompt
     * @return the step's chunks
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        prompts.add(prompt);
        int index = prompts.size() - 1;
        if (index >= steps.size()) {
            return Flux.error(new IllegalStateException("No scripted reply for request " + (index + 1)));
        }
        return steps.get(index).apply(prompt);
    }

    /**
     * A step that streams text in pieces and then stops.
     *
     * @param pieces the text pieces
     * @return the step
     */
    static Function<Prompt, Flux<ChatResponse>> text(String... pieces) {
        return prompt -> {
            List<ChatResponse> chunks = new ArrayList<>();
            for (int index = 0; index < pieces.length; index++) {
                boolean last = index == pieces.length - 1;
                chunks.add(chunk(AssistantMessage.builder().content(pieces[index]).build(), last ? "STOP" : null, last));
            }
            return Flux.fromIterable(chunks);
        };
    }

    /**
     * A step in which the model asks for one tool call.
     *
     * @param tool      tool name
     * @param arguments JSON arguments
     * @return the step
     */
    static Function<Prompt, Flux<ChatResponse>> toolCall(String tool, String arguments) {
        return prompt -> Flux.just(chunk(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, arguments)))
                .build(), "TOOL_CALLS", false));
    }

    /**
     * A step that fails before sending anything.
     *
     * @param error the failure
     * @return the step
     */
    static Function<Prompt, Flux<ChatResponse>> failure(Throwable error) {
        return prompt -> Flux.error(error);
    }

    /**
     * A step that sends some text and then fails.
     *
     * @param text  the text sent first
     * @param error the failure
     * @return the step
     */
    static Function<Prompt, Flux<ChatResponse>> textThenFailure(String text, Throwable error) {
        return prompt -> Flux.concat(
                Flux.just(chunk(AssistantMessage.builder().content(text).build(), null, false)),
                Flux.error(error));
    }

    /**
     * Builds one streamed chunk.
     *
     * @param message      the chunk's message
     * @param finishReason why the model stopped, or {@code null} mid-stream
     * @param withUsage    whether to report token usage
     * @return the chunk
     */
    private static ChatResponse chunk(AssistantMessage message, String finishReason, boolean withUsage) {
        ChatGenerationMetadata metadata = finishReason == null
                ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        ChatResponseMetadata responseMetadata = withUsage
                ? ChatResponseMetadata.builder().model("scripted").usage(new DefaultUsage(10, 5)).build()
                : ChatResponseMetadata.builder().model("scripted").build();
        return new ChatResponse(List.of(new Generation(message, metadata)), responseMetadata);
    }
}
