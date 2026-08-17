package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaService;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private final SmartDeviceEntityMapper smartDeviceEntityMapper = new SmartDeviceEntityMapper();
    private final EntityStateService entityStateService = mock(EntityStateService.class);

    private SmartDeviceService newService() {
        return new SmartDeviceService(repository, kasaService, kasaDiscoveryService,
                merossDeviceService, tapoDeviceService, new ObjectMapper(),
                smartDeviceEntityMapper, entityStateService);
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

    @Test
    @DisplayName("Kasa-Scan identifiziert das Geraet ueber die stabile deviceId, nicht ueber die (DHCP-)IP")
    void scanKasaUsesStableDeviceIdAsIdentity() {
        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp("192.168.1.77");
        dto.setDeviceId("8006ABCDEF");
        dto.setModel("HS100(EU)");
        dto.setAlias("Wohnzimmer");
        dto.setRelayState(true);
        when(kasaDiscoveryService.discover()).thenReturn(List.of(dto));
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "8006ABCDEF"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.KASA);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("8006ABCDEF", saved.getExternalDeviceId(),
                "Kasa-Identitaet muss die stabile deviceId sein, nicht die IP");
        assertEquals("192.168.1.77", saved.getIpAddress(), "IP wird weiterhin als Kommunikationsadresse gepflegt");
        assertTrue(saved.isOnline(), "frisch entdecktes Geraet muss online sein");
    }

    @Test
    @DisplayName("Kasa turnOn spricht das Geraet ueber die IP-Adresse an, nicht ueber die externalDeviceId")
    void turnOnKasaUsesIpAddressForCommunication() {
        SmartDevice device = new SmartDevice();
        device.setId(5L);
        device.setDeviceType(DeviceType.KASA);
        device.setExternalDeviceId("8006ABCDEF");
        device.setIpAddress("192.168.1.77");
        device.setDeviceName("Wohnzimmer");
        when(repository.findById(5L)).thenReturn(Optional.of(device));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().turnOn(5L);

        verify(kasaService).turnOn("192.168.1.77");
    }

    @Test
    @DisplayName("addKasaDeviceByIp persistiert ein neues Geraet mit den gesondeten Werten und meldet den Entity-State")
    void addKasaDeviceByIpPersistsNewDeviceAndReportsEntityState() {
        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp("192.168.1.116");
        dto.setDeviceId("8006ABCDEF");
        dto.setModel("HS100(EU)");
        dto.setAlias("Kueche");
        dto.setRelayState(true);
        when(kasaService.probe("192.168.1.116")).thenReturn(dto);
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "8006ABCDEF"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().addKasaDeviceByIp("192.168.1.116");

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals(DeviceType.KASA, saved.getDeviceType());
        assertEquals("8006ABCDEF", saved.getExternalDeviceId());
        assertEquals("192.168.1.116", saved.getIpAddress());
        assertEquals("Kueche", saved.getDeviceName());
        assertEquals("HS100(EU)", saved.getModel());
        assertTrue(saved.isOnline());
        assertTrue(saved.isPoweredOn());
        verify(entityStateService).reportState(any());
    }

    @Test
    @DisplayName("addKasaDeviceByIp fuer eine bereits bekannte hardware-deviceId aktualisiert die bestehende Zeile statt eine zweite anzulegen")
    void addKasaDeviceByIpUpdatesExistingDeviceByHardwareDeviceId() {
        SmartDevice existing = new SmartDevice();
        existing.setId(9L);
        existing.setDeviceType(DeviceType.KASA);
        existing.setExternalDeviceId("8006ABCDEF");
        existing.setIpAddress("192.168.1.50");
        existing.setDeviceName("Altes Wohnzimmer");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "8006ABCDEF"))
                .thenReturn(Optional.of(existing));

        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp("192.168.1.99"); // neue IP fuer dasselbe Geraet
        dto.setDeviceId("8006ABCDEF");
        dto.setModel("HS100(EU)");
        dto.setAlias("Wohnzimmer");
        dto.setRelayState(false);
        when(kasaService.probe("192.168.1.99")).thenReturn(dto);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().addKasaDeviceByIp("192.168.1.99");

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals(9L, saved.getId(), "es muss dieselbe Zeile aktualisiert werden, keine neue angelegt");
        assertEquals("192.168.1.99", saved.getIpAddress(), "die IP wird als Kommunikationsadresse aktualisiert");
        assertEquals("Wohnzimmer", saved.getDeviceName());
    }

    @Test
    @DisplayName("addKasaDeviceByIp laesst eine KasaCommunicationException durch, ohne ein halbfertiges Geraet zu persistieren")
    void addKasaDeviceByIpPropagatesCommunicationFailureWithoutPersisting() {
        when(kasaService.probe("192.168.1.200"))
                .thenThrow(new KasaCommunicationException("Failed to communicate with Kasa device at IP 192.168.1.200 after 3 attempts"));

        assertThrows(KasaCommunicationException.class, () -> newService().addKasaDeviceByIp("192.168.1.200"));

        verify(repository, never()).save(any());
    }
}
