package com.household.manager.agent.tools;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/** Gemeinsame Eingabe-Auswertung der Notiz-Tools, damit die drei nicht auseinanderlaufen. */
final class NoteInputs {

    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 8760;

    private NoteInputs() {
    }

    /** Fehlt {@code expiresInHours}, ist die Notiz unbefristet (null). */
    static LocalDateTime expiresAt(Map<String, Object> input, Clock clock) {
        Object raw = input.get("expiresInHours");
        if (raw == null) {
            return null;
        }
        long hours = asLong(raw, "expiresInHours");
        if (hours < MIN_HOURS || hours > MAX_HOURS) {
            throw new IllegalArgumentException("expiresInHours muss zwischen " + MIN_HOURS + " und " + MAX_HOURS + " liegen.");
        }
        return LocalDateTime.now(clock).plusHours(hours);
    }

    static long id(Map<String, Object> input) {
        return asLong(input.get("id"), "id");
    }

    /** Pflicht-Ganzzahl eines beliebigen Feldes; auch von den Haus-Tools genutzt. */
    static long integer(Map<String, Object> input, String field) {
        return asLong(input.get(field), field);
    }

    static String text(Map<String, Object> input) {
        Object raw = input.get("text");
        if (raw == null) {
            throw new IllegalArgumentException("text fehlt.");
        }
        return raw.toString();
    }

    private static long asLong(Object raw, String field) {
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        throw new IllegalArgumentException(field + " muss eine ganze Zahl sein.");
    }
}
