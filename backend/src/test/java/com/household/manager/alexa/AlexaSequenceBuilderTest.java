package com.household.manager.alexa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlexaSequenceBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AlexaSequenceBuilder builder = new AlexaSequenceBuilder(mapper);

    private AlexaRemoteDevice device(String serial, String type) {
        return new AlexaRemoteDevice(serial, "Kueche", type, "ROOK", List.of("AUDIO_PLAYER"));
    }

    @Test
    void speakPayloadContainsPreviewEnvelopeAndSpeakNode() throws Exception {
        String body = builder.buildSpeak(device("DSN1", "A1TYPE"), "cid-123", "de-DE", "Hallo Welt");

        JsonNode root = mapper.readTree(body);
        assertThat(root.get("behaviorId").asText()).isEqualTo("PREVIEW");
        assertThat(root.get("status").asText()).isEqualTo("ENABLED");

        JsonNode seq = mapper.readTree(root.get("sequenceJson").asText());
        JsonNode op = seq.get("startNode");
        assertThat(op.get("type").asText()).isEqualTo("Alexa.Speak");
        JsonNode payload = op.get("operationPayload");
        assertThat(payload.get("deviceSerialNumber").asText()).isEqualTo("DSN1");
        assertThat(payload.get("deviceType").asText()).isEqualTo("A1TYPE");
        assertThat(payload.get("customerId").asText()).isEqualTo("cid-123");
        assertThat(payload.get("locale").asText()).isEqualTo("de-DE");
        assertThat(payload.get("textToSpeak").asText()).isEqualTo("Hallo Welt");
    }

    @Test
    void announcePayloadTargetsAllDevicesWithAnnouncementNode() throws Exception {
        String body = builder.buildAnnouncement(
                List.of(device("DSN1", "A1TYPE"), device("DSN2", "A2TYPE")),
                "cid-123", "de-DE", "Abendessen ist fertig");

        JsonNode root = mapper.readTree(body);
        JsonNode seq = mapper.readTree(root.get("sequenceJson").asText());
        JsonNode op = seq.get("startNode");
        assertThat(op.get("type").asText()).isEqualTo("AlexaAnnouncement");
        JsonNode payload = op.get("operationPayload");
        assertThat(payload.get("skillId").asText()).isEqualTo("amzn1.ask.1p.routines.messaging");
        assertThat(payload.get("content").get(0).get("speak").get("value").asText())
                .isEqualTo("Abendessen ist fertig");
        JsonNode devices = payload.get("target").get("devices");
        assertThat(devices).hasSize(2);
        assertThat(devices.get(0).get("deviceSerialNumber").asText()).isEqualTo("DSN1");
        assertThat(devices.get(0).get("deviceTypeId").asText()).isEqualTo("A1TYPE");
        assertThat(devices.get(1).get("deviceSerialNumber").asText()).isEqualTo("DSN2");
    }

    @Test
    void announcementEscapesQuotesInText() throws Exception {
        String body = builder.buildAnnouncement(
                List.of(device("DSN1", "A1TYPE")), "cid", "de-DE", "Sag \"Hallo\"");
        // Muss wieder parsebar sein -> keine Escaping-Fehler
        JsonNode root = mapper.readTree(body);
        JsonNode seq = mapper.readTree(root.get("sequenceJson").asText());
        assertThat(seq.get("startNode").get("operationPayload")
                .get("content").get(0).get("speak").get("value").asText())
                .isEqualTo("Sag \"Hallo\"");
    }
}
