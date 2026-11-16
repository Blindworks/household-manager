package com.household.manager.grocery.matching;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;

/** Stabiler Fingerabdruck eines Angebotstexts; die Kette gehört bewusst nicht dazu. */
public final class OfferFingerprint {

    private OfferFingerprint() {
    }

    public static String of(String title, String brand, String description, String packaging) {
        String text = Stream.of(title, brand, description, packaging)
                .map(OfferFingerprint::normalize)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
