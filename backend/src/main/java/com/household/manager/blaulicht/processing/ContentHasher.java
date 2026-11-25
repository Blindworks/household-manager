package com.household.manager.blaulicht.processing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Inhalts-Fingerabdruck eines Falls: SHA-256 über Überschrift, Text und Ort, normalisiert (Kleinschreibung,
 * nur Buchstaben und Ziffern). Fängt dieselbe Meldung, die unter neuer GUID erneut erscheint.
 */
public final class ContentHasher {

    private ContentHasher() {
    }

    public static String hash(String headline, String body, String locality) {
        String normalized = normalize(headline) + "|" + normalize(body) + "|" + normalize(locality);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.GERMAN)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
