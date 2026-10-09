package com.household.manager.thermostat;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.hub.TapoHubClient;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatPollingServiceTest {

    @Mock
    private ThermostatRepository repository;
    @Mock
    private TapoHubClient hubClient;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private ThermostatHistoryRecorder historyRecorder;

    private ThermostatStateCache cache;
    private ThermostatPollingService poller;
    private Thermostat living;
    private Thermostat bedroom;
    private Thermostat otherHub;

    @BeforeEach
    void setUp() {
        cache = new ThermostatStateCache();
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T16:30:00Z"), ZoneId.of("Europe/Berlin"));
        poller = new ThermostatPollingService(repository, hubClient, cache, new ThermostatEntityMapper(),
                historyRecorder, entityStateService, clock, new ObjectMapper());
        living = thermostat(1L, "KE-1", "Wohnzimmer", "10.0.0.1");
        bedroom = thermostat(2L, "KE-2", "Schlafzimmer", "10.0.0.1");
        otherHub = thermostat(3L, "KE-3", "Bad", "10.0.0.2");
        lenient().when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());
    }

    private Thermostat thermostat(long id, String child, String name, String hubIp) {
        return Thermostat.builder().id(id).childDeviceId(child).hubDeviceId("HUB").hubIp(hubIp)
                .name(name).active(true).build();
    }

    private ThermostatReading reading(String child, double current) {
        return new ThermostatReading(child, "x", true, current, 21.0, ThermostatMode.MANUAL, false, false);
    }

    @Test
    @DisplayName("Zwei Thermostate am selben Hub kosten genau einen Hub-Aufruf")
    void oneHubCallPerHub() {
        when(repository.findByActiveTrue()).thenReturn(List.of(living, bedroom, otherHub));
        when(hubClient.readThermostats("10.0.0.1")).thenReturn(List.of(reading("KE-1", 20), reading("KE-2", 19)));
        when(hubClient.readThermostats("10.0.0.2")).thenReturn(List.of(reading("KE-3", 18)));

        poller.poll();

        verify(hubClient, times(1)).readThermostats("10.0.0.1");
        verify(hubClient, times(1)).readThermostats("10.0.0.2");
        verify(entityStateService, times(9)).reportState(any(EntityStateUpdate.class));
    }

    @Test
    @DisplayName("Hub wirft: beide Thermostate unavailable, die Methode wirft nicht")
    void hubFailureMarksOnlyItsThermostatsUnavailable() {
        when(repository.findByActiveTrue()).thenReturn(List.of(living, bedroom, otherHub));
        when(hubClient.readThermostats("10.0.0.1")).thenThrow(new TapoException("Hub weg"));
        when(hubClient.readThermostats("10.0.0.2")).thenReturn(List.of(reading("KE-3", 18)));

        assertDoesNotThrow(() -> poller.poll());

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(9)).reportState(captor.capture());
        long unavailable = captor.getAllValues().stream().filter(u -> "unavailable".equals(u.state())).count();
        assertEquals(6, unavailable);
        assertFalse(cache.find(1L).orElseThrow().reading().online());
        assertEquals(true, cache.find(3L).orElseThrow().reading().online());
    }

    @Test
    @DisplayName("Kindgeraet fehlt in der Hub-Antwort: online=false und unavailable")
    void missingChildIsOffline() {
        when(repository.findByActiveTrue()).thenReturn(List.of(living, bedroom));
        when(hubClient.readThermostats("10.0.0.1")).thenReturn(List.of(reading("KE-1", 20)));

        poller.poll();

        assertEquals(true, cache.find(1L).orElseThrow().reading().online());
        assertFalse(cache.find(2L).orElseThrow().reading().online());
    }

    @Test
    @DisplayName("Historie wird vor dem Entity-Reporting geschrieben")
    void historyRecordedBeforeReporting() {
        when(repository.findByActiveTrue()).thenReturn(List.of(living));
        when(hubClient.readThermostats("10.0.0.1")).thenReturn(List.of(reading("KE-1", 20)));

        poller.poll();

        var order = inOrder(historyRecorder, entityStateService);
        order.verify(historyRecorder).record(any(), any(), any());
        order.verify(entityStateService, times(3)).reportState(any(EntityStateUpdate.class));
    }

    @Test
    @DisplayName("Thermostate ohne Hub-IP werden uebersprungen")
    void skipsThermostatsWithoutHubIp() {
        Thermostat noIp = thermostat(4L, "KE-4", "Keller", null);
        when(repository.findByActiveTrue()).thenReturn(List.of(noIp));

        poller.poll();

        verify(hubClient, never()).readThermostats(any());
    }

    @Test
    @DisplayName("Fehler im Repository lassen poll() nicht werfen")
    void repositoryFailureNeverThrows() {
        when(repository.findByActiveTrue()).thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> poller.poll());
    }
}
