package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture in {@link #KL110_SYSINFO} is the REAL, redacted {@code system.get_sysinfo} response
 * measured against the user's Kasa KL110 bulb (192.168.1.101) on 2026-08-18.
 * <p>
 * Unlike {@link com.household.manager.tapo.TapoCapabilityMapper}, Kasa devices state their
 * capabilities EXPLICITLY via {@code is_dimmable}/{@code is_color}/{@code is_variable_color_temp}
 * flags rather than requiring capability derivation from field presence - simpler than the Tapo
 * path, and the reason for the difference is documented here rather than guessed at.
 */
class KasaCapabilityMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Real KL110 response, identifiers redacted (values are irrelevant to capability derivation). */
    private static final String KL110_SYSINFO = """
            {"sw_ver":"1.8.11 Build 191113 Rel.105336","hw_ver":"1.0","model":"KL110(EU)",
              "description":"Smart Wi-Fi LED Bulb with Dimmable Light","alias":"Treppenhaus ",
              "mic_type":"IOT.SMARTBULB","dev_state":"normal","mic_mac":"<entfernt>","deviceId":"<entfernt>",
              "light_state":{"on_off":0,"dft_on_state":{"mode":"normal","hue":0,"saturation":0,"color_temp":2700,"brightness":100}},
              "is_dimmable":1,"is_color":0,"is_variable_color_temp":0,
              "rssi":-61,"active_mode":"none","heapsize":292656,"err_code":0}
            """;

    private static final String PLUG_SYSINFO = """
            {"sw_ver":"1.0.8 Build 191111 Rel.105336","hw_ver":"1.0","model":"HS100(EU)",
              "deviceId":"<entfernt>","alias":"Steckdose","relay_state":1,"mic_type":"IOT.SMARTPLUGSWITCH",
              "err_code":0}
            """;

    @Test
    @DisplayName("KL110-Antwort ergibt SWITCH,BRIGHTNESS (is_color und is_variable_color_temp sind 0)")
    void kl110SysInfoYieldsSwitchAndBrightnessOnly() throws Exception {
        JsonNode sysInfo = objectMapper.readTree(KL110_SYSINFO);

        String capabilities = KasaCapabilityMapper.deriveCapabilities(sysInfo);

        assertThat(capabilities).isEqualTo("SWITCH,BRIGHTNESS");
    }

    @Test
    @DisplayName("Eine Farb-Leuchtmittel-Antwort (alle drei Flags gesetzt) ergibt alle vier Faehigkeiten in fester Reihenfolge")
    void colorBulbSysInfoYieldsAllFourCapabilitiesInFixedOrder() throws Exception {
        JsonNode sysInfo = objectMapper.readTree("""
                {"model":"KL130(EU)","is_dimmable":1,"is_color":1,"is_variable_color_temp":1,
                  "light_state":{"on_off":1,"brightness":80,"hue":200,"saturation":100,"color_temp":0}}
                """);

        String capabilities = KasaCapabilityMapper.deriveCapabilities(sysInfo);

        assertThat(capabilities).isEqualTo("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
    }

    @Test
    @DisplayName("Eine Steckdosen-Antwort (keine is_*-Flags) ergibt nur SWITCH")
    void plugSysInfoYieldsOnlySwitch() throws Exception {
        JsonNode sysInfo = objectMapper.readTree(PLUG_SYSINFO);

        String capabilities = KasaCapabilityMapper.deriveCapabilities(sysInfo);

        assertThat(capabilities).isEqualTo("SWITCH");
    }

    @Test
    @DisplayName("Ein fehlender sysInfo-Knoten ergibt nur SWITCH (defensiv, wie beim Tapo-Pendant)")
    void missingSysInfoYieldsOnlySwitch() {
        JsonNode missing = objectMapper.missingNode();

        String capabilities = KasaCapabilityMapper.deriveCapabilities(missing);

        assertThat(capabilities).isEqualTo("SWITCH");
    }

    @Test
    @DisplayName("Eine Bulb-sysinfo (light_state vorhanden) OHNE is_*-Flags faellt sicher auf SWITCH zurueck (aeltere Firmware)")
    void bulbShapedSysInfoMissingCapabilityFlagsFailsSafeToSwitch() throws Exception {
        JsonNode sysInfo = objectMapper.readTree("""
                {"model":"KL110(EU)",
                  "light_state":{"on_off":0,"dft_on_state":{"brightness":100}}}
                """);

        String capabilities = KasaCapabilityMapper.deriveCapabilities(sysInfo);

        assertThat(capabilities).isEqualTo("SWITCH");
    }

    @Test
    @DisplayName("Ein Wanddimmer (is_dimmable:1, aber KEIN light_state) bekommt keine BRIGHTNESS-Faehigkeit")
    void wallDimmerWithoutLightStateGetsNoLightCapabilities() throws Exception {
        // HS220/KS220/KP405-Form: is_dimmable:1 direkt in sysinfo, aber kein light_state - dieses
        // Geraet spricht nicht smartlife.iot.smartbulb.lightingservice und darf deshalb keine
        // BRIGHTNESS-Faehigkeit (und damit keinen Frontend-Regler) bekommen, der nur 400 lieferte.
        JsonNode sysInfo = objectMapper.readTree("""
                {"model":"HS220(US)","is_dimmable":1,"relay_state":1}
                """);

        String capabilities = KasaCapabilityMapper.deriveCapabilities(sysInfo);

        assertThat(capabilities).isEqualTo("SWITCH");
    }
}
