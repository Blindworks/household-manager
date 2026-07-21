package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NukiLockActionNodeHandlerTest {

    @Mock
    private NukiLockService lockService;

    private NukiLockActionNodeHandler handler() {
        return new NukiLockActionNodeHandler(lockService);
    }

    private final FlowMessage msg = FlowMessage.of(Map.of());

    @Test
    void locksAndPassesMessageThrough() {
        NodeResult result = handler().handle(msg,
                new NodeConfig(Map.of("smartlockId", "17958143231", "action", "lock")), null);

        verify(lockService).executeAction(17958143231L, NukiLockAction.LOCK);
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void unlocksAndUnlatches() {
        handler().handle(msg, new NodeConfig(Map.of("smartlockId", "42", "action", "unlock")), null);
        verify(lockService).executeAction(42L, NukiLockAction.UNLOCK);

        handler().handle(msg, new NodeConfig(Map.of("smartlockId", "42", "action", "unlatch")), null);
        verify(lockService).executeAction(42L, NukiLockAction.UNLATCH);
    }

    @Test
    void validateRequiresParseableIdAndValidAction() {
        assertEquals(2, handler().validate(NodeConfig.empty()).size());
        assertFalse(handler().validate(
                new NodeConfig(Map.of("smartlockId", "abc", "action", "lock"))).isEmpty());
        assertFalse(handler().validate(
                new NodeConfig(Map.of("smartlockId", "42", "action", "toggle"))).isEmpty());
        assertTrue(handler().validate(
                new NodeConfig(Map.of("smartlockId", "42", "action", "lock"))).isEmpty());
    }

    @Test
    void typeAndFieldsAreDescribed() {
        assertEquals("nuki-lock-action", handler().type());
        assertEquals(2, handler().fields().size());
    }
}
