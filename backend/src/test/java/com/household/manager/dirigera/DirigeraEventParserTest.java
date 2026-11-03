package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DirigeraEventParserTest {

    private final DirigeraEventParser parser = new DirigeraEventParser(new ObjectMapper());

    @Test
    void echterTastendruckDesBilresa() throws IOException {
        DirigeraRemotePress press = parser.parseRemotePress(fixture("remote-press-event.json")).orElseThrow();

        assertThat(press.deviceId()).isEqualTo("4ab740b2-efb5-4301-8bf5-63c702722938_1");
        assertThat(press.clickPattern()).isEqualTo("singlePress");
        assertThat(press.time()).isEqualTo(Instant.parse("2026-11-03T17:28:58.700Z"));
    }

    @Test
    void zustandsaenderungIstKeinTastendruck() throws IOException {
        assertThat(parser.parseRemotePress(fixture("device-state-changed-event.json"))).isEmpty();
    }

    @Test
    void tastendruckOhneMusterOderGeraetWirdVerworfen() {
        assertThat(parser.parseRemotePress("{\"type\":\"remotePressEvent\",\"data\":{\"id\":\"x_1\"}}")).isEmpty();
        assertThat(parser.parseRemotePress("{\"type\":\"remotePressEvent\",\"data\":{\"clickPattern\":\"singlePress\"}}")).isEmpty();
        assertThat(parser.parseRemotePress("{\"type\":\"remotePressEvent\",\"data\":{\"id\":\"\",\"clickPattern\":\"singlePress\"}}")).isEmpty();
    }

    @Test
    void echtesOeffnenUndSchliessenDesBadfensters() throws IOException {
        DirigeraContactChange open = parser.parseContactChange(fixture("contact-open-event.json")).orElseThrow();
        DirigeraContactChange closed = parser.parseContactChange(fixture("contact-closed-event.json")).orElseThrow();

        // der MYGGBETT schickt keine relationId, der Schluessel ist die Geraete-Id (wie im Geraete-Parser)
        assertThat(open.key()).isEqualTo("07ddd369-cdff-4593-a0f5-c995dfcea5f2_1");
        assertThat(open.open()).isTrue();
        assertThat(open.lastSeen()).isEqualTo(Instant.parse("2026-11-03T17:35:17Z"));
        assertThat(open.reachable()).isTrue();
        assertThat(closed.open()).isFalse();
    }

    @Test
    void temperaturmeldungIstKeinKontaktwechsel() throws IOException {
        assertThat(parser.parseContactChange(fixture("device-state-changed-event.json"))).isEmpty();
    }

    @Test
    void kontaktmeldungOhneIsOpenWirdIgnoriert() {
        assertThat(parser.parseContactChange("{\"type\":\"deviceStateChanged\",\"data\":{\"id\":\"x_1\","
                + "\"deviceType\":\"openCloseSensor\",\"attributes\":{\"batteryPercentage\":90}}}")).isEmpty();
    }

    @Test
    void tastendruckIstKeinKontaktwechsel() throws IOException {
        assertThat(parser.parseContactChange(fixture("remote-press-event.json"))).isEmpty();
    }

    @Test
    void kaputtesJsonWirftNicht() {
        assertThat(parser.parseRemotePress("kein json")).isEmpty();
        assertThat(parser.parseRemotePress("[]")).isEmpty();
    }

    @Test
    void fehlendeZeitIstNull() {
        DirigeraRemotePress press = parser.parseRemotePress(
                "{\"type\":\"remotePressEvent\",\"data\":{\"id\":\"x_1\",\"clickPattern\":\"longPress\"}}").orElseThrow();

        assertThat(press.time()).isNull();
    }

    private static String fixture(String name) throws IOException {
        try (var in = DirigeraEventParserTest.class.getResourceAsStream("/dirigera/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
