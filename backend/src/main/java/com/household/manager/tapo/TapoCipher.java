package com.household.manager.tapo;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

final class TapoCipher {

    private TapoCipher() {
    }

    static byte[] sha1(byte[] value) {
        return digest("SHA-1", value);
    }

    static byte[] sha256(byte[] value) {
        return digest("SHA-256", value);
    }

    static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] plainText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(plainText);
        } catch (GeneralSecurityException ex) {
            throw new TapoException("Tapo AES-Verschluesselung fehlgeschlagen.", ex);
        }
    }

    static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] cipherText) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(cipherText);
        } catch (GeneralSecurityException ex) {
            throw new TapoException("Tapo AES-Entschluesselung fehlgeschlagen.", ex);
        }
    }

    static String sha1DigestUsername(String username) {
        byte[] hash = sha1(username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (GeneralSecurityException ex) {
            throw new TapoException("Tapo-Hashberechnung fehlgeschlagen.", ex);
        }
    }
}
