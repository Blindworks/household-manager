package com.household.manager.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationServiceTest {

    @Mock
    private TelegramApiClient apiClient;

    private TelegramProperties props(List<Long> chatIds) {
        TelegramProperties p = new TelegramProperties();
        p.setBotToken("t");
        p.setAnthropicApiKey("k");
        p.setAllowedChatIds(chatIds);
        return p;
    }

    @Test
    void broadcastsToAllAllowedChats() {
        TelegramNotificationService service =
                new TelegramNotificationService(props(List.of(1L, 2L)), apiClient);

        service.sendToAllowedChats("Waschmaschine fertig");

        verify(apiClient).sendMessage(1L, "Waschmaschine fertig");
        verify(apiClient).sendMessage(2L, "Waschmaschine fertig");
    }

    @Test
    void oneFailingChatDoesNotStopTheOthers() {
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(1L, "x");
        TelegramNotificationService service =
                new TelegramNotificationService(props(List.of(1L, 2L)), apiClient);

        assertDoesNotThrow(() -> service.sendToAllowedChats("x"));
        verify(apiClient).sendMessage(2L, "x");
    }

    @Test
    void unconfiguredIntegrationSendsNothing() {
        TelegramProperties unconfigured = props(List.of());
        TelegramNotificationService service = new TelegramNotificationService(unconfigured, apiClient);

        service.sendToAllowedChats("x");
        service.sendTo(5L, "x");

        verifyNoInteractions(apiClient);
    }
}
