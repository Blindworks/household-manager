package com.household.manager.kasa;

import com.household.manager.kasa.exception.KasaCommunicationException;
import lombok.RequiredArgsConstructor;
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
public class KasaTcpClient {

    private static final int KASA_PORT = 9999;
    private static final int LENGTH_HEADER_SIZE = 4;

    private final KasaCrypto kasaCrypto;

    @Value("${kasa.tcp.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${kasa.tcp.read-timeout-ms:2000}")
    private int readTimeoutMs;

    public String send(String ip, String payload) {
        byte[] requestPacket = kasaCrypto.encode(payload);
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
        } catch (IOException ex) {
            throw new KasaCommunicationException("Failed to communicate with Kasa device at IP " + ip, ex);
        }
    }
}
