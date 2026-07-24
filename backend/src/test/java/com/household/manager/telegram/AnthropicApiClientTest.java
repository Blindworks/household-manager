package com.household.manager.telegram;

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
        TelegramProperties props = new TelegramProperties();
        props.setAnthropicApiKey("sk-test");
        props.setModel("claude-haiku-4-5-20251001");
        client = new AnthropicApiClient(props, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void sendsModelSystemMessagesAndToolsWithAuthHeaders() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(header("x-api-key", "sk-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
                .andExpect(jsonPath("$.system").value("Du bist ein Assistent"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.tools[0].name").value("list_switches"))
                .andRespond(withSuccess("""
                        {"stop_reason":"end_turn","content":[{"type":"text","text":"Hallo!"}]}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage("Du bist ein Assistent",
                List.of(AnthropicMessage.user("hi")),
                List.of(Map.of("name", "list_switches", "description", "d", "input_schema", Map.of())));

        assertEquals("end_turn", response.stopReason());
        assertEquals("Hallo!", response.text());
    }

    @Test
    void parsesToolUseBlocks() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("""
                        {"stop_reason":"tool_use","content":[
                          {"type":"text","text":"Ich schaue nach."},
                          {"type":"tool_use","id":"tu_1","name":"list_switches","input":{"limit":5}}]}
                        """, MediaType.APPLICATION_JSON));

        AnthropicResponse response = client.createMessage("s", List.of(AnthropicMessage.user("hi")), List.of());

        assertEquals("tool_use", response.stopReason());
        assertEquals(1, response.toolUseBlocks().size());
        assertEquals("tu_1", response.toolUseBlocks().get(0).get("id"));
        assertEquals("list_switches", response.toolUseBlocks().get(0).get("name"));
        assertEquals(Map.of("limit", 5), response.toolUseBlocks().get(0).get("input"));
    }

    @Test
    void apiErrorBecomesTelegramException() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withServerError());

        assertThrows(TelegramException.class,
                () -> client.createMessage("s", List.of(AnthropicMessage.user("hi")), List.of()));
    }
}
