package com.household.manager.dirigera.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dirigera.DirigeraException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * HTTPS-Zugriff auf die lokale API des Dirigera-Hubs. Jeder Aufruf mit Token läuft über einen
 * Client, der genau dem beim Koppeln gemerkten Zertifikat vertraut ({@link PinnedTrustManager});
 * ein anderes Gerät unter der Hub-Adresse scheitert im Handshake, bevor das Token gesendet wird.
 */
@Slf4j
@Component
public class DirigeraHttpClient {

    static final int HUB_PORT = 8443;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String CLIENT_NAME = "household-manager";

    private final int port;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Je Fingerabdruck ein Client, damit nicht jeder Poll einen neuen HttpClient baut. */
    private final Map<String, HttpClient> pinnedClients = new ConcurrentHashMap<>();
    private volatile TokenResponse lastTokenResponse;

    /** Letzte Antwort des Token-Endpunkts, für die Fehlermeldung beim Koppeln. */
    public record TokenResponse(int status, String body) {
    }

    public DirigeraHttpClient() {
        this(HUB_PORT);
    }

    DirigeraHttpClient(int port) {
        this.port = port;
    }

    /**
     * Holt den Fingerabdruck des Hub-Zertifikats. Einzige Stelle, an der blind vertraut wird
     * (Trust on first use beim Koppeln); es wird dabei nichts gesendet außer dem Handshake.
     */
    public String fetchFingerprint(String host) {
        CapturingTrustManager capturing = new CapturingTrustManager();
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {capturing}, null);
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
                raw.setSoTimeout((int) REQUEST_TIMEOUT.toMillis());
                try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, false)) {
                    ssl.startHandshake();
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new DirigeraException("Hub unter " + host + " nicht erreichbar: " + e.getMessage(), e);
        }
        if (capturing.leaf == null) {
            throw new DirigeraException("Hub unter " + host + " hat kein Zertifikat geliefert");
        }
        return PinnedTrustManager.fingerprint(capturing.leaf);
    }

    public String authorize(String host, String certSha256, String challenge) {
        URI uri = uri(host, "/v1/oauth/authorize?audience=homesmart.local&response_type=code"
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256");
        HttpResponse<String> response = send(certSha256, HttpRequest.newBuilder(uri).GET());
        checkStatus(response);
        return field(response.body(), "code")
                .orElseThrow(() -> new DirigeraException("Hub-Antwort ohne Code"));
    }

    /** Liefert das Token erst, nachdem der Knopf am Hub gedrückt wurde; vorher leer. */
    public Optional<String> requestToken(String host, String certSha256, String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("name", CLIENT_NAME);
        form.put("grant_type", "authorization_code");
        form.put("code_verifier", verifier);
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(host, "/v1/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = send(certSha256, request);
        lastTokenResponse = new TokenResponse(response.statusCode(), response.body());
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        return field(response.body(), "access_token");
    }

    public Optional<TokenResponse> lastTokenResponse() {
        return Optional.ofNullable(lastTokenResponse);
    }

    /** Rohes JSON-Array aller Geräte des Hubs. */
    public String getDevices(String host, String certSha256, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(host, "/v1/devices"))
                .header("Authorization", "Bearer " + token)
                .GET();
        HttpResponse<String> response = send(certSha256, request);
        checkStatus(response);
        return response.body();
    }

    private HttpResponse<String> send(String certSha256, HttpRequest.Builder request) {
        HttpClient client = pinnedClient(certSha256);
        try {
            return client.send(request.timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (hasCause(e, CertificateException.class)) {
                throw new DirigeraCertificateMismatchException(
                        "Zertifikat des Hubs hat sich geaendert, neu koppeln", e);
            }
            throw new DirigeraException("Hub nicht erreichbar: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirigeraException("Anfrage an den Hub unterbrochen", e);
        }
    }

    private HttpClient pinnedClient(String certSha256) {
        String key = PinnedTrustManager.normalize(certSha256);
        return pinnedClients.computeIfAbsent(key, this::buildPinnedClient);
    }

    private HttpClient buildPinnedClient(String certSha256) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new PinnedTrustManager(certSha256)}, null);
            // Kein sslParameters(...setEndpointIdentificationAlgorithm(null)): der HttpClient
            // überschreibt das mit "HTTPS". Die Hostnamenprüfung entfällt, weil der
            // PinnedTrustManager ein X509ExtendedTrustManager ist (siehe dort).
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .sslContext(ctx)
                    .build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS-Kontext nicht erzeugbar", e);
        }
    }

    private static void checkStatus(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401) {
            throw new DirigeraUnauthorizedException("Hub hat das Token abgelehnt");
        }
        if (status >= 400) {
            throw new DirigeraException("Hub antwortet mit HTTP " + status);
        }
    }

    private Optional<String> field(String json, String name) {
        try {
            JsonNode value = objectMapper.readTree(json).get(name);
            return value != null && value.isTextual() && !value.asText().isEmpty()
                    ? Optional.of(value.asText())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private URI uri(String host, String pathAndQuery) {
        return URI.create("https://" + host + ":" + port + pathAndQuery);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** Akzeptiert jedes Zertifikat und merkt sich das Leaf — nur für {@link #fetchFingerprint}. */
    private static final class CapturingTrustManager extends X509ExtendedTrustManager {

        private volatile X509Certificate leaf;

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            if (chain != null && chain.length > 0) {
                leaf = chain[0];
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Keine Client-Zertifikate");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
