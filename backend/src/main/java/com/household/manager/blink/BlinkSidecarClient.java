package com.household.manager.blink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.vision.VisionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-Client fuer die Kamera-Endpunkte des blink-vision-Sidecars.
 * Derselbe Sidecar wie bei der Gesichtserkennung (VisionProperties.sidecarBaseUrl);
 * 409 vom Sidecar heisst "nicht bei Blink angemeldet" und wird als
 * IllegalStateException (-> 400) gemeldet, nie als 401.
 */
@Service
@Slf4j
public class BlinkSidecarClient {

    /** Clips koennen einige MB gross sein und kommen ueber die Blink-Cloud. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final VisionProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public BlinkSidecarClient(VisionProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = createHttpClient();
    }

    /**
     * Der Client MUSS auf HTTP/1.1 festgelegt sein: Der Default HTTP_2 versucht bei
     * http://-URLs ein h2c-Upgrade und schickt die Anfrage dabei OHNE Body. uvicorn
     * lehnt das Upgrade ab, verarbeitet die Anfrage aber trotzdem — und sieht einen
     * leeren Body. Als eigene Methode statt Inline-Code im Konstruktor, damit ein
     * Regressionstest die Pinnung direkt pruefen kann (Muster VisionSidecarClient,
     * wo genau dieser Fehler real passiert ist und Stunden gekostet hat).
     */
    static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Kamera laut Sidecar (cameraId ist die stabile Blink-Hardware-Id). */
    public record SidecarCamera(String cameraId, String name, String type, boolean armed,
                                String battery, String syncName, boolean syncArmed) {}

    /** Clip-Metadaten aus dem Local-Storage-Manifest. */
    public record SidecarClip(String clipId, String createdAt, Long sizeBytes) {}

    /**
     * @param force true erzwingt einen frischen Abruf beim Sidecar (Query-Parameter
     *              {@code ?force=true}). Noetig, weil blinkpys {@code async_arm()} den
     *              lokalen Zustand nicht selbst aktualisiert und ein ungezwungener
     *              {@code refresh()} in eine 30-s-Drossel des Sidecars laeuft — ohne
     *              den Parameter saehe ein Nachpoll nach dem Scharf-/Unscharf-Schalten
     *              weiterhin den alten Zustand. Deshalb NICHT entfernen, auch wenn er
     *              wie ein No-op aussieht: der spaetere Poller ruft ausschliesslich
     *              {@code listCameras(true)} direkt nach jeder Zustandsaenderung auf,
     *              die Fachschicht sonst {@code listCameras(false)}.
     */
    public List<SidecarCamera> listCameras(boolean force) {
        return parseCameras(getJson(camerasPath(force)));
    }

    /** Bequemlichkeits-Ueberladung fuer den Normalfall (kein erzwungener Refresh). */
    public List<SidecarCamera> listCameras() {
        return listCameras(false);
    }

    public void setCameraArmed(String cameraId, boolean armed) {
        postJson("/cameras/" + encode(cameraId) + (armed ? "/arm" : "/disarm"));
    }

    public void setSyncArmed(String syncName, boolean armed) {
        postJson("/system/" + encode(syncName) + (armed ? "/arm" : "/disarm"));
    }

    public byte[] snapshot(String cameraId) {
        return sendBytes("POST", "/cameras/" + encode(cameraId) + "/snapshot");
    }

    public byte[] thumbnail(String cameraId) {
        return sendBytes("GET", "/cameras/" + encode(cameraId) + "/thumbnail");
    }

    public List<SidecarClip> listClips(String cameraId) {
        return parseClips(getJson("/cameras/" + encode(cameraId) + "/clips"));
    }

    public byte[] clip(String cameraId, String clipId) {
        return sendBytes("GET", "/cameras/" + encode(cameraId) + "/clips/" + encode(clipId));
    }

    /** Pfad fuer den Kamera-Abruf; eigene, testbare Methode statt Inline-String-Bau in listCameras. */
    static String camerasPath(boolean force) {
        return force ? "/cameras?force=true" : "/cameras";
    }

    // ==================== Parsing (testbar) ====================

    static List<SidecarCamera> parseCameras(JsonNode root) {
        List<SidecarCamera> cameras = new ArrayList<>();
        for (JsonNode node : root) {
            cameras.add(new SidecarCamera(
                    node.path("cameraId").asText(),
                    node.path("name").asText(),
                    node.path("type").asText(""),
                    node.path("armed").asBoolean(false),
                    node.path("battery").asText(null),
                    node.path("syncName").asText(),
                    node.path("syncArmed").asBoolean(false)));
        }
        return cameras;
    }

    static List<SidecarClip> parseClips(JsonNode root) {
        List<SidecarClip> clips = new ArrayList<>();
        for (JsonNode node : root) {
            clips.add(new SidecarClip(
                    node.path("clipId").asText(),
                    node.path("createdAt").asText(null),
                    node.path("sizeBytes").isNumber() ? node.path("sizeBytes").asLong() : null));
        }
        return clips;
    }

    // ==================== HTTP ====================

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode getJson(String path) {
        byte[] body = sendBytes("GET", path);
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new BlinkException("Unlesbare Antwort des blink-vision-Sidecars: " + path, ex);
        }
    }

    private void postJson(String path) {
        sendBytes("POST", path);
    }

    private byte[] sendBytes(String method, String path) {
        String url = properties.getSidecarBaseUrl() + path;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response =
                    httpClient.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 409) {
                // Wird vom bestehenden IllegalStateException-Handler zu 400 -
                // NIE 401, sonst wirft der Auth-Interceptor aus der Haushalts-Session.
                throw new IllegalStateException("Nicht bei Blink angemeldet - "
                        + "Anmeldung auf der Seite Gesichtserkennung nachholen.");
            }
            if (response.statusCode() == 404) {
                throw new IllegalArgumentException("Kamera oder Clip nicht gefunden.");
            }
            if (response.statusCode() / 100 != 2) {
                throw new BlinkException("blink-vision " + path + " HTTP " + response.statusCode()
                        + ": " + extractError(response.body()));
            }
            return response.body();
        } catch (IllegalStateException | IllegalArgumentException | BlinkException ex) {
            throw ex;
        } catch (java.net.ConnectException ex) {
            throw new BlinkException(
                    "blink-vision-Sidecar ist nicht erreichbar (" + url + "). Laeuft der Dienst?", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BlinkException("Blink-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new BlinkException("Blink-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }

    private String extractError(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            JsonNode node = mapper.readTree(text);
            if (node.path("detail").has("error")) {
                return node.path("detail").path("error").asText();
            }
            return node.path("error").asText(text);
        } catch (Exception ex) {
            return text;
        }
    }
}
