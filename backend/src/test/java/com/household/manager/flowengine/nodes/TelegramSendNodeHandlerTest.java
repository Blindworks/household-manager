package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.telegram.TelegramNotificationService;
import com.household.manager.common.DeliveryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramSendNodeHandlerTest {

    @Mock
    private TelegramNotificationService notificationService;
    @Mock
    private NodeContext ctx;

    private TelegramSendNodeHandler handler() {
        return new TelegramSendNodeHandler(notificationService);
    }

    @Test
    void broadcastsWithResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "{entityId} ist jetzt {newState}"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "switch.x", "newState", "on", "oldState", "off"));

        when(notificationService.sendToAllowedChats("switch.x ist jetzt on")).thenReturn(DeliveryResult.ok());

        NodeResult result = handler().handle(msg, cfg, ctx);

        verify(notificationService).sendToAllowedChats("switch.x ist jetzt on");
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void explicitChatIdSendsOnlyThere() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi", "chatId", "42"));

        when(notificationService.sendTo(42L, "hi")).thenReturn(DeliveryResult.ok());

        handler().handle(FlowMessage.of(Map.of()), cfg, ctx);

        verify(notificationService).sendTo(42L, "hi");
    }

    @Test
    void handle_reportsFailure_whenDeliveryFails() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi"));
        when(notificationService.sendToAllowedChats("hi"))
                .thenReturn(DeliveryResult.failed("Telegram nicht konfiguriert"));

        NodeResult result = handler().handle(FlowMessage.of(Map.of()), cfg, ctx);

        verify(ctx).reportFailure(contains("Telegram nicht konfiguriert"));
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void handle_doesNotReportFailure_whenDelivered() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi"));
        when(notificationService.sendToAllowedChats("hi")).thenReturn(DeliveryResult.ok());

        handler().handle(FlowMessage.of(Map.of()), cfg, ctx);

        verify(ctx, never()).reportFailure(anyString());
    }

    @Test
    void validateRequiresMessageAndNumericChatId() {
        assertFalse(handler().validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("message", "x", "chatId", "abc"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x", "chatId", "42"))).isEmpty());
    }

    @Test
    void typeAndPortsMatchCatalogExpectations() {
        assertEquals("telegram-send", handler().type());
        assertEquals(1, handler().outputPorts());
        assertFalse(handler().fields().isEmpty());
    }
}
