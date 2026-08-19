package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.smartdevice.LightState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verhalten von {@link KasaService}: unicast getStatus/probe muessen dieselbe
 * sysinfo-JSON-Antwort (wie sie auch die Broadcast-Discovery erhaelt) korrekt
 * in ihre jeweilige DTO abbilden - fuer Steckdosen UNVERAENDERT ueber relay_state,
 * fuer Leuchtmittel neu ueber light_state/dft_on_state.
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

    /** Real KL110 response (192.168.1.101, measured 2026-08-18), redacted, off (relies on dft_on_state). */
    private static final String KL110_SYSINFO_RESPONSE = """
            {"system":{"get_sysinfo":{
                "sw_ver":"1.8.11 Build 191113 Rel.105336","hw_ver":"1.0","model":"KL110(EU)",
                "description":"Smart Wi-Fi LED Bulb with Dimmable Light","alias":"Treppenhaus ",
                "mic_type":"IOT.SMARTBULB","dev_state":"normal","mic_mac":"AABBCCDDEEFF","deviceId":"KL110DEVICEID000000000000000000",
                "light_state":{"on_off":0,"dft_on_state":{"mode":"normal","hue":0,"saturation":0,"color_temp":2700,"brightness":100}},
                "is_dimmable":1,"is_color":0,"is_variable_color_temp":0,
                "rssi":-61,"active_mode":"none","heapsize":292656,"err_code":0
            }}}""";

    private final KasaTcpClient kasaTcpClient = mock(KasaTcpClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KasaService kasaService = new KasaService(kasaTcpClient, objectMapper);

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
    @DisplayName("getStatus() bleibt fuer Steckdosen unveraendert funktionsfaehig (bestehende Vertragspruefung)")
    void getStatusStillParsesSysInfo() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(REALISTIC_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.116");

        assertEquals("8006ABCDEF1234567890ABCDEF123456", status.deviceId());
        assertEquals("Wohnzimmer Lampe", status.alias());
        assertTrue(status.relayState());
        assertFalse(status.bulb());
        assertEquals("SWITCH", status.capabilities());
        assertNull(status.brightness());
    }

    @Test
    @DisplayName("Eine Steckdosen-sysinfo hat keine Leuchtmittel-Felder und wird weiterhin ueber relay_state gelesen")
    void plugSysInfoHasNoLightFieldsAndUsesRelayState() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(REALISTIC_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.116");

        assertFalse(status.bulb());
        assertNull(status.hue());
        assertNull(status.saturation());
        assertNull(status.colorTemp());
    }

    @Test
    @DisplayName("Ein ausgeschaltetes Leuchtmittel liest on_off aus light_state, NICHT relay_state (das es nicht gibt)")
    void bulbOffStateIsReadFromLightStateOnOff() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(KL110_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.101");

        assertTrue(status.bulb());
        assertFalse(status.relayState());
    }

    @Test
    @DisplayName("Ein ausgeschaltetes Leuchtmittel liest Helligkeit/Farbe aus light_state.dft_on_state")
    void bulbOffStateReadsValuesFromDftOnState() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(KL110_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.101");

        assertEquals(100, status.brightness());
        assertEquals(0, status.hue());
        assertEquals(0, status.saturation());
        assertEquals(2700, status.colorTemp());
    }

    @Test
    @DisplayName("Ein eingeschaltetes Leuchtmittel liest Helligkeit/Farbe direkt aus light_state, nicht aus dft_on_state")
    void bulbOnStateReadsValuesDirectlyFromLightState() {
        String onResponse = KL110_SYSINFO_RESPONSE.replace(
                "\"light_state\":{\"on_off\":0,\"dft_on_state\":{\"mode\":\"normal\",\"hue\":0,\"saturation\":0,\"color_temp\":2700,\"brightness\":100}}",
                "\"light_state\":{\"on_off\":1,\"mode\":\"normal\",\"hue\":10,\"saturation\":20,\"color_temp\":3200,\"brightness\":55}");
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(onResponse);

        KasaStatusDto status = kasaService.getStatus("192.168.1.101");

        assertTrue(status.relayState());
        assertEquals(55, status.brightness());
        assertEquals(10, status.hue());
        assertEquals(20, status.saturation());
        assertEquals(3200, status.colorTemp());
    }

    @Test
    @DisplayName("Kapazitaeten des KL110 werden aus is_dimmable/is_color/is_variable_color_temp abgeleitet")
    void kl110CapabilitiesAreDerivedFromDeviceFlags() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(KL110_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.101");

        assertEquals("SWITCH,BRIGHTNESS", status.capabilities());
    }

    @Test
    @DisplayName("Der Alias mit angehaengtem Leerzeichen wird getrimmt")
    void aliasTrailingSpaceIsTrimmed() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(KL110_SYSINFO_RESPONSE);

        KasaStatusDto status = kasaService.getStatus("192.168.1.101");

        assertEquals("Treppenhaus", status.alias());
    }

    @Test
    @DisplayName("turnOn() sendet fuer eine Steckdose weiterhin set_relay_state")
    void turnOnSendsSetRelayStateForPlug() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn("{}");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.turnOn("192.168.1.116", false);

        verify(kasaTcpClient).send(eq("192.168.1.116"), payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains("\"set_relay_state\""));
        assertFalse(payloadCaptor.getValue().contains("transition_light_state"));
    }

    @Test
    @DisplayName("turnOn() sendet fuer ein Leuchtmittel transition_light_state statt set_relay_state")
    void turnOnSendsTransitionLightStateForBulb() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(bulbResultResponse(1, ""));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.turnOn("192.168.1.101", true);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        JsonNode state = payload.path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertEquals(1, state.path("on_off").asInt());
        assertFalse(payload.has("system"));
    }

    @Test
    @DisplayName("turnOn() wirft KasaCommunicationException mit err_msg, wenn das Leuchtmittel einen Fehler meldet")
    void turnOnThrowsWithErrMsgWhenBulbReportsError() {
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn("""
                        {"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":
                            {"err_code":-10000,"err_msg":"Invalid input argument"}}}""");

        KasaCommunicationException ex = assertThrows(KasaCommunicationException.class,
                () -> kasaService.turnOn("192.168.1.101", true));
        assertTrue(ex.getMessage().contains("-10000"));
        assertTrue(ex.getMessage().contains("Invalid input argument"));
    }

    @Test
    @DisplayName("turnOff() sendet fuer ein Leuchtmittel transition_light_state mit on_off:0")
    void turnOffSendsTransitionLightStateForBulb() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn(bulbResultResponse(0, ""));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.turnOff("192.168.1.101", true);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        JsonNode state = payload.path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertEquals(0, state.path("on_off").asInt());
    }

    @Test
    @DisplayName("turnOff() sendet fuer eine Steckdose weiterhin set_relay_state mit state:0")
    void turnOffSendsSetRelayStateForPlug() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn("{}");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.turnOff("192.168.1.116", false);

        verify(kasaTcpClient).send(eq("192.168.1.116"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals(0, payload.path("system").path("set_relay_state").path("state").asInt());
    }

    @Test
    @DisplayName("Das bestehende einparametrige turnOn(ip)/turnOff(ip) bleibt fuer den rohen IP-Endpunkt unveraendert (Steckdosen-Payload)")
    void legacySingleArgTurnOnStillSendsPlugPayload() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn("{}");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.turnOn("192.168.1.116");

        verify(kasaTcpClient).send(eq("192.168.1.116"), payloadCaptor.capture());
        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals(1, payload.path("system").path("set_relay_state").path("state").asInt());
    }

    // ==================== setLightState() - measured protocol facts (echter KL110, 2026-08-19) ====================

    /**
     * Baut eine plausible {@code transition_light_state}-Antwort. Simuliert NICHT zwangslaeufig,
     * was das echte Geraet fuer eine gegebene Anfrage zurueckliefert - siehe die dedizierten Tests
     * unten fuer die tatsaechlich gemessenen (teils ueberraschenden) Antworten.
     */
    private String bulbResultResponse(int onOff, String extraResultFields) {
        return """
                {"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":
                    {"on_off":%d,%s"err_code":0}}}""".formatted(onOff, extraResultFields.isEmpty() ? "" : extraResultFields + ",");
    }

    @Test
    @DisplayName("setLightState() sendet IMMER on_off:1 und ignore_default:1, unabhaengig davon, was der Aufrufer setzt")
    void setLightStateAlwaysSendsOnOffAndIgnoreDefault() throws Exception {
        // Gemessen: {"brightness":40} ohne on_off blieb ein stiller No-op (aus, unveraendert,
        // err_code:0); {"on_off":1,"brightness":60} ohne ignore_default schaltete ein, wendete aber
        // den geraeteseitigen Default (100) statt der angefragten 60 an (ebenfalls err_code:0). Erst
        // {"on_off":1,"brightness":35,"ignore_default":1} liess den Wert tatsaechlich landen.
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"brightness\":70"));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.setLightState("192.168.1.101", new LightState(70, null, null, null), true);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode state = objectMapper.readTree(payloadCaptor.getValue())
                .path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertEquals(1, state.path("on_off").asInt());
        assertEquals(1, state.path("ignore_default").asInt());
    }

    @Test
    @DisplayName("setLightState() mit nur Helligkeit sendet ausschliesslich brightness (kein hue/saturation/color_temp)")
    void setLightStateWithOnlyBrightnessSendsOnlyBrightness() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"brightness\":70"));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.setLightState("192.168.1.101", new LightState(70, null, null, null), true);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode state = objectMapper.readTree(payloadCaptor.getValue())
                .path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertEquals(70, state.path("brightness").asInt());
        assertFalse(state.has("hue"));
        assertFalse(state.has("saturation"));
        assertFalse(state.has("color_temp"));
    }

    @Test
    @DisplayName("setLightState() mit Farbe haengt bei COLOR_TEMP-faehigen Geraeten color_temp:0 an, um in den Farbmodus zu wechseln")
    void setLightStateWithColorAppendsColorTempZeroWhenDeviceSupportsColorTemp() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"hue\":200,\"saturation\":80,\"color_temp\":0"));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.setLightState("192.168.1.101", new LightState(null, 200, 80, null), true);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode state = objectMapper.readTree(payloadCaptor.getValue())
                .path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertEquals(200, state.path("hue").asInt());
        assertEquals(80, state.path("saturation").asInt());
        assertEquals(0, state.path("color_temp").asInt());
    }

    @Test
    @DisplayName("setLightState() mit Farbe laesst color_temp weg, wenn das Geraet COLOR_TEMP nicht meldet")
    void setLightStateWithColorOmitsColorTempWhenUnsupported() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"hue\":0,\"saturation\":0"));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.setLightState("192.168.1.101", new LightState(null, 200, 80, null), false);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode state = objectMapper.readTree(payloadCaptor.getValue())
                .path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertFalse(state.has("color_temp"));
    }

    @Test
    @DisplayName("setLightState() mit reiner Farbtemperatur sendet nur color_temp, kein hue/saturation")
    void setLightStateWithColorTempOnlySendsOnlyColorTemp() throws Exception {
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"color_temp\":4000"));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        kasaService.setLightState("192.168.1.101", new LightState(null, null, null, 4000), true);

        verify(kasaTcpClient).send(eq("192.168.1.101"), payloadCaptor.capture());
        JsonNode state = objectMapper.readTree(payloadCaptor.getValue())
                .path("smartlife.iot.smartbulb.lightingservice").path("transition_light_state");
        assertEquals(4000, state.path("color_temp").asInt());
        assertFalse(state.has("hue"));
        assertFalse(state.has("saturation"));
    }

    @Test
    @DisplayName("setLightState() liest die tatsaechlich vom Geraet gemeldeten Werte zurueck, nicht die angefragten (ignore_default korrekt angewendet)")
    void setLightStateReturnsActualReportedValuesFromResponse() {
        // Gemessen: {"on_off":1,"brightness":35,"ignore_default":1} -> {"on_off":1,"brightness":35,
        // "err_code":0} - die Antwort spiegelt exakt den angewendeten Wert.
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"brightness\":35"));

        KasaLightCommandResult result = kasaService.setLightState(
                "192.168.1.101", new LightState(35, null, null, null), true);

        assertTrue(result.poweredOn());
        assertEquals(35, result.lightState().brightness());
    }

    @Test
    @DisplayName("setLightState() vertraut err_code:0 NICHT als Beweis, dass der Wert ankam - liest hue unveraendert zurueck, wenn das Geraet es so meldet")
    void setLightStateDoesNotTrustErrCodeZeroAsProofOfApplication() {
        // Gemessen: {"on_off":1,"hue":200,"saturation":80,"color_temp":0,"ignore_default":1} an
        // dieses NICHT farbfaehige Geraet gesendet -> Antwort err_code:0, aber hue blieb bei 0.
        // Diese Methode darf die Anfrage (hue=200) nicht als Ergebnis zurueckgeben, sondern muss
        // exakt das melden, was das Geraet selbst berichtet (hue=0, unveraendert).
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn(bulbResultResponse(1, "\"hue\":0,\"saturation\":0,\"color_temp\":2700,\"brightness\":100"));

        KasaLightCommandResult result = kasaService.setLightState(
                "192.168.1.101", new LightState(null, 200, 80, null), false);

        assertEquals(0, result.lightState().hue(),
                "die Rueckgabe muss den vom Geraet gemeldeten, unveraenderten hue widerspiegeln - nicht den angefragten Wert 200");
    }

    @Test
    @DisplayName("setLightState() liest resultierende Werte aus dft_on_state, wenn das Geraet trotz Anfrage als aus meldet")
    void setLightStateReadsResultFromDftOnStateWhenDeviceReportsOff() {
        // Verteidigend: transition_light_state ist strukturell identisch zu get_sysinfo.light_state
        // (siehe KasaSysInfoMapper) - dieselbe on_off/dft_on_state-Verschachtelung muss deshalb auch
        // hier greifen, falls das Geraet trotz gesendetem on_off:1 als aus antwortet (z. B. Fehler
        // beim Einschalten selbst trotz err_code:0 - nicht am echten KL110 beobachtet, aber die
        // Antwortform erlaubt es strukturell).
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn("""
                        {"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":
                            {"on_off":0,"dft_on_state":{"brightness":100,"hue":0,"saturation":0,"color_temp":2700},"err_code":0}}}""");

        KasaLightCommandResult result = kasaService.setLightState(
                "192.168.1.101", new LightState(70, null, null, null), true);

        assertFalse(result.poweredOn());
        assertEquals(100, result.lightState().brightness());
    }

    @Test
    @DisplayName("setLightState() wirft KasaCommunicationException mit err_code und err_msg bei einem ungueltigen Wert")
    void setLightStateThrowsWithErrCodeAndErrMsgOnInvalidValue() {
        // Gemessen: {"on_off":1,"brightness":150,"ignore_default":1} -> {"err_code":-10000,
        // "err_msg":"Invalid input argument"} - das Geraet meldet echte Fehler, kein err_code:0.
        when(kasaTcpClient.send(anyString(), anyString()))
                .thenReturn("""
                        {"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":
                            {"err_code":-10000,"err_msg":"Invalid input argument"}}}""");

        KasaCommunicationException ex = assertThrows(KasaCommunicationException.class,
                () -> kasaService.setLightState("192.168.1.101", new LightState(150, null, null, null), true));
        assertTrue(ex.getMessage().contains("-10000"));
        assertTrue(ex.getMessage().contains("Invalid input argument"));
    }

    @Test
    @DisplayName("setLightState() wirft KasaCommunicationException, wenn die Antwort keine transition_light_state enthaelt")
    void setLightStateThrowsWhenResponseHasNoTransitionLightState() {
        when(kasaTcpClient.send(anyString(), anyString())).thenReturn("{}");

        assertThrows(KasaCommunicationException.class,
                () -> kasaService.setLightState("192.168.1.101", new LightState(50, null, null, null), true));
    }
}
