package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.repository.SmartDeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TapoDeviceService#probeAddress(String)}: der manuelle "Adresse setzen"-Weg, der die
 * in einem Docker-Bridge-Netz blockierte UDP-Discovery umgeht (siehe SmartDeviceService,
 * addKasaDeviceByIp-Pendant fuer Tapo).
 */
class TapoDeviceServiceTest {

    private final TapoCloudService tapoCloudService = mock(TapoCloudService.class);
    private final TapoDiscoveryService tapoDiscoveryService = mock(TapoDiscoveryService.class);
    private final TapoDeviceFactory tapoDeviceFactory = mock(TapoDeviceFactory.class);
    private final TapoProperties tapoProperties = new TapoProperties();
    private final SmartDeviceRepository smartDeviceRepository = mock(SmartDeviceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TapoDeviceService newService() {
        tapoProperties.setEmail("test@example.com");
        tapoProperties.setPassword("secret");
        when(tapoCloudService.decodeAlias(any())).thenAnswer(inv -> inv.getArgument(0));
        return new TapoDeviceService(tapoCloudService, tapoDiscoveryService, tapoDeviceFactory,
                tapoProperties, smartDeviceRepository, objectMapper);
    }

    private JsonNode deviceInfo(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("probeAddress versucht zuerst KLAP und liefert dessen Ergebnis, wenn es klappt")
    void probeAddressPrefersKlap() throws Exception {
        TapoLocalDeviceConnection klapConnection = mock(TapoLocalDeviceConnection.class);
        when(klapConnection.getDeviceInfo()).thenReturn(
                deviceInfo("{\"nickname\":\"Flur\",\"model\":\"L530\",\"device_on\":true,"
                        + "\"brightness\":80,\"hue\":0,\"saturation\":0,\"color_temp\":2700}"));
        when(tapoDeviceFactory.create(eq(TapoAuthProtocol.KLAP), eq("192.168.1.114"), any(), any()))
                .thenReturn(klapConnection);

        TapoAddressProbeResult result = newService().probeAddress("192.168.1.114");

        assertEquals(TapoAuthProtocol.KLAP, result.protocol());
        assertEquals("L530", result.state().model());
        assertEquals("Flur", result.state().nickname());
        assertEquals("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP", result.state().capabilities());
    }

    @Test
    @DisplayName("probeAddress faellt auf AES zurueck, wenn KLAP fehlschlaegt")
    void probeAddressFallsBackToAes() throws Exception {
        when(tapoDeviceFactory.create(eq(TapoAuthProtocol.KLAP), eq("192.168.1.114"), any(), any()))
                .thenThrow(new TapoException("KLAP handshake failed"));

        TapoLocalDeviceConnection aesConnection = mock(TapoLocalDeviceConnection.class);
        when(aesConnection.getDeviceInfo()).thenReturn(
                deviceInfo("{\"nickname\":\"Garage\",\"model\":\"P110(EU)\",\"device_on\":false}"));
        when(tapoDeviceFactory.create(eq(TapoAuthProtocol.AES), eq("192.168.1.114"), any(), any()))
                .thenReturn(aesConnection);

        TapoAddressProbeResult result = newService().probeAddress("192.168.1.114");

        assertEquals(TapoAuthProtocol.AES, result.protocol());
        assertEquals("Garage", result.state().nickname());
        assertEquals("SWITCH", result.state().capabilities());
    }

    @Test
    @DisplayName("probeAddress wirft eine TapoException, wenn weder KLAP noch AES antworten")
    void probeAddressThrowsWhenBothProtocolsFail() {
        when(tapoDeviceFactory.create(eq(TapoAuthProtocol.KLAP), eq("192.168.1.200"), any(), any()))
                .thenThrow(new TapoException("no route to host"));
        when(tapoDeviceFactory.create(eq(TapoAuthProtocol.AES), eq("192.168.1.200"), any(), any()))
                .thenThrow(new TapoException("no route to host"));

        assertThrows(TapoException.class, () -> newService().probeAddress("192.168.1.200"));
    }
}
