package org.greenalienhouseparty.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the application wires the primary model and the configured fallback models, in order, with the
 * real Spring AI 2.0 OpenAI auto-configuration. No model is called.
 */
@SpringBootTest(properties = {
        "data.backend.url=http://127.0.0.1:9",
        "spring.ai.openai.chat.model=primary-model",
        "chat.fallback-models[0].model=backup-model",
        "chat.fallback-models[1].model=other-endpoint-model",
        "chat.fallback-models[1].base-url=http://127.0.0.1:9/v1",
        "chat.fallback-models[1].api-key=other-key",
        "chat.fallback-models[2].model= "
})
class ChatModelRouterIntegrationTest {

    @Autowired
    private ChatModelRouter router;

    /**
     * Verifies the primary comes first, fallbacks follow in order and a fallback with no model name is skipped.
     */
    @Test
    void ordersPrimaryThenFallbacks() {
        assertThat(router.candidates())
                .extracting(ChatModelRouter.Candidate::name)
                .containsExactly("primary-model", "backup-model", "other-endpoint-model");
    }
}
