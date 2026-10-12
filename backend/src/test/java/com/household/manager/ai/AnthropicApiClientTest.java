package com.household.manager.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AnthropicApiClientTest {

    private AnthropicApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        AiProperties props = new AiProperties();
        props.setAnthropicApiKey("sk-test");
        props.setAnthropicBaseUrl("https://api.anthropic.test");
        client = new AnthropicApiClient(props, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void sendsModelSystemMessagesAndToolsWithAuthHeaders() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andExpect(header("x-api-key", "sk-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
                .andExpect(jsonPath("$.system[0].text").value("Du bist ein Assistent"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.tools[0].name").value("list_switches"))
                .andRespond(withSuccess("""
                        {"stop_reason":"end_turn","content":[{"type":"text","text":"Hallo!"}]}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage(AnthropicRequest.of("claude-haiku-4-5-20251001", 100, "Du bist ein Assistent",
                List.of(AnthropicMessage.user("hi")),
                List.of(Map.of("name", "list_switches", "description", "d", "input_schema", Map.of()))));

        assertEquals("end_turn", response.stopReason());
        assertEquals("Hallo!", response.text());
    }

    @Test
    void parsesToolUseBlocks() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andRespond(withSuccess("""
                        {"stop_reason":"tool_use","content":[
                          {"type":"text","text":"Ich schaue nach."},
                          {"type":"tool_use","id":"tu_1","name":"list_switches","input":{"limit":5}}]}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage(AnthropicRequest.of("m", 100, "s", List.of(AnthropicMessage.user("hi")), List.of()));

        assertEquals("tool_use", response.stopReason());
        assertEquals(1, response.toolUseBlocks().size());
        assertEquals("tu_1", response.toolUseBlocks().get(0).get("id"));
        assertEquals("list_switches", response.toolUseBlocks().get(0).get("name"));
        assertEquals(Map.of("limit", 5), response.toolUseBlocks().get(0).get("input"));
    }

    @Test
    void readsUsageTokens() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andRespond(withSuccess("""
                        {"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}],
                         "usage":{"input_tokens":123,"output_tokens":7}}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage(
                AnthropicRequest.of("m", 100, "sys", List.of(AnthropicMessage.user("hi")), List.of()));

        assertEquals(123, response.inputTokens());
        assertEquals(7, response.outputTokens());
    }

    @Test
    void apiErrorBecomesAiException() {
        server.expect(requestTo("https://api.anthropic.test/v1/messages"))
                .andRespond(withServerError());

        assertThrows(AiException.class,
                () -> client.createMessage(AnthropicRequest.of("m", 100, "s", List.of(AnthropicMessage.user("hi")), List.of())));
    }
}
