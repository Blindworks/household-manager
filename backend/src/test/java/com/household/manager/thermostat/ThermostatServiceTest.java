package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.hub.TapoHubClient;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatServiceTest {

    private static final String HUB_IP = "192.168.1.50";

    @Mock
    private ThermostatRepository repository;
    @Mock
    private TapoHubClient hubClient;
    @Mock
    private AuditService auditService;

    private ThermostatStateCache cache;
    private ThermostatService service;
    private Thermostat living;
    private Thermostat bedroom;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T16:30:00Z"), ZoneId.of("Europe/Berlin"));
        cache = new ThermostatStateCache();
        service = new ThermostatService(repository, hubClient, cache, auditService, clock);
        living = thermostat(1L, "KE-1", "Wohnzimmer");
        bedroom = thermostat(2L, "KE-2", "Schlafzimmer");
        lenient().when(repository.findByActiveTrue()).thenReturn(List.of(living, bedroom));
        lenient().when(repository.findById(1L)).thenReturn(Optional.of(living));
        lenient().when(repository.findById(2L)).thenReturn(Optional.of(bedroom));
        lenient().when(repository.findByHubIp(HUB_IP)).thenReturn(List.of(living, bedroom));
        lenient().when(hubClient.readThermostats(HUB_IP)).thenReturn(List.of(
                reading("KE-1", 20.5, 21.5), reading("KE-2", 18.0, 17.0)));
    }

    private Thermostat thermostat(long id, String childId, String name) {
        return Thermostat.builder().id(id).childDeviceId(childId).hubDeviceId("HUB")
                .hubIp(HUB_IP).name(name).active(true).build();
    }

    private ThermostatReading reading(String childId, double current, double target) {
        return new ThermostatReading(childId, "x", true, current, target, ThermostatMode.MANUAL, false, false);
    }

    @Test
    @DisplayName("setTarget ruft den Client mit Hub-IP und Kind-Id, liest nach und auditiert danach")
    void setTargetWritesReadsBackAndAudits() {
        ThermostatDto dto = service.setTarget(1L, 21.5);

        var order = inOrder(hubClient, auditService);
        order.verify(hubClient).setTargetTemp(HUB_IP, "KE-1", 21.5);
        order.verify(hubClient).readThermostats(HUB_IP);
        order.verify(auditService).record(org.mockito.ArgumentMatchers.eq("thermostat.target"), anyString());
        assertEquals(21.5, dto.targetTemp());
        assertEquals(20.5, dto.currentTemp());
        assertEquals(LocalDateTime.of(2026, 10, 9, 18, 30), dto.lastUpdated());
    }

    @Test
    @DisplayName("Bei Client-Fehler gibt es keinen Audit-Eintrag")
    void noAuditWhenClientFails() {
        doThrow(new TapoException("Hub weg")).when(hubClient).setTargetTemp(HUB_IP, "KE-1", 21.5);

        assertThrows(TapoException.class, () -> service.setTarget(1L, 21.5));

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Fehlschlagendes Nachlesen nach erfolgreichem Schreiben kippt die Aktion nicht")
    void readBackFailureDoesNotFailWrite() {
        when(hubClient.readThermostats(HUB_IP)).thenThrow(new TapoException("Nachlesen tot"));

        service.setTarget(1L, 21.5);

        verify(auditService).record(org.mockito.ArgumentMatchers.eq("thermostat.target"), anyString());
    }

    @Test
    @DisplayName("Ungueltige Solltemperatur wird vor jedem Geraetezugriff abgelehnt")
    void invalidTargetRejectedBeforeHub() {
        assertThrows(IllegalArgumentException.class, () -> service.setTarget(1L, 21.3));

        verifyNoInteractions(hubClient);
    }

    @Test
    @DisplayName("Unbekannte Id ergibt ResourceNotFoundException")
    void unknownIdIsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.setTarget(99L, 21.0));
    }

    @Test
    @DisplayName("setMode OFF setzt Ziel 5 Grad und schaltet Frostschutz aus")
    void modeOffSetsFiveDegreesWithoutFrostProtection() {
        service.setMode(1L, ThermostatMode.OFF);

        verify(hubClient).setTargetTemp(HUB_IP, "KE-1", 5.0);
        verify(hubClient).setFrostProtection(HUB_IP, "KE-1", false);
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("thermostat.mode"), anyString());
    }

    @Test
    @DisplayName("setMode FROST_PROTECTION schaltet nur Frostschutz an")
    void modeFrostProtectionOnlyTogglesFlag() {
        service.setMode(1L, ThermostatMode.FROST_PROTECTION);

        verify(hubClient).setFrostProtection(HUB_IP, "KE-1", true);
        verify(hubClient, never()).setTargetTemp(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("setMode MANUAL schaltet Frostschutz aus und laesst das Ziel stehen")
    void modeManualClearsFrostProtectionOnly() {
        service.setMode(1L, ThermostatMode.MANUAL);

        verify(hubClient).setFrostProtection(HUB_IP, "KE-1", false);
        verify(hubClient, never()).setTargetTemp(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("bulk ohne Ids trifft alle aktiven Thermostate, liest je Hub einmal nach, ein Audit")
    void bulkWithoutIdsHitsAllActive() {
        List<ThermostatDto> result = service.bulk(null, 17.0);

        verify(hubClient).setTargetTemp(HUB_IP, "KE-1", 17.0);
        verify(hubClient).setTargetTemp(HUB_IP, "KE-2", 17.0);
        verify(hubClient, org.mockito.Mockito.times(1)).readThermostats(HUB_IP);
        verify(auditService, org.mockito.Mockito.times(1))
                .record(org.mockito.ArgumentMatchers.eq("thermostat.bulk"), anyString());
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("bulk mit Ids trifft nur diese")
    void bulkWithIdsHitsOnlyThose() {
        service.bulk(List.of(2L), 17.0);

        verify(hubClient).setTargetTemp(HUB_IP, "KE-2", 17.0);
        verify(hubClient, never()).setTargetTemp(HUB_IP, "KE-1", 17.0);
    }

    @Test
    @DisplayName("list liefert ohne Cache-Eintrag offline ohne erfundene Werte")
    void listWithoutCacheIsOffline() {
        List<ThermostatDto> list = service.list();

        assertEquals(2, list.size());
        ThermostatDto first = list.get(0);
        assertEquals(false, first.online());
        assertEquals(null, first.currentTemp());
        assertEquals(null, first.lastUpdated());
    }
}
