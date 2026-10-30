package com.household.manager.dirigera.client;

import com.household.manager.dirigera.DirigeraException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lokaler HTTPS-Server mit selbstsigniertem Zertifikat (CN=dirigera-test) auf 127.0.0.1 —
 * Hostname und Zertifikat passen also absichtlich nicht zusammen, wie beim echten Hub.
 */
class DirigeraHttpClientTest {

    private static final String HOST = "127.0.0.1";
    private static final String WRONG =
            "63:26:C5:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00";

    private HttpsServer server;
    private DirigeraHttpClient client;
    private final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();
    private volatile int tokenStatus = 403;
    private volatile String tokenBody = "{\"error\":\"button not pressed\"}";
    private volatile String lastTokenRequestBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext()));
        server.createContext("/v1/devices", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            receivedAuthHeaders.add(String.valueOf(auth));
            if ("Bearer good".equals(auth)) {
                respond(ex, 200, "[{\"id\":\"x\"}]");
            } else {
                respond(ex, 401, "unauthorized");
            }
        });
        server.createContext("/v1/oauth/authorize", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            boolean ok = query.contains("audience=homesmart.local") && query.contains("response_type=code")
                    && query.contains("code_challenge=chal") && query.contains("code_challenge_method=S256");
            respond(ex, ok ? 200 : 400, ok ? "{\"code\":\"the-code\"}" : "{}");
        });
        server.createContext("/v1/oauth/token", ex -> {
            lastTokenRequestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, tokenStatus, tokenBody);
        });
        server.start();
        client = new DirigeraHttpClient(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchFingerprintLiefertDenFingerabdruckDesServers() {
        assertThat(client.fetchFingerprint(HOST)).isEqualTo(PinnedTrustManagerTest.EXPECTED);
    }

    @Test
    void getDevicesMitPassendemFingerabdruckTrotzAbweichendemHostnamen() {
        assertThat(client.getDevices(HOST, PinnedTrustManagerTest.EXPECTED, "good")).isEqualTo("[{\"id\":\"x\"}]");
    }

    @Test
    void falscherFingerabdruckBrichtVorDemSendenDesTokensAb() {
        assertThatThrownBy(() -> client.getDevices(HOST, WRONG, "good"))
                .isInstanceOf(DirigeraCertificateMismatchException.class);
        assertThat(receivedAuthHeaders).isEmpty();
    }

    @Test
    void status401WirdZuUnauthorized() {
        assertThatThrownBy(() -> client.getDevices(HOST, PinnedTrustManagerTest.EXPECTED, "revoked"))
                .isInstanceOf(DirigeraUnauthorizedException.class);
    }

    @Test
    void authorizeLiefertCode() {
        assertThat(client.authorize(HOST, PinnedTrustManagerTest.EXPECTED, "chal")).isEqualTo("the-code");
    }

    @Test
    void requestTokenOhneKnopfdruckIstLeerUndMerktAntwort() {
        assertThat(client.requestToken(HOST, PinnedTrustManagerTest.EXPECTED, "the-code", "verifier")).isEmpty();

        assertThat(client.lastTokenResponse()).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.body()).contains("button not pressed");
        });
        assertThat(lastTokenRequestBody)
                .contains("code=the-code", "name=household-manager",
                        "grant_type=authorization_code", "code_verifier=verifier");
    }

    @Test
    void requestTokenNachKnopfdruckLiefertToken() {
        tokenStatus = 200;
        tokenBody = "{\"access_token\":\"tok\"}";

        assertThat(client.requestToken(HOST, PinnedTrustManagerTest.EXPECTED, "the-code", "verifier")).contains("tok");
    }

    @Test
    void nichtErreichbarerHostWirdZuDirigeraException() {
        server.stop(0);

        assertThatThrownBy(() -> client.getDevices(HOST, PinnedTrustManagerTest.EXPECTED, "good"))
                .isInstanceOf(DirigeraException.class)
                .isNotInstanceOf(DirigeraCertificateMismatchException.class);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static SSLContext serverSslContext() throws Exception {
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = DirigeraHttpClientTest.class.getResourceAsStream("/dirigera/test-hub.p12")) {
            keyStore.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }
}
