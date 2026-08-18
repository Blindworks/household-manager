package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TapoDeviceStateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TapoCloudService cloudService = new TapoCloudService(objectMapper, new TapoProperties());

    @Test
    void shouldBuildStateFromDeviceInfoAndCloudFallback() throws Exception {
        var deviceInfo = objectMapper.readTree("""
                {
                  "nickname": "U3Rlcm4=",
                  "model": "P110",
                  "device_on": true
                }
                """);

        TapoCloudDevice cloudDevice = new TapoCloudDevice(
                "V29obnppbW1lcg==",
                "1",
                "ROLE_OWNER",
                "P110",
                "device-1",
                "SMART.TAPOPLUG",
                "Plug",
                "1.0",
                "AA-BB-CC-DD-EE-FF",
                "1.2.3",
                "https://example.invalid"
        );

        TapoDeviceState state = TapoDeviceState.from(deviceInfo, cloudDevice, cloudService);

        assertThat(state.nickname()).isEqualTo("Stern");
        assertThat(state.model()).isEqualTo("P110");
        assertThat(state.poweredOn()).isTrue();
        assertThat(state.online()).isTrue();
    }

    // ==================== readColorTempRange defensive guards (per-guard coverage) ====================

    /**
     * Same real (redacted) L530 {@code get_device_info} response as
     * {@link TapoCapabilityMapperTest#L530_DEVICE_INFO}, measured 2026-08-18 against the user's
     * bulb — see {@link TapoLocalProbeManualTest}. Kept as its own literal here (rather than
     * sharing a constant across test classes) so each test file stays self-contained; the {@code
     * color_temp_range} field is overwritten per test to exercise one guard at a time.
     */
    private static String l530DeviceInfo(String colorTempRangeJson) {
        return """
                {
                  "device_id": "<entfernt>", "fw_ver": "1.1.2 Build 260508 Rel.035137", "hw_ver": "2.0",
                  "type": "SMART.TAPOBULB", "model": "L530", "mac": "<entfernt>",
                  %s "overheated": false, "ip": "192.168.1.114",
                  "nickname": "Rmx1cg==",
                  "device_on": false, "brightness": 50, "hue": 0, "saturation": 100, "color_temp": 2985
                }
                """.formatted(colorTempRangeJson == null ? "" : "\"color_temp_range\": " + colorTempRangeJson + ",");
    }

    @Test
    void realL530PayloadYieldsItsActualColorTempRange() throws Exception {
        var deviceInfo = objectMapper.readTree(l530DeviceInfo("[2500, 6500]"));

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.colorTempMin()).isEqualTo(2500);
        assertThat(state.colorTempMax()).isEqualTo(6500);
    }

    @Test
    void missingColorTempRangeYieldsNullBounds() throws Exception {
        var deviceInfo = objectMapper.readTree(l530DeviceInfo(null));

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.colorTempMin()).isNull();
        assertThat(state.colorTempMax()).isNull();
    }

    @Test
    void colorTempRangeThatIsNotAnArrayYieldsNullBounds() throws Exception {
        var deviceInfo = objectMapper.readTree(l530DeviceInfo("\"not-an-array\""));

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.colorTempMin()).isNull();
        assertThat(state.colorTempMax()).isNull();
    }

    @Test
    void colorTempRangeWithWrongArraySizeYieldsNullBounds() throws Exception {
        var deviceInfo = objectMapper.readTree(l530DeviceInfo("[2500]"));

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.colorTempMin()).isNull();
        assertThat(state.colorTempMax()).isNull();
    }

    @Test
    void negativeColorTempRangeValueYieldsNullBounds() throws Exception {
        var deviceInfo = objectMapper.readTree(l530DeviceInfo("[-1, 6500]"));

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.colorTempMin()).isNull();
        assertThat(state.colorTempMax()).isNull();
    }

    @Test
    void colorTempRangeWithMinNotBelowMaxYieldsNullBounds() throws Exception {
        var deviceInfo = objectMapper.readTree(l530DeviceInfo("[6500, 6500]"));

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.colorTempMin()).isNull();
        assertThat(state.colorTempMax()).isNull();
    }

    // ==================== readCurrentLightState ====================

    @Test
    void realL530PayloadYieldsItsCurrentLightValuesIncludingZero() throws Exception {
        // color_temp: 0 here is a real "pure colour mode" value, not "absent" - same reasoning
        // TapoCapabilityMapper already applies to capability derivation.
        var deviceInfo = objectMapper.readTree("""
                {
                  "model": "L530", "device_on": true,
                  "brightness": 80, "hue": 200, "saturation": 100, "color_temp": 0
                }
                """);

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.currentLightState().brightness()).isEqualTo(80);
        assertThat(state.currentLightState().hue()).isEqualTo(200);
        assertThat(state.currentLightState().saturation()).isEqualTo(100);
        assertThat(state.currentLightState().colorTemp()).isEqualTo(0);
    }

    @Test
    void plugWithNoLightFieldsYieldsNullCurrentLightState() throws Exception {
        var deviceInfo = objectMapper.readTree("""
                {
                  "model": "P110", "device_on": true, "overheated": false
                }
                """);

        TapoDeviceState state = TapoDeviceState.fromLocal(deviceInfo, cloudService);

        assertThat(state.currentLightState()).isNull();
    }
}
