package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.push.PushNotificationService;
import com.household.manager.common.DeliveryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSendNodeHandlerTest {

    @Mock
    private PushNotificationService notificationService;
    @Mock
    private NodeContext ctx;

    private PushSendNodeHandler handler() {
        return new PushSendNodeHandler(notificationService);
    }

    @Test
    void broadcastsWithDefaultTitleAndResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "{entityId} ist jetzt {newState}"));
        FlowMessage msg = FlowMessage.of(Map.of("entityId", "switch.x", "newState", "on", "oldState", "off"));

        when(notificationService.sendToAll("Household Manager", "switch.x ist jetzt on"))
                .thenReturn(DeliveryResult.ok());

        NodeResult result = handler().handle(msg, cfg, ctx);

        verify(notificationService).sendToAll("Household Manager", "switch.x ist jetzt on");
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void explicitUserIdSendsOnlyToThatUser() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi", "title", "Alarm", "userId", "7"));

        when(notificationService.sendToUser(7L, "Alarm", "hi")).thenReturn(DeliveryResult.ok());

        handler().handle(FlowMessage.of(Map.of()), cfg, ctx);

        verify(notificationService).sendToUser(7L, "Alarm", "hi");
    }

    @Test
    void handle_reportsFailure_whenDeliveryFails() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi"));
        when(notificationService.sendToAll("Household Manager", "hi"))
                .thenReturn(DeliveryResult.failed("keine Push-Geräte"));

        NodeResult result = handler().handle(FlowMessage.of(Map.of()), cfg, ctx);

        verify(ctx).reportFailure(contains("keine Push-Geräte"));
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void handle_doesNotReportFailure_whenDelivered() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "hi"));
        when(notificationService.sendToAll("Household Manager", "hi")).thenReturn(DeliveryResult.ok());

        handler().handle(FlowMessage.of(Map.of()), cfg, ctx);

        verify(ctx, never()).reportFailure(anyString());
    }

    @Test
    void titleSupportsPlaceholdersToo() {
        NodeConfig cfg = new NodeConfig(Map.of("message", "x", "title", "{entityId}"));

        when(notificationService.sendToAll("sensor.tuer", "x")).thenReturn(DeliveryResult.ok());

        handler().handle(FlowMessage.of(Map.of("entityId", "sensor.tuer")), cfg, ctx);

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
