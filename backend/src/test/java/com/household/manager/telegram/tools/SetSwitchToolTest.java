package com.household.manager.telegram.tools;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetSwitchToolTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private SwitchCommandService switchCommandService;

    private EntityState entity(String state) {
        EntityState e = new EntityState();
        e.setEntityId("switch.meross_x");
        e.setState(state);
        return e;
    }

    private SetSwitchTool tool() {
        return new SetSwitchTool(entityStateService, switchCommandService);
    }

    @Test
    void togglesWhenStateDiffers() throws Exception {
        when(entityStateService.getByEntityId("switch.meross_x")).thenReturn(Optional.of(entity("off")));

        String result = tool().execute(Map.of("entityId", "switch.meross_x", "state", "on"));

        verify(switchCommandService).toggle("switch.meross_x");
        assertTrue(result.contains("on"));
    }

    @Test
    void skipsToggleWhenAlreadyInDesiredState() throws Exception {
        when(entityStateService.getByEntityId("switch.meross_x")).thenReturn(Optional.of(entity("on")));

        String result = tool().execute(Map.of("entityId", "switch.meross_x", "state", "on"));

        verify(switchCommandService, never()).toggle(any());
        assertTrue(result.contains("bereits"));
    }

    @Test
    void unknownEntityFails() {
        when(entityStateService.getByEntityId("switch.nix")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("entityId", "switch.nix", "state", "on")));
    }

    @Test
    void invalidStateFails() {
        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("entityId", "switch.meross_x", "state", "an")));
    }
}
