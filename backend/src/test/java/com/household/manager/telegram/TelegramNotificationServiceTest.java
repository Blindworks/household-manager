package com.household.manager.telegram;

import com.household.manager.common.DeliveryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotificationServiceTest {

    @Mock
    private TelegramApiClient apiClient;

    private TelegramProperties props(List<Long> chatIds) {
        TelegramProperties p = new TelegramProperties();
        p.setBotToken("t");
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

    @Test
    void sendTo_returnsFailed_whenApiThrows() {
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(1L, "x");
        TelegramNotificationService service = new TelegramNotificationService(props(List.of(1L)), apiClient);

        DeliveryResult result = service.sendTo(1L, "x");

        assertFalse(result.delivered());
        assertTrue(result.failure().contains("weg"));
    }

    @Test
    void sendToAllowedChats_returnsFailed_ifAnyChatFails() {
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(1L, "x");
        TelegramNotificationService service = new TelegramNotificationService(props(List.of(1L, 2L)), apiClient);

        DeliveryResult result = service.sendToAllowedChats("x");

        assertFalse(result.delivered());
        verify(apiClient).sendMessage(2L, "x");
    }

    @Test
    void sendTo_returnsFailed_whenNotConfigured() {
        TelegramNotificationService service = new TelegramNotificationService(props(List.of()), apiClient);

        DeliveryResult result = service.sendTo(5L, "x");

        assertFalse(result.delivered());
        assertTrue(result.failure().contains("nicht konfiguriert"));
    }

    @Test
    void sendTo_returnsOk_whenSent() {
        TelegramNotificationService service = new TelegramNotificationService(props(List.of(1L)), apiClient);

        assertTrue(service.sendTo(1L, "x").delivered());
    }
}
