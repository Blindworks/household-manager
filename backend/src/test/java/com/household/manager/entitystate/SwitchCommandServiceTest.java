package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.service.SmartDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwitchCommandServiceTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private ManualEntityService manualEntityService;
    @Mock
    private SmartDeviceService smartDeviceService;
    @Mock
    private SmartDeviceRepository smartDeviceRepository;
    @Mock
    private EntityUsageService entityUsageService;
    @Mock
    private AuditService auditService;

    private SwitchCommandService service;

    @BeforeEach
    void setUp() {
        service = new SwitchCommandService(
                entityStateService, manualEntityService, smartDeviceService,
                smartDeviceRepository, entityUsageService,
                new SwitchResponseMapper(new EntityStateResponseMapper(new ObjectMapper())), auditService);
    }

    private EntityState switchEntity(String state) {
        return EntityState.builder()
                .entityId("switch.kasa_abc")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Stehlampe")
                .state(state)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityState manualEntity(String state) {
        return EntityState.builder()
                .entityId("input_boolean.manual_nachtmodus")
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state(state)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private SmartDevice device() {
        return SmartDevice.builder()
                .id(42L)
                .deviceType(DeviceType.KASA)
                .externalDeviceId("abc")
                .build();
    }

    private void stubUsage() {
        when(entityUsageService.recordToggle(anyString()))
                .thenReturn(EntityUsage.builder().entityId("x").toggleCount(1).build());
    }

    @Test
    void schaltet_ein_eingeschaltetes_geraet_aus() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("on")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        stubUsage();

        service.toggle("switch.kasa_abc");

        verify(smartDeviceService).turnOff(42L);
        verify(smartDeviceService, never()).turnOn(anyLong());
    }

    @Test
    void schaltet_ein_ausgeschaltetes_geraet_ein() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        stubUsage();

        service.toggle("switch.kasa_abc");

        verify(smartDeviceService).turnOn(42L);
    }

    @Test
    void behandelt_ein_nicht_erreichbares_geraet_wie_ausgeschaltet() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("unavailable")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        stubUsage();

        service.toggle("switch.kasa_abc");

        verify(smartDeviceService).turnOn(42L);
    }

    @Test
    void delegiert_manuelle_helfer_an_den_manual_service() {
        when(entityStateService.getByEntityId("input_boolean.manual_nachtmodus"))
                .thenReturn(Optional.of(manualEntity("off")));
        stubUsage();

        service.toggle("input_boolean.manual_nachtmodus");

        verify(manualEntityService).toggle("input_boolean.manual_nachtmodus");
        verifyNoInteractions(smartDeviceService);
    }

    @Test
    void liefert_den_zustand_nach_dem_schalten() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")), Optional.of(switchEntity("on")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        when(entityUsageService.recordToggle("switch.kasa_abc"))
                .thenReturn(EntityUsage.builder().entityId("switch.kasa_abc").toggleCount(3).build());

        SwitchResponse response = service.toggle("switch.kasa_abc");

        assertThat(response.state()).isEqualTo("on");
        assertThat(response.toggleCount()).isEqualTo(3);
    }

    @Test
    void zaehlt_den_vorgang_nur_bei_erfolg() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        doThrow(new RuntimeException("Geraet nicht erreichbar")).when(smartDeviceService).turnOn(42L);

        assertThatThrownBy(() -> service.toggle("switch.kasa_abc"))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(entityUsageService);
    }

    @Test
    void unbekannte_entitaet_wirft_not_found() {
        when(entityStateService.getByEntityId("switch.kasa_weg")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggle("switch.kasa_weg"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void entitaet_ohne_geraet_wirft_not_found() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggle("switch.kasa_abc"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nicht_schaltbare_entitaet_wirft_illegal_argument() {
        EntityState sensor = EntityState.builder()
                .entityId("sensor.zigbee_bad_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("bad")
                .friendlyName("Bad Temperatur")
                .state("21.5")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(entityStateService.getByEntityId("sensor.zigbee_bad_temperature"))
                .thenReturn(Optional.of(sensor));

        assertThatThrownBy(() -> service.toggle("sensor.zigbee_bad_temperature"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
