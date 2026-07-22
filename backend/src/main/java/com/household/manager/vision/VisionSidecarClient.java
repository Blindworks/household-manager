package com.household.manager.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * HTTP-Client fuer den blink-vision-Sidecar. Der Sidecar kapselt den kompletten
 * Blink-/InsightFace-Teil; dieses Backend ruft ihn nur ueber eine schlanke HTTP-API auf.
 */
@Service
@Slf4j
public class VisionSidecarClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final VisionProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public VisionSidecarClient(VisionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = createHttpClient();
    }

    /**
     * Der Client MUSS auf HTTP/1.1 festgelegt sein: Der Default HTTP_2 versucht bei
     * http://-URLs ein h2c-Upgrade und schickt die Anfrage dabei OHNE Body. uvicorn
     * lehnt das Upgrade ab, verarbeitet die Anfrage aber trotzdem — und sieht einen
     * leeren Body (HTTP 422 "Field required"). Der Alexa-Sidecar ist nicht betroffen,
     * weil Nodes HTTP-Server den Upgrade-Header ignoriert und den Body normal liest.
     */
    static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Login-/Kamerastatus laut Sidecar. */
    public record SidecarStatus(boolean loggedIn, boolean cameraFound, String cameraName, String lastPollAt) {}

    /** Eine Person mit ihren Referenz-Embeddings (Payload fuer den Sidecar). */
    public record SidecarPerson(Long personId, String name, List<float[]> embeddings) {}

    public SidecarStatus getStatus() {
        return parseStatus(get("/status"));
    }

    /** Startet den Blink-Login (E-Mail/Passwort); danach folgt i. d. R. ein 2FA-Verify. */
    public void login(String username, String password) {
        ObjectNode body = mapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        post("/auth/login", body);
    }

    /** Schliesst den Login mit der 2FA-PIN ab. */
    public void verify(String code) {
        ObjectNode body = mapper.createObjectNode();
        body.put("code", code);
        post("/auth/verify", body);
    }

    /** Berechnet das Embedding fuer ein Referenzfoto (JPEG/PNG-Bytes). */
    public float[] computeEmbedding(byte[] photo) {
        ObjectNode body = mapper.createObjectNode();
        body.put("imageBase64", Base64.getEncoder().encodeToString(photo));
        return parseEmbedding(post("/embeddings", body));
    }

    /** Pusht die vollstaendige Personenliste (Embeddings) an den Sidecar. */
    public void pushPersons(List<SidecarPerson> persons) {
        put("/persons", buildPersonsPayload(mapper, persons));
    }

    // ==================== Parsing (testbar) ====================

    static SidecarStatus parseStatus(JsonNode root) {
        return new SidecarStatus(
                root.path("loggedIn").asBoolean(false),
                root.path("cameraFound").asBoolean(false),
                root.path("cameraName").asText(null),
                root.path("lastPollAt").asText(null));
    }

    static float[] parseEmbedding(JsonNode root) {
        JsonNode embedding = root.path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new VisionException("Auf dem Foto wurde kein Gesicht erkannt.");
        }
        float[] values = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            values[i] = (float) embedding.get(i).asDouble();
        }
        return values;
    }

    static ArrayNode buildPersonsPayload(ObjectMapper mapper, List<SidecarPerson> persons) {
        ArrayNode payload = mapper.createArrayNode();
        for (SidecarPerson person : persons) {
            ObjectNode node = payload.addObject();
            node.put("personId", person.personId());
            node.put("name", person.name());
            ArrayNode embeddings = node.putArray("embeddings");
            for (float[] embedding : person.embeddings()) {
                ArrayNode values = embeddings.addArray();
                for (float value : embedding) {
                    values.add(value);
                }
            }
        }
        return payload;
    }

    // ==================== HTTP ====================

    private JsonNode get(String path) {
        return send("GET", path, null);
    }

    private JsonNode post(String path, JsonNode body) {
        return send("POST", path, body);
    }

    private JsonNode put(String path, JsonNode body) {
        return send("PUT", path, body);
    }

    private JsonNode send(String method, String path, JsonNode body) {
        String url = properties.getSidecarBaseUrl() + path;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json");
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                body == null ? "{}" : mapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new VisionException("blink-vision " + path + " HTTP " + response.statusCode()
                        + ": " + extractError(response.body()));
            }
            if (response.body() == null || response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            return mapper.readTree(response.body());
        } catch (VisionException ex) {
            throw ex;
        } catch (java.net.ConnectException ex) {
            throw new VisionException("blink-vision-Sidecar ist nicht erreichbar (" + url + "). Laeuft der Dienst?", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VisionException("blink-vision-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new VisionException("blink-vision-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }

    private String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return mapper.readTree(body).path("error").asText(body);
        } catch (Exception ex) {
            return body;
        }
    }
}
