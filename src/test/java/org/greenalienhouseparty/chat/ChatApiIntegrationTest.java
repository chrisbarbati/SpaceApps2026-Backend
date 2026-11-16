package org.greenalienhouseparty.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end tests of the chat routes over HTTP: server-sent events, the complete reply, validation and the
 * OpenAPI description. The model router is replaced with a scripted model that uses the application's real
 * tools, so no AI provider is called.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "data.backend.url=http://127.0.0.1:9")
class ChatApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @MockitoBean
    private ChatModelRouter router;

    @Autowired
    private ChatToolRegistry tools;

    /**
     * Verifies a streamed reply arrives as named server-sent events in order, with a real tool's progress
     * events and JSON data carrying the event type.
     *
     * @throws Exception if the request fails
     */
    @Test
    void streamsServerSentEventsWithToolProgress() throws Exception {
        useModel(new ScriptedChatModel(
                ScriptedChatModel.toolCall("getCurrentDate", "{}"),
                ScriptedChatModel.text("It is ", "November.")));

        HttpResponse<String> response = post("/chat/stream", """
                {"message":"What month is it?","context":{"selectedFieldId":"field-1"}}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<String> eventNames = response.body().lines()
                .filter(line -> line.startsWith("event:"))
                .map(line -> line.substring("event:".length()).strip())
                .toList();
        assertThat(eventNames).containsExactly("meta", "model", "tool_call", "tool_result", "token", "token", "done");
        List<JsonNode> data = response.body().lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> JSON.readTree(line.substring("data:".length())))
                .toList();
        assertThat(data.get(2).get("type").asString()).isEqualTo("tool_call");
        assertThat(data.get(2).get("tool").asString()).isEqualTo("getCurrentDate");
        assertThat(data.get(2).get("label").asString()).isEqualTo("Checking the date");
        assertThat(data.get(3).get("status").asString()).isEqualTo("ok");
        assertThat(data.get(4).get("text").asString()).isEqualTo("It is ");
        assertThat(data.getLast().get("conversationId").asString()).isEqualTo(data.getFirst().get("conversationId").asString());
    }

    /**
     * Verifies the non-streaming route gathers the reply into one response.
     *
     * @throws Exception if the request fails
     */
    @Test
    void returnsTheCompleteReply() throws Exception {
        useModel(new ScriptedChatModel(
                ScriptedChatModel.toolCall("explainTerm", "{\"term\":\"NDVI\"}"),
                ScriptedChatModel.text("NDVI measures greenness.")));

        HttpResponse<String> response = post("/chat", """
                {"message":"What is NDVI?"}
                """);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("answer").asString()).isEqualTo("NDVI measures greenness.");
        assertThat(body.get("model").asString()).isEqualTo("scripted");
        assertThat(body.get("toolCalls").get(0).get("tool").asString()).isEqualTo("explainTerm");
        assertThat(body.get("toolCalls").get(0).get("status").asString()).isEqualTo("ok");
        assertThat(body.get("conversationId").asString()).isNotBlank();
    }

    /**
     * Verifies a blank message is rejected before any model is called, and forgetting a conversation succeeds.
     *
     * @throws Exception if a request fails
     */
    @Test
    void rejectsBlankMessagesAndForgetsConversations() throws Exception {
        assertThat(post("/chat/stream", "{\"message\":\"  \"}").statusCode()).isEqualTo(422);
        assertThat(post("/chat", "{}").statusCode()).isEqualTo(422);

        HttpResponse<String> forget = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/some-id")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(forget.statusCode()).isEqualTo(204);
    }

    /**
     * Verifies the chat routes and event types appear in the OpenAPI description.
     *
     * @throws Exception if the request fails
     */
    @Test
    void documentsTheChatRoutes() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode docs = JSON.readTree(response.body());
        assertThat(docs.get("paths").has("/chat/stream")).isTrue();
        assertThat(docs.get("paths").has("/chat")).isTrue();
        assertThat(docs.get("paths").has("/chat/{conversation_id}")).isTrue();
        assertThat(docs.get("components").get("schemas").has("ChatRequest")).isTrue();
    }

    /**
     * Makes the router answer with one scripted model using the application's tools.
     *
     * @param model the scripted model
     */
    private void useModel(ScriptedChatModel model) {
        ChatClient client = ChatClient.builder(model).defaultToolCallbacks(tools.callbacks()).build();
        when(router.candidates()).thenReturn(List.of(new ChatModelRouter.Candidate("scripted", client)));
    }

    /**
     * Posts JSON to a route.
     *
     * @param path the route
     * @param body the JSON body
     * @return the response
     * @throws Exception if the request fails
     */
    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
