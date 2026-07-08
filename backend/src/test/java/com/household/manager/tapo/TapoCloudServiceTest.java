package com.household.manager.tapo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TapoCloudServiceTest {

    private final TapoCloudService service = new TapoCloudService(new ObjectMapper(), new TapoProperties());

    @Test
    void decodeAliasShouldDecodeBase64Utf8() {
        assertThat(service.decodeAlias("U3Rlcm4=")).isEqualTo("Stern");
    }

    @Test
    void decodeAliasShouldReturnOriginalForPlainText() {
        assertThat(service.decodeAlias("Wohnzimmer")).isEqualTo("Wohnzimmer");
    }

    private TapoCloudDevice device(String deviceType, String model) {
        return new TapoCloudDevice(
                "QWxpYXM=", "1", "role", model, "DEV", deviceType,
                "Name", "1.0", "AA:BB:CC:DD:EE:FF", "1.0.0", "https://example.com");
    }

    @Test
    @DisplayName("Kasa-Cloud-Geraete (IOT.*) werden nicht als Tapo klassifiziert")
    void kasaDevicesAreNotClassifiedAsTapo() {
        assertThat(service.isTapoDevice(device("IOT.SMARTPLUGSWITCH", "HS100(EU)"))).isFalse();
        assertThat(service.isTapoDevice(device("IOT.SMARTBULB", "LB130(EU)"))).isFalse();
        assertThat(service.isTapoDevice(device("IOT.IPCAMERA", "KC200"))).isFalse();
        assertThat(service.isTapoDevice(device("SMART.KASAPLUG", "KP115"))).isFalse();
    }

    @Test
    @DisplayName("Echte Tapo-Geraete werden weiterhin als Tapo klassifiziert")
    void tapoDevicesAreClassifiedAsTapo() {
        assertThat(service.isTapoDevice(device("SMART.TAPOPLUG", "P110(EU)"))).isTrue();
        assertThat(service.isTapoDevice(device("SMART.TAPOBULB", "L530(EU)"))).isTrue();
        assertThat(service.isTapoDevice(device("SMART.IPCAMERA", "C200"))).isTrue();
    }
}
