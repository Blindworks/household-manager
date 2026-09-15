package com.household.manager.zigbee.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeBridgeMessageParserTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private final ZigbeeBridgeMessageParser parser =
            new ZigbeeBridgeMessageParser(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static final String DEVICES = """
            [
              {"ieee_address":"0x00124b0021aabbcc","type":"Coordinator","network_address":0,
               "supported":true,"friendly_name":"Coordinator","definition":null,
               "power_source":"DC Source","interviewing":false,"interview_completed":true},
              {"ieee_address":"0x00158d0005a1b2c3","type":"EndDevice","network_address":12345,
               "supported":true,"friendly_name":"Motion Büro","disabled":false,
               "definition":{"model":"SNZB-03","vendor":"SONOFF","description":"Motion sensor","exposes":[]},
               "power_source":"Battery","interviewing":false,"interview_completed":true,
               "unknown_future_field":{"x":1}},
              {"ieee_address":"0x00158d0005ffffff","type":"Router","network_address":777,
               "supported":true,"friendly_name":"Steckdose Flur",
               "definition":{"model":"ZBMINI","vendor":"SONOFF","description":"Switch"},
               "power_source":"Mains (single phase)","interview_state":"IN_PROGRESS"}
            ]
            """;

    @Test
    void liestGeraeteOhneCoordinator() {
        Optional<List<ZigbeeBridgeDevice>> result = parser.parseDevices("zigbee2mqtt/bridge/devices", DEVICES);

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(ZigbeeBridgeDevice::friendlyName)
                .containsExactly("Motion Büro", "Steckdose Flur");
        ZigbeeBridgeDevice motion = result.get().get(0);
        assertThat(motion.ieeeAddress()).isEqualTo("0x00158d0005a1b2c3");
        assertThat(motion.model()).isEqualTo("SNZB-03");
        assertThat(motion.vendor()).isEqualTo("SONOFF");
        assertThat(motion.battery()).isTrue();
        assertThat(motion.interviewCompleted()).isTrue();
        assertThat(motion.networkAddress()).isEqualTo(12345);
    }

    @Test
    void liestInterviewStateDerZweierVersion() {
        List<ZigbeeBridgeDevice> devices = parser.parseDevices("zigbee2mqtt/bridge/devices", DEVICES).orElseThrow();

        ZigbeeBridgeDevice router = devices.get(1);
        assertThat(router.interviewCompleted()).isFalse();
        assertThat(router.battery()).isFalse();
    }

    @Test
    void geraeteOhneDefinitionBleibenErhalten() {
        String payload = """
                [{"ieee_address":"0x1","type":"EndDevice","friendly_name":"0x1","supported":false,
                  "definition":null,"power_source":"Battery","interview_completed":false}]
                """;

        List<ZigbeeBridgeDevice> devices = parser.parseDevices("zigbee2mqtt/bridge/devices", payload).orElseThrow();

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).model()).isNull();
        assertThat(devices.get(0).supported()).isFalse();
    }

    @Test
    void anderesTopicOderKaputtesJsonErgibtLeer() {
        assertThat(parser.parseDevices("zigbee2mqtt/Motion Büro", DEVICES)).isEmpty();
        assertThat(parser.parseDevices("zigbee2mqtt/bridge/devices", "[{broken")).isEmpty();
        assertThat(parser.parseDevices("zigbee2mqtt/bridge/devices", "{\"not\":\"array\"}")).isEmpty();
    }

    @Test
    void liestInfoMitOffenemAnlernfensterUndAktiverPruefung() {
        String payload = """
                {"version":"2.1.3","permit_join":true,"permit_join_end":1757950000000,
                 "config":{"availability":{"enabled":true,"active":{"timeout":10},"passive":{"timeout":1500}}}}
                """;

        ZigbeeBridgeInfo info = parser.parseInfo("zigbee2mqtt/bridge/info", payload).orElseThrow();

        assertThat(info.version()).isEqualTo("2.1.3");
        assertThat(info.permitJoin()).isTrue();
        assertThat(info.permitJoinEnd()).isEqualTo(Instant.ofEpochMilli(1757950000000L));
        assertThat(info.availabilityCheckEnabled()).isTrue();
    }

    @Test
    void liestPermitJoinTimeoutDerEinerVersion() {
        String payload = """
                {"version":"1.42.0","permit_join":true,"permit_join_timeout":120,"config":{}}
                """;

        ZigbeeBridgeInfo info = parser.parseInfo("zigbee2mqtt/bridge/info", payload).orElseThrow();

        assertThat(info.permitJoin()).isTrue();
        assertThat(info.permitJoinEnd()).isEqualTo(Instant.parse("2026-09-15T10:02:00Z"));
    }

    @Test
    void geschlossenesFensterIgnoriertAltenPermitJoinTimeout() {
        String payload = """
                {"version":"1.42.0","permit_join":false,"permit_join_timeout":120,"config":{}}
                """;

        ZigbeeBridgeInfo info = parser.parseInfo("zigbee2mqtt/bridge/info", payload).orElseThrow();

        assertThat(info.permitJoin()).isFalse();
        assertThat(info.permitJoinEnd()).isNull();
    }

    @Test
    void permitJoinEndGewinntGegenTimeoutUndNullTimeoutErgibtKeinEnde() {
        ZigbeeBridgeInfo beides = parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"permit_join\":true,\"permit_join_end\":1757950000000,\"permit_join_timeout\":120}").orElseThrow();
        assertThat(beides.permitJoinEnd()).isEqualTo(Instant.ofEpochMilli(1757950000000L));

        ZigbeeBridgeInfo abgelaufen = parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"permit_join\":true,\"permit_join_timeout\":0}").orElseThrow();
        assertThat(abgelaufen.permitJoinEnd()).isNull();
    }

    @Test
    void unbekannteStromquelleGiltAlsBatterie() {
        assertThat(bridgeDevice(null).battery()).isTrue();
        assertThat(bridgeDevice("Unknown").battery()).isTrue();
        assertThat(bridgeDevice("Battery").battery()).isTrue();
        assertThat(bridgeDevice("Mains (single phase)").battery()).isFalse();
        assertThat(bridgeDevice("DC Source").battery()).isFalse();
    }

    private static ZigbeeBridgeDevice bridgeDevice(String powerSource) {
        return new ZigbeeBridgeDevice("0x1", "Sensor", "EndDevice", powerSource, true, true, null, null, null, null);
    }

    @Test
    void verfuegbarkeitspruefungAlsBooleanOderFehlend() {
        assertThat(parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"1.42.0\",\"permit_join\":false,\"config\":{\"availability\":true}}")
                .orElseThrow().availabilityCheckEnabled()).isTrue();
        assertThat(parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"1.42.0\",\"permit_join\":false,\"config\":{\"availability\":false}}")
                .orElseThrow().availabilityCheckEnabled()).isFalse();
        assertThat(parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"2.1.3\",\"permit_join\":false,\"config\":{\"availability\":{\"enabled\":false}}}")
                .orElseThrow().availabilityCheckEnabled()).isFalse();
        ZigbeeBridgeInfo ohne = parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"1.42.0\",\"permit_join\":false,\"config\":{}}").orElseThrow();
        assertThat(ohne.availabilityCheckEnabled()).isFalse();
        assertThat(ohne.permitJoinEnd()).isNull();
    }

    @Test
    void liestAlleVierEreignistypen() {
        ZigbeeBridgeEvent joined = parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_joined\",\"data\":{\"friendly_name\":\"0x00158d00aaaaaaaa\",\"ieee_address\":\"0x00158d00aaaaaaaa\"}}")
                .orElseThrow();
        assertThat(joined.type()).isEqualTo("device_joined");
        assertThat(joined.ieeeAddress()).isEqualTo("0x00158d00aaaaaaaa");
        assertThat(joined.receivedAt()).isNull();

        ZigbeeBridgeEvent interview = parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_interview\",\"data\":{\"friendly_name\":\"0x00158d00aaaaaaaa\",\"ieee_address\":\"0x00158d00aaaaaaaa\",\"status\":\"successful\",\"supported\":true,\"definition\":{\"model\":\"SNZB-02\",\"vendor\":\"SONOFF\"}}}")
                .orElseThrow();
        assertThat(interview.status()).isEqualTo("successful");
        assertThat(interview.model()).isEqualTo("SNZB-02");
        assertThat(interview.vendor()).isEqualTo("SONOFF");

        assertThat(parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_announce\",\"data\":{\"friendly_name\":\"Motion Büro\",\"ieee_address\":\"0x1\"}}"))
                .isPresent();
        assertThat(parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_leave\",\"data\":{\"friendly_name\":\"Motion Büro\",\"ieee_address\":\"0x1\"}}"))
                .isPresent();
    }

    @Test
    void unbekannterEreignistypWirdIgnoriert() {
        assertThat(parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"something_new\",\"data\":{}}")).isEmpty();
        assertThat(parser.parseEvent("zigbee2mqtt/bridge/state",
                "{\"type\":\"device_joined\",\"data\":{}}")).isEmpty();
    }

    @Test
    void liestAntwortenMitTransaktion() {
        ZigbeeBridgeResponse ok = parser.parseResponse("zigbee2mqtt/bridge/response/permit_join",
                "{\"data\":{\"time\":240},\"status\":\"ok\",\"transaction\":\"abc-1\"}").orElseThrow();
        assertThat(ok.request()).isEqualTo("permit_join");
        assertThat(ok.transaction()).isEqualTo("abc-1");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.error()).isNull();

        ZigbeeBridgeResponse error = parser.parseResponse("zigbee2mqtt/bridge/response/device/remove",
                "{\"data\":{},\"status\":\"error\",\"error\":\"Device 'x' does not exist\",\"transaction\":\"abc-2\"}").orElseThrow();
        assertThat(error.request()).isEqualTo("device/remove");
        assertThat(error.ok()).isFalse();
        assertThat(error.error()).isEqualTo("Device 'x' does not exist");
    }

    @Test
    void antwortOhneTransaktionHatNullTransaktion() {
        ZigbeeBridgeResponse response = parser.parseResponse("zigbee2mqtt/bridge/response/health_check",
                "{\"data\":{\"healthy\":true},\"status\":\"ok\"}").orElseThrow();
        assertThat(response.transaction()).isNull();
    }
}
