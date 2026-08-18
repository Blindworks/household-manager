package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture in {@link #L530_DEVICE_INFO} is the REAL, redacted {@code get_device_info}
 * response measured against the user's Tapo L530 bulb (192.168.1.114) on 2026-08-18
 * (see {@link TapoLocalProbeManualTest} / the tplink-leuchtmittel plan, Task 1).
 */
class TapoCapabilityMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Real L530 response, identifiers redacted (values are irrelevant to capability derivation). */
    private static final String L530_DEVICE_INFO = """
            {
              "device_id": "<entfernt>", "fw_ver": "1.1.2 Build 260508 Rel.035137", "hw_ver": "2.0",
              "type": "SMART.TAPOBULB", "model": "L530", "mac": "<entfernt>",
              "hw_id": "<entfernt>", "fw_id": "<entfernt>", "oem_id": "<entfernt>",
              "color_temp_range": [2500, 6500], "overheated": false, "ip": "192.168.1.114",
              "time_diff": 60, "ssid": "<entfernt>", "rssi": -55, "signal_level": 2,
              "lang": "de_DE", "avatar": "table_lamp_1", "region": "Europe/Berlin", "specs": "",
              "nickname": "Rmx1cg==", "has_set_location_info": false,
              "device_on": false, "brightness": 50, "hue": 0, "saturation": 100, "color_temp": 2985,
              "dynamic_light_effect_enable": false,
              "default_states": { "re_power_type": "always_on", "type": "last_states",
                "state": { "brightness": 50, "hue": 0, "saturation": 100, "color_temp": 2985 } }
            }
            """;

    private static final String PLUG_DEVICE_INFO = """
            {
              "device_id": "<entfernt>", "fw_ver": "1.0.0", "hw_ver": "1.0",
              "type": "SMART.TAPOPLUG", "model": "P110", "mac": "<entfernt>",
              "device_on": true, "overheated": false
            }
            """;

    @Test
    @DisplayName("L530-Antwort ergibt alle vier Faehigkeiten in fester Reihenfolge")
    void l530DeviceInfoYieldsAllFourCapabilitiesInFixedOrder() throws Exception {
        JsonNode deviceInfo = objectMapper.readTree(L530_DEVICE_INFO);

        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);

        assertThat(capabilities).isEqualTo("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
    }

    @Test
    @DisplayName("Eine Steckdosen-Antwort ergibt nur SWITCH")
    void plugDeviceInfoYieldsOnlySwitch() throws Exception {
        JsonNode deviceInfo = objectMapper.readTree(PLUG_DEVICE_INFO);

        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);

        assertThat(capabilities).isEqualTo("SWITCH");
    }

    @Test
    @DisplayName("color_temp: 0 (reiner Farbmodus) zaehlt weiterhin als COLOR_TEMP-faehig")
    void colorTempZeroStillYieldsColorTempCapability() throws Exception {
        JsonNode deviceInfo = objectMapper.readTree("""
                {
                  "model": "L530", "device_on": true,
                  "brightness": 80, "hue": 200, "saturation": 100, "color_temp": 0
                }
                """);

        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);

        assertThat(capabilities).isEqualTo("SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP");
    }

    @Test
    @DisplayName("Ein weiss-nur-Leuchtmittel (Helligkeit + Farbtemperatur, keine Farbe) ergibt SWITCH,BRIGHTNESS,COLOR_TEMP")
    void whiteOnlyBulbYieldsSwitchBrightnessColorTemp() throws Exception {
        JsonNode deviceInfo = objectMapper.readTree("""
                {
                  "model": "L510", "device_on": true,
                  "brightness": 70, "color_temp": 4000
                }
                """);

        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);

        assertThat(capabilities).isEqualTo("SWITCH,BRIGHTNESS,COLOR_TEMP");
    }
}
