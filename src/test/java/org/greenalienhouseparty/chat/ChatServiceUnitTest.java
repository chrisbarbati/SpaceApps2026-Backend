package org.greenalienhouseparty.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.greenalienhouseparty.dto.ChatEvent;
import org.greenalienhouseparty.dto.ChatReply;
import org.greenalienhouseparty.dto.ChatRequest;
import org.greenalienhouseparty.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ChatService} against scripted models through a real Spring AI 2.0 {@link ChatClient}, so the
 * tool-calling advisor, tool context and streaming behave as in production.
 */
class ChatServiceUnitTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ChatRequest.Context CONTEXT = new ChatRequest.Context(
            "field-7",
            List.of(new ChatRequest.FieldContext("field-7", "North 40", "corn", null)),
            null,
            "ON",
            List.of("corn", "soybeans"));

    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();

    private final ChatPromptBuilder prompts =
            new ChatPromptBuilder(Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC));

    /**
     * Test tools that record what they were given.
     */
    static final class TestTools {

        final AtomicReference<String> fieldSeen = new AtomicReference<>();

        /**
         * Resolves the field the same way the real tools do.
         *
         * @param fieldId     field id or empty
         * @param toolContext chat context
         * @return the field used
         */
        @Tool(name = "whichField", description = "Returns the field id used")
        public String whichField(@ToolParam(description = "Field id", required = false) String fieldId, ToolContext toolContext) {
            String id = ChatToolContext.from(toolContext).resolveFieldId(fieldId);
            fieldSeen.set(id);
            return id;
        }

        /**
         * Sends a proposal event.
         *
         * @param toolContext chat context
         * @return confirmation
         */
        @Tool(name = "propose", description = "Proposes a plan")
        public String propose(ToolContext toolContext) {
            ChatToolContext.from(toolContext).emit(new ChatEvent.Proposal("p-1", "field-7", "Plan", "Corn → Soybeans",
                    null, "test", true, "Test only", List.of()));
            return "proposed";
        }

        /**
         * Always fails.
         *
         * @return never
         */
        @Tool(name = "broken", description = "Always fails")
        public String broken() {
            throw new IllegalStateException("data backend down");
        }
    }

    @Test
    void streamsMetaTokensAndDoneAndRemembersTheTurn() {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("Hello ", "farmer"));
        ChatService service = service(new TestTools(), model);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "Hi", CONTEXT));

        assertThat(events.getFirst()).isInstanceOf(ChatEvent.Meta.class);
        String conversationId = ((ChatEvent.Meta) events.getFirst()).conversationId();
        assertThat(conversationId).isNotBlank();
        assertThat(events.get(1)).isEqualTo(new ChatEvent.Model("primary", 0));
        assertThat(events).filteredOn(ChatEvent.Token.class::isInstance)
                .extracting(event -> ((ChatEvent.Token) event).text())
                .containsExactly("Hello ", "farmer");
        ChatEvent.Done done = (ChatEvent.Done) events.getLast();
        assertThat(done.conversationId()).isEqualTo(conversationId);
        assertThat(done.finishReason()).isEqualTo("STOP");
        assertThat(done.usage().totalTokens()).isEqualTo(15);

        List<Message> remembered = memory.get(conversationId);
        assertThat(remembered).extracting(Message::getMessageType).containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(remembered.get(1).getText()).isEqualTo("Hello farmer");
    }

    @Test
    void sendsOnScreenContextInTheInstructionsAndPreviousTurnsAsHistory() {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("First"), ScriptedChatModel.text("Second"));
        ChatService service = service(new TestTools(), model);

        String conversationId = ((ChatEvent.Meta) collect(service, new ChatRequest(null, "One", CONTEXT)).getFirst())
                .conversationId();
        collect(service, new ChatRequest(conversationId, "Two", CONTEXT));

        List<Message> second = model.prompts().get(1).getInstructions();
        assertThat(second.getFirst().getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(second.getFirst().getText())
                .contains("Selected field: field-7 (named \"North 40\"), current crop: corn")
                .contains("Crops the farmer can grow: corn, soybeans")
                .contains("2026-11-15");
        assertThat(second).extracting(Message::getText).containsSubsequence("One", "First", "Two");
    }

    @Test
    void runsToolsWithTheSelectedFieldAndReportsThem() {
        TestTools tools = new TestTools();
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall("whichField", "{}"),
                ScriptedChatModel.text("It is field-7."));
        ChatService service = service(tools, model);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "What can you tell me about this field?", CONTEXT));

        assertThat(tools.fieldSeen.get()).isEqualTo("field-7");
        assertThat(events).extracting(ChatEvent::eventName)
                .containsSubsequence(ChatEvent.META, ChatEvent.MODEL, ChatEvent.TOOL_CALL, ChatEvent.TOOL_RESULT,
                        ChatEvent.TOKEN, ChatEvent.DONE);
        ChatEvent.ToolCall call = events.stream().filter(ChatEvent.ToolCall.class::isInstance)
                .map(ChatEvent.ToolCall.class::cast).findFirst().orElseThrow();
        assertThat(call.tool()).isEqualTo("whichField");
        assertThat(call.label()).isEqualTo("Checking which field");
        ChatEvent.ToolResult result = events.stream().filter(ChatEvent.ToolResult.class::isInstance)
                .map(ChatEvent.ToolResult.class::cast).findFirst().orElseThrow();
        assertThat(result.callId()).isEqualTo(call.callId());
        assertThat(result.status()).isEqualTo(ChatEvent.ToolResult.OK);
    }

    @Test
    void reportsToolFailuresAndLetsTheModelExplainThem() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall("broken", "{}"),
                ScriptedChatModel.text("That data is unavailable right now."));
        ChatService service = service(new TestTools(), model);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "Soil?", CONTEXT));

        ChatEvent.ToolResult result = events.stream().filter(ChatEvent.ToolResult.class::isInstance)
                .map(ChatEvent.ToolResult.class::cast).findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(ChatEvent.ToolResult.FAILED);
        assertThat(result.message()).contains("data backend down");
        assertThat(events.getLast()).isInstanceOf(ChatEvent.Done.class);
        // The failure was sent back to the model as the tool's result
        assertThat(model.prompts().get(1).getInstructions().toString()).contains("data backend down");
    }

    @Test
    void streamsProposalsAndNotesThemInMemory() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall("propose", "{}"),
                ScriptedChatModel.text("Here is a plan for you to review."));
        ChatService service = service(new TestTools(), model);

        ChatReply reply = service.reply(new ChatRequest(null, "Plan my rotation", CONTEXT)).block(Duration.ofSeconds(10));

        assertThat(reply.proposals()).singleElement().satisfies(proposal -> {
            assertThat(proposal.proposalId()).isEqualTo("p-1");
            assertThat(proposal.summary()).isEqualTo("Corn → Soybeans");
        });
        assertThat(reply.answer()).isEqualTo("Here is a plan for you to review.");
        assertThat(reply.toolCalls()).singleElement().satisfies(call -> assertThat(call.status()).isEqualTo("ok"));
        assertThat(memory.get(reply.conversationId()).get(1).getText())
                .contains("Proposed to the farmer for approval: proposal p-1 for field field-7");
    }

    @Test
    void fallsBackToTheNextModelWhenThePrimaryFailsBeforeAnswering() {
        ScriptedChatModel primary = new ScriptedChatModel(ScriptedChatModel.failure(new IOException("connection reset")));
        ScriptedChatModel backup = new ScriptedChatModel(ScriptedChatModel.text("Backup answer"));
        ChatService service = service(new TestTools(), primary, backup);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "Hi", CONTEXT));

        assertThat(events).extracting(ChatEvent::eventName)
                .containsExactly(ChatEvent.META, ChatEvent.MODEL, ChatEvent.FALLBACK, ChatEvent.MODEL, ChatEvent.TOKEN,
                        ChatEvent.DONE);
        assertThat(events.get(2)).isEqualTo(new ChatEvent.Fallback("primary", "could not reach the model", "backup"));
        assertThat(((ChatEvent.Done) events.getLast()).model()).isEqualTo("backup");
    }

    @Test
    void doesNotFallBackOnceTheFarmerHasSeenPartOfTheAnswer() {
        ScriptedChatModel primary = new ScriptedChatModel(
                ScriptedChatModel.textThenFailure("Partial", new IOException("connection reset")));
        ScriptedChatModel backup = new ScriptedChatModel(ScriptedChatModel.text("Backup answer"));
        ChatService service = service(new TestTools(), primary, backup);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "Hi", CONTEXT));

        assertThat(events).extracting(ChatEvent::eventName)
                .containsExactly(ChatEvent.META, ChatEvent.MODEL, ChatEvent.TOKEN, ChatEvent.ERROR);
        assertThat(((ChatEvent.Error) events.getLast()).code()).isEqualTo("model_unavailable");
        assertThat(backup.prompts()).isEmpty();
        String conversationId = ((ChatEvent.Meta) events.getFirst()).conversationId();
        assertThat(memory.get(conversationId)).isEmpty();
    }

    @Test
    void doesNotFallBackForErrorsAnotherModelWouldRepeat() {
        ScriptedChatModel primary = new ScriptedChatModel(ScriptedChatModel.failure(new IllegalArgumentException("bad")));
        ScriptedChatModel backup = new ScriptedChatModel(ScriptedChatModel.text("Backup answer"));
        ChatService service = service(new TestTools(), primary, backup);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "Hi", CONTEXT));

        assertThat(events.getLast()).isEqualTo(new ChatEvent.Error("internal_error",
                "Something went wrong while answering. Please try again."));
        assertThat(backup.prompts()).isEmpty();
    }

    @Test
    void reportsAnErrorWhenEveryModelFails() {
        ScriptedChatModel primary = new ScriptedChatModel(ScriptedChatModel.failure(new IOException("down")));
        ScriptedChatModel backup = new ScriptedChatModel(ScriptedChatModel.failure(new IOException("down too")));
        ChatService service = service(new TestTools(), primary, backup);

        List<ChatEvent> events = collect(service, new ChatRequest(null, "Hi", CONTEXT));

        assertThat(events).extracting(ChatEvent::eventName)
                .containsExactly(ChatEvent.META, ChatEvent.MODEL, ChatEvent.FALLBACK, ChatEvent.MODEL, ChatEvent.ERROR);
    }

    @Test
    void forgetsConversations() {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("Hello"));
        ChatService service = service(new TestTools(), model);
        String conversationId = ((ChatEvent.Meta) collect(service, new ChatRequest("c-1", "Hi", null)).getFirst())
                .conversationId();
        assertThat(conversationId).isEqualTo("c-1");

        service.forget("c-1");

        assertThat(memory.get("c-1")).isEmpty();
    }

    @Test
    void rejectsMissingAndOverlongMessages() {
        ChatService service = service(new TestTools(), new ScriptedChatModel());

        assertThatThrownBy(() -> service.stream(new ChatRequest(null, "  ", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.stream(new ChatRequest(null, "x".repeat(ChatService.MAX_MESSAGE_LENGTH + 1), null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    /**
     * Builds the service over one or two scripted models named "primary" and "backup".
     *
     * @param tools  the test tools
     * @param models the models in order
     * @return the service
     */
    private ChatService service(TestTools tools, ChatModel... models) {
        List<ToolCallback> callbacks = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .map(callback -> (ToolCallback) new EventPublishingToolCallback(callback,
                        callback.getToolDefinition().name().equals("whichField") ? "Checking which field" : "Working",
                        JSON))
                .toList();
        List<ChatModelRouter.Candidate> candidates = new java.util.ArrayList<>();
        String[] names = {"primary", "backup"};
        for (int index = 0; index < models.length; index++) {
            candidates.add(new ChatModelRouter.Candidate(names[index],
                    ChatClient.builder(models[index]).defaultToolCallbacks(callbacks).build()));
        }
        return new ChatService(new ChatModelRouter(candidates), memory, prompts);
    }

    /**
     * Collects a reply's events.
     *
     * @param service the service
     * @param request the message
     * @return the events in order
     */
    private static List<ChatEvent> collect(ChatService service, ChatRequest request) {
        return service.stream(request).collectList().block(Duration.ofSeconds(10));
    }
}
