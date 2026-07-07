package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaService;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.tapo.TapoAuthProtocol;
import com.household.manager.tapo.TapoCloudDevice;
import com.household.manager.tapo.TapoDeviceService;
import com.household.manager.tapo.TapoDiscoveryDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verhalten des SmartDeviceService beim Tapo-Scan: der Live-Status darf den
 * bereits von der lokalen Discovery persistierten Zustand nicht ueberschreiben,
 * wenn die Live-Abfrage fehlschlaegt.
 */
class SmartDeviceServiceTest {

    private final SmartDeviceRepository repository = mock(SmartDeviceRepository.class);
    private final KasaService kasaService = mock(KasaService.class);
    private final KasaDiscoveryService kasaDiscoveryService = mock(KasaDiscoveryService.class);
    private final MerossDeviceService merossDeviceService = mock(MerossDeviceService.class);
    private final TapoDeviceService tapoDeviceService = mock(TapoDeviceService.class);

    private SmartDeviceService newService() {
        return new SmartDeviceService(repository, kasaService, kasaDiscoveryService,
                merossDeviceService, tapoDeviceService, new ObjectMapper());
    }

    @Test
    @DisplayName("Scan behaelt den zuletzt bekannten Powered-Status, wenn die Live-Statusabfrage fehlschlaegt")
    void scanTapoKeepsLivePoweredStateWhenStatusProbeFails() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "U3Rlcm4=", "0", "role", "P110(EU)", "DEV1", "SMART.TAPOPLUG",
                "Stern", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        TapoDiscoveryDevice localDevice = new TapoDiscoveryDevice(
                "192.168.1.50", TapoAuthProtocol.KLAP, "DEV1", "P110(EU)", "Stern", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localDevice));

        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Stern");
        existing.setOnline(true);
        existing.setPoweredOn(true);
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("KLAP timeout"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.TAPO);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertTrue(saved.isPoweredOn(), "poweredOn darf nicht auf false zurueckgesetzt werden");
        assertTrue(saved.isOnline(), "lokal gefundenes Geraet muss online sein");
    }

    @Test
    @DisplayName("Scan markiert ein nur cloud-bekanntes, nicht erreichbares Geraet als offline")
    void scanTapoMarksCloudOnlyDeviceOfflineWhenUnreachable() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "R2Fyw6Fnw6U=", "0", "role", "P110(EU)", "DEV2", "SMART.TAPOPLUG",
                "Garage", "1.0", "11:22:33:44:55:66", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of());

        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV2"))
                .thenReturn(Optional.empty());

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("no route to host"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.TAPO);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertFalse(saved.isOnline(), "nicht erreichbares Cloud-only-Geraet darf nicht als online gelten");
    }
}
