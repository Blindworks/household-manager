package com.household.manager.agent;

import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentActionDescriberTest {

    private final ThermostatService thermostatService = mock(ThermostatService.class);
    private final SwitchQueryService switchQueryService = mock(SwitchQueryService.class);
    private final HouseModeQueryService houseModeQueryService = mock(HouseModeQueryService.class);
    private final NukiLockService nukiLockService = mock(NukiLockService.class);
    private AgentActionDescriber describer;

    @BeforeEach
    void setUp() {
        describer = new AgentActionDescriber(thermostatService, switchQueryService, houseModeQueryService,
                nukiLockService);
    }

    private static AgentAction action(ActionClass c, String target, Map<String, Object> params) {
        return new AgentAction(c, target, params, "geheimer Grund", false);
    }

    private ThermostatDto thermostat(long id, String name) {
        return new ThermostatDto(id, name, true, null, null, com.household.manager.tapo.hub.ThermostatMode.MANUAL,
                false, false, null);
    }

    @Test
    void heatingUsesNameAndGermanTemperature() {
        when(thermostatService.list()).thenReturn(List.of(thermostat(3L, "Wohnzimmer")));
        assertThat(describer.describe(action(ActionClass.HEATING, "3", Map.of("targetTemp", 21))))
                .isEqualTo("Heizung Wohnzimmer auf 21,0 °C");
        assertThat(describer.describe(action(ActionClass.HEATING, "3", Map.of("targetTemp", 21.5, "mode", "MANUAL"))))
                .isEqualTo("Heizung Wohnzimmer auf 21,5 °C (Modus MANUAL)");
    }

    @Test
    void heatingWithUnknownIdFallsBack() {
        when(thermostatService.list()).thenReturn(List.of());
        AgentAction a = action(ActionClass.HEATING, "3", Map.of("targetTemp", 21));
        assertThat(describer.describe(a)).isEqualTo(a.describe());
    }

    @Test
    void switchUsesDisplayNameAndGermanState() {
        SwitchResponse s = SwitchResponse.builder().entityId("switch.x").displayName("Stehlampe").build();
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(s));
        assertThat(describer.describe(action(ActionClass.SWITCH, "switch.x", Map.of("state", "on"))))
                .isEqualTo("Stehlampe an");
        assertThat(describer.describe(action(ActionClass.SWITCH, "switch.x", Map.of("state", "off"))))
                .isEqualTo("Stehlampe aus");
    }

    @Test
    void modeUsesDisplayName() {
        when(houseModeQueryService.listModes()).thenReturn(List.of(
                ModeResponse.builder().entityId("input_boolean.manual_nachtmodus").displayName("Nachtmodus").build()));
        assertThat(describer.describe(action(ActionClass.MODE, "input_boolean.manual_nachtmodus",
                Map.of("state", "off")))).isEqualTo("Nachtmodus aus");
    }

    @Test
    void lockUsesName() {
        when(nukiLockService.listLocks()).thenReturn(List.of(new NukiLockResponse(7L, "Haustür", "locked", null, null, false)));
        assertThat(describer.describe(action(ActionClass.LOCK, "7", Map.of()))).isEqualTo("Haustür verriegeln");
    }

    @Test
    void notifyShowsChannelAndMessageButNoteIsConstant() {
        AgentAction a = action(ActionClass.NOTIFY, "telegram", Map.of("message", "Fenster offen"));
        assertThat(describer.describe(a)).isEqualTo("Nachricht an Telegram: Fenster offen");
        assertThat(describer.describeForNote(a)).isEqualTo("Nachricht");
        assertThat(describer.describe(action(ActionClass.NOTIFY, "push", Map.of("message", "x"))))
                .isEqualTo("Nachricht an Push: x");
    }

    @Test
    void describeForNoteUsesDisplayNamesForDeviceActions() {
        when(houseModeQueryService.listModes()).thenReturn(List.of(
                ModeResponse.builder().entityId("m").displayName("Nachtmodus").build()));
        assertThat(describer.describeForNote(action(ActionClass.MODE, "m", Map.of("state", "on"))))
                .isEqualTo("Nachtmodus an");
    }

    @Test
    void throwingServiceFallsBack() {
        when(switchQueryService.listSwitches(any())).thenThrow(new IllegalStateException("db weg"));
        AgentAction a = action(ActionClass.SWITCH, "switch.x", Map.of("state", "on"));
        assertThat(describer.describe(a)).isEqualTo(a.describe());
    }
}
