package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.LightStateRequest;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
import com.household.manager.kasa.KasaDiscoveryService;
import com.household.manager.kasa.KasaLightCommandResult;
import com.household.manager.kasa.KasaService;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.meross.service.MerossDeviceService;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.smartdevice.LightState;
import com.household.manager.tapo.TapoAddressProbeResult;
import com.household.manager.tapo.TapoAuthProtocol;
import com.household.manager.tapo.TapoCloudDevice;
import com.household.manager.tapo.TapoDeviceService;
import com.household.manager.tapo.TapoDeviceState;
import com.household.manager.tapo.TapoDiscoveryDevice;
import com.household.manager.tapo.TapoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final AuditService auditService = mock(AuditService.class);

    private SmartDeviceService newService() {
        return new SmartDeviceService(repository, kasaService, kasaDiscoveryService,
                merossDeviceService, tapoDeviceService, new ObjectMapper(),
                smartDeviceEntityMapper, entityStateService, auditService);
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
    @DisplayName("Scan behaelt die zuletzt bekannten Faehigkeiten, wenn die Live-Statusabfrage fehlschlaegt")
    void scanTapoKeepsExistingCapabilitiesWhenStatusProbeFails() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "DEV1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        TapoDiscoveryDevice localDevice = new TapoDiscoveryDevice(
                "192.168.1.114", TapoAuthProtocol.KLAP, "DEV1", "L530", "Flur", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localDevice));

        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Flur");
        existing.setOnline(true);
        existing.setPoweredOn(true);
        existing.setCapabilities("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
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
        assertEquals("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", saved.getCapabilities(),
                "eine fehlgeschlagene Live-Abfrage darf die zuvor gespeicherten Faehigkeiten nicht auf SWITCH zuruecksetzen");
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
    @DisplayName("Kasa-Scan uebernimmt Faehigkeiten, Bulb-Flag und aktuelle Lichtwerte eines Leuchtmittels statt hartkodiertem SWITCH")
    void scanKasaBulbPersistsCapabilitiesAndCurrentLightValues() {
        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp("192.168.1.101");
        dto.setDeviceId("KL110DEVICEID");
        dto.setModel("KL110(EU)");
        dto.setAlias("Treppenhaus");
        dto.setRelayState(false);
        dto.setBulb(true);
        dto.setCapabilities("SWITCH,BRIGHTNESS");
        dto.setBrightness(100);
        dto.setHue(0);
        dto.setSaturation(0);
        dto.setColorTemp(2700);
        when(kasaDiscoveryService.discover()).thenReturn(List.of(dto));
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "KL110DEVICEID"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.KASA);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("SWITCH,BRIGHTNESS", saved.getCapabilities(),
                "Faehigkeiten muessen aus der sysinfo abgeleitet werden, nicht hartkodiert SWITCH sein");
        assertTrue(saved.getMetadata().contains("\"lightBrightness\":100"));
        assertTrue(saved.getMetadata().contains("\"kasaBulb\":true"));
    }

    @Test
    @DisplayName("Kasa-Scan legt fuer eine Steckdose (kein bulb-Flag, keine Faehigkeiten im DTO) weiterhin nur SWITCH an")
    void scanKasaPlugStillYieldsOnlySwitchCapability() {
        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp("192.168.1.116");
        dto.setDeviceId("8006PLUG");
        dto.setModel("HS100(EU)");
        dto.setAlias("Kueche");
        dto.setRelayState(true);
        // bulb=false (Default), capabilities=null - wie eine echte KasaDiscoveryService-Antwort fuer eine Steckdose
        when(kasaDiscoveryService.discover()).thenReturn(List.of(dto));
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "8006PLUG"))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.KASA);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        assertEquals("SWITCH", captor.getValue().getCapabilities());
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

        verify(kasaService).turnOn("192.168.1.77", false);
    }

    @Test
    @DisplayName("Kasa turnOn/turnOff senden fuer ein als Leuchtmittel markiertes Geraet das bulb=true-Flag")
    void turnOnOffKasaPassesBulbFlagFromStoredMetadata() {
        SmartDevice device = new SmartDevice();
        device.setId(6L);
        device.setDeviceType(DeviceType.KASA);
        device.setExternalDeviceId("KL110DEVICEID");
        device.setIpAddress("192.168.1.101");
        device.setDeviceName("Treppenhaus");
        device.setMetadata("{\"kasaBulb\":true}");
        when(repository.findById(6L)).thenReturn(Optional.of(device));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().turnOn(6L);
        newService().turnOff(6L);

        verify(kasaService).turnOn("192.168.1.101", true);
        verify(kasaService).turnOff("192.168.1.101", true);
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

    // ==================== Tapo: ohne lokale Discovery steuerbar (Task 3) ====================

    @Test
    @DisplayName("Scan setzt ein Geraet mit bekannter IP online, wenn der Live-Probe klappt, auch ohne lokalen Discovery-Treffer")
    void scanTapoDeviceWithStoredIpGoesOnlineWhenProbeSucceedsWithoutLocalDiscoveryHit() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "DEV1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of()); // Docker-Bridge: nie ein Treffer

        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Flur");
        existing.setIpAddress("192.168.1.114"); // manuell gesetzt (PUT /devices/{id}/address)
        existing.setOnline(false);
        existing.setPoweredOn(false);
        existing.setCapabilities("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
        existing.setMetadata("{\"authProtocol\":\"KLAP\"}");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));

        when(tapoDeviceService.getStatus(eq("DEV1"), eq("192.168.1.114"), any()))
                .thenReturn(new TapoDeviceState("Flur", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.TAPO);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertTrue(saved.isOnline(), "Geraet mit erreichbarer, bekannter IP muss online werden, auch ohne lokalen Discovery-Treffer");
        assertEquals("192.168.1.114", saved.getIpAddress());
    }

    @Test
    @DisplayName("Scan behaelt das zuvor gespeicherte authProtocol, wenn lokale Discovery in dieser Runde nichts findet")
    void scanTapoPreservesStoredAuthProtocolWhenLocalDiscoveryMisses() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "DEV1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of());

        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Flur");
        existing.setIpAddress("192.168.1.114");
        existing.setMetadata("{\"authProtocol\":\"KLAP\"}");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanAndPersistDevices(DeviceType.TAPO);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getMetadata().contains("\"authProtocol\":\"KLAP\""),
                "das zuvor per PUT /devices/{id}/address gesetzte Protokoll darf nicht verloren gehen, "
                        + "nur weil die lokale Discovery in dieser Runde nichts gefunden hat");
    }

    @Test
    @DisplayName("Scan legt ein nur lokal gefundenes Geraet ohne Cloud-Eintrag an")
    void scanTapoAdoptsLocalOnlyDeviceWithoutCloudAccount() {
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of());

        TapoDiscoveryDevice localOnly = new TapoDiscoveryDevice(
                "192.168.1.120", TapoAuthProtocol.KLAP, "DEVLOCAL", "L530", "Kueche", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localOnly));

        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEVLOCAL"))
                .thenReturn(Optional.empty());
        when(tapoDeviceService.getStatus(eq("DEVLOCAL"), eq("192.168.1.120"), eq(TapoAuthProtocol.KLAP)))
                .thenReturn(new TapoDeviceState("Kueche", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SmartDevice> persisted = newService().scanTapoDevices();

        assertEquals(1, persisted.size());
        SmartDevice saved = persisted.get(0);
        assertEquals("DEVLOCAL", saved.getExternalDeviceId());
        assertEquals("Kueche", saved.getDeviceName());
        assertEquals("L530", saved.getModel());
        assertTrue(saved.isOnline());
    }

    @Test
    @DisplayName("Scan legt ein in Cloud- und lokaler Liste vorhandenes Geraet genau einmal an")
    void scanTapoCreatesDeviceInBothSourcesExactlyOnce() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "DEV1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        TapoDiscoveryDevice localDevice = new TapoDiscoveryDevice(
                "192.168.1.114", TapoAuthProtocol.KLAP, "DEV1", "L530", "Flur", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localDevice));

        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.empty());
        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("nicht relevant fuer diesen Test"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SmartDevice> persisted = newService().scanTapoDevices();

        assertEquals(1, persisted.size(), "ein Geraet in beiden Quellen darf nur einmal angelegt werden");
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("Scan behaelt cloud-only Geraete unveraendert bei (Regression fuer den Merge-Umbau)")
    void scanTapoLeavesCloudOnlyDeviceBehaviorUnchanged() {
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

        List<SmartDevice> persisted = newService().scanTapoDevices();

        assertEquals(1, persisted.size());
        assertEquals("DEV2", persisted.get(0).getExternalDeviceId());
        assertFalse(persisted.get(0).isOnline());
    }

    /**
     * Hinweis zur Umbenennung (Review-Fund, Item 5): {@code TapoDeviceService.discoverLocalDevices}
     * liefert ein {@link TapoDiscoveryDevice} ueberhaupt nur nach einem bereits erfolgreichen
     * authentifizierten Handshake (siehe {@code TapoDiscoveryService.discoverLocalDevices}, das
     * jeden Fehlschlag vorher schluckt) — "gefunden, aber falsches TP-Link-Konto" kann diesen
     * zweiten getStatus()-Aufruf hier also strukturell gar nicht erreichen. Realistisch ist
     * dagegen, dass das Geraet zwischen der Discovery und dieser Nachfrage kurzzeitig wieder
     * unerreichbar ist (Netz-Aussetzer, oder das Geraet laesst nur eine gleichzeitige Verbindung
     * zu). Der Test simuliert genau das statt einer "falsches Konto"-Erzaehlung, die der Code nie
     * einloesen koennte.
     */
    @Test
    @DisplayName("Scan legt ein lokal gefundenes Geraet trotz voruebergehend nicht erreichbarer Nachfrage als offline an und bricht nicht ab")
    void scanTapoKeepsLocalOnlyDeviceOfflineAndContinuesWhenFollowUpProbeFails() {
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of());

        TapoDiscoveryDevice transientlyUnreachable = new TapoDiscoveryDevice(
                "192.168.1.130", TapoAuthProtocol.KLAP, "DEVFLAKY", "L530", "Flackert", false);
        TapoDiscoveryDevice working = new TapoDiscoveryDevice(
                "192.168.1.131", TapoAuthProtocol.KLAP, "DEVOWN", "L530", "Eigen", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(transientlyUnreachable, working));

        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEVFLAKY"))
                .thenReturn(Optional.empty());
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEVOWN"))
                .thenReturn(Optional.empty());
        when(tapoDeviceService.getStatus(eq("DEVFLAKY"), eq("192.168.1.130"), any()))
                .thenThrow(new TapoException("Verbindung verloren"));
        when(tapoDeviceService.getStatus(eq("DEVOWN"), eq("192.168.1.131"), any()))
                .thenReturn(new TapoDeviceState("Eigen", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SmartDevice> persisted = newService().scanTapoDevices();

        assertEquals(2, persisted.size(), "ein fehlschlagendes Geraet darf den Scan der uebrigen nicht abbrechen");
        Map<String, SmartDevice> byId = persisted.stream()
                .collect(java.util.stream.Collectors.toMap(SmartDevice::getExternalDeviceId, d -> d));
        assertFalse(byId.get("DEVFLAKY").isOnline(), "Geraet, das bei der Nachfrage nicht antwortet, muss offline sein, nicht fehlen");
        assertTrue(byId.get("DEVOWN").isOnline());
    }

    @Test
    @DisplayName("Scan behandelt eine abweichende Gross-/Kleinschreibung der deviceId als dasselbe Geraet, nicht als Dopplung")
    void scanTapoTreatsDeviceIdCaseDifferenceAsSameDevice() {
        // Cloud-API und die Selbstauskunft des Geraets sind zwei unabhaengige Quellen fuer
        // "dieselbe" deviceId - hier bewusst mit unterschiedlicher Gross-/Kleinschreibung.
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "dev1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        TapoDiscoveryDevice localDevice = new TapoDiscoveryDevice(
                "192.168.1.114", TapoAuthProtocol.KLAP, "DEV1", "L530", "Flur", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localDevice));

        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "dev1"))
                .thenReturn(Optional.empty());
        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("nicht relevant fuer diesen Test"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SmartDevice> persisted = newService().scanTapoDevices();

        assertEquals(1, persisted.size(),
                "eine reine Gross-/Kleinschreibungs-Abweichung der deviceId darf keine zweite Zeile fuer dasselbe physische Geraet erzeugen");
        verify(repository, times(1)).save(any());
    }

    /**
     * CRITICAL-adjacent regression: TapoDeviceService.getOrCreateLocalConnection keys its
     * connection cache on deviceId:protocol only, NOT the ip. After a DHCP reshuffle a stale
     * cached connection would otherwise keep talking to whatever physical device now sits at the
     * OLD ip (it would authenticate happily if it's another bulb on the same Tapo account) - a
     * switch could silently control the WRONG device with no error. setTapoDeviceAddress already
     * guards against this explicitly for the manual path; a scan-discovered IP change needs the
     * identical guard.
     */
    @Test
    @DisplayName("Scan invalidiert die zwischengespeicherte Verbindung, wenn sich die IP eines Geraets aendert")
    void scanTapoClearsCachedConnectionWhenIpChanges() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "DEV1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        TapoDiscoveryDevice localDevice = new TapoDiscoveryDevice(
                "192.168.1.200", TapoAuthProtocol.KLAP, "DEV1", "L530", "Flur", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localDevice));

        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setIpAddress("192.168.1.114"); // alte, jetzt per DHCP neu vergebene IP
        existing.setCapabilities("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("nicht relevant fuer diesen Test"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanTapoDevices();

        verify(tapoDeviceService).clearLocalConnection("DEV1");
    }

    @Test
    @DisplayName("Scan invalidiert NICHTS, wenn die IP eines Geraets unveraendert bleibt")
    void scanTapoDoesNotClearCachedConnectionWhenIpIsUnchanged() {
        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "Rmx1cg==", "0", "role", "L530", "DEV1", "SMART.TAPOBULB",
                "Flur", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of(cloudDevice));

        TapoDiscoveryDevice localDevice = new TapoDiscoveryDevice(
                "192.168.1.114", TapoAuthProtocol.KLAP, "DEV1", "L530", "Flur", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(localDevice));

        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setIpAddress("192.168.1.114"); // gleiche IP wie die Discovery
        existing.setCapabilities("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenThrow(new RuntimeException("nicht relevant fuer diesen Test"));
        when(tapoDeviceService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tapoDeviceService.buildMetadata(any())).thenReturn(new HashMap<>());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().scanTapoDevices();

        verify(tapoDeviceService, never()).clearLocalConnection(any());
    }

    // ==================== Tapo: Adresse manuell setzen (Task 3) ====================

    @Test
    @DisplayName("setTapoDeviceAddress persistiert IP, Protokoll und aktualisierten Zustand")
    void setTapoDeviceAddressPersistsIpProtocolAndRefreshedState() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Alter Name");
        existing.setCapabilities("SWITCH");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        TapoDeviceState state = new TapoDeviceState("Flur", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
        when(tapoDeviceService.probeAddress("192.168.1.114"))
                .thenReturn(new TapoAddressProbeResult("DEV1", TapoAuthProtocol.KLAP, state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().setTapoDeviceAddress(1L, "192.168.1.114");

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("192.168.1.114", saved.getIpAddress());
        assertTrue(saved.isOnline());
        assertTrue(saved.isPoweredOn());
        assertEquals("Flur", saved.getDeviceName());
        assertEquals("L530", saved.getModel());
        assertEquals("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", saved.getCapabilities());
        assertTrue(saved.getMetadata().contains("\"authProtocol\":\"KLAP\""));
        verify(entityStateService).reportState(any());
    }

    /**
     * Regression for the resolveColorTempRange javadoc's claim ("captured ... on a previous
     * scan/refresh/address-set") actually being true, and for exposing it on SmartDeviceResponse
     * (item 6): the persisted metadata must carry the device's own colour-temp range AND its
     * current brightness/hue/saturation/colorTemp values from the probe response, not hand-written
     * fixtures — and toResponse must surface the light values as their own typed fields.
     */
    @Test
    @DisplayName("setTapoDeviceAddress persistiert Farbtemperatur-Bereich und aktuelle Lichtwerte aus der Geraete-Probe")
    void setTapoDeviceAddressPersistsColorTempRangeAndCurrentLightStateFromProbe() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setCapabilities("SWITCH");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        TapoDeviceState state = new TapoDeviceState("Flur", "L530", true, true,
                "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", 2500, 6500,
                new LightState(70, 200, 80, 0));
        when(tapoDeviceService.probeAddress("192.168.1.114"))
                .thenReturn(new TapoAddressProbeResult("DEV1", TapoAuthProtocol.KLAP, state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SmartDeviceResponse response = newService().setTapoDeviceAddress(1L, "192.168.1.114");

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        String metadata = captor.getValue().getMetadata();
        assertTrue(metadata.contains("\"colorTempRangeMin\":2500"));
        assertTrue(metadata.contains("\"colorTempRangeMax\":6500"));
        assertTrue(metadata.contains("\"lightBrightness\":70"));
        assertTrue(metadata.contains("\"lightHue\":200"));
        assertTrue(metadata.contains("\"lightSaturation\":80"));
        assertTrue(metadata.contains("\"lightColorTemp\":0"));

        assertEquals(70, response.getBrightness());
        assertEquals(200, response.getHue());
        assertEquals(80, response.getSaturation());
        assertEquals(0, response.getColorTemp());
    }

    @Test
    @DisplayName("setTapoDeviceAddress lehnt ein Nicht-Tapo-Geraet mit einer klaren Meldung ab")
    void setTapoDeviceAddressRejectsNonTapoDevice() {
        SmartDevice existing = new SmartDevice();
        existing.setId(2L);
        existing.setDeviceType(DeviceType.KASA);
        existing.setExternalDeviceId("8006ABCDEF");
        when(repository.findById(2L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> newService().setTapoDeviceAddress(2L, "192.168.1.114"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setTapoDeviceAddress laesst die TapoException durch und persistiert nichts, wenn das Geraet nicht antwortet")
    void setTapoDeviceAddressPropagatesFailureWithoutPersisting() {
        SmartDevice existing = new SmartDevice();
        existing.setId(3L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        when(repository.findById(3L)).thenReturn(Optional.of(existing));

        when(tapoDeviceService.probeAddress("192.168.1.200"))
                .thenThrow(new TapoException("Tapo-Geraet unter 192.168.1.200 ist weder ueber KLAP noch ueber AES erreichbar."));

        assertThrows(TapoException.class, () -> newService().setTapoDeviceAddress(3L, "192.168.1.200"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setTapoDeviceAddress lehnt eine IP ab, hinter der ein ANDERES Geraet antwortet, und persistiert nichts (KRITISCH)")
    void setTapoDeviceAddressRejectsWhenProbedDeviceIdDiffersFromEditedDevice() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Flur");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        // Vertippte, aber gueltige IP: antwortet erfolgreich, ist aber ein ANDERES Tapo-Geraet.
        TapoDeviceState otherDeviceState = new TapoDeviceState("Kueche", "P110(EU)", false, true, "SWITCH");
        when(tapoDeviceService.probeAddress("192.168.1.199"))
                .thenReturn(new TapoAddressProbeResult("DEV2", TapoAuthProtocol.KLAP, otherDeviceState));

        assertThrows(IllegalArgumentException.class, () -> newService().setTapoDeviceAddress(1L, "192.168.1.199"));

        verify(repository, never()).save(any());
        verify(entityStateService, never()).reportState(any());
    }

    @Test
    @DisplayName("setTapoDeviceAddress lehnt eine Probe-Antwort ohne deviceId ab, statt die Identitaet ungeprueft zu uebernehmen")
    void setTapoDeviceAddressRejectsWhenProbeReportsNoDeviceId() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        TapoDeviceState state = new TapoDeviceState("Flur", "L530", true, true, "SWITCH");
        when(tapoDeviceService.probeAddress("192.168.1.114"))
                .thenReturn(new TapoAddressProbeResult(null, TapoAuthProtocol.KLAP, state));

        assertThrows(IllegalArgumentException.class, () -> newService().setTapoDeviceAddress(1L, "192.168.1.114"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setTapoDeviceAddress invalidiert nach Erfolg den lokalen Verbindungscache (WICHTIG)")
    void setTapoDeviceAddressClearsStaleConnectionCacheOnSuccess() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        TapoDeviceState state = new TapoDeviceState("Flur", "L530", true, true, "SWITCH");
        when(tapoDeviceService.probeAddress("192.168.1.114"))
                .thenReturn(new TapoAddressProbeResult("DEV1", TapoAuthProtocol.KLAP, state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().setTapoDeviceAddress(1L, "192.168.1.114");

        // Ohne das wuerde TapoDeviceService.getOrCreateLocalConnection eine gecachte Verbindung
        // gegen die ALTE IP fuer turnOn/turnOff weiterverwenden (der Cache-Key ignoriert die IP).
        verify(tapoDeviceService).clearLocalConnection("DEV1");
    }

    @Test
    @DisplayName("setTapoDeviceAddress entfernt einen veralteten localDiscoveryError-Hinweis nach Erfolg")
    void setTapoDeviceAddressRemovesStaleLocalDiscoveryErrorHint() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setMetadata("{\"localDiscoveryError\":\"war vorher nicht erreichbar\"}");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        TapoDeviceState state = new TapoDeviceState("Flur", "L530", true, true, "SWITCH");
        when(tapoDeviceService.probeAddress("192.168.1.114"))
                .thenReturn(new TapoAddressProbeResult("DEV1", TapoAuthProtocol.KLAP, state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().setTapoDeviceAddress(1L, "192.168.1.114");

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        assertFalse(captor.getValue().getMetadata().contains("localDiscoveryError"),
                "ein erfolgreich manuell gesetzter Hostname/IP darf keinen veralteten Fehlerhinweis in der UI hinterlassen");
    }

    // ==================== Tapo: Refresh aktualisiert Faehigkeiten (Review-Fund Item 7) ====================

    @Test
    @DisplayName("refreshDeviceState uebernimmt vom Geraet neu gemeldete Faehigkeiten, nicht nur online/poweredOn")
    void refreshDeviceStateUpgradesTapoCapabilities() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Flur");
        existing.setIpAddress("192.168.1.114");
        existing.setCapabilities("SWITCH"); // z.B. vor einem Firmware-Update ohne Lichtfelder erfasst
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        when(tapoDeviceService.getStatus(eq("DEV1"), eq("192.168.1.114"), any()))
                .thenReturn(new TapoDeviceState("Flur", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().refreshDeviceState(1L);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        assertEquals("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", captor.getValue().getCapabilities(),
                "die Spec verspricht Faehigkeiten-Erkennung bei Scan ODER Refresh - vorher aktualisierte refresh sie nicht");
    }

    @Test
    @DisplayName("refreshDeviceState uebernimmt fuer ein Kasa-Leuchtmittel Faehigkeiten und aktuelle Lichtwerte")
    void refreshDeviceStateUpgradesKasaBulbCapabilitiesAndLightValues() {
        SmartDevice existing = new SmartDevice();
        existing.setId(1L);
        existing.setDeviceType(DeviceType.KASA);
        existing.setExternalDeviceId("KL110DEVICEID");
        existing.setDeviceName("Treppenhaus");
        existing.setIpAddress("192.168.1.101");
        existing.setCapabilities("SWITCH"); // z.B. vor der Faehigkeiten-Erkennung erfasst
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        when(kasaService.getStatus("192.168.1.101")).thenReturn(new KasaStatusDto(
                false, "Treppenhaus", "KL110DEVICEID", true, "SWITCH,BRIGHTNESS", 100, 0, 0, 2700));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().refreshDeviceState(1L);

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("SWITCH,BRIGHTNESS", saved.getCapabilities());
        assertTrue(saved.getMetadata().contains("\"lightBrightness\":100"));
        assertTrue(saved.getMetadata().contains("\"kasaBulb\":true"));
    }

    // ==================== Licht-Steuerung (Task 4) ====================

    private SmartDevice tapoLight(Long id, String capabilities, String metadataJson) {
        SmartDevice device = new SmartDevice();
        device.setId(id);
        device.setDeviceType(DeviceType.TAPO);
        device.setExternalDeviceId("DEV1");
        device.setDeviceName("Flur");
        device.setIpAddress("192.168.1.114");
        device.setCapabilities(capabilities);
        device.setMetadata(metadataJson);
        when(repository.findById(id)).thenReturn(Optional.of(device));
        return device;
    }

    @Test
    @DisplayName("setLightState reicht die Werte durch, persistiert den aufgefrischten Zustand und schreibt einen Audit-Eintrag")
    void setLightStateAppliesRefreshesAndAudits() {
        tapoLight(1L, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", null);
        when(tapoDeviceService.getStatus(eq("DEV1"), eq("192.168.1.114"), any()))
                .thenReturn(new TapoDeviceState("Flur", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LightStateRequest request = LightStateRequest.builder().brightness(70).hue(200).saturation(80).build();
        newService().setLightState(1L, request);

        verify(tapoDeviceService).setLightState(eq("DEV1"), eq("192.168.1.114"), any(),
                eq(new LightState(70, 200, 80, null)), eq(true));
        verify(repository).save(any());
        verify(entityStateService).reportState(any());
        verify(auditService).record(eq("device.light.set"), anyString());
    }

    /**
     * CRITICAL regression: a mixed request used to pass validation, then buildSetDeviceInfoParams
     * silently took the colour branch and sent color_temp:0 - discarding the requested colorTemp
     * value - while the response still carried 200 and the audit entry claimed colorTemp was set.
     * That is exactly the silent-ignore the capability check below the mixed-check forbids, just
     * from the other direction: it must be rejected loudly, before anything reaches the device.
     */
    @Test
    @DisplayName("setLightState lehnt eine gemischte Anfrage aus Farbe UND Farbtemperatur ab und sendet nichts")
    void setLightStateRejectsMixedColorAndColorTempRequest() {
        tapoLight(1L, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", null);

        LightStateRequest request = LightStateRequest.builder().hue(200).saturation(80).colorTemp(4000).build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, request));
        assertTrue(ex.getMessage().contains("schliessen sich aus"));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
        verify(repository, never()).save(any());
        verify(auditService, never()).record(anyString(), anyString());
    }

    /** Same rejection when only hue (no saturation) is combined with colorTemp. */
    @Test
    @DisplayName("setLightState lehnt hue allein zusammen mit Farbtemperatur ebenfalls ab")
    void setLightStateRejectsHueAloneCombinedWithColorTemp() {
        tapoLight(1L, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", null);

        LightStateRequest request = LightStateRequest.builder().hue(200).colorTemp(4000).build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(1L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("setLightState lehnt eine Faehigkeit ab, die das Geraet nicht meldet, und sendet nichts an das Geraet")
    void setLightStateRejectsUnreportedCapability() {
        tapoLight(1L, "SWITCH", null); // keine BRIGHTNESS-Faehigkeit

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(1L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setLightState lehnt eine leere Anfrage ohne jedes Feld ab")
    void setLightStateRejectsEmptyRequest() {
        tapoLight(1L, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", null);

        LightStateRequest request = LightStateRequest.builder().build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(1L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * KASA used to be rejected here too (Kasa bulbs did not exist yet); now that
     * {@link DeviceType#KASA} also supports light control, MEROSS is the genuinely
     * unsupported type this guard must still reject.
     */
    @Test
    @DisplayName("setLightState lehnt ein Geraet ohne Lichtsteuerung (Meross) ab")
    void setLightStateRejectsUnsupportedDeviceType() {
        SmartDevice device = new SmartDevice();
        device.setId(5L);
        device.setDeviceType(DeviceType.MEROSS);
        when(repository.findById(5L)).thenReturn(Optional.of(device));

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(5L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
        verify(kasaService, never()).setLightState(any(), any(), anyBoolean());
    }

    private SmartDevice kasaLight(Long id, String capabilities) {
        SmartDevice device = new SmartDevice();
        device.setId(id);
        device.setDeviceType(DeviceType.KASA);
        device.setExternalDeviceId("KL110DEVICEID");
        device.setDeviceName("Treppenhaus");
        device.setIpAddress("192.168.1.101");
        device.setCapabilities(capabilities);
        device.setMetadata("{\"kasaBulb\":true}");
        when(repository.findById(id)).thenReturn(Optional.of(device));
        return device;
    }

    @Test
    @DisplayName("setLightState fuer ein Kasa-Leuchtmittel persistiert die vom Geraet SELBST gemeldeten Werte (nicht die angefragten) und schreibt einen Audit-Eintrag, ohne einen zweiten getStatus()-Roundtrip")
    void setLightStateAppliesToKasaBulb() {
        kasaLight(10L, "SWITCH,BRIGHTNESS");
        // KasaService.setLightState liest die tatsaechlich vom Geraet gemeldeten Werte aus derselben
        // Schreibantwort zurueck (siehe die dortige Javadoc fuer die gemessene Begruendung) - ein
        // zweiter kasaService.getStatus()-Aufruf ist deshalb weder noetig noch erwartet.
        when(kasaService.setLightState(eq("192.168.1.101"), eq(new LightState(70, null, null, null)), eq(false)))
                .thenReturn(new KasaLightCommandResult(true, new LightState(70, null, null, null)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LightStateRequest request = LightStateRequest.builder().brightness(70).build();
        SmartDeviceResponse response = newService().setLightState(10L, request);

        verify(kasaService).setLightState(eq("192.168.1.101"), eq(new LightState(70, null, null, null)), eq(false));
        verify(kasaService, never()).getStatus(any());
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
        verify(repository).save(any());
        verify(entityStateService).reportState(any());
        verify(auditService).record(eq("device.light.set"), anyString());
        assertTrue(response.isPoweredOn());
        assertEquals(70, response.getBrightness());
    }

    @Test
    @DisplayName("setLightState persistiert den vom Geraet gemeldeten Wert, auch wenn er vom angefragten abweicht (err_code:0 beweist keine Anwendung)")
    void setLightStatePersistsActualDeviceReportedValueEvenWhenDifferentFromRequest() {
        // Gemessen: eine Farbanfrage an ein nicht-farbfaehiges Geraet liefert err_code:0, aber der
        // gemeldete hue bleibt unveraendert. SmartDeviceService darf sich nicht auf den angefragten
        // Wert verlassen, sondern muss exakt das persistieren, was KasaService.setLightState anhand
        // der Geraeteantwort zurueckgibt.
        kasaLight(10L, "SWITCH,BRIGHTNESS");
        when(kasaService.setLightState(eq("192.168.1.101"), eq(new LightState(35, null, null, null)), eq(false)))
                .thenReturn(new KasaLightCommandResult(true, new LightState(20, null, null, null)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LightStateRequest request = LightStateRequest.builder().brightness(35).build();
        SmartDeviceResponse response = newService().setLightState(10L, request);

        assertEquals(20, response.getBrightness(),
                "die Antwort muss den vom Geraet tatsaechlich gemeldeten Wert (20) zeigen, nicht den angefragten (35)");
    }

    @Test
    @DisplayName("setLightState lehnt ein Kasa-Geraet ohne Leuchtmittel-Flag ab (z.B. ein Wanddimmer) - dieselbe 400-Ablehnung wie Meross")
    void setLightStateRejectsNonBulbKasaDevice() {
        SmartDevice dimmer = new SmartDevice();
        dimmer.setId(11L);
        dimmer.setDeviceType(DeviceType.KASA);
        dimmer.setExternalDeviceId("HS220DEVICEID");
        dimmer.setDeviceName("Flurdimmer");
        dimmer.setIpAddress("192.168.1.120");
        dimmer.setCapabilities("SWITCH");
        dimmer.setMetadata(null); // kein kasaBulb-Flag gesetzt -> isKasaBulb() muss false liefern
        when(repository.findById(11L)).thenReturn(Optional.of(dimmer));

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(11L, request));
        assertTrue(ex.getMessage().contains("unterstuetzt keine Lichtsteuerung"));

        verify(kasaService, never()).setLightState(any(), any(), anyBoolean());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("PUT /devices/{id}/light lehnt fuer ein Kasa-Geraet eine nicht gemeldete Faehigkeit ab (400) und sendet nichts")
    void setLightStateRejectsUnreportedCapabilityForKasa() {
        kasaLight(10L, "SWITCH"); // keine BRIGHTNESS-Faehigkeit

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(10L, request));

        verify(kasaService, never()).setLightState(any(), any(), anyBoolean());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setLightState fuer ein Kasa-Leuchtmittel laesst eine KasaCommunicationException durch und persistiert nichts")
    void setLightStatePropagatesKasaFailureWithoutPersisting() {
        kasaLight(10L, "SWITCH,BRIGHTNESS");
        org.mockito.Mockito.doThrow(new KasaCommunicationException("Kasa-Geraet nicht erreichbar"))
                .when(kasaService).setLightState(eq("192.168.1.101"), any(), anyBoolean());

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(KasaCommunicationException.class, () -> newService().setLightState(10L, request));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setLightState: Helligkeit 0 und 101 werden abgelehnt, 1 und 100 werden akzeptiert")
    void setLightStateValidatesBrightnessBoundaries() {
        tapoLight(1L, "SWITCH,BRIGHTNESS", null);
        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().brightness(0).build()));
        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().brightness(101).build()));
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenReturn(new TapoDeviceState("Flur", "L530", true, true, "SWITCH,BRIGHTNESS"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().setLightState(1L, LightStateRequest.builder().brightness(1).build());
        newService().setLightState(1L, LightStateRequest.builder().brightness(100).build());

        verify(tapoDeviceService, times(2)).setLightState(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("setLightState lehnt eine Farbtemperatur ausserhalb des vom Geraet gemeldeten Bereichs ab")
    void setLightStateRejectsColorTempOutsideDeviceReportedRange() {
        tapoLight(1L, "SWITCH,COLOR_TEMP", "{\"colorTempRangeMin\":2500,\"colorTempRangeMax\":6500}");

        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().colorTemp(2000).build()));
        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().colorTemp(7000).build()));
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("setLightState faellt ohne gespeicherten Bereich auf 2500-6500 zurueck")
    void setLightStateFallsBackToDefaultColorTempRangeWhenDeviceReportedNone() {
        tapoLight(1L, "SWITCH,COLOR_TEMP", null);

        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().colorTemp(2000).build()));
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("setLightState laesst eine TapoException durch, wenn das Geraet nicht antwortet, und persistiert nichts")
    void setLightStatePropagatesFailureWithoutPersisting() {
        tapoLight(1L, "SWITCH,BRIGHTNESS", null);
        org.mockito.Mockito.doThrow(new TapoException("Tapo-Geraet nicht erreichbar"))
                .when(tapoDeviceService).setLightState(eq("DEV1"), eq("192.168.1.114"), any(), any(), anyBoolean());

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(TapoException.class, () -> newService().setLightState(1L, request));

        verify(repository, never()).save(any());
        verify(auditService, never()).record(anyString(), anyString());
    }
}
