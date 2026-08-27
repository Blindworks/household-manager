package com.household.manager.blink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlinkSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parstKameralisteMitAllenFeldern() throws Exception {
        var json = mapper.readTree("""
                [{"cameraId":"123","name":"Haustuer","type":"doorbell","armed":true,
                  "battery":"ok","syncName":"Zuhause","syncArmed":false}]""");

        List<BlinkSidecarClient.SidecarCamera> cameras = BlinkSidecarClient.parseCameras(json);

        assertThat(cameras).containsExactly(new BlinkSidecarClient.SidecarCamera(
                "123", "Haustuer", "doorbell", true, "ok", "Zuhause", false));
    }

    @Test
    void parstKameraOhneBatterieAlsNull() throws Exception {
        var json = mapper.readTree("""
                [{"cameraId":"5","name":"Innen","type":"","armed":false,
                  "battery":null,"syncName":"Zuhause","syncArmed":true}]""");

        assertThat(BlinkSidecarClient.parseCameras(json).get(0).battery()).isNull();
    }

    @Test
    void parstKameraMitFehlendemBatterieFeldAlsNull() throws Exception {
        // "battery" fehlt komplett im JSON (nicht nur null) - JsonNode.path() liefert dafuer
        // einen MissingNode, der sich fuer isNull()/asText(null) genauso verhaelt wie ein
        // expliziter null-Wert. Ohne diesen Test waere das unverifiziert.
        var json = mapper.readTree("""
                [{"cameraId":"7","name":"Garage","type":"mini","armed":false,
                  "syncName":"Zuhause","syncArmed":false}]""");

        assertThat(BlinkSidecarClient.parseCameras(json).get(0).battery()).isNull();
    }

    @Test
    void parstClipliste() throws Exception {
        var json = mapper.readTree("""
                [{"clipId":"42","createdAt":"2026-08-27T14:30:05","sizeBytes":1234}]""");

        assertThat(BlinkSidecarClient.parseClips(json)).containsExactly(
                new BlinkSidecarClient.SidecarClip("42", "2026-08-27T14:30:05", 1234L));
    }
}
