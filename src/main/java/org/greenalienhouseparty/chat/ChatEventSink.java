package org.greenalienhouseparty.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.greenalienhouseparty.dto.ChatEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Side channel for events raised while the assistant is answering one message.
 *
 * <p>Tools run inside Spring AI's tool-calling loop, out of sight of the model's text stream. They reach this
 * sink through the tool context (which is never sent to the model) and emit {@link ChatEvent.ToolCall},
 * {@link ChatEvent.ToolResult} and {@link ChatEvent.Proposal} events, which {@link ChatService} merges into
 * the reply stream.</p>
 *
 * <p>The sink also records whether anything visible to the farmer has been sent, which decides whether a
 * failed model may still be replaced by a fallback, and keeps the proposals made, so they can be noted in the
 * conversation memory. One instance per message; safe to emit to from several threads.</p>
 */
public final class ChatEventSink {

    private final Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

    private final AtomicBoolean visibleOutput = new AtomicBoolean();

    private final List<ChatEvent.Proposal> proposals = new ArrayList<>();

    /**
     * Sends an event to the reply stream. Events emitted after {@link #complete()} are dropped.
     *
     * @param event the event to send
     */
    public synchronized void emit(ChatEvent event) {
        if (event.visibleOutput()) {
            visibleOutput.set(true);
        }
        if (event instanceof ChatEvent.Proposal proposal) {
            proposals.add(proposal);
        }
        sink.tryEmitNext(event);
    }

    /**
     * Records that something visible to the farmer was sent on another path, such as answer text.
     */
    public void markVisibleOutput() {
        visibleOutput.set(true);
    }

    /**
     * Says whether anything visible to the farmer has been sent for this message.
     *
     * @return {@code true} once answer text, a data lookup or a proposal has been sent
     */
    public boolean hasVisibleOutput() {
        return visibleOutput.get();
    }

    /**
     * Returns the proposals made while answering this message.
     *
     * @return the proposals, in the order they were made
     */
    public synchronized List<ChatEvent.Proposal> proposals() {
        return List.copyOf(proposals);
    }

    /**
     * Ends the side channel. Called once the model's answer has finished or failed.
     */
    public synchronized void complete() {
        sink.tryEmitComplete();
    }

    /**
     * Returns the events as a stream. May be subscribed to once.
     *
     * @return the events emitted to this sink
     */
    public Flux<ChatEvent> asFlux() {
        return sink.asFlux();
    }
}
