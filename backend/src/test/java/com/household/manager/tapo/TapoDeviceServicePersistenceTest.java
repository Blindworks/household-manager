package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        return new TapoDeviceService(cloudService, discoveryService, deviceFactory,
                new TapoProperties(), repository, new ObjectMapper());
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
}
