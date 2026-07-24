package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TelegramApiClientTest {

    private TelegramApiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("123:SECRET");
        client = new TelegramApiClient(props, new RestTemplateBuilder());
        server = MockRestServiceServer.createServer(client.restTemplate());
    }

    @Test
    void getUpdatesParsesUpdatesAndIgnoresUnknownFields() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/getUpdates?offset=7&timeout=30"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":8,"unknown":1,
                          "message":{"text":"hallo","date":123,"chat":{"id":42,"type":"private"}}}]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        List<TelegramUpdate> updates = client.getUpdates(7, 30);

        assertEquals(1, updates.size());
        assertEquals(8, updates.get(0).updateId());
        assertEquals(42, updates.get(0).message().chat().id());
        assertEquals("hallo", updates.get(0).message().text());
    }

    @Test
    void sendMessagePostsChatIdAndText() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.chat_id").value(42))
                .andExpect(jsonPath("$.text").value("hi"))
                .andRespond(withSuccess("{\"ok\":true}", org.springframework.http.MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> client.sendMessage(42, "hi"));
    }

    @Test
    void sendMessageTruncatesOverlongText() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.hasLength(4096)))
                .andRespond(withSuccess("{\"ok\":true}", org.springframework.http.MediaType.APPLICATION_JSON));

        client.sendMessage(42, "x".repeat(5000));
    }

    @Test
    void errorsNeverLeakTheBotToken() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/getUpdates?offset=0&timeout=30"))
                .andRespond(withServerError());

        TelegramException ex = assertThrows(TelegramException.class, () -> client.getUpdates(0, 30));
        assertFalse(ex.getMessage().contains("SECRET"));
    }
}
