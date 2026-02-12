package com.household.manager.tapo.protocol;

import lombok.Getter;

/**
 * Represents an active KLAP session with a Tapo device.
 * Contains encryption keys and session state.
 */
@Getter
public class KlapSession {
    private final String deviceIp;
    private final String sessionCookie;
    private final byte[] key;           // AES encryption key (16 bytes)
    private final byte[] baseIv;        // Base IV (12 bytes), sequence will be appended
    private final byte[] signatureKey;  // Signature key for request signing (28 bytes)
    private int sequence;               // Request sequence number (increments with each request)

    public KlapSession(String deviceIp, String sessionCookie, byte[] key, byte[] baseIv, byte[] signatureKey, int sequence) {
        this.deviceIp = deviceIp;
        this.sessionCookie = sessionCookie;
        this.key = key;
        this.baseIv = baseIv;
        this.signatureKey = signatureKey;
        this.sequence = sequence;
    }

    /**
     * Increment the sequence number for the next request.
     */
    public void incrementSequence() {
        this.sequence++;
    }
}
