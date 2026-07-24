package com.household.manager.telegram.tools;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SetModeToolTest {

    @Mock
    private HouseModeQueryService houseModeQueryService;
    @Mock
    private ManualEntityService manualEntityService;

    private SetModeTool tool() {
        return new SetModeTool(houseModeQueryService, manualEntityService);
    }

    private ModeResponse mode(String entityId, String state) {
        return ModeResponse.builder().entityId(entityId).displayName("Modus").state(state).build();
    }

    @Test
    void togglesAKnownModeToDesiredState() throws Exception {
        when(houseModeQueryService.listModes()).thenReturn(List.of(mode("input_boolean.mode_night", "off")));

        tool().execute(Map.of("entityId", "input_boolean.mode_night", "state", "on"));

        verify(manualEntityService).toggle("input_boolean.mode_night");
    }

    @Test
    void refusesEntityThatIsNoMode() {
        when(houseModeQueryService.listModes()).thenReturn(List.of(mode("input_boolean.mode_night", "off")));

        assertThrows(IllegalArgumentException.class,
                () -> tool().execute(Map.of("entityId", "input_boolean.irgendwas", "state", "on")));
        verifyNoInteractions(manualEntityService);
    }

    @Test
    void skipsWhenAlreadyInDesiredState() throws Exception {
        when(houseModeQueryService.listModes()).thenReturn(List.of(mode("input_boolean.mode_night", "on")));

        String result = tool().execute(Map.of("entityId", "input_boolean.mode_night", "state", "on"));

        verify(manualEntityService, never()).toggle(any());
        assertTrue(result.contains("bereits"));
    }
}
