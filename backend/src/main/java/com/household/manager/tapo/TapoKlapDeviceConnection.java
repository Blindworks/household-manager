package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TapoKlapDeviceConnection implements TapoLocalDeviceConnection {

    private static final Logger log = LoggerFactory.getLogger(TapoKlapDeviceConnection.class);

    private static final int SOCKET_TIMEOUT_MS = 5_000;
    private static final int SESSION_TTL_SECONDS = 300;

    /** Ports to try in order: plain HTTP first, then HTTPS as fallback for newer firmware. */
    private static final int[] PORTS_TO_TRY = {80, 443};

    private final ObjectMapper objectMapper;
    private final String username;
    private final String password;
    private final String host;
    /** URL path prefix, e.g. {@code /app} */
    private final String appPath = "/app";

    private String sessionCookie;
    private byte[] key;
    private byte[] ivPrefix;
    private byte[] signatureSeed;
    private AtomicInteger sequence;
    private Instant authenticatedAt;
    private KlapSocketSession activeSession;
    /** Port that returned 403 on a KLAP /request; excluded from next handshake attempt. -1 = none. */
    private int requestDeniedPort = -1;

    public TapoKlapDeviceConnection(
            HttpClient ignoredHttpClient,
            ObjectMapper objectMapper,
            String username,
            String password,
            String ipAddress
    ) {
        this.objectMapper = objectMapper;
        this.username = username;
        this.password = password;
        this.host = ipAddress;
    }

    // -------------------------------------------------------------------------
    // TapoLocalDeviceConnection interface
    // -------------------------------------------------------------------------

    @Override
    public JsonNode getDeviceInfo() {
        return executeRequest(buildRequest("get_device_info", null));
    }

    @Override
    public void setDevicePowered(boolean poweredOn) {
        executeRequest(buildRequest("set_device_info",
                objectMapper.createObjectNode().put("device_on", poweredOn)));
    }

    @Override
    public JsonNode getEnergyUsage() {
        return executeRequest(buildRequest("get_energy_usage", null));
    }

    @Override
    public JsonNode getCurrentPower() {
        return executeRequest(buildRequest("get_current_power", null));
    }

    /** Builds a KLAP request node including requestTimeMils as required by newer firmware. */
    private ObjectNode buildRequest(String method, ObjectNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        req.put("requestTimeMils", System.currentTimeMillis());
        return req;
    }

    // -------------------------------------------------------------------------
    // Request execution
    // -------------------------------------------------------------------------

    private JsonNode executeRequest(JsonNode requestData) {
        return executeRequestInternal(requestData, true);
    }

    /**
     * Serialized per connection: concurrent requests (e.g. info + energy fired together
     * by the UI) would otherwise interleave reads/writes on the shared socket and both
     * time out.
     */
    private synchronized JsonNode executeRequestInternal(JsonNode requestData, boolean retryOnAuthError) {
        ensureAuthenticated();
        int seq = sequence.incrementAndGet();
        log.debug("KLAP /request seq={} ({})", seq, seq < 0 ? "negativ!" : "positiv");
        byte[] payload = encrypt(requestData.toString(), seq);

        try {
            HttpRawResponse response = activeSession.post(
                    appPath + "/request?seq=" + seq,
                    payload,
                    "application/octet-stream",
                    sessionCookie
            );

            if (response.statusCode == 403) {
                log.debug("KLAP /request 403 - Body ({} Bytes): {}",
                        response.body.length, new String(response.body, StandardCharsets.UTF_8));
                if (retryOnAuthError) {
                    // Exclude the current port from the next handshake so we fall through to port 443.
                    requestDeniedPort = activeSession.getPort();
                    log.debug("KLAP /request 403 auf port={} – naechster Versuch schlaegt diesen Port aus", requestDeniedPort);
                    invalidateSession();
                    return executeRequestInternal(requestData, false);
                }
            }

            if (response.statusCode < 200 || response.statusCode >= 300) {
                throw new TapoException("Tapo KLAP-Request fehlgeschlagen mit HTTP " + response.statusCode);
            }

            JsonNode parsed = objectMapper.readTree(decrypt(response.body, seq));

            int errorCode = parsed.path("error_code").asInt(0);
            if (errorCode == -1301 && retryOnAuthError) {
                invalidateSession();
                return executeRequestInternal(requestData, false);
            }

            validateResponse(parsed, "Tapo KLAP-Geraet");
            return parsed.path("result");

        } catch (IOException ex) {
            throw new TapoException("Tapo KLAP-Kommunikation fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    private synchronized void invalidateSession() {
        authenticatedAt = null;
        sessionCookie = null;
        key = null;
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }
        // Note: requestDeniedPort is intentionally NOT cleared here –
        // it must survive the invalidation so ensureAuthenticated can skip the bad port.
    }

    /**
     * Build candidate auth hashes. Newer Tapo firmware (1.4+) may require
     * a different credential hash format. We try all known variants.
     */
    private List<byte[]> buildAuthHashCandidates() {
        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

        // V1: SHA256(SHA1(user) + SHA1(pass)) — original KLAP v2
        byte[] v1 = TapoCipher.sha256(concat(
                TapoCipher.sha1(usernameBytes),
                TapoCipher.sha1(passwordBytes)
        ));

        // V2: SHA256(SHA1(SHA256(user)) + SHA1(SHA256(pass))) — newer firmware
        byte[] v2 = TapoCipher.sha256(concat(
                TapoCipher.sha1(TapoCipher.sha256(usernameBytes)),
                TapoCipher.sha1(TapoCipher.sha256(passwordBytes))
        ));

        return List.of(v1, v2);
    }

    private synchronized void ensureAuthenticated() {
        if (authenticatedAt != null
                && Instant.now().isBefore(authenticatedAt.plusSeconds(SESSION_TTL_SECONDS))
                && activeSession != null
                && !activeSession.isClosed()) {
            return;
        }

        List<byte[]> authHashCandidates = buildAuthHashCandidates();
        TapoException lastException = null;

        // Try each port (80 HTTP, 443 HTTPS) with each credential candidate.
        // Skip any port that is known to deny /request (e.g. port 80 on newer firmware).
        for (int port : PORTS_TO_TRY) {
            if (port == requestDeniedPort) {
                log.debug("KLAP ueberspringe port={} (request wurde dort mit 403 abgelehnt)", port);
                continue;
            }
            boolean secure = (port == 443);
            for (int i = 0; i < authHashCandidates.size(); i++) {
                try {
                    performHandshake(authHashCandidates.get(i), port, secure);
                    log.debug("KLAP Handshake erfolgreich: port={}, Credential-Kandidat {}",
                            port, i == 0 ? "v1" : "v2");
                    requestDeniedPort = -1; // Reset on successful handshake on a new port
                    return;
                } catch (TapoException ex) {
                    log.debug("KLAP Handshake fehlgeschlagen (port={}, Kandidat {}): {}",
                            port, i == 0 ? "v1" : "v2", ex.getMessage());
                    lastException = ex;
                }
            }
        }

        throw lastException != null ? lastException
                : new TapoException("Tapo KLAP-Authentifizierung mit allen Ports und Credential-Formaten fehlgeschlagen.");
    }

    private void performHandshake(byte[] userHash, int port, boolean secure) {
        // Close any stale session before opening a new one.
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }

        byte[] localSeed = new byte[16];
        new SecureRandom().nextBytes(localSeed);

        KlapSocketSession session = new KlapSocketSession(host, port, SOCKET_TIMEOUT_MS, secure);
        try {
            // --- Handshake 1 ---
            HttpRawResponse response1 = session.post(
                    appPath + "/handshake1",
                    localSeed,
                    "application/octet-stream",
                    null
            );

            if (response1.statusCode == 403) {
                throw new TapoException("Tapo KLAP-Handshake1 HTTP 403 (Zugriff verweigert)");
            }
            if (response1.statusCode < 200 || response1.statusCode >= 300) {
                throw new TapoException("Tapo KLAP-Handshake1 fehlgeschlagen mit HTTP " + response1.statusCode);
            }

            log.debug("KLAP Handshake1 Set-Cookie Headers: {}", response1.setCookieValues);
            sessionCookie = TapoSessionCookie.extract(response1.setCookieValues);
            log.debug("KLAP verwendetes Session-Cookie: {}", sessionCookie);

            byte[] body1 = response1.body;
            if (body1.length < 48) {
                throw new TapoException(
                        "Tapo KLAP-Handshake1 lieferte ungueltige Daten (Laenge: " + body1.length + ").");
            }

            byte[] remoteSeed = Arrays.copyOfRange(body1, 0, 16);
            byte[] serverHash = Arrays.copyOfRange(body1, 16, 48);
            byte[] localHash = TapoCipher.sha256(concat(localSeed, remoteSeed, userHash));
            log.debug("KLAP Handshake1: body={} Bytes, remoteSeed={} Bytes, serverHash={} Bytes",
                    body1.length, remoteSeed.length, serverHash.length);

            if (!Arrays.equals(localHash, serverHash)) {
                log.debug("KLAP Hash-Verifikation fehlgeschlagen fuer diesen Kandidaten");
                session.close();
                throw new TapoException(
                        "Tapo KLAP-Handshake-Pruefung fehlgeschlagen (Credential-Format passt nicht).");
            }
            log.debug("KLAP Hash-Verifikation erfolgreich");

            // --- Handshake 2 (same socket) ---
            byte[] handshake2Payload = TapoCipher.sha256(concat(remoteSeed, localSeed, userHash));
            HttpRawResponse response2 = session.post(
                    appPath + "/handshake2",
                    handshake2Payload,
                    "application/octet-stream",
                    sessionCookie
            );

            log.debug("KLAP Handshake2: HTTP {}, Connection-Header: {}, Body ({} Bytes): {}",
                    response2.statusCode,
                    response2.connectionHeader != null ? response2.connectionHeader : "(nicht gesetzt)",
                    response2.body.length,
                    new String(response2.body, StandardCharsets.UTF_8));

            if (response2.statusCode < 200 || response2.statusCode >= 300) {
                session.close();
                throw new TapoException("Tapo KLAP-Handshake2 fehlgeschlagen mit HTTP " + response2.statusCode);
            }

            // --- Derive session keys ---
            KlapSessionKeys sessionKeys = deriveSessionKeys(localSeed, remoteSeed, userHash);
            key = sessionKeys.key();
            ivPrefix = sessionKeys.ivPrefix();
            sequence = new AtomicInteger(sessionKeys.initialSequence());
            signatureSeed = sessionKeys.signatureSeed();
            authenticatedAt = Instant.now();

            // Handshake succeeded — keep this session open for subsequent requests.
            activeSession = session;

        } catch (IOException ex) {
            session.close();
            throw new TapoException("Tapo KLAP-Authentifizierung fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Crypto helpers
    // -------------------------------------------------------------------------

    record KlapSessionKeys(byte[] key, byte[] ivPrefix, int initialSequence, byte[] signatureSeed) {
    }

    /**
     * KLAP derives every session component from "lsk"/"iv"/"ldk" plus the RAW
     * concatenation of localSeed, remoteSeed and userHash — hashing the
     * concatenation first yields keys the device rejects with 403 on /request.
     */
    static KlapSessionKeys deriveSessionKeys(byte[] localSeed, byte[] remoteSeed, byte[] userHash) {
        byte[] sessionSeed = concat(localSeed, remoteSeed, userHash);
        byte[] key = Arrays.copyOf(
                TapoCipher.sha256(concat("lsk".getBytes(StandardCharsets.UTF_8), sessionSeed)), 16);
        byte[] ivDigest = TapoCipher.sha256(
                concat("iv".getBytes(StandardCharsets.UTF_8), sessionSeed));
        byte[] ivPrefix = Arrays.copyOf(ivDigest, 12);
        int initialSequence = ByteBuffer.wrap(ivDigest, ivDigest.length - 4, 4).getInt();
        byte[] signatureSeed = Arrays.copyOf(
                TapoCipher.sha256(concat("ldk".getBytes(StandardCharsets.UTF_8), sessionSeed)), 28);
        return new KlapSessionKeys(key, ivPrefix, initialSequence, signatureSeed);
    }

    private byte[] encrypt(String requestData, int seq) {
        byte[] cipherText = TapoCipher.aesCbcEncrypt(
                key, ivForSequence(seq), requestData.getBytes(StandardCharsets.UTF_8));
        byte[] signature = TapoCipher.sha256(
                concat(signatureSeed, ByteBuffer.allocate(4).putInt(seq).array(), cipherText));
        return concat(signature, cipherText);
    }

    private String decrypt(byte[] payload, int seq) {
        if (payload.length < 33) {
            throw new TapoException("Tapo KLAP-Antwort ist zu kurz (" + payload.length + " Bytes).");
        }
        byte[] cipherText = Arrays.copyOfRange(payload, 32, payload.length);
        return new String(TapoCipher.aesCbcDecrypt(key, ivForSequence(seq), cipherText), StandardCharsets.UTF_8);
    }

    private byte[] ivForSequence(int seq) {
        return concat(ivPrefix, ByteBuffer.allocate(4).putInt(seq).array());
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static void validateResponse(JsonNode response, String source) {
        int errorCode = response.path("error_code").asInt(0);
        if (errorCode == 0) {
            return;
        }
        throw new TapoException(source + "-Fehler " + errorCode + ": "
                + response.path("msg").asText(response.path("result").path("msg").asText("Unbekannter Fehler")));
    }

    // =========================================================================
    // Inner class: raw HTTP/1.1 over a persistent TCP socket
    // =========================================================================

    /**
     * Holds a value object carrying the parsed parts of one HTTP/1.1 response
     * that callers need to inspect.
     */
    private static final class HttpRawResponse {
        final int statusCode;
        final List<String> setCookieValues;
        final String connectionHeader;
        final byte[] body;

        HttpRawResponse(int statusCode, List<String> setCookieValues, String connectionHeader, byte[] body) {
            this.statusCode = statusCode;
            this.setCookieValues = setCookieValues;
            this.connectionHeader = connectionHeader;
            this.body = body;
        }
    }

    /**
     * A single persistent TCP connection to the Tapo device that speaks raw HTTP/1.1.
     * All three KLAP steps (handshake1, handshake2, request) must travel over the
     * same socket because firmware 1.4.0 ties the session to the TCP connection.
     *
     * <p>The caller is responsible for calling {@link #close()} when the session is
     * no longer needed or after any unrecoverable error.</p>
     */
    private static final class KlapSocketSession {

        private static final Logger log = LoggerFactory.getLogger(KlapSocketSession.class);

        private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };

        private final String host;
        private final int port;
        private final int timeoutMs;
        private final boolean secure;
        private Socket socket;
        private OutputStream out;
        private InputStream in;
        private boolean closed = false;

        KlapSocketSession(String host, int port, int timeoutMs, boolean secure) {
            this.host = host;
            this.port = port;
            this.timeoutMs = timeoutMs;
            this.secure = secure;
        }

        int getPort() {
            return port;
        }

        boolean isClosed() {
            return closed || socket == null || socket.isClosed();
        }

        void close() {
            closed = true;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ex) {
                    log.debug("KLAP Socket konnte nicht geschlossen werden: {}", ex.getMessage());
                }
            }
        }

        /**
         * Sends an HTTP/1.1 POST request and returns the parsed response.
         * Opens the socket on the first call; subsequent calls reuse it.
         *
         * @param path        request path including query string, e.g. {@code /app/handshake1}
         * @param body        raw request body bytes
         * @param contentType value for the {@code Content-Type} header
         * @param cookie      optional {@code Cookie} header value; {@code null} means omit the header
         */
        HttpRawResponse post(String path, byte[] body, String contentType, String cookie) throws IOException {
            ensureConnected();
            writeRequest(path, body, contentType, cookie);
            return readResponse();
        }

        private void ensureConnected() throws IOException {
            if (socket != null && !socket.isClosed()) {
                return;
            }
            log.debug("KLAP oeffnet {} Verbindung zu {}:{}", secure ? "HTTPS" : "HTTP", host, port);
            if (secure) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, TRUST_ALL, new SecureRandom());
                    SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
                    ssl.setSoTimeout(timeoutMs);
                    ssl.setTcpNoDelay(true);
                    ssl.startHandshake();
                    socket = ssl;
                } catch (Exception ex) {
                    throw new IOException("TLS-Verbindung zu " + host + ":" + port + " fehlgeschlagen: " + ex.getMessage(), ex);
                }
            } else {
                socket = new Socket(host, port);
                socket.setSoTimeout(timeoutMs);
                socket.setTcpNoDelay(true);
            }
            out = socket.getOutputStream();
            in = socket.getInputStream();
        }

        private void writeRequest(String path, byte[] body, String contentType, String cookie) throws IOException {
            StringBuilder headers = new StringBuilder();
            headers.append("POST ").append(path).append(" HTTP/1.1\r\n");
            headers.append("Host: ").append(host).append("\r\n");
            headers.append("Connection: keep-alive\r\n");
            headers.append("Content-Type: ").append(contentType).append("\r\n");
            headers.append("Content-Length: ").append(body.length).append("\r\n");
            if (cookie != null) {
                headers.append("Cookie: ").append(cookie).append("\r\n");
            }
            headers.append("\r\n");

            out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        private HttpRawResponse readResponse() throws IOException {
            // --- Status line ---
            String statusLine = readLine();
            log.debug("KLAP HTTP Status: {}", statusLine);
            int statusCode = parseStatusCode(statusLine);

            // --- Headers ---
            List<String> setCookieValues = new ArrayList<>();
            String connectionHeader = null;
            int contentLength = -1;
            boolean chunked = false;

            String headerLine;
            while (!(headerLine = readLine()).isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = headerLine.substring(0, colon).trim().toLowerCase();
                String value = headerLine.substring(colon + 1).trim();

                switch (name) {
                    case "set-cookie" -> setCookieValues.add(value);
                    case "content-length" -> {
                        try {
                            contentLength = Integer.parseInt(value.trim());
                        } catch (NumberFormatException ignored) {
                            // Leave contentLength = -1; chunked check will handle it.
                        }
                    }
                    case "transfer-encoding" -> {
                        if (value.toLowerCase().contains("chunked")) {
                            chunked = true;
                        }
                    }
                    case "connection" -> connectionHeader = value;
                    default -> { /* intentionally ignored */ }
                }
            }

            // --- Body ---
            byte[] bodyBytes = chunked ? readChunkedBody() : readFixedBody(contentLength);

            return new HttpRawResponse(statusCode, setCookieValues, connectionHeader, bodyBytes);
        }

        /**
         * Reads a CRLF-terminated line from the socket input stream without buffering
         * across calls, so that subsequent binary body reads work correctly.
         */
        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') {
                    int next = in.read();
                    if (next == '\n') {
                        break;
                    }
                    // Bare CR — unlikely in HTTP/1.1, but handle gracefully.
                    sb.append((char) b);
                    if (next != -1) {
                        sb.append((char) next);
                    }
                } else if (b == '\n') {
                    // Bare LF — tolerate.
                    break;
                } else {
                    sb.append((char) b);
                }
            }
            return sb.toString();
        }

        private int parseStatusCode(String statusLine) {
            // "HTTP/1.1 200 OK"
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) {
                throw new TapoException("Ungueltige HTTP-Statuszeile vom Geraet: " + statusLine);
            }
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) {
                throw new TapoException("HTTP-Statuscode nicht lesbar: " + statusLine);
            }
        }

        private byte[] readFixedBody(int contentLength) throws IOException {
            if (contentLength <= 0) {
                return new byte[0];
            }
            byte[] buffer = new byte[contentLength];
            int bytesRead = 0;
            while (bytesRead < contentLength) {
                int n = in.read(buffer, bytesRead, contentLength - bytesRead);
                if (n == -1) {
                    throw new IOException("Verbindung unterbrochen nach " + bytesRead
                            + " von " + contentLength + " Bytes.");
                }
                bytesRead += n;
            }
            return buffer;
        }

        private byte[] readChunkedBody() throws IOException {
            List<byte[]> chunks = new ArrayList<>();
            int totalLength = 0;

            while (true) {
                String sizeLine = readLine().trim();
                // Strip any chunk extensions (e.g. "1a;ext=val" → "1a")
                int semicolon = sizeLine.indexOf(';');
                if (semicolon >= 0) {
                    sizeLine = sizeLine.substring(0, semicolon).trim();
                }
                int chunkSize;
                try {
                    chunkSize = Integer.parseInt(sizeLine, 16);
                } catch (NumberFormatException ex) {
                    throw new IOException("Ungueltige Chunk-Groesse: " + sizeLine);
                }
                if (chunkSize == 0) {
                    // Trailing CRLF after last chunk
                    readLine();
                    break;
                }
                byte[] chunk = new byte[chunkSize];
                int bytesRead = 0;
                while (bytesRead < chunkSize) {
                    int n = in.read(chunk, bytesRead, chunkSize - bytesRead);
                    if (n == -1) {
                        throw new IOException("Verbindung unterbrochen innerhalb eines Chunks.");
                    }
                    bytesRead += n;
                }
                // Each chunk is followed by CRLF
                readLine();
                chunks.add(chunk);
                totalLength += chunkSize;
            }

            byte[] result = new byte[totalLength];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        }
    }
}
