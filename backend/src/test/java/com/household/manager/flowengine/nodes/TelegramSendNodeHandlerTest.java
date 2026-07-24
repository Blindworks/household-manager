package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.telegram.TelegramNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TelegramSendNodeHandlerTest {

    @Mock
    private TelegramNotificationService notificationService;

    private TelegramSendNodeHandler handler() {
        return new TelegramSendNodeHandler(notificationService);
    }

    @Test
    void broadcastsWithResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "{entityId} ist jetzt {newState}"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "switch.x", "newState", "on", "oldState", "off"));

        NodeResult result = handler().handle(msg, cfg, null);

        verify(notificationService).sendToAllowedChats("switch.x ist jetzt on");
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void explicitChatIdSendsOnlyThere() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi", "chatId", "42"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(notificationService).sendTo(42L, "hi");
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
