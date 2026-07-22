package com.household.manager.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionSidecarClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Regressionsschutz: Mit dem Default HTTP_2 versucht der JDK-Client bei http://-URLs
     * ein h2c-Upgrade und schickt die Anfrage OHNE Body — uvicorn antwortet dann mit
     * HTTP 422 "Field required". Der Foto-Upload war dadurch komplett kaputt.
     */
    @Test
    void httpClientIsPinnedToHttp11() {
        assertThat(VisionSidecarClient.createHttpClient().version())
                .isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
    }

    @Test
    void parsesEmbeddingResponse() throws Exception {
        var json = mapper.readTree("{\"embedding\":[0.5,-0.25,0.0],\"faces\":1,\"ignoredField\":true}");
        float[] embedding = VisionSidecarClient.parseEmbedding(json);
        assertThat(embedding).containsExactly(0.5f, -0.25f, 0.0f);
    }

    @Test
    void embeddingResponseWithoutFaceThrows() throws Exception {
        var json = mapper.readTree("{\"embedding\":null,\"faces\":0}");
        org.junit.jupiter.api.Assertions.assertThrows(VisionException.class,
                () -> VisionSidecarClient.parseEmbedding(json));
    }

    @Test
    void parsesStatusResponse() throws Exception {
        var json = mapper.readTree(
                "{\"loggedIn\":true,\"cameraFound\":true,\"cameraName\":\"Haustuer\",\"lastPollAt\":\"2026-07-22T10:00:00Z\",\"extra\":1}");
        VisionSidecarClient.SidecarStatus status = VisionSidecarClient.parseStatus(json);
        assertThat(status.loggedIn()).isTrue();
        assertThat(status.cameraFound()).isTrue();
        assertThat(status.cameraName()).isEqualTo("Haustuer");
    }

    @Test
    void parsesStatusResponseWithMissingCameraNameAsNull() throws Exception {
        var json = mapper.readTree("{\"loggedIn\":false,\"cameraFound\":false}");
        VisionSidecarClient.SidecarStatus status = VisionSidecarClient.parseStatus(json);
        assertThat(status.cameraName()).isNull();
        assertThat(status.lastPollAt()).isNull();
    }

    @Test
    void buildsPersonsPayload() throws Exception {
        var payload = VisionSidecarClient.buildPersonsPayload(mapper, List.of(
                new VisionSidecarClient.SidecarPerson(1L, "Benedikt", List.of(new float[]{0.1f, 0.2f}))));
        assertThat(payload.get(0).get("personId").asLong()).isEqualTo(1L);
        assertThat(payload.get(0).get("name").asText()).isEqualTo("Benedikt");
        assertThat(payload.get(0).get("embeddings").get(0).get(1).floatValue()).isEqualTo(0.2f);
    }
}
