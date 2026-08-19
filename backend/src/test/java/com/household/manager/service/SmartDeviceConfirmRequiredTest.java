package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaService;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.tapo.TapoDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Die Geraeteliste traegt das Bestaetigungs-Flag der zugehoerigen Switch-Entitaet
 * mit, damit die Geraeteseite den Ausschalt-Schutz ohne zweiten Request und ohne
 * eigene Kenntnis der entityId-Konvention anzeigen kann.
 */
class SmartDeviceConfirmRequiredTest {

    private SmartDeviceRepository repository;
    private EntityStateService entityStateService;
    private SmartDeviceService service;

    @BeforeEach
    void setUp() {
        repository = mock(SmartDeviceRepository.class);
        entityStateService = mock(EntityStateService.class);
        service = new SmartDeviceService(
                repository,
                mock(KasaService.class),
                mock(KasaDiscoveryService.class),
                mock(MerossDeviceService.class),
                mock(TapoDeviceService.class),
                new ObjectMapper(),
                new SmartDeviceEntityMapper(),
                entityStateService,
                mock(AuditService.class));
    }

    @Test
    @DisplayName("Gesetztes Flag der Switch-Entitaet erscheint in der Geraeteliste")
    void reportsConfirmRequiredFromEntityState() {
        when(repository.findAllByOrderByDeviceTypeAscDeviceNameAsc()).thenReturn(List.of(device()));
        EntityState guarded = new EntityState();
        guarded.setConfirmRequired(true);
        when(entityStateService.getByEntityId("switch.tapo_dev1")).thenReturn(Optional.of(guarded));

        List<SmartDeviceResponse> devices = service.getAllDevices();

        assertTrue(devices.get(0).isConfirmRequired(),
                "Flag der gespiegelten Switch-Entitaet muss durchgereicht werden");
    }

    @Test
    @DisplayName("Geraet ohne gespiegelte Entitaet meldet kein Bestaetigungs-Flag")
    void defaultsToFalseWithoutEntityState() {
        when(repository.findAllByOrderByDeviceTypeAscDeviceNameAsc()).thenReturn(List.of(device()));
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());

        List<SmartDeviceResponse> devices = service.getAllDevices();

        assertFalse(devices.get(0).isConfirmRequired());
    }

    private SmartDevice device() {
        SmartDevice device = new SmartDevice();
        device.setId(1L);
        device.setDeviceType(DeviceType.TAPO);
        device.setExternalDeviceId("DEV1");
        device.setDeviceName("Stehlampe");
        device.setOnline(true);
        device.setPoweredOn(true);
        device.setCapabilities("SWITCH");
        return device;
    }
}
