package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.EntityState;
import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZigbeeAvailabilityWatchdogTest {

    @Mock
    private ZigbeeStreamMonitor monitor;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private ZigbeeConnectionControl connectionControl;

    private final ZigbeeWatchdogProperties properties = new ZigbeeWatchdogProperties();
    private ZigbeeAvailabilityWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new ZigbeeAvailabilityWatchdog(
                monitor, properties, entityStateService, connectionControl, new ObjectMapper());
        when(entityStateService.find(isNull(), eq(EntitySource.ZIGBEE)))
                .thenReturn(List.of(sensorEntity(), buttonEntity()));
    }

    private EntityState sensorEntity() {
        return EntityState.builder()
                .entityId("sensor.zigbee_temperatur_buero_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Temperatur Buero")
                .friendlyName("Temperatur Buero Temperatur")
                .state("21.3")
                .attributes("{\"unit\":\"°C\",\"deviceClass\":\"temperature\"}")
                .build();
    }

    private EntityState buttonEntity() {
        return EntityState.builder()
                .entityId("event.zigbee_button_buero_action")
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Button Buero")
                .friendlyName("Button Buero Taster")
                .state("single")
                .build();
    }

    private void silentFor(long minutes) {
        when(monitor.status()).thenReturn(new ZigbeeStreamStatus(
                ZigbeeStreamStatus.Health.STILL, Instant.parse("2026-07-28T12:00:00Z"),
                minutes, "online", Instant.parse("2026-07-28T12:00:00Z"), List.of()));
    }

    private void healthy() {
        when(monitor.status()).thenReturn(new ZigbeeStreamStatus(
                ZigbeeStreamStatus.Health.OK, Instant.parse("2026-07-28T12:00:00Z"),
                0, "online", Instant.parse("2026-07-28T12:00:00Z"), List.of()));
    }

    @Test
    void tutNichtsSolangeAllesLaeuft() {
        healthy();

        watchdog.check();

        verifyNoInteractions(connectionControl);
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void versuchtBeiStilleZuerstDieSelbstheilung() {
        silentFor(16);

        watchdog.check();

        verify(connectionControl).forceReconnect();
        verify(entityStateService, never()).reportState(any());
        verify(entityStateService, never()).reportEvent(any());
    }

    @Test
    void meldetKeinenAlarmWennDieSelbstheilungGreift() {
        silentFor(16);
        watchdog.check();
        healthy();

        watchdog.check();

        verify(entityStateService, never()).reportEvent(any());
    }

    @Test
    void meldetAusfallErstNachDerGnadenfrist() {
        silentFor(16);
        watchdog.check();
        silentFor(16 + properties.getRecoverGraceMinutes() + 1);

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("event.zigbee_bridge_status");
        assertThat(captor.getValue().state()).isEqualTo("failed");
    }

    @Test
    void schweigtWaehrendDerGnadenfrist() {
        silentFor(16);
        watchdog.check();
        silentFor(16 + properties.getRecoverGraceMinutes() - 1);

        watchdog.check();

        verify(entityStateService, never()).reportEvent(any());
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void setztNurZustandsEntitaetenAufUnavailableUndBehaeltDieAttribute() {
        silentFor(16);
        watchdog.check();
        silentFor(99);

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(1)).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.zigbee_temperatur_buero_temperature");
        assertThat(update.state()).isEqualTo("unavailable");
        assertThat(update.attributes()).containsEntry("deviceClass", "temperature");
    }

    @Test
    void meldetDenAusfallNurEinmal() {
        silentFor(16);
        watchdog.check();
        silentFor(99);
        watchdog.check();
        watchdog.check();
        watchdog.check();

        verify(entityStateService, times(1)).reportEvent(any());
        // Ebenso wenig darf sich der Rest wiederholen: reportState waere im Ausfall
        // eine Schreiblast pro Minute, forceReconnect wuerde die MQTT-Verbindung
        // minuetlich trennen und die Anbindung zusaetzlich sabotieren.
        verify(entityStateService, times(1)).reportState(any());
        verify(connectionControl, times(1)).forceReconnect();
    }

    @Test
    void gibtEntwarnungWennDieAnbindungZurueckkommt() {
        silentFor(16);
        watchdog.check();
        silentFor(99);
        watchdog.check();
        reset(entityStateService);
        healthy();

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("recovered");
    }

    @Test
    void wirftNiemals() {
        when(monitor.status()).thenThrow(new IllegalStateException("boom"));

        watchdog.check();
    }
}
