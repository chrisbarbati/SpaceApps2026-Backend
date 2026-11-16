package org.greenalienhouseparty.chat;

import java.util.UUID;

import org.greenalienhouseparty.dto.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wraps one of the assistant's tools so each call is reported in the reply stream.
 *
 * <p>Before the tool runs, a {@link ChatEvent.ToolCall} with a plain-language label is sent; afterwards a
 * {@link ChatEvent.ToolResult} with the outcome and duration. The app can show "Checking soil data..." while a
 * slow lookup runs. The tool itself, its definition and Spring AI's handling of its result and errors are
 * unchanged: a failure is reported and then re-thrown, so Spring AI turns it into a message the model can
 * read.</p>
 */
public final class EventPublishingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(EventPublishingToolCallback.class);

    private final ToolCallback delegate;

    private final String label;

    private final ObjectMapper objectMapper;

    /**
     * Wraps a tool.
     *
     * @param delegate     the tool
     * @param label        what the tool does, in plain language, for example "Checking soil data"
     * @param objectMapper parses the tool's JSON arguments for the event
     */
    public EventPublishingToolCallback(ToolCallback delegate, String label, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.label = label;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * Runs the tool without a chat context. No events are sent.
     *
     * @param toolInput the tool's JSON arguments
     * @return the tool's result
     */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * Runs the tool, reporting the start and outcome to the chat's event sink when there is one.
     *
     * @param toolInput   the tool's JSON arguments
     * @param toolContext the chat context Spring AI passes to tools
     * @return the tool's result
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        ChatToolContext chat = ChatToolContext.from(toolContext);
        String callId = UUID.randomUUID().toString();
        chat.emit(new ChatEvent.ToolCall(callId, name, label, parseArguments(toolInput)));
        long started = System.nanoTime();
        log.info("Assistant tool {} started", name);
        try {
            String result = delegate.call(toolInput, toolContext);
            long duration = elapsedMs(started);
            log.info("Assistant tool {} finished in {} ms", name, duration);
            chat.emit(new ChatEvent.ToolResult(callId, name, ChatEvent.ToolResult.OK, duration, null));
            return result;
        } catch (RuntimeException exception) {
            long duration = elapsedMs(started);
            log.warn("Assistant tool {} failed after {} ms: {}", name, duration, exception.getMessage());
            chat.emit(new ChatEvent.ToolResult(callId, name, ChatEvent.ToolResult.FAILED, duration, reason(exception)));
            throw exception;
        }
    }

    /**
     * Returns the plain-language label shown while the tool runs.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * Parses the tool's arguments for display, falling back to the raw text when they are not JSON.
     *
     * @param toolInput the tool's arguments
     * @return the arguments as JSON
     */
    private JsonNode parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(toolInput);
        } catch (RuntimeException exception) {
            return objectMapper.getNodeFactory().stringNode(toolInput);
        }
    }

    /**
     * Finds the most useful message in an exception: Spring AI wraps tool failures, so the cause's message is
     * preferred.
     *
     * @param exception the failure
     * @return a short reason
     */
    private static String reason(RuntimeException exception) {
        Throwable root = exception.getCause() != null ? exception.getCause() : exception;
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    /**
     * Milliseconds since a start time.
     *
     * @param startedNanos the start, from {@link System#nanoTime()}
     * @return elapsed milliseconds
     */
    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
