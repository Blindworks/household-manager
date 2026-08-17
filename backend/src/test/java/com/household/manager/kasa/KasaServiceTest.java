package com.household.manager.kasa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verhalten von {@link KasaService}: unicast getStatus/probe muessen dieselbe
 * sysinfo-JSON-Antwort (wie sie auch die Broadcast-Discovery erhaelt) korrekt
 * in ihre jeweilige DTO abbilden.
 */
class KasaServiceTest {

    private static final String REALISTIC_SYSINFO_RESPONSE = """
            {"system":{"get_sysinfo":{
                "sw_ver":"1.0.8 Build 191111 Rel.105336",
                "hw_ver":"1.0",
                "model":"HS100(EU)",
                "deviceId":"8006ABCDEF1234567890ABCDEF123456",
                "alias":"Wohnzimmer Lampe",
                "relay_state":1,
                "on_time":120,
                "active_mode":"schedule",
                "feature":"TIM",
                "updating":0,
                "icon_hash":"",
                "rssi":-52,
                "led_off":0,
                "longitude_i":0,
                "latitude_i":0,
                "hwId":"ABCDEF1234567890ABCDEF1234567890",
                "fwId":"00000000000000000000000000000000",
                "deviceId2":"ABCDEF1234567890ABCDEF1234567890ABCDEF12",
                "oemId":"ABCDEF1234567890ABCDEF1234567890",
                "next_action":{"type":-1},
                "ntc_state":0,
                "err_code":0
            }}}""";

    private final KasaTcpClient kasaTcpClient = mock(KasaTcpClient.class);
    private final KasaService kasaService = new KasaService(kasaTcpClient, new ObjectMapper());

    @Test
    @DisplayName("probe() bildet deviceId, model, alias und relay_state aus der sysinfo-Antwort ab")
    void probeMapsDeviceIdModelAliasAndRelayStateFromSysInfo() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(REALISTIC_SYSINFO_RESPONSE);

        KasaDiscoveryDto dto = kasaService.probe("192.168.1.116");

        assertEquals("192.168.1.116", dto.getIp());
        assertEquals("8006ABCDEF1234567890ABCDEF123456", dto.getDeviceId());
        assertEquals("HS100(EU)", dto.getModel());
        assertEquals("Wohnzimmer Lampe", dto.getAlias());
        assertTrue(dto.isRelayState());
    }

    @Test
    @DisplayName("probe() bildet einen ausgeschalteten relay_state korrekt auf false ab")
    void probeMapsRelayStateOffCorrectly() {
        String responseWithRelayOff = REALISTIC_SYSINFO_RESPONSE.replace("\"relay_state\":1", "\"relay_state\":0");
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(responseWithRelayOff);

        KasaDiscoveryDto dto = kasaService.probe("192.168.1.116");

        assertFalse(dto.isRelayState());
    }

    @Test
    @DisplayName("probe() wirft KasaCommunicationException, wenn die Antwort keine sysinfo enthaelt")
    void probeThrowsWhenResponseHasNoSysInfo() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn("{\"system\":{}}");

        assertThrows(KasaCommunicationException.class, () -> kasaService.probe("192.168.1.116"));
    }

    @Test
    @DisplayName("probe() wirft KasaCommunicationException weiter, wenn der TCP-Client scheitert")
    void probePropagatesCommunicationFailure() {
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenThrow(new KasaCommunicationException("Failed to communicate with Kasa device at IP 192.168.1.116 after 3 attempts"));

        assertThrows(KasaCommunicationException.class, () -> kasaService.probe("192.168.1.116"));
    }

    @Test
    @DisplayName("getStatus() bleibt unveraendert funktionsfaehig (bestehende Vertragspruefung)")
    void getStatusStillParsesSysInfo() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(REALISTIC_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.116");

        assertEquals("8006ABCDEF1234567890ABCDEF123456", status.deviceId());
        assertEquals("Wohnzimmer Lampe", status.alias());
        assertTrue(status.relayState());
    }
}
