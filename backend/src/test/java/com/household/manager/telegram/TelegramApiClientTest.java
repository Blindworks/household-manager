package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.client.MockRestServiceServer;

import org.springframework.http.MediaType;

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
    void sendMessageWithKeyboardPostsReplyMarkupAndReturnsMessageId() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.chat_id").value(5))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].text").value("Ja"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].callback_data").value("agent:1:APPROVE"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":42}}", MediaType.APPLICATION_JSON));

        long id = client.sendMessage(5L, "Hallo", List.of(List.of(new InlineButton("Ja", "agent:1:APPROVE"))));

        assertEquals(42L, id);
        server.verify();
    }

    @Test
    void sendMessageWithoutKeyboardOmitsReplyMarkup() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.reply_markup").doesNotExist())
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertEquals(0L, client.sendMessage(5L, "Hallo", List.of()));
    }

    @Test
    void editReplyMarkupWithEmptyKeyboardRemovesButtons() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/editMessageReplyMarkup"))
                .andExpect(jsonPath("$.chat_id").value(5))
                .andExpect(jsonPath("$.message_id").value(42))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard").isEmpty())
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        client.editReplyMarkup(5L, 42L, List.of());
        server.verify();
    }

    @Test
    void answerCallbackQueryPostsIdAndText() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/answerCallbackQuery"))
                .andExpect(jsonPath("$.callback_query_id").value("cb1"))
                .andExpect(jsonPath("$.text").value("Erledigt"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        client.answerCallbackQuery("cb1", "Erledigt");
        server.verify();
    }

    @Test
    void answerCallbackQueryWithNullTextSendsEmptyText() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/answerCallbackQuery"))
                .andExpect(jsonPath("$.text").value(""))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> client.answerCallbackQuery("cb1", null));
        server.verify();
    }

    @Test
    void keyboardWithNullFieldsDoesNotThrowNpe() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/sendMessage"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].text").value(""))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":1}}", MediaType.APPLICATION_JSON));

        assertEquals(1L, client.sendMessage(5L, "Hallo", List.of(List.of(new InlineButton(null, "agent:1:REJECT")))));
    }

    @Test
    void getUpdatesParsesCallbackQuery() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/getUpdates?offset=0&timeout=30"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":9,"callback_query":{"id":"cb1",
                          "from":{"id":7,"first_name":"Benedikt"},"data":"agent:3:REJECT",
                          "message":{"message_id":42,"chat":{"id":5}}}}]}
                        """, MediaType.APPLICATION_JSON));

        TelegramUpdate update = client.getUpdates(0, 30).get(0);

        assertNull(update.message());
        assertEquals("cb1", update.callbackQuery().id());
        assertEquals("Benedikt", update.callbackQuery().from().firstName());
        assertEquals(7L, update.callbackQuery().from().id());
        assertEquals("agent:3:REJECT", update.callbackQuery().data());
        assertEquals(42L, update.callbackQuery().message().messageId());
        assertEquals(5L, update.callbackQuery().message().chat().id());
    }

    @Test
    void errorsNeverLeakTheBotToken() {
        server.expect(requestTo("https://api.telegram.org/bot123:SECRET/getUpdates?offset=0&timeout=30"))
                .andRespond(withServerError());

        TelegramException ex = assertThrows(TelegramException.class, () -> client.getUpdates(0, 30));
        assertFalse(ex.getMessage().contains("SECRET"));
    }
}
