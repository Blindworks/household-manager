package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.LightStateRequest;
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
import com.household.manager.tapo.LightState;
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

    @Test
    @DisplayName("Scan legt ein lokal gefundenes Geraet trotz fehlgeschlagener Anmeldung als offline an und bricht nicht ab")
    void scanTapoKeepsLocalOnlyDeviceWithFailedHandshakeAsOfflineAndContinues() {
        when(tapoDeviceService.discoverCloudDevices()).thenReturn(List.of());

        TapoDiscoveryDevice failing = new TapoDiscoveryDevice(
                "192.168.1.130", TapoAuthProtocol.KLAP, "DEVFOREIGN", "L530", "Fremdkonto", false);
        TapoDiscoveryDevice working = new TapoDiscoveryDevice(
                "192.168.1.131", TapoAuthProtocol.KLAP, "DEVOWN", "L530", "Eigen", true);
        when(tapoDeviceService.discoverLocalDevices()).thenReturn(List.of(failing, working));

        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEVFOREIGN"))
                .thenReturn(Optional.empty());
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEVOWN"))
                .thenReturn(Optional.empty());
        when(tapoDeviceService.getStatus(eq("DEVFOREIGN"), eq("192.168.1.130"), any()))
                .thenThrow(new TapoException("Anmeldung fehlgeschlagen: anderes TP-Link-Konto"));
        when(tapoDeviceService.getStatus(eq("DEVOWN"), eq("192.168.1.131"), any()))
                .thenReturn(new TapoDeviceState("Eigen", "L530", true, true, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<SmartDevice> persisted = newService().scanTapoDevices();

        assertEquals(2, persisted.size(), "ein fehlschlagendes Geraet darf den Scan der uebrigen nicht abbrechen");
        Map<String, SmartDevice> byId = persisted.stream()
                .collect(java.util.stream.Collectors.toMap(SmartDevice::getExternalDeviceId, d -> d));
        assertFalse(byId.get("DEVFOREIGN").isOnline(), "Geraet mit fehlgeschlagener Anmeldung muss offline sein, nicht fehlen");
        assertTrue(byId.get("DEVOWN").isOnline());
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
                .thenReturn(new TapoAddressProbeResult(TapoAuthProtocol.KLAP, state));
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
                eq(new LightState(70, 200, 80, null)));
        verify(repository).save(any());
        verify(entityStateService).reportState(any());
        verify(auditService).record(eq("device.light.set"), anyString());
    }

    @Test
    @DisplayName("setLightState lehnt eine Faehigkeit ab, die das Geraet nicht meldet, und sendet nichts an das Geraet")
    void setLightStateRejectsUnreportedCapability() {
        tapoLight(1L, "SWITCH", null); // keine BRIGHTNESS-Faehigkeit

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(1L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("setLightState lehnt eine leere Anfrage ohne jedes Feld ab")
    void setLightStateRejectsEmptyRequest() {
        tapoLight(1L, "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", null);

        LightStateRequest request = LightStateRequest.builder().build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(1L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any());
    }

    @Test
    @DisplayName("setLightState lehnt ein Nicht-Tapo-Geraet ab")
    void setLightStateRejectsNonTapoDevice() {
        SmartDevice device = new SmartDevice();
        device.setId(5L);
        device.setDeviceType(DeviceType.KASA);
        when(repository.findById(5L)).thenReturn(Optional.of(device));

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(IllegalArgumentException.class, () -> newService().setLightState(5L, request));

        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any());
    }

    @Test
    @DisplayName("setLightState: Helligkeit 0 und 101 werden abgelehnt, 1 und 100 werden akzeptiert")
    void setLightStateValidatesBrightnessBoundaries() {
        tapoLight(1L, "SWITCH,BRIGHTNESS", null);
        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().brightness(0).build()));
        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().brightness(101).build()));
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any());

        when(tapoDeviceService.getStatus(any(), any(), any()))
                .thenReturn(new TapoDeviceState("Flur", "L530", true, true, "SWITCH,BRIGHTNESS"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        newService().setLightState(1L, LightStateRequest.builder().brightness(1).build());
        newService().setLightState(1L, LightStateRequest.builder().brightness(100).build());

        verify(tapoDeviceService, times(2)).setLightState(any(), any(), any(), any());
    }

    @Test
    @DisplayName("setLightState lehnt eine Farbtemperatur ausserhalb des vom Geraet gemeldeten Bereichs ab")
    void setLightStateRejectsColorTempOutsideDeviceReportedRange() {
        tapoLight(1L, "SWITCH,COLOR_TEMP", "{\"colorTempRangeMin\":2500,\"colorTempRangeMax\":6500}");

        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().colorTemp(2000).build()));
        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().colorTemp(7000).build()));
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any());
    }

    @Test
    @DisplayName("setLightState faellt ohne gespeicherten Bereich auf 2500-6500 zurueck")
    void setLightStateFallsBackToDefaultColorTempRangeWhenDeviceReportedNone() {
        tapoLight(1L, "SWITCH,COLOR_TEMP", null);

        assertThrows(IllegalArgumentException.class,
                () -> newService().setLightState(1L, LightStateRequest.builder().colorTemp(2000).build()));
        verify(tapoDeviceService, never()).setLightState(any(), any(), any(), any());
    }

    @Test
    @DisplayName("setLightState laesst eine TapoException durch, wenn das Geraet nicht antwortet, und persistiert nichts")
    void setLightStatePropagatesFailureWithoutPersisting() {
        tapoLight(1L, "SWITCH,BRIGHTNESS", null);
        org.mockito.Mockito.doThrow(new TapoException("Tapo-Geraet nicht erreichbar"))
                .when(tapoDeviceService).setLightState(eq("DEV1"), eq("192.168.1.114"), any(), any());

        LightStateRequest request = LightStateRequest.builder().brightness(50).build();
        assertThrows(TapoException.class, () -> newService().setLightState(1L, request));

        verify(repository, never()).save(any());
        verify(auditService, never()).record(anyString(), anyString());
    }
}
