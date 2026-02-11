package com.household.manager.tapo.protocol;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TapoCipherTest {

    private final TapoCipher tapoCipher = new TapoCipher();

    @Test
    void encryptDecrypt_shouldRoundtripAesPayload() {
        byte[] key = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] iv = "abcdef1234567890".getBytes(StandardCharsets.UTF_8);
        String plaintext = "{\"method\":\"get_device_info\"}";

        String encrypted = tapoCipher.encrypt(key, iv, plaintext);
        String decrypted = tapoCipher.decrypt(key, iv, encrypted);

        assertEquals(plaintext, decrypted);
    }

    @Test
    void decryptWithPrivateKey_shouldDecryptHandshakePayload() throws Exception {
        KeyPair keyPair = tapoCipher.generateKeyPair();
        byte[] payload = "0123456789abcdefFEDCBA9876543210".getBytes(StandardCharsets.UTF_8);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
        String encryptedBase64 = Base64.getEncoder().encodeToString(cipher.doFinal(payload));

        byte[] decrypted = tapoCipher.decryptWithPrivateKey(keyPair.getPrivate(), encryptedBase64);

        assertArrayEquals(payload, decrypted);
    }
}
