package com.household.manager.calendar;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Erzeugt den stabilen Schluessel einer Kalender-Kategorie aus ihrem Namen.
 *
 * <p>Der Schluessel wird ausschliesslich beim Anlegen vergeben und danach nie wieder
 * berechnet: Flows filtern ueber den State von {@code event.calendar_reminder} darauf,
 * ein Umbenennen darf sie nicht ins Leere laufen lassen.
 */
@Component
public class CalendarCategoryKeyGenerator {

    private static final int MAX_LENGTH = 50;
    private static final String FALLBACK = "kategorie";

    /**
     * @param name  der Anzeigename
     * @param taken bereits vergebene Schluessel; bei Kollision wird "_2", "_3", ... angehaengt
     */
    public String generate(String name, Set<String> taken) {
        String base = normalize(name);
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = truncate(base, MAX_LENGTH - ("_" + suffix).length()) + "_" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Nur die deutschen Sonderzeichen (Umlaute, scharfes S) werden transliteriert, alles
     * andere jenseits davon (z. B. "Café", "Façade") wird bewusst zum Trennzeichen -
     * die Haushaltsanwendung ist deutschsprachig, eine vollstaendige Transliterationstabelle
     * waere hier ueber das Ziel hinaus.
     */
    private String normalize(String name) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? FALLBACK : truncate(slug, MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
