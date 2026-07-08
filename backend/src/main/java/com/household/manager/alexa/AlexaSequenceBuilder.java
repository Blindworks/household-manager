package com.household.manager.alexa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Baut die exakten Request-Bodies fuer POST /api/behaviors/preview.
 * <p>
 * Die Struktur entspricht den bewaehrten Referenzimplementierungen
 * (alexa-remote-control, alexa_media_player): eine aeussere PREVIEW-Huelle mit
 * einem als String eingebetteten "sequenceJson".
 */
@Component
public class AlexaSequenceBuilder {

    private static final String SEQUENCE_TYPE = "com.amazon.alexa.behaviors.model.Sequence";
    private static final String OPERATION_NODE_TYPE =
            "com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode";
    private static final String ANNOUNCEMENT_SKILL_ID = "amzn1.ask.1p.routines.messaging";

    private final ObjectMapper mapper;

    public AlexaSequenceBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Alexa.Speak: einfache Sprachausgabe auf genau einem Geraet, ohne Signalton. */
    public String buildSpeak(AlexaRemoteDevice device, String customerId, String locale, String text) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("deviceType", device.deviceType());
        payload.put("deviceSerialNumber", device.serialNumber());
        payload.put("customerId", customerId);
        payload.put("locale", locale);
        payload.put("textToSpeak", text);

        ObjectNode startNode = mapper.createObjectNode();
        startNode.put("@type", OPERATION_NODE_TYPE);
        startNode.put("type", "Alexa.Speak");
        startNode.set("operationPayload", payload);

        return wrapSequence(startNode);
    }

    /** AlexaAnnouncement: Durchsage mit Signalton an ein oder mehrere Geraete. */
    public String buildAnnouncement(List<AlexaRemoteDevice> devices, String customerId,
                                    String locale, String text) {
        ObjectNode display = mapper.createObjectNode();
        display.put("title", "Household Manager");
        display.put("body", text);

        ObjectNode speak = mapper.createObjectNode();
        speak.put("type", "text");
        speak.put("value", text);

        ObjectNode contentItem = mapper.createObjectNode();
        contentItem.put("locale", locale);
        contentItem.set("display", display);
        contentItem.set("speak", speak);

        ArrayNode content = mapper.createArrayNode();
        content.add(contentItem);

        ArrayNode targetDevices = mapper.createArrayNode();
        for (AlexaRemoteDevice device : devices) {
            ObjectNode d = mapper.createObjectNode();
            d.put("deviceSerialNumber", device.serialNumber());
            d.put("deviceTypeId", device.deviceType());
            targetDevices.add(d);
        }

        ObjectNode target = mapper.createObjectNode();
        target.put("customerId", customerId);
        target.set("devices", targetDevices);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("expandTextField", "None");
        payload.put("customerId", customerId);
        payload.put("locale", locale);
        payload.set("content", content);
        payload.set("target", target);
        payload.put("skillId", ANNOUNCEMENT_SKILL_ID);

        ObjectNode startNode = mapper.createObjectNode();
        startNode.put("@type", OPERATION_NODE_TYPE);
        startNode.put("type", "AlexaAnnouncement");
        startNode.set("operationPayload", payload);

        return wrapSequence(startNode);
    }

    private String wrapSequence(ObjectNode startNode) {
        try {
            ObjectNode sequence = mapper.createObjectNode();
            sequence.put("@type", SEQUENCE_TYPE);
            sequence.set("startNode", startNode);

            ObjectNode body = mapper.createObjectNode();
            body.put("behaviorId", "PREVIEW");
            body.put("sequenceJson", mapper.writeValueAsString(sequence));
            body.put("status", "ENABLED");
            return mapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Sequenz konnte nicht serialisiert werden.", ex);
        }
    }
}
