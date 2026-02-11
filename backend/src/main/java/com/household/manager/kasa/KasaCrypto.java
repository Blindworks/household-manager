package com.household.manager.kasa;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Implements TP-Link Kasa XOR transport encoding/decoding.
 */
@Component
public class KasaCrypto {

    private static final int INITIAL_KEY = 171;
    private static final int LENGTH_HEADER_SIZE = 4;

    public byte[] encode(String payload) {
        byte[] plainBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedPayload = encryptPayload(plainBytes);

        ByteBuffer buffer = ByteBuffer.allocate(LENGTH_HEADER_SIZE + encryptedPayload.length);
        buffer.putInt(encryptedPayload.length);
        buffer.put(encryptedPayload);
        return buffer.array();
    }

    public String decode(byte[] responsePacket) {
        if (responsePacket.length < LENGTH_HEADER_SIZE) {
            throw new IllegalArgumentException("Kasa response packet must contain a 4-byte length header");
        }

        byte[] encryptedPayload = Arrays.copyOfRange(responsePacket, LENGTH_HEADER_SIZE, responsePacket.length);
        byte[] plainBytes = decryptPayload(encryptedPayload);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    public byte[] encryptPayload(byte[] plainBytes) {
        byte[] encrypted = new byte[plainBytes.length];
        int key = INITIAL_KEY;
        for (int i = 0; i < plainBytes.length; i++) {
            int cipherByte = (plainBytes[i] & 0xFF) ^ key;
            encrypted[i] = (byte) cipherByte;
            key = cipherByte;
        }
        return encrypted;
    }

    public byte[] decryptPayload(byte[] encryptedBytes) {
        byte[] plain = new byte[encryptedBytes.length];
        int key = INITIAL_KEY;
        for (int i = 0; i < encryptedBytes.length; i++) {
            int cipherByte = encryptedBytes[i] & 0xFF;
            plain[i] = (byte) (cipherByte ^ key);
            key = cipherByte;
        }
        return plain;
    }
}
