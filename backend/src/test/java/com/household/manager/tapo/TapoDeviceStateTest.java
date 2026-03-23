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
}
