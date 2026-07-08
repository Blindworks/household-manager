package com.household.manager.entitystate;

import java.util.Locale;

/**
 * Bildet stabile Entity-IDs nach dem Schema
 * {@code <domain>.<source>_<slug(ref)>[_<suffix>]}.
 * IDs werden maschinell aus stabilen Referenzen erzeugt, nie aus änderbaren Anzeigenamen.
 */
public final class EntityIds {

    private EntityIds() {
    }

    public static String build(EntityDomain domain, EntitySource source, String sourceRef, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append(domain.idPrefix())
                .append('.')
                .append(slug(source.name()))
                .append('_')
                .append(slug(sourceRef));
        if (suffix != null && !suffix.isBlank()) {
            sb.append('_').append(slug(suffix));
        }
        return sb.toString();
    }

    /**
     * Normalisiert beliebigen Text zu einem ID-Segment: Kleinbuchstaben,
     * Umlaute transliteriert, alles andere zu '_' (ohne Doppel-/Randunterstriche).
     */
    public static String slug(String input) {
        String lower = input.toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss");
        String replaced = lower.replaceAll("[^a-z0-9]+", "_");
        return replaced.replaceAll("^_+|_+$", "");
    }
}
