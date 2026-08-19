package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduziert den am 2026-08-19 in PROD gemessenen Ausfallmodus: die Geraete
 * (P110/L900/L530) schliessen eine Keep-Alive-Verbindung idle-seitig nach ~1-2
 * Minuten, waehrend die Session hier 300 s im Cache liegt. Ein Request in diesem
 * Fenster liest EOF vom toten Socket - und darf das Geraet nicht als offline
 * werten, sondern muss genau einmal mit frischem Handshake wiederholen (dasselbe
 * Muster wie bei HTTP 403 / error_code -1301).
 */
class TapoKlapStaleSessionTest {

    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "geheim";

    @Test
    @DisplayName("Vom Geraet geschlossene Keep-Alive-Verbindung wird mit frischem Handshake wiederholt")
    void reauthenticatesWhenDeviceClosedIdleConnection() throws Exception {
        try (FakeKlapDevice device = new FakeKlapDevice(USERNAME, PASSWORD)) {
            TapoKlapDeviceConnection connection = new TapoKlapDeviceConnection(
                    null, new ObjectMapper(), USERNAME, PASSWORD, "127.0.0.1",
                    new int[]{device.port()});

            JsonNode first = connection.getDeviceInfo();
            assertEquals("L530", first.path("model").asText(), "erster Abruf ueber frische Session");
            assertEquals(1, device.handshakeCount(), "erster Abruf braucht genau einen Handshake");

            // Das Geraet schliesst die Verbindung idle-seitig (real: nach ~1-2 Minuten,
            // waehrend die Client-Session-TTL von 300 s noch laeuft).
            device.dropCurrentConnection();

            JsonNode second = connection.getDeviceInfo();
            assertEquals("L530", second.path("model").asText(),
                    "Abruf nach geraeteseitigem Verbindungsabbau muss ueber einen neuen Handshake gelingen");
            assertEquals(2, device.handshakeCount(), "zweiter Abruf muss neu authentifizieren");
        }
    }

    @Test
    @DisplayName("EOF statt Antwort (Geraet schliesst nach dem Request) wird ebenfalls einmal wiederholt")
    void retriesWhenDeviceClosesConnectionWithoutResponse() throws Exception {
        try (FakeKlapDevice device = new FakeKlapDevice(USERNAME, PASSWORD)) {
            TapoKlapDeviceConnection connection = new TapoKlapDeviceConnection(
                    null, new ObjectMapper(), USERNAME, PASSWORD, "127.0.0.1",
                    new int[]{device.port()});

            connection.getDeviceInfo();
            assertEquals(1, device.handshakeCount());

            // Das Geraet nimmt den naechsten Request noch an, schliesst dann aber die
            // Verbindung ohne Antwort - der Client liest ein sauberes EOF.
            device.dropInsteadOfAnsweringNextRequest();

            JsonNode result = connection.getDeviceInfo();
            assertEquals("L530", result.path("model").asText(),
                    "EOF auf der wiederverwendeten Verbindung muss einen frischen Handshake ausloesen");
            assertEquals(2, device.handshakeCount());
        }
    }

    /**
     * Minimales echtes KLAP-Geraet auf einem ephemeren Port: HTTP/1.1 ueber einen
     * rohen Socket, handshake1/handshake2/request mit derselben Schluesselableitung
     * wie der Client ({@link TapoKlapDeviceConnection#deriveSessionKeys}).
     */
    private static final class FakeKlapDevice implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] userHash;
        private final Thread acceptThread;
        private final AtomicInteger handshakes = new AtomicInteger();
        private volatile Socket currentClient;
        private volatile boolean running = true;
        private volatile boolean dropInsteadOfAnswering = false;

        private byte[] sessionKey;
        private byte[] sessionIvPrefix;
        private byte[] sessionSignatureSeed;

        FakeKlapDevice(String username, String password) throws IOException {
            this.userHash = TapoCipher.sha256(concat(
                    TapoCipher.sha1(username.getBytes(StandardCharsets.UTF_8)),
                    TapoCipher.sha1(password.getBytes(StandardCharsets.UTF_8))));
            this.serverSocket = new ServerSocket(0);
            this.acceptThread = new Thread(this::acceptLoop, "fake-klap-device");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int handshakeCount() {
            return handshakes.get();
        }

        /** Der naechste /app/request wird gelesen, aber statt einer Antwort wird die Verbindung geschlossen. */
        void dropInsteadOfAnsweringNextRequest() {
            dropInsteadOfAnswering = true;
        }

        /** Simuliert den geraeteseitigen Idle-Close der Keep-Alive-Verbindung. */
        void dropCurrentConnection() throws IOException, InterruptedException {
            Socket client = currentClient;
            if (client != null) {
                client.close();
            }
            // Dem Client-TCP-Stack Zeit geben, das FIN zu verarbeiten, damit der
            // naechste Request deterministisch auf EOF laeuft.
            Thread.sleep(100);
        }

        @Override
        public void close() throws IOException {
            running = false;
            serverSocket.close();
            Socket client = currentClient;
            if (client != null) {
                client.close();
            }
        }

        private void acceptLoop() {
            while (running) {
                try (Socket client = serverSocket.accept()) {
                    currentClient = client;
                    serveConnection(client);
                } catch (Exception ex) {
                    // Verbindungsabbrueche (auch dropCurrentConnection) sind Teil des Tests.
                }
            }
        }

        private void serveConnection(Socket client) throws IOException {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (!client.isClosed()) {
                HttpRequest request = readRequest(in);
                if (request == null) {
                    return; // Client hat die Verbindung beendet
                }
                if (request.path.startsWith("/app/handshake1")) {
                    handleHandshake1(request, out);
                } else if (request.path.startsWith("/app/handshake2")) {
                    writeResponse(out, 200, null, new byte[0]);
                } else if (request.path.startsWith("/app/request")) {
                    handleRequest(request, out);
                } else {
                    writeResponse(out, 404, null, new byte[0]);
                }
            }
        }

        private void handleHandshake1(HttpRequest request, OutputStream out) throws IOException {
            handshakes.incrementAndGet();
            byte[] localSeed = request.body;
            byte[] remoteSeed = new byte[16];
            new SecureRandom().nextBytes(remoteSeed);

            TapoKlapDeviceConnection.KlapSessionKeys keys =
                    TapoKlapDeviceConnection.deriveSessionKeys(localSeed, remoteSeed, userHash);
            sessionKey = keys.key();
            sessionIvPrefix = keys.ivPrefix();
            sessionSignatureSeed = keys.signatureSeed();

            byte[] serverHash = TapoCipher.sha256(concat(localSeed, remoteSeed, userHash));
            writeResponse(out, 200, "TP_SESSIONID=FAKESESSION;TIMEOUT=86400", concat(remoteSeed, serverHash));
        }

        private void handleRequest(HttpRequest request, OutputStream out) throws IOException {
            if (dropInsteadOfAnswering) {
                dropInsteadOfAnswering = false;
                currentClient.close();
                return;
            }
            int seq = Integer.parseInt(request.path.substring(request.path.indexOf("seq=") + 4));
            byte[] iv = concat(sessionIvPrefix, ByteBuffer.allocate(4).putInt(seq).array());
            // Anfrage entschluesseln (nur um das Protokoll ernst zu nehmen; Inhalt egal)
            TapoCipher.aesCbcDecrypt(sessionKey, iv, Arrays.copyOfRange(request.body, 32, request.body.length));

            byte[] cipherText = TapoCipher.aesCbcEncrypt(sessionKey, iv,
                    "{\"error_code\":0,\"result\":{\"device_id\":\"FAKE\",\"model\":\"L530\",\"device_on\":false}}"
                            .getBytes(StandardCharsets.UTF_8));
            byte[] signature = TapoCipher.sha256(concat(
                    sessionSignatureSeed, ByteBuffer.allocate(4).putInt(seq).array(), cipherText));
            writeResponse(out, 200, null, concat(signature, cipherText));
        }

        private HttpRequest readRequest(InputStream in) throws IOException {
            String requestLine = readLine(in);
            if (requestLine.isEmpty()) {
                return null;
            }
            String path = requestLine.split(" ")[1];
            int contentLength = 0;
            String headerLine;
            while (!(headerLine = readLine(in)).isEmpty()) {
                String lower = headerLine.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.substring(headerLine.indexOf(':') + 1).trim());
                }
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n == -1) {
                    return null;
                }
                read += n;
            }
            return new HttpRequest(path, body);
        }

        private String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    line.write(b);
                }
            }
            return line.toString(StandardCharsets.US_ASCII);
        }

        private void writeResponse(OutputStream out, int status, String setCookie, byte[] body) throws IOException {
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 ").append(status).append(status == 200 ? " OK" : " Error").append("\r\n");
            headers.append("Content-Length: ").append(body.length).append("\r\n");
            if (setCookie != null) {
                headers.append("Set-Cookie: ").append(setCookie).append("\r\n");
            }
            headers.append("Connection: keep-alive\r\n\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        private record HttpRequest(String path, byte[] body) {
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
    }
}
