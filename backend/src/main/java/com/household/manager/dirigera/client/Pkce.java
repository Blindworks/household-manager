package com.household.manager.dirigera.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE-Paar (RFC 7636, Methode S256) für die Kopplung mit dem Hub. */
public record Pkce(String verifier, String challenge) {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final int VERIFIER_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static Pkce generate() {
        StringBuilder verifier = new StringBuilder(VERIFIER_LENGTH);
        for (int i = 0; i < VERIFIER_LENGTH; i++) {
            verifier.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String v = verifier.toString();
        return new Pkce(v, challengeOf(v));
    }

    public static String challengeOf(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfuegbar", e);
        }
    }
}
