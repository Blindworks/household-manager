package com.household.manager.agent.tools;

import com.household.manager.agent.ActionClass;
import com.household.manager.agent.AgentAction;
import com.household.manager.agent.AgentActionGate;
import com.household.manager.agent.GateResult;
import com.household.manager.ai.AgentScope;
import com.household.manager.calendar.CalendarEventService;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.dto.CalendarPersonView;
import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.nuki.NukiLockService;
import com.household.manager.nuki.dto.NukiLockResponse;
import com.household.manager.service.SeriesRange;
import com.household.manager.service.TemperatureSeriesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HouseActionToolsTest {

    @Mock AgentActionGate gate;
    @Mock NukiLockService nuki;
    @Mock TemperatureSeriesService temperatures;
    @Mock CalendarEventService calendar;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    @BeforeEach
    void stubGate() {
        lenient().when(gate.submit(any())).thenReturn(new GateResult("ok", 1L, true));
    }

    private AgentAction submitted() {
        ArgumentCaptor<AgentAction> captor = ArgumentCaptor.forClass(AgentAction.class);
        verify(gate).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    void setHeatingBuildsHeatingAction() throws Exception {
        SetHeatingTool tool = new SetHeatingTool(gate);
        assertEquals(AgentScope.HOUSE, tool.scope());
        assertEquals("ok", tool.execute(Map.of("thermostatId", 3, "targetTemp", 21.5, "reason", "Kalt")));
        AgentAction a = submitted();
        assertEquals(ActionClass.HEATING, a.actionClass());
        assertEquals("3", a.target());
        assertEquals(21.5, a.doubleParam("targetTemp"));
        assertFalse(a.params().containsKey("mode"));
        assertFalse(a.urgent());
    }

    @Test
    void setHeatingNeedsTempOrMode() {
        SetHeatingTool tool = new SetHeatingTool(gate);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(Map.of("thermostatId", 3, "reason", "x")));
        verifyNoInteractions(gate);
    }

    @Test
    void setHeatingNeedsReason() {
        SetHeatingTool tool = new SetHeatingTool(gate);
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("thermostatId", 3, "targetTemp", 20.0, "reason", " ")));
        verifyNoInteractions(gate);
    }

    @Test
    void setSwitchBuildsSwitchAction() throws Exception {
        HouseSetSwitchTool tool = new HouseSetSwitchTool(gate);
        assertEquals(AgentScope.HOUSE, tool.scope());
        tool.execute(Map.of("entityId", "switch.x", "state", "off", "reason", "Nacht"));
        AgentAction a = submitted();
        assertEquals(ActionClass.SWITCH, a.actionClass());
        assertEquals("switch.x", a.target());
        assertEquals("off", a.stringParam("state"));
    }

    @Test
    void setModeBuildsModeAction() throws Exception {
        HouseSetModeTool tool = new HouseSetModeTool(gate);
        assertEquals(AgentScope.HOUSE, tool.scope());
        tool.execute(Map.of("entityId", "input_boolean.manual_nacht", "state", "on", "reason", "Spät"));
        AgentAction a = submitted();
        assertEquals(ActionClass.MODE, a.actionClass());
        assertEquals("input_boolean.manual_nacht", a.target());
        assertEquals("on", a.stringParam("state"));
    }

    @Test
    void notifyBuildsNotifyActionWithUrgent() throws Exception {
        NotifyTool tool = new NotifyTool(gate);
        assertEquals(AgentScope.HOUSE, tool.scope());
        tool.execute(Map.of("channel", "push", "message", "Fenster offen", "urgent", true, "reason", "Frost"));
        AgentAction a = submitted();
        assertEquals(ActionClass.NOTIFY, a.actionClass());
        assertEquals("push", a.target());
        assertEquals("Fenster offen", a.stringParam("message"));
        assertEquals("push", a.stringParam("channel"));
        assertTrue(a.urgent());
    }

    @SuppressWarnings("unchecked")
    @Test
    void notifySchemaOffersOnlyTelegramAndPush() {
        Map<String, Object> properties = (Map<String, Object>) new NotifyTool(gate).inputSchema().get("properties");
        Map<String, Object> channel = (Map<String, Object>) properties.get("channel");
        assertEquals(List.of("telegram", "push"), channel.get("enum"));
    }

    @Test
    void notifyDefaultsToNotUrgent() throws Exception {
        new NotifyTool(gate).execute(Map.of("channel", "telegram", "message", "Hi", "reason", "r"));
        assertFalse(submitted().urgent());
    }

    @Test
    void lockDoorUsesGivenId() throws Exception {
        HouseLockDoorTool tool = new HouseLockDoorTool(gate, nuki);
        assertEquals(AgentScope.HOUSE, tool.scope());
        tool.execute(Map.of("smartlockId", "42", "reason", "Nacht"));
        AgentAction a = submitted();
        assertEquals(ActionClass.LOCK, a.actionClass());
        assertEquals("42", a.target());
        verifyNoInteractions(nuki);
    }

    @Test
    void lockDoorResolvesSingleLock() throws Exception {
        NukiLockResponse lock = mock(NukiLockResponse.class);
        when(lock.smartlockId()).thenReturn(7L);
        when(nuki.listLocks()).thenReturn(List.of(lock));
        new HouseLockDoorTool(gate, nuki).execute(Map.of("reason", "Nacht"));
        assertEquals("7", submitted().target());
    }

    @Test
    void lockDoorAmbiguousFails() {
        NukiLockResponse a = mock(NukiLockResponse.class);
        NukiLockResponse b = mock(NukiLockResponse.class);
        when(nuki.listLocks()).thenReturn(List.of(a, b));
        assertThrows(IllegalArgumentException.class,
                () -> new HouseLockDoorTool(gate, nuki).execute(Map.of("reason", "Nacht")));
        verifyNoInteractions(gate);
    }

    @Test
    void temperatureHistoryFormatsGermanAndCapsAt48Newest() throws Exception {
        List<TimeValue> points = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            points.add(TimeValue.builder().time(LocalDateTime.of(2026, 10, 12, 0, 0).plusMinutes(i * 10L))
                    .value(new BigDecimal("21.34")).build());
        }
        when(temperatures.getSensorSeries("zigbee:1", SeriesRange.DAY)).thenReturn(
                TemperatureSensorSeries.builder().sensorId("zigbee:1").name("Wohnzimmer").source("ZIGBEE")
                        .temperature(points).humidity(List.of()).build());
        GetTemperatureHistoryTool tool = new GetTemperatureHistoryTool(temperatures);
        assertEquals(AgentScope.HOUSE, tool.scope());
        String out = tool.execute(Map.of("sensorId", "zigbee:1"));
        String[] lines = out.strip().split("\n");
        assertTrue(lines[0].contains("Wohnzimmer"));
        assertEquals(48, Arrays.stream(lines).filter(l -> l.matches("\\d\\d:\\d\\d: .* °C")).count());
        assertTrue(out.contains("21,3 °C"));
        assertFalse(out.contains("00:00: "), "älteste Punkte fallen weg");
        assertTrue(out.contains("09:50: "), "neuester Punkt bleibt");
    }

    @Test
    void calendarFiltersWindowAndFormatsLines() throws Exception {
        CalendarOccurrenceResponse timed = CalendarOccurrenceResponse.builder()
                .occurrenceDate(LocalDate.of(2026, 10, 13)).startTime(LocalTime.of(8, 0)).title("Zahnarzt")
                .persons(List.of(new CalendarPersonView(1L, "Benedikt"))).build();
        CalendarOccurrenceResponse allDay = CalendarOccurrenceResponse.builder()
                .occurrenceDate(LocalDate.of(2026, 10, 14)).allDay(true).title("Müll").persons(List.of()).build();
        CalendarOccurrenceResponse far = CalendarOccurrenceResponse.builder()
                .occurrenceDate(LocalDate.of(2026, 10, 30)).allDay(true).title("Fern").persons(List.of()).build();
        when(calendar.getUpcoming(20)).thenReturn(List.of(timed, allDay, far));
        GetCalendarTool tool = new GetCalendarTool(calendar, clock);
        assertEquals(AgentScope.HOUSE, tool.scope());
        String out = tool.execute(Map.of("days", 3));
        assertTrue(out.contains("2026-10-13 08:00 Zahnarzt (Benedikt)"));
        assertTrue(out.contains("2026-10-14 ganztägig Müll"));
        assertFalse(out.contains("Fern"));
    }

    @Test
    void calendarRejectsDaysOutOfRange() {
        GetCalendarTool tool = new GetCalendarTool(calendar, clock);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(Map.of("days", 15)));
    }
}
