package com.household.manager.blesensor;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MAC-Normalform des Moduls: kleingeschrieben, Doppelpunkte. Toleriert Grossbuchstaben,
 * Bindestriche und fehlende Trenner, damit Tablet und Admin auf dieselbe Zeile treffen.
 */
public final class BleMacs {

    private static final Pattern HEX12 = Pattern.compile("^[0-9a-f]{12}$");

    private BleMacs() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("MAC fehlt.");
        }
        String hex = raw.trim().toLowerCase(Locale.ROOT).replace(":", "").replace("-", "");
        if (!HEX12.matcher(hex).matches()) {
            throw new IllegalArgumentException("Ungueltige MAC-Adresse: '" + raw + "'");
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    /** Referenz fuer Entity-Ids: die Normalform ohne Doppelpunkte. */
    public static String entityRef(String normalizedMac) {
        return normalizedMac.replace(":", "");
    }
}
