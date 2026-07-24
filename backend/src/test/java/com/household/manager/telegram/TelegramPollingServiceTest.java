package com.household.manager.telegram;

import com.household.manager.telegram.dto.TelegramChat;
import com.household.manager.telegram.dto.TelegramMessage;
import com.household.manager.telegram.dto.TelegramUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramPollingServiceTest {

    @Mock
    private TelegramApiClient apiClient;
    @Mock
    private TelegramAgentService agentService;

    private TelegramPollingService service() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("t");
        props.setAnthropicApiKey("k");
        props.setAllowedChatIds(List.of(42L));
        return new TelegramPollingService(props, apiClient, agentService);
    }

    private TelegramUpdate update(long chatId, String text) {
        return new TelegramUpdate(1, new TelegramMessage(new TelegramChat(chatId), text));
    }

    @Test
    void allowedChatGetsAnAgentReply() {
        when(agentService.handleUserMessage(42L, "hallo")).thenReturn("Hi!");

        service().handleUpdate(update(42L, "hallo"));

        verify(apiClient).sendMessage(42L, "Hi!");
    }

    @Test
    void foreignChatIsIgnoredCompletely() {
        service().handleUpdate(update(99L, "lass mich rein"));

        verifyNoInteractions(agentService);
        verify(apiClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void updatesWithoutTextAreIgnored() {
        service().handleUpdate(new TelegramUpdate(1, null));
        service().handleUpdate(update(42L, null));

        verifyNoInteractions(agentService);
    }

    @Test
    void sendFailureDoesNotPropagate() {
        when(agentService.handleUserMessage(42L, "hallo")).thenReturn("Hi!");
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(42L, "Hi!");

        assertDoesNotThrow(() -> service().handleUpdate(update(42L, "hallo")));
    }
}
