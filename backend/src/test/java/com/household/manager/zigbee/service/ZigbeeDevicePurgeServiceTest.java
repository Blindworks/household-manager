package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.ZigbeeDeviceKnownToBridgeException;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeDevicePurgeServiceTest {

    @Mock private ZigbeeDeviceRepository deviceRepository;
    @Mock private ZigbeeMeasurementRepository measurementRepository;
    @Mock private EntityStateRepository entityStateRepository;
    @Mock private EntityTileVisibilityRepository tileVisibilityRepository;
    @Mock private AuditService auditService;

    private final ZigbeeDeviceRegistry registry =
            new ZigbeeDeviceRegistry(Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC));
    private ZigbeeDevicePurgeService service;

    private final ZigbeeDevice device = ZigbeeDevice.builder().id(7L).friendlyName("Alter Sensor").build();

    @BeforeEach
    void setUp() {
        service = new ZigbeeDevicePurgeService(registry, deviceRepository, measurementRepository,
                entityStateRepository, tileVisibilityRepository, auditService);
        registry.replaceDevices(List.of());
    }

    private static EntityState entity(String id, String ref) {
        return EntityState.builder().entityId(id).domain(EntityDomain.SENSOR).source(EntitySource.ZIGBEE)
                .sourceRef(ref).friendlyName(id).state("1").build();
    }

    @Test
    void loeschtMesswerteGeraetEntitaetenUndKachelregeln() {
        when(deviceRepository.findById(7L)).thenReturn(Optional.of(device));
        when(measurementRepository.deleteByDeviceId(7L)).thenReturn(1234);
        EntityState mine = entity("sensor.zigbee_alter_sensor_temperature", "Alter Sensor");
        EntityState other = entity("sensor.zigbee_anderes_temperature", "Anderes");
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of(mine, other));
        EntityTileVisibility rule = EntityTileVisibility.builder()
                .entityId("sensor.zigbee_alter_sensor_temperature").tileKey("switches").build();
        when(tileVisibilityRepository.findByEntityIdIn(List.of("sensor.zigbee_alter_sensor_temperature")))
                .thenReturn(List.of(rule));

        ZigbeeDevicePurgeService.PurgeResult result = service.purge(7L);

        assertThat(result.measurements()).isEqualTo(1234);
        assertThat(result.entities()).isEqualTo(1);
        verify(tileVisibilityRepository).deleteAll(List.of(rule));
        verify(entityStateRepository).deleteAll(List.of(mine));
        verify(deviceRepository).delete(device);
        verify(auditService).record("zigbee.device.purge", "Alter Sensor: 1234 Messwerte, 1 Entitaeten");
    }

    @Test
    void verweigertWennZigbee2mqttDasGeraetNochKennt() {
        when(deviceRepository.findById(7L)).thenReturn(Optional.of(device));
        registry.replaceDevices(List.of(new ZigbeeBridgeDevice("0x1", "Alter Sensor", "EndDevice", "Battery",
                true, true, null, null, null, 1)));

        assertThatThrownBy(() -> service.purge(7L)).isInstanceOf(ZigbeeDeviceKnownToBridgeException.class);

        verify(deviceRepository, never()).delete(any());
        verify(auditService, never()).record(anyString(), anyString());
    }

    @Test
    void unbekannteIdIst404() {
        when(deviceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purge(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
