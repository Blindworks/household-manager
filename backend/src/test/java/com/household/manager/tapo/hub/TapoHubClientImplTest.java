package com.household.manager.tapo.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.household.manager.tapo.TapoAuthProtocol;
import com.household.manager.tapo.TapoDeviceFactory;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.TapoLocalDeviceConnection;
import com.household.manager.tapo.TapoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TapoHubClientImplTest {

    private static final String HUB_IP = "192.168.1.50";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TapoDeviceFactory factory;
    private TapoLocalDeviceConnection connection;
    private TapoHubClientImpl client;

    @BeforeEach
    void setUp() {
        factory = mock(TapoDeviceFactory.class);
        connection = mock(TapoLocalDeviceConnection.class);
        TapoProperties properties = new TapoProperties();
        properties.setEmail("user@example.com");
        properties.setPassword("geheim");
        when(factory.create(any(), eq(HUB_IP), any(), any())).thenReturn(connection);
        client = new TapoHubClientImpl(factory, properties, new ThermostatReadingMapper(), objectMapper);
    }

    private JsonNode childListFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tapo/hub/child-device-list-ke100.json")) {
            return objectMapper.readTree(in);
        }
    }

    @Test
    @DisplayName("readThermostats sendet genau einen Request und liefert die Thermostate")
    void readsAllThermostatsWithOneRequest() throws Exception {
        when(connection.request(eq("get_child_device_list"), any())).thenReturn(childListFixture());

        List<ThermostatReading> readings = client.readThermostats(HUB_IP);

        assertEquals(4, readings.size());
        verify(connection, times(1)).request(any(), any());
    }

    @Test
    @DisplayName("setTargetTemp baut den verschachtelten control_child-Body")
    void setTargetTempBuildsNestedControlChild() {
        client.setTargetTemp(HUB_IP, "KE100-WOHNZIMMER", 21.5);

        ArgumentCaptor<ObjectNode> params = ArgumentCaptor.forClass(ObjectNode.class);
        verify(connection).request(eq("control_child"), params.capture());
        assertEquals("KE100-WOHNZIMMER", params.getValue().path("device_id").asText());
        JsonNode inner = params.getValue().path("requestData");
        assertEquals("set_device_info", inner.path("method").asText());
        assertEquals(21.5, inner.path("params").path("target_temp").asDouble());
    }

    @Test
    @DisplayName("setFrostProtection sendet frost_protection_on")
    void setFrostProtectionSendsFlag() {
        client.setFrostProtection(HUB_IP, "KE100-SCHLAFZIMMER", true);

        ArgumentCaptor<ObjectNode> params = ArgumentCaptor.forClass(ObjectNode.class);
        verify(connection).request(eq("control_child"), params.capture());
        assertEquals(true, params.getValue().path("requestData").path("params")
                .path("frost_protection_on").asBoolean());
    }

    @Test
    @DisplayName("Bei Fehler wird genau einmal mit neuer Verbindung wiederholt")
    void retriesOnceWithFreshConnection() throws Exception {
        TapoLocalDeviceConnection secondConnection = mock(TapoLocalDeviceConnection.class);
        when(factory.create(any(), eq(HUB_IP), any(), any())).thenReturn(connection, secondConnection);
        when(connection.request(eq("get_child_device_list"), any()))
                .thenReturn(childListFixture())
                .thenThrow(new TapoException("Socket tot"));
        when(secondConnection.request(eq("get_child_device_list"), any())).thenReturn(childListFixture());

        client.readThermostats(HUB_IP);
        List<ThermostatReading> second = client.readThermostats(HUB_IP);

        assertEquals(4, second.size());
        verify(secondConnection, times(1)).request(any(), any());
    }

    @Test
    @DisplayName("Scheitert auch der Wiederholungsversuch, wird TapoException geworfen")
    void failsWhenRetryAlsoFails() {
        when(connection.request(any(), any())).thenThrow(new TapoException("tot"));

        assertThrows(TapoException.class, () -> client.readThermostats(HUB_IP));
    }

    @Test
    @DisplayName("Ohne bekanntes Protokoll wird KLAP zuerst, dann AES probiert")
    void fallsBackToAesWhenKlapFails() throws Exception {
        TapoLocalDeviceConnection klap = mock(TapoLocalDeviceConnection.class);
        TapoLocalDeviceConnection aes = mock(TapoLocalDeviceConnection.class);
        when(factory.create(eq(TapoAuthProtocol.KLAP), eq(HUB_IP), any(), any())).thenReturn(klap);
        when(factory.create(eq(TapoAuthProtocol.AES), eq(HUB_IP), any(), any())).thenReturn(aes);
        when(klap.request(any(), any())).thenThrow(new TapoException("KLAP abgelehnt"));
        when(aes.request(eq("get_child_device_list"), any())).thenReturn(childListFixture());

        assertEquals(4, client.readThermostats(HUB_IP).size());
        client.readThermostats(HUB_IP);

        verify(factory, never()).create(eq(TapoAuthProtocol.UNKNOWN), any(), any(), any());
        verify(klap, times(1)).request(any(), any());
        verify(aes, times(2)).request(any(), any());
    }

    @Test
    @DisplayName("Abgelehnter Kindbefehl (response_data.error_code != 0) wirft TapoException mit Code")
    void rejectedChildCommandThrows() throws Exception {
        when(connection.request(eq("control_child"), any())).thenReturn(objectMapper.readTree(
                "{\"response_data\":{\"method\":\"set_device_info\",\"error_code\":-1008}}"));

        TapoException ex = assertThrows(TapoException.class,
                () -> client.setTargetTemp(HUB_IP, "KE100-WOHNZIMMER", 21.5));
        assertEquals(true, ex.getMessage().contains("-1008"));
        TapoException frost = assertThrows(TapoException.class,
                () -> client.setFrostProtection(HUB_IP, "KE100-WOHNZIMMER", true));
        assertEquals(true, frost.getMessage().contains("-1008"));
    }

    @Test
    @DisplayName("Erfolgreicher Kindbefehl (error_code 0) wirft nicht")
    void acceptedChildCommandPasses() throws Exception {
        when(connection.request(eq("control_child"), any())).thenReturn(objectMapper.readTree(
                "{\"response_data\":{\"result\":{},\"error_code\":0}}"));

        client.setTargetTemp(HUB_IP, "KE100-WOHNZIMMER", 21.5);
    }

    @Test
    @DisplayName("probeHub liefert die Hub-Geraete-Id und die Thermostate")
    void probeHubReturnsIdentityAndThermostats() throws Exception {
        when(connection.getDeviceInfo()).thenReturn(objectMapper.readTree("{\"device_id\":\"HUB-1\",\"model\":\"H100\"}"));
        when(connection.request(eq("get_child_device_list"), any())).thenReturn(childListFixture());

        HubProbeResult result = client.probeHub(HUB_IP);

        assertEquals("HUB-1", result.hubDeviceId());
        assertEquals(4, result.thermostats().size());
    }

    @Test
    @DisplayName("probeHub ohne erreichbaren Hub wirft TapoException")
    void probeHubFailsLoudly() {
        when(connection.getDeviceInfo()).thenThrow(new TapoException("nicht erreichbar"));

        assertThrows(TapoException.class, () -> client.probeHub(HUB_IP));
    }
}
