package org.greenalienhouseparty.chat;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;

/**
 * The AI models the assistant may use, in the order they are tried.
 *
 * <p>The first candidate is the primary model configured through {@code spring.ai.openai.*}; the rest are
 * the fallbacks from {@code chat.fallback-models}. Each candidate has its own {@link ChatClient} with the
 * assistant's tools registered, so any of them can answer a message in full. {@link ChatService} moves to
 * the next candidate only when one fails before the farmer has seen anything.</p>
 */
public final class ChatModelRouter {

    /**
     * One model the assistant may use.
     *
     * @param name   the model's name, shown to the app in {@code model} events
     * @param client chat client for the model, with the assistant's tools
     */
    public record Candidate(String name, ChatClient client) {
    }

    private final List<Candidate> candidates;

    /**
     * Creates a router.
     *
     * @param candidates models in the order they are tried; at least one
     * @throws IllegalArgumentException when there is no model
     */
    public ChatModelRouter(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one chat model is required");
        }
        this.candidates = List.copyOf(candidates);
    }

    /**
     * Returns the models in the order they are tried.
     *
     * @return the candidates; the primary first
     */
    public List<Candidate> candidates() {
        return candidates;
    }
}
