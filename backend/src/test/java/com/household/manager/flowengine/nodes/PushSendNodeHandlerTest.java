package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.push.PushNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PushSendNodeHandlerTest {

    @Mock
    private PushNotificationService notificationService;

    private PushSendNodeHandler handler() {
        return new PushSendNodeHandler(notificationService);
    }

    @Test
    void broadcastsWithDefaultTitleAndResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "{entityId} ist jetzt {newState}"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "switch.x", "newState", "on", "oldState", "off"));

        NodeResult result = handler().handle(msg, cfg, null);

        verify(notificationService).sendToAll("Household Manager", "switch.x ist jetzt on");
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void explicitUserIdSendsOnlyToThatUser() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi", "title", "Alarm", "userId", "7"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(notificationService).sendToUser(7L, "Alarm", "hi");
    }

    @Test
    void titleSupportsPlaceholdersToo() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "x", "title", "{entityId}"));

        handler().handle(FlowMessage.of(Map.of("entityId", "sensor.tuer")), cfg, null);

        verify(notificationService).sendToAll("sensor.tuer", "x");
    }

    @Test
    void validateRequiresMessageAndNumericUserId() {
        assertFalse(handler().validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler().validate(new NodeConfig(Map.of("message", "x", "userId", "abc"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("message", "x", "userId", "7"))).isEmpty());
    }

    @Test
    void typeAndPortsMatchCatalogExpectations() {
        assertEquals("push-send", handler().type());
        assertEquals(1, handler().outputPorts());
        assertFalse(handler().fields().isEmpty());
    }
}
