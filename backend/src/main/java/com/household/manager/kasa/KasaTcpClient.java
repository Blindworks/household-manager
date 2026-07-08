package com.household.manager.kasa;

import com.household.manager.kasa.exception.KasaCommunicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

@Component
@RequiredArgsConstructor
@Slf4j
public class KasaTcpClient {

    private static final int KASA_PORT = 9999;
    private static final int LENGTH_HEADER_SIZE = 4;

    private final KasaCrypto kasaCrypto;

    @Value("${kasa.tcp.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${kasa.tcp.read-timeout-ms:2000}")
    private int readTimeoutMs;

    @Value("${kasa.tcp.max-attempts:3}")
    private int maxAttempts;

    @Value("${kasa.tcp.retry-backoff-ms:250}")
    private long retryBackoffMs;

    public String send(String ip, String payload) {
        byte[] requestPacket = kasaCrypto.encode(payload);
        IOException lastError = null;

        // Kasa plugs accept only a single TCP connection on port 9999 at a time, so a
        // transient timeout or a briefly busy device would otherwise be misread as "offline".
        // A few quick retries make the reachability check reliable.
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return exchange(ip, requestPacket);
            } catch (IOException ex) {
                lastError = ex;
                log.debug("Kasa communication attempt {}/{} with {} failed: {}",
                        attempt, maxAttempts, ip, ex.getMessage());
                if (attempt < maxAttempts) {
                    backoffBeforeRetry(ip);
                }
            }
        }

        throw new KasaCommunicationException(
                "Failed to communicate with Kasa device at IP " + ip + " after " + maxAttempts + " attempts",
                lastError);
    }

    private String exchange(String ip, byte[] requestPacket) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, KASA_PORT), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            try (OutputStream out = socket.getOutputStream();
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                out.write(requestPacket);
                out.flush();

                byte[] lengthHeader = new byte[LENGTH_HEADER_SIZE];
                in.readFully(lengthHeader);
                int payloadLength = ByteBuffer.wrap(lengthHeader).getInt();
                if (payloadLength < 0) {
                    throw new KasaCommunicationException("Invalid negative payload length from device " + ip);
                }

                byte[] encryptedPayload = new byte[payloadLength];
                in.readFully(encryptedPayload);

                ByteBuffer responsePacket = ByteBuffer.allocate(LENGTH_HEADER_SIZE + payloadLength);
                responsePacket.put(lengthHeader);
                responsePacket.put(encryptedPayload);
                return kasaCrypto.decode(responsePacket.array());
            }
        }
    }

    private void backoffBeforeRetry(String ip) {
        if (retryBackoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KasaCommunicationException("Interrupted while retrying Kasa communication with " + ip, interrupted);
        }
    }
}
