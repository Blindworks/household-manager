package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DirigeraButtonParsingTest {

    private final DirigeraDeviceParser parser = new DirigeraDeviceParser(new ObjectMapper());

    @Test
    void bilresaErgibtJeTasteEinenEintrag() throws IOException {
        DirigeraDevices devices = parser.parse(fixture("bilresa-devices.json"));

        assertThat(devices.sensors()).isEmpty();
        assertThat(devices.buttons()).extracting(DirigeraButton::deviceId).containsExactly(
                "4ab740b2-efb5-4301-8bf5-63c702722938_1", "4ab740b2-efb5-4301-8bf5-63c702722938_2");
        DirigeraButton top = devices.buttons().get(0);
        assertThat(top.name()).isEqualTo("Button Treppenhaus");
        assertThat(top.position()).isEqualTo("top");
        assertThat(top.model()).isEqualTo("BILRESA dual button");
        assertThat(top.room()).isEqualTo("Treppenhaus");
        assertThat(top.batteryPercent()).isEqualTo(100);
        // der Raum steht nur am ersten Teilgeraet
        assertThat(devices.buttons().get(1).room()).isNull();
        assertThat(devices.buttons().get(1).position()).isEqualTo("bottom");
    }

    @Test
    void bestandsFixtureEnthaeltKeineTaster() throws IOException {
        assertThat(parser.parse(fixture("devices.json")).buttons()).isEmpty();
    }

    @Test
    void tasteOhneIdWirdIgnoriert() {
        assertThat(parser.parse("[{\"deviceType\":\"genericSwitch\",\"attributes\":{}}]").buttons()).isEmpty();
    }

    private static String fixture(String name) throws IOException {
        try (var in = DirigeraButtonParsingTest.class.getResourceAsStream("/dirigera/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
