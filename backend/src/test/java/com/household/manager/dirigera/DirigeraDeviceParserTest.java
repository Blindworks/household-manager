package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirigeraDeviceParserTest {

    private final DirigeraDeviceParser parser = new DirigeraDeviceParser(new ObjectMapper());

    @Test
    void fixtureErgibtVierSensorenMitZusammengefuehrtenWerten() throws IOException {
        DirigeraDevices d = parser.parse(fixture());

        assertThat(d.gatewayId()).isEqualTo("05f1aba0-b770-479e-994a-f167e187ce32_1");
        assertThat(d.sensors()).hasSize(4);
        DirigeraSensorSnapshot ess = bySensor(d, "dcc8d385-781d-4071-adf8-5f403b0cb997");
        assertThat(ess.name()).isEqualTo("Temperatur Esszimmer");
        assertThat(ess.room()).isEqualTo("Esszimmer");
        assertThat(ess.temperature()).isEqualByComparingTo("18.79");
        assertThat(ess.temperature().scale()).isEqualTo(2);
        assertThat(ess.humidity()).isEqualByComparingTo("52");
        assertThat(ess.batteryPercent()).isEqualTo(100);
        assertThat(ess.reachable()).isTrue();
        assertThat(ess.lastSeen()).isEqualTo(Instant.parse("2026-10-30T08:53:36Z"));
    }

    @Test
    void teilgeraetZuordnungNachFeldNichtNachSuffix() {
        // _1 traegt hier die Feuchte, _2 die Temperatur
        String json = "[" + sensor("a_1", "a", "currentRH", "40", 90, true, "2026-10-30T08:00:00.000Z", "N", "R")
                + "," + sensor("a_2", "a", "currentTemperature", "21.5", 90, true, "2026-10-30T08:00:00.000Z", "N", "R") + "]";

        DirigeraSensorSnapshot s = parser.parse(json).sensors().get(0);

        assertThat(s.temperature()).isEqualByComparingTo("21.5");
        assertThat(s.humidity()).isEqualByComparingTo("40");
    }

    @Test
    void nurEinTeilgeraetLiefertFehlendenWertAlsNull() {
        String json = "[" + sensor("a_1", "a", "currentTemperature", "21.5", 90, true, "2026-10-30T08:00:00.000Z", "N", "R") + "]";

        DirigeraSensorSnapshot s = parser.parse(json).sensors().get(0);

        assertThat(s.temperature()).isEqualByComparingTo("21.5");
        assertThat(s.humidity()).isNull();
    }

    @Test
    void fremderDeviceTypeWirdIgnoriert() {
        String json = "[{\"id\":\"l_1\",\"relationId\":\"l\",\"deviceType\":\"light\",\"isReachable\":true,\"attributes\":{}},"
                + sensor("a_1", "a", "currentTemperature", "21.5", 90, true, "2026-10-30T08:00:00.000Z", "N", "R") + "]";

        DirigeraDevices d = parser.parse(json);

        assertThat(d.sensors()).hasSize(1);
        assertThat(d.sensors().get(0).relationId()).isEqualTo("a");
        assertThat(d.gatewayId()).isNull();
    }

    @Test
    void unreachableWennEinTeilgeraetUnreachable() {
        String json = "[" + sensor("a_1", "a", "currentTemperature", "21.5", 90, true, "2026-10-30T08:00:00.000Z", "N", "R")
                + "," + sensor("a_2", "a", "currentRH", "40", 90, false, "2026-10-30T08:00:00.000Z", "N", "R") + "]";

        assertThat(parser.parse(json).sensors().get(0).reachable()).isFalse();
    }

    @Test
    void batterieIstMinimumDerTeilgeraete() {
        String json = "[" + sensor("a_1", "a", "currentTemperature", "21.5", 100, true, "2026-10-30T08:00:00.000Z", "N", "R")
                + "," + sensor("a_2", "a", "currentRH", "40", 40, true, "2026-10-30T08:00:00.000Z", "N", "R") + "]";

        assertThat(parser.parse(json).sensors().get(0).batteryPercent()).isEqualTo(40);
    }

    @Test
    void lastSeenIstMaximumDerTeilgeraete() {
        String json = "[" + sensor("a_1", "a", "currentTemperature", "21.5", 90, true, "2026-10-30T08:00:00.000Z", "N", "R")
                + "," + sensor("a_2", "a", "currentRH", "40", 90, true, "2026-10-30T09:00:00.000Z", "N", "R") + "]";

        assertThat(parser.parse(json).sensors().get(0).lastSeen()).isEqualTo(Instant.parse("2026-10-30T09:00:00Z"));
    }

    @Test
    void leererCustomNameFaelltAufModellZurueck() {
        String json = "[" + sensor("a_1", "a", "currentTemperature", "21.5", 90, true, "2026-10-30T08:00:00.000Z", "", "R") + "]";

        assertThat(parser.parse(json).sensors().get(0).name()).isEqualTo("TIMMERFLOTTE temp/hmd sensor");
    }

    @Test
    void keinArrayWirftDirigeraException() {
        assertThatThrownBy(() -> parser.parse("{}")).isInstanceOf(DirigeraException.class);
    }

    @Test
    void unlesbaresJsonWirftDirigeraException() {
        assertThatThrownBy(() -> parser.parse("nope")).isInstanceOf(DirigeraException.class);
    }

    private static DirigeraSensorSnapshot bySensor(DirigeraDevices d, String relationId) {
        return d.sensors().stream().filter(s -> s.relationId().equals(relationId)).findFirst().orElseThrow();
    }

    private static String sensor(String id, String relationId, String valueField, String value, int battery,
                                 boolean reachable, String lastSeen, String customName, String room) {
        return "{\"id\":\"" + id + "\",\"relationId\":\"" + relationId + "\",\"deviceType\":\"environmentSensor\","
                + "\"isReachable\":" + reachable + ",\"lastSeen\":\"" + lastSeen + "\","
                + "\"attributes\":{\"" + valueField + "\":" + value + ",\"customName\":\"" + customName + "\","
                + "\"model\":\"TIMMERFLOTTE temp/hmd sensor\",\"batteryPercentage\":" + battery + "},"
                + "\"room\":{\"name\":\"" + room + "\"}}";
    }

    private static String fixture() throws IOException {
        try (var in = DirigeraDeviceParserTest.class.getResourceAsStream("/dirigera/devices.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
