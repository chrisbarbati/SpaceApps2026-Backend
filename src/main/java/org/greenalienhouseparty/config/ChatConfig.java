package org.greenalienhouseparty.config;

import java.util.ArrayList;
import java.util.List;

import org.greenalienhouseparty.chat.ChatModelRouter;
import org.greenalienhouseparty.chat.ChatProperties;
import org.greenalienhouseparty.chat.ChatToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;

/**
 * Wires the chat assistant onto Spring AI 2.0.
 *
 * <p>The primary model is Spring AI's auto-configured {@link ChatModel}, set up from
 * {@code spring.ai.openai.*} against any OpenAI-compatible endpoint. Each entry of
 * {@code chat.fallback-models} becomes another {@link OpenAiChatModel} that copies the primary's options and
 * overrides the model name and, when given, the endpoint and key. Every model gets its own
 * {@link ChatClient} built with the auto-configured {@link ToolCallingAdvisor} builder (so the tool-call loop
 * and its limits from {@code spring.ai.tools.*} apply) and the assistant's tools as defaults.</p>
 *
 * <p>Conversation memory is a {@link MessageWindowChatMemory} over the configured
 * {@link ChatMemoryRepository} (in memory unless another repository is configured).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);

    /**
     * Keeps the most recent messages of each conversation.
     *
     * @param repositories the configured memory store, if any
     * @param properties   chat settings
     * @return the conversation memory
     */
    @Bean
    ChatMemory chatMemory(ObjectProvider<ChatMemoryRepository> repositories, ChatProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repositories.getIfAvailable(InMemoryChatMemoryRepository::new))
                .maxMessages(properties.getMemory().getMaxMessages())
                .build();
    }

    /**
     * Builds the ordered list of models the assistant may use.
     *
     * @param primary              the auto-configured chat model
     * @param properties           chat settings, including fallback models
     * @param chatProperties       Spring AI's OpenAI chat settings, for the primary endpoint and key
     * @param commonProperties     Spring AI's shared OpenAI settings, for the primary endpoint and key
     * @param toolCallingManager   executes tool calls, with the configured limits
     * @param toolAdvisorBuilder   Spring AI's tool-calling loop
     * @param observationRegistry  observation registry, when present
     * @param tools                the assistant's tools
     * @return the model router
     */
    @Bean
    ChatModelRouter chatModelRouter(
            ChatModel primary,
            ChatProperties properties,
            ObjectProvider<OpenAiChatProperties> chatProperties,
            ObjectProvider<OpenAiCommonProperties> commonProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilder,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ChatToolRegistry tools) {
        ObservationRegistry observations = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
        ToolCallingAdvisor.Builder<?> advisor = toolAdvisorBuilder.getIfAvailable(
                () -> ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager));

        List<ChatModelRouter.Candidate> candidates = new ArrayList<>();
        candidates.add(candidate(nameOf(primary), primary, observations, advisor, tools));

        for (ChatProperties.FallbackModel fallback : properties.getFallbackModels()) {
            if (fallback.getModel() == null || fallback.getModel().isBlank()) {
                log.warn("Ignoring a chat fallback model with no model name");
                continue;
            }
            if (!(primary instanceof OpenAiChatModel openAi)) {
                log.warn("Chat fallback models need the OpenAI-compatible chat model; ignoring {}", fallback.getModel());
                continue;
            }
            OpenAiChatModel model = fallbackModel(openAi, fallback, chatProperties.getIfAvailable(),
                    commonProperties.getIfAvailable(), toolCallingManager, observations);
            candidates.add(candidate(fallback.getModel(), model, observations, advisor, tools));
        }
        log.info("Chat assistant models in order: {}", candidates.stream().map(ChatModelRouter.Candidate::name).toList());
        return new ChatModelRouter(candidates);
    }

    /**
     * Builds a fallback model from the primary's options.
     *
     * @param primary          the primary model
     * @param fallback         the fallback's settings
     * @param chatProperties   OpenAI chat settings, or {@code null}
     * @param commonProperties shared OpenAI settings, or {@code null}
     * @param toolCallingManager executes tool calls
     * @param observations     observation registry
     * @return the fallback model
     */
    static OpenAiChatModel fallbackModel(OpenAiChatModel primary, ChatProperties.FallbackModel fallback,
                                         OpenAiChatProperties chatProperties, OpenAiCommonProperties commonProperties,
                                         ToolCallingManager toolCallingManager, ObservationRegistry observations) {
        OpenAiChatOptions.Builder options = primary.getOptions().mutate();
        options.model(fallback.getModel());
        String baseUrl = firstNonBlank(fallback.getBaseUrl(),
                chatProperties == null ? null : chatProperties.getBaseUrl(),
                commonProperties == null ? null : commonProperties.getBaseUrl());
        if (baseUrl != null) {
            options.baseUrl(baseUrl);
        }
        String apiKey = firstNonBlank(fallback.getApiKey(),
                chatProperties == null ? null : chatProperties.getApiKey(),
                commonProperties == null ? null : commonProperties.getApiKey());
        if (apiKey != null) {
            options.apiKey(apiKey);
        }
        if (commonProperties != null) {
            options.timeout(commonProperties.getTimeout());
            options.maxRetries(commonProperties.getMaxRetries());
        }
        return OpenAiChatModel.builder()
                .options(options.build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observations)
                .build();
    }

    /**
     * Builds one router candidate with the assistant's tools.
     *
     * @param name         the model's name
     * @param model        the model
     * @param observations observation registry
     * @param advisor      Spring AI's tool-calling loop
     * @param tools        the assistant's tools
     * @return the candidate
     */
    private static ChatModelRouter.Candidate candidate(String name, ChatModel model, ObservationRegistry observations,
                                                       ToolCallingAdvisor.Builder<?> advisor, ChatToolRegistry tools) {
        ChatClient client = ChatClient.builder(model, observations, null, null, advisor)
                .defaultToolCallbacks(tools.callbacks())
                .build();
        return new ChatModelRouter.Candidate(name, client);
    }

    /**
     * Names a model from its default options.
     *
     * @param model the model
     * @return its model name, or {@code primary} when not set
     */
    private static String nameOf(ChatModel model) {
        String name = model.getDefaultOptions() == null ? null : model.getDefaultOptions().getModel();
        return name == null || name.isBlank() ? "primary" : name;
    }

    /**
     * Returns the first value that is not blank.
     *
     * @param values candidates in order of preference
     * @return the first non-blank value, or {@code null}
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
