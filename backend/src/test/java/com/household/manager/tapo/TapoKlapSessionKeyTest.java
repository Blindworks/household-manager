package com.household.manager.tapo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KLAP session-key derivation against reference vectors computed from the
 * protocol definition (python-kasa / plugp100): each component hashes
 * "lsk"/"iv"/"ldk" + localSeed + remoteSeed + userHash — the RAW concatenation,
 * not an intermediate hash of it. A wrong derivation lets handshake1/2 succeed
 * but every /request is rejected with HTTP 403.
 */
class TapoKlapSessionKeyTest {

    private static final byte[] LOCAL_SEED = range(0, 16);
    private static final byte[] REMOTE_SEED = range(16, 32);
    private static final byte[] USER_HASH =
            HexFormat.of().parseHex("0d1f2ab59877a4ac7b7d4e6a24734ea3b0d3fb27a746dc68089be6ac4e863bac");

    @Test
    @DisplayName("Session-Schluessel entsprechen den KLAP-Referenzvektoren")
    void derivesSessionKeysPerKlapReference() {
        TapoKlapDeviceConnection.KlapSessionKeys keys =
                TapoKlapDeviceConnection.deriveSessionKeys(LOCAL_SEED, REMOTE_SEED, USER_HASH);

        assertArrayEquals(HexFormat.of().parseHex("d2ade3fe48e08fc63bde543c53e7ade7"),
                keys.key(), "AES-Key");
        assertArrayEquals(HexFormat.of().parseHex("81cfc259785fcb5133fab5cf"),
                keys.ivPrefix(), "IV-Prefix");
        assertEquals(-55129822, keys.initialSequence(), "initiale Sequenznummer");
        assertArrayEquals(HexFormat.of().parseHex("a61568513d4d417fb74c9599c6512f29e9acd2957ff2d49f56737ba9"),
                keys.signatureSeed(), "Signatur-Seed");
    }

    @Test
    @DisplayName("Requests auf einer Verbindung sind serialisiert (gemeinsamer Socket)")
    void requestExecutionIsSerializedPerConnection() throws Exception {
        var method = TapoKlapDeviceConnection.class
                .getDeclaredMethod("executeRequestInternal", com.fasterxml.jackson.databind.JsonNode.class, boolean.class);
        assertEquals(true, java.lang.reflect.Modifier.isSynchronized(method.getModifiers()),
                "Parallele Requests (z.B. info + energy) teilen sich den TCP-Socket und muessen serialisiert werden");
    }

    private static byte[] range(int from, int to) {
        byte[] bytes = new byte[to - from];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (from + i);
        }
        return bytes;
    }
}
