package com.household.manager.flowengine.nodes;

import com.household.manager.audit.AuditService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.SmartDeviceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SwitchDeviceNodeHandlerTest {

    @Mock
    private SmartDeviceService smartDeviceService;
    @Mock
    private AuditService auditService;

    private SwitchDeviceNodeHandler handler() {
        return new SwitchDeviceNodeHandler(smartDeviceService, auditService);
    }

    private final FlowMessage msg = FlowMessage.of(Map.of());

    @Test
    void turnsOnAndPassesMessageThrough() {
        NodeResult result = handler().handle(msg, new NodeConfig(Map.of("deviceId", 42, "action", "on")), null);

        verify(smartDeviceService).turnOn(42L);
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void turnsOff() {
        handler().handle(msg, new NodeConfig(Map.of("deviceId", 42, "action", "off")), null);
        verify(smartDeviceService).turnOff(42L);
    }

    @Test
    void auditiertErfolgreichesSchalten() {
        handler().handle(msg, new NodeConfig(Map.of("deviceId", 42, "action", "on")), null);
        verify(auditService).record("switch.device", "42 -> on");
    }

    @Test
    void validateRequiresDeviceIdAndValidAction() {
        assertEquals(2, handler().validate(NodeConfig.empty()).size());
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", 1, "action", "toggle"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("deviceId", 1, "action", "on"))).isEmpty());
    }
}
