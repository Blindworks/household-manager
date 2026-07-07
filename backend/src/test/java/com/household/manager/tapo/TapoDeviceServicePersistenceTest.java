package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Persistenz-Verhalten des TapoDeviceService: gespeicherte IPs kommen aus der
 * smart_devices-Tabelle statt aus einer erneuten Discovery.
 */
class TapoDeviceServicePersistenceTest {

    private final SmartDeviceRepository repository = mock(SmartDeviceRepository.class);
    private final TapoCloudService cloudService = mock(TapoCloudService.class);
    private final TapoDiscoveryService discoveryService = mock(TapoDiscoveryService.class);
    private final TapoDeviceFactory deviceFactory = mock(TapoDeviceFactory.class);

    private TapoDeviceService newService() {
        TapoProperties properties = new TapoProperties();
        properties.setEmail("test@example.com");
        properties.setPassword("secret");
        return new TapoDeviceService(cloudService, discoveryService, deviceFactory,
                properties, repository, new ObjectMapper());
    }

    @Test
    @DisplayName("IP und Protokoll kommen aus der smart_devices-Tabelle")
    void resolvesIpAddressFromDatabase() {
        SmartDevice stored = new SmartDevice();
        stored.setDeviceType(DeviceType.TAPO);
        stored.setExternalDeviceId("ABC123");
        stored.setDeviceName("Stehlampe");
        stored.setIpAddress("192.168.1.112");
        stored.setMetadata("{\"authProtocol\":\"KLAP\"}");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "ABC123"))
                .thenReturn(Optional.of(stored));

        assertEquals("192.168.1.112", newService().resolveIpAddress("ABC123"));
    }

    @Test
    @DisplayName("Ohne DB-Treffer faellt die Aufloesung auf die Discovery zurueck")
    void fallsBackToDiscoveryWhenDatabaseHasNoIp() {
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        // Discovery liefert nichts -> null
        assertNull(newService().resolveIpAddress("UNKNOWN"));
    }

    @Test
    @DisplayName("Discovery schreibt NICHT in die DB (kein transaktionaler Nebeneffekt)")
    void discoverLocalDevicesDoesNotPersist() {
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "L900-5(EU)", "Lichtstreifen Buero", true)));
        when(deviceFactory.create(any(), any(), any(), any()))
                .thenReturn(mock(TapoLocalDeviceConnection.class));

        newService().discoverLocalDevices();

        // Persistenz gehoert ausschliesslich in den expliziten Scan (SmartDeviceService),
        // damit Discovery gefahrlos aus fremden Transaktionen (Refresh/Steuerung) aufgerufen werden kann.
        verify(repository, never()).save(any(SmartDevice.class));
    }

    @Test
    @DisplayName("getStatus loest bei lokalem Fehlschlag KEINE Re-Discovery aus")
    void getStatusDoesNotRediscoverOnLocalFailure() {
        TapoLocalDeviceConnection deadConnection = mock(TapoLocalDeviceConnection.class);
        when(deadConnection.getDeviceInfo()).thenThrow(new TapoException("connect timed out"));
        when(deviceFactory.create(any(), any(), any(), any())).thenReturn(deadConnection);

        assertThrows(TapoException.class,
                () -> newService().getStatus("DEV1", "192.168.1.50", TapoAuthProtocol.KLAP));

        // Lesepfade duerfen keinen UDP-Discovery-Sturm ausloesen (nur turnOn/turnOff heilen selbst).
        verify(discoveryService, never()).discoverLocalDevices(any(), any());
    }

    @Test
    @DisplayName("Bei toter gespeicherter IP: Re-Discovery und ein Retry (ohne DB-Schreiben)")
    void healsStaleIpViaRediscovery() {
        TapoLocalDeviceConnection deadConnection = mock(TapoLocalDeviceConnection.class);
        doThrow(new TapoException("connect timed out"))
                .when(deadConnection).setDevicePowered(true);
        TapoLocalDeviceConnection freshConnection = mock(TapoLocalDeviceConnection.class);

        when(deviceFactory.create(any(), eq("192.168.1.99"), any(), any()))
                .thenReturn(deadConnection);
        when(deviceFactory.create(any(), eq("192.168.1.153"), any(), any()))
                .thenReturn(freshConnection);
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "P110(EU)", "Stern", true)));

        newService().turnOn("DEV1", "192.168.1.99", TapoAuthProtocol.KLAP);

        verify(freshConnection).setDevicePowered(true);
        // Selbstheilung aktualisiert nur den In-Memory-Cache; die DB wird beim naechsten Scan geschrieben.
        verify(repository, never()).save(any(SmartDevice.class));
        verify(cloudService, never()).setDevicePowered(any(), anyBoolean());
    }

    @Test
    @DisplayName("Findet die Re-Discovery nichts, gibt es einen klaren lokalen Fehler")
    void failsWithClearMessageWhenRediscoveryFindsNothing() {
        TapoLocalDeviceConnection deadConnection = mock(TapoLocalDeviceConnection.class);
        doThrow(new TapoException("connect timed out"))
                .when(deadConnection).setDevicePowered(true);
        when(deviceFactory.create(any(), any(), any(), any())).thenReturn(deadConnection);
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discoveryService.discoverLocalDevices(any(), any()))
                .thenReturn(List.of());

        TapoException ex = assertThrows(TapoException.class,
                () -> newService().turnOn("DEV1", "192.168.1.99", TapoAuthProtocol.KLAP));

        assertTrue(ex.getMessage().contains("erneuter Suche"));
        verify(cloudService, never()).setDevicePowered(any(), anyBoolean());
    }
}
