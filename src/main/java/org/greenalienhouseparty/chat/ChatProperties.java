package org.greenalienhouseparty.chat;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the chat assistant under the {@code chat} prefix.
 *
 * <p>The primary model is configured through Spring AI's own {@code spring.ai.openai.*} properties (base URL,
 * API key, model name, temperature). This class adds what Spring AI does not provide: an ordered list of
 * fallback models, tried when the primary fails before writing anything, and the size of each
 * conversation's memory.</p>
 *
 * <pre>
 * chat.fallback-models[0].model=smaller-model
 * chat.fallback-models[1].model=other-model
 * chat.fallback-models[1].base-url=https://other-endpoint/v1
 * chat.fallback-models[1].api-key=...
 * chat.memory.max-messages=20
 * </pre>
 */
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    /** Fallback models in the order they are tried after the primary. */
    private List<FallbackModel> fallbackModels = new ArrayList<>();

    /** Conversation memory settings. */
    private final Memory memory = new Memory();

    /**
     * Returns the fallback models in the order they are tried.
     *
     * @return the fallback models; never {@code null}
     */
    public List<FallbackModel> getFallbackModels() {
        return fallbackModels;
    }

    /**
     * Sets the fallback models in the order they are tried.
     *
     * @param fallbackModels the fallback models
     */
    public void setFallbackModels(List<FallbackModel> fallbackModels) {
        this.fallbackModels = fallbackModels == null ? new ArrayList<>() : fallbackModels;
    }

    /**
     * Returns the conversation memory settings.
     *
     * @return the memory settings
     */
    public Memory getMemory() {
        return memory;
    }

    /**
     * One fallback model on an OpenAI-compatible endpoint. The endpoint and key default to the primary
     * model's, so a fallback on the same endpoint only needs a model name.
     */
    public static class FallbackModel {

        /** Model name sent to the endpoint. Required. */
        private String model;

        /** Endpoint base URL; defaults to the primary model's. */
        private String baseUrl;

        /** API key; defaults to the primary model's. */
        private String apiKey;

        /**
         * Returns the model name.
         *
         * @return the model name
         */
        public String getModel() {
            return model;
        }

        /**
         * Sets the model name.
         *
         * @param model the model name
         */
        public void setModel(String model) {
            this.model = model;
        }

        /**
         * Returns the endpoint base URL, or {@code null} to use the primary model's.
         *
         * @return the base URL
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Sets the endpoint base URL.
         *
         * @param baseUrl the base URL
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * Returns the API key, or {@code null} to use the primary model's.
         *
         * @return the API key
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Sets the API key.
         *
         * @param apiKey the API key
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * Conversation memory settings.
     */
    public static class Memory {

        /** Most recent messages kept per conversation; older ones are forgotten. */
        private int maxMessages = 20;

        /**
         * Returns how many recent messages are kept per conversation.
         *
         * @return the message limit
         */
        public int getMaxMessages() {
            return maxMessages;
        }

        /**
         * Sets how many recent messages are kept per conversation.
         *
         * @param maxMessages the message limit
         */
        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }
    }
}
