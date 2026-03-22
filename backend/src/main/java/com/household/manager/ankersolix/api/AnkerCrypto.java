package com.household.manager.ankersolix.api;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

public class AnkerCrypto {

    /**
     * Anker server EC public key (SECP256R1 / NIST P-256), uncompressed point.
     * Source: thomluther/anker-solix-api (Python reference)
     */
    private static final String SERVER_PUBLIC_KEY_HEX =
            "04c5c00c4f8d1197cc7c3167c52bf7acb054d722f0ef08dcd7e0883236e0d72a3868d9750cb47fa4619248f3d83f0f662671dadc6e2d31c2f41db0161651c7c076";

    private final KeyPair clientKeyPair;
    private final byte[]  sharedKey;

    public AnkerCrypto() {
        try {
            ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256r1");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(ecSpec, new SecureRandom());
            this.clientKeyPair = kpg.generateKeyPair();

            PublicKey serverPublicKey = decodeUncompressedEcPoint(hexToBytes(SERVER_PUBLIC_KEY_HEX));

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(clientKeyPair.getPrivate());
            ka.doPhase(serverPublicKey, true);
            this.sharedKey = ka.generateSecret();
        } catch (Exception e) {
            throw new AnkerApiException("ECDH key exchange initialisation failed.", e);
        }
    }

    /**
     * AES-256-CBC encrypt the password.
     * Key = sharedKey (32 bytes), IV = sharedKey[0..15].
     */
    public String encryptPassword(String plainPassword) {
        try {
            SecretKeySpec aesKey = new SecretKeySpec(sharedKey, "AES");
            IvParameterSpec iv   = new IvParameterSpec(sharedKey, 0, 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, iv);
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new AnkerApiException("AES encryption failed.", e);
        }
    }

    /**
     * Returns the client's EC public key as an uncompressed-point hex string (04‖X‖Y).
     */
    public String getClientPublicKeyHex() {
        ECPublicKey ecPub = (ECPublicKey) clientKeyPair.getPublic();
        ECPoint point = ecPub.getW();
        byte[] x = toBytes32(point.getAffineX());
        byte[] y = toBytes32(point.getAffineY());
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04;
        System.arraycopy(x, 0, uncompressed, 1, 32);
        System.arraycopy(y, 0, uncompressed, 33, 32);
        return bytesToHex(uncompressed);
    }

    /** MD5 hex digest (lowercase). */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new AnkerApiException("MD5 computation failed.", e);
        }
    }

    /** Unix epoch milliseconds as string – used as 'transaction' value. */
    public static String generateTransaction() {
        return String.valueOf(System.currentTimeMillis());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static PublicKey decodeUncompressedEcPoint(byte[] uncompressed) throws Exception {
        if (uncompressed[0] != 0x04 || uncompressed.length != 65) {
            throw new IllegalArgumentException("Expected 65-byte uncompressed EC point");
        }
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 33, 65));

        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);

        ECPoint point = new ECPoint(x, y);
        ECPublicKeySpec keySpec = new ECPublicKeySpec(point, ecSpec);
        return KeyFactory.getInstance("EC").generatePublic(keySpec);
    }

    private static byte[] toBytes32(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) return raw;
        if (raw.length == 33 && raw[0] == 0) {
            byte[] trimmed = new byte[32];
            System.arraycopy(raw, 1, trimmed, 0, 32);
            return trimmed;
        }
        if (raw.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
            return padded;
        }
        throw new IllegalArgumentException("BigInteger too large for 32 bytes: " + raw.length);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
