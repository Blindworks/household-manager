package com.household.manager.sun;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Die vier Sonnenereignisse, die Flows kennen. Einzige Definition der
 * Schluesselwoerter {@code dawn}/{@code sunrise}/{@code sunset}/{@code dusk} —
 * Trigger-Node und Ausdrucks-Parser fragen dieses Enum.
 *
 * <p>{@code dawn}/{@code dusk} sind Beginn bzw. Ende der <b>buergerlichen</b>
 * Daemmerung (Sonne 6 Grad unter dem Horizont).
 */
public enum SunEvent {
    DAWN("dawn"),
    SUNRISE("sunrise"),
    SUNSET("sunset"),
    DUSK("dusk");

    /** Groesster erlaubter Versatz in Minuten (beide Richtungen) — schuetzt vor Tippfehlern wie -3000. */
    public static final int MAX_OFFSET_MINUTES = 240;

    private final String key;

    SunEvent(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<SunEvent> fromKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(e -> e.key.equals(normalized)).findFirst();
    }

    /** Schluesselwoerter in Deklarationsreihenfolge (fuer den Node-Katalog). */
    public static List<String> keys() {
        return Arrays.stream(values()).map(SunEvent::key).toList();
    }
}
