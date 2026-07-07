package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

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
    @DisplayName("Discovery legt unbekannte Geraete in der DB an")
    void discoveryPersistsNewDevices() {
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "L900-5(EU)", "Lichtstreifen Buero", true)));
        when(deviceFactory.create(any(), any(), any(), any()))
                .thenReturn(mock(TapoLocalDeviceConnection.class));

        newService().discoverLocalDevices();

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("192.168.1.153", saved.getIpAddress());
        assertEquals("DEV1", saved.getExternalDeviceId());
        assertEquals("Lichtstreifen Buero", saved.getDeviceName());
        assertTrue(saved.getMetadata().contains("\"authProtocol\":\"KLAP\""));
    }

    @Test
    @DisplayName("Discovery aktualisiert IP, ohne Namen oder fremde Metadata zu ueberschreiben")
    void discoveryUpdatesExistingDeviceWithoutClobbering() {
        SmartDevice existing = new SmartDevice();
        existing.setDeviceType(DeviceType.TAPO);
        existing.setExternalDeviceId("DEV1");
        existing.setDeviceName("Mein Wunschname");
        existing.setIpAddress("192.168.1.99");
        existing.setMetadata("{\"deviceMac\":\"aa:bb\"}");
        when(repository.findByDeviceTypeAndExternalDeviceId(DeviceType.TAPO, "DEV1"))
                .thenReturn(Optional.of(existing));
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "L900-5(EU)", "Lichtstreifen Buero", true)));
        when(deviceFactory.create(any(), any(), any(), any()))
                .thenReturn(mock(TapoLocalDeviceConnection.class));

        newService().discoverLocalDevices();

        ArgumentCaptor<SmartDevice> captor = ArgumentCaptor.forClass(SmartDevice.class);
        verify(repository).save(captor.capture());
        SmartDevice saved = captor.getValue();
        assertEquals("192.168.1.153", saved.getIpAddress());
        assertEquals("Mein Wunschname", saved.getDeviceName());
        assertTrue(saved.getMetadata().contains("deviceMac"));
        assertTrue(saved.getMetadata().contains("\"authProtocol\":\"KLAP\""));
    }

    @Test
    @DisplayName("Bei toter gespeicherter IP: Re-Discovery, DB-Update, ein Retry")
    void healsStaleIpViaRediscovery() {
        TapoLocalDeviceConnection deadConnection = mock(TapoLocalDeviceConnection.class);
        doThrow(new TapoException("connect timed out"))
                .when(deadConnection).setDevicePowered(true);
        TapoLocalDeviceConnection freshConnection = mock(TapoLocalDeviceConnection.class);

        when(deviceFactory.create(any(), eq("192.168.1.99"), any(), any()))
                .thenReturn(deadConnection);
        when(deviceFactory.create(any(), eq("192.168.1.153"), any(), any()))
                .thenReturn(freshConnection);
        when(repository.findByDeviceTypeAndExternalDeviceId(any(), any()))
                .thenReturn(Optional.empty());
        when(discoveryService.discoverLocalDevices(any(), any())).thenReturn(List.of(
                new TapoDiscoveryDevice("192.168.1.153", TapoAuthProtocol.KLAP,
                        "DEV1", "P110(EU)", "Stern", true)));

        newService().turnOn("DEV1", "192.168.1.99", TapoAuthProtocol.KLAP);

        verify(freshConnection).setDevicePowered(true);
        verify(repository).save(any(SmartDevice.class));
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
