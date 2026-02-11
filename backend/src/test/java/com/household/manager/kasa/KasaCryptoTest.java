package com.household.manager.kasa;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KasaCryptoTest {

    private final KasaCrypto kasaCrypto = new KasaCrypto();

    @Test
    void encryptPayload_shouldUseTpLinkXorAlgorithm() {
        byte[] plain = new byte[]{0x01, 0x02, 0x03};
        byte[] expectedEncrypted = new byte[]{(byte) 0xAA, (byte) 0xA8, (byte) 0xAB};

        byte[] actualEncrypted = kasaCrypto.encryptPayload(plain);

        assertArrayEquals(expectedEncrypted, actualEncrypted);
    }

    @Test
    void decryptPayload_shouldUseTpLinkXorAlgorithm() {
        byte[] encrypted = new byte[]{(byte) 0xAA, (byte) 0xA8, (byte) 0xAB};
        byte[] expectedPlain = new byte[]{0x01, 0x02, 0x03};

        byte[] actualPlain = kasaCrypto.decryptPayload(encrypted);

        assertArrayEquals(expectedPlain, actualPlain);
    }

    @Test
    void encode_shouldPrefixPayloadLengthHeaderBigEndian() {
        byte[] packet = kasaCrypto.encode("abc");

        assertEquals(7, packet.length);
        assertArrayEquals(new byte[]{0x00, 0x00, 0x00, 0x03}, Arrays.copyOfRange(packet, 0, 4));
        assertEquals("abc", kasaCrypto.decode(packet));
    }
}
