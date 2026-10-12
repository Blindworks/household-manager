package com.household.manager.agent;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.ModeResponse;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.nuki.NukiLockAction;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.push.PushNotificationService;
import com.household.manager.service.AlexaAnnouncementService;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.telegram.TelegramNotificationService;
import com.household.manager.thermostat.ThermostatDto;
import com.household.manager.thermostat.ThermostatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentActionExecutorTest {
    @Mock ThermostatService thermostatService;
    @Mock SwitchQueryService switchQueryService;
    @Mock SwitchCommandService switchCommandService;
    @Mock HouseModeQueryService houseModeQueryService;
    @Mock ManualEntityService manualEntityService;
    @Mock NukiLockService nukiLockService;
    @Mock TelegramNotificationService telegramNotificationService;
    @Mock PushNotificationService pushNotificationService;
    @Mock AlexaAnnouncementService alexaAnnouncementService;
    @Mock AuditService auditService;

    AgentActionExecutor executor() {
        return new AgentActionExecutor(thermostatService, switchQueryService, switchCommandService,
                houseModeQueryService, manualEntityService, nukiLockService, telegramNotificationService,
                pushNotificationService, alexaAnnouncementService, auditService);
    }

    @Test void heatingSetsTargetAndMode() {
        when(thermostatService.list()).thenReturn(List.of(new ThermostatDto(3L, "Wohnzimmer", true, 18.5, 17.0, ThermostatMode.MANUAL, false, false, null)));
        ActionOutcome out = executor().execute(new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), "kalt", false));
        verify(thermostatService).setTarget(3L, 21.0);
        assertFalse(out.alreadySatisfied());
    }
    @Test void heatingAlreadyAtTargetDoesNothing() {
        when(thermostatService.list()).thenReturn(List.of(new ThermostatDto(3L, "Wohnzimmer", true, 20.0, 21.0, ThermostatMode.MANUAL, false, false, null)));
        ActionOutcome out = executor().execute(new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), "kalt", false));
        verify(thermostatService, never()).setTarget(anyLong(), anyDouble());
        assertTrue(out.alreadySatisfied());
    }
    @Test void switchTogglesOnlyWhenStateDiffers() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("off").displayName("X").build()));
        executor().execute(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "r", false));
        verify(switchCommandService).toggle("switch.x");
    }
    @Test void switchAlreadyOnSkips() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("on").displayName("X").build()));
        assertTrue(executor().execute(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "r", false)).alreadySatisfied());
        verifyNoInteractions(switchCommandService);
    }
    @Test void unknownSwitchThrows() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> executor().execute(new AgentAction(ActionClass.SWITCH, "switch.nope", Map.of("state", "on"), "r", false)));
    }
    @Test void modeUsesManualEntityToggle() {
        when(houseModeQueryService.listModes()).thenReturn(List.of(ModeResponse.builder().entityId("input_boolean.manual_nachtmodus").state("off").displayName("Nacht").build()));
        executor().execute(new AgentAction(ActionClass.MODE, "input_boolean.manual_nachtmodus", Map.of("state", "on"), "r", false));
        verify(manualEntityService).toggle("input_boolean.manual_nachtmodus");
    }
    @Test void lockOnlyLocks() {
        when(nukiLockService.listLocks()).thenReturn(List.of(new NukiLockResponse(7L, "Haustür", "unlocked", "closed", 80, false)));
        executor().execute(new AgentAction(ActionClass.LOCK, "7", Map.of(), "r", false));
        verify(nukiLockService).executeAction(7L, NukiLockAction.LOCK);
    }
    @Test void lockAlreadyLockedSkips() {
        when(nukiLockService.listLocks()).thenReturn(List.of(new NukiLockResponse(7L, "Haustür", "locked", "closed", 80, false)));
        assertTrue(executor().execute(new AgentAction(ActionClass.LOCK, "7", Map.of(), "r", false)).alreadySatisfied());
        verify(nukiLockService, never()).executeAction(anyLong(), any());
    }
    @Test void notifyTelegramSendsToAllowedChats() {
        executor().execute(new AgentAction(ActionClass.NOTIFY, "telegram", Map.of("channel", "telegram", "message", "Hallo"), "r", false));
        verify(telegramNotificationService).sendToAllowedChats("Hallo");
    }
    @Test void notifyPushUsesAgentTitle() {
        executor().execute(new AgentAction(ActionClass.NOTIFY, "push", Map.of("channel", "push", "message", "Hallo"), "r", false));
        verify(pushNotificationService).sendToAll("Hausverstand", "Hallo");
    }
    @Test void notifyAlexaAnnouncesWithChime() {
        executor().execute(new AgentAction(ActionClass.NOTIFY, "alexa", Map.of("channel", "alexa", "message", "Hallo"), "r", false));
        verify(alexaAnnouncementService).announce("Hallo", List.of(), AlexaTtsMode.ANNOUNCE);
    }
    @Test void everyExecutedActionIsAudited() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("off").displayName("X").build()));
        executor().execute(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "weil", false));
        verify(auditService).record(eq(AuditActor.agent()), eq("agent.action.execute"), contains("switch.x"));
    }
    @Test void isSatisfiedTrueWhenSwitchAlreadyOn() {
        when(switchQueryService.listSwitches(null)).thenReturn(List.of(SwitchResponse.builder().entityId("switch.x").state("on").displayName("X").build()));
        assertTrue(executor().isSatisfied(new AgentAction(ActionClass.SWITCH, "switch.x", Map.of("state", "on"), "r", false)));
        verifyNoInteractions(switchCommandService);
        verifyNoInteractions(auditService);
    }
    @Test void isSatisfiedFalseForNotify() {
        assertFalse(executor().isSatisfied(new AgentAction(ActionClass.NOTIFY, "push", Map.of("channel", "push", "message", "x"), "r", false)));
        verifyNoInteractions(pushNotificationService);
    }
}
