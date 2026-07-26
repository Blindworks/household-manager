package com.household.manager.tractive;

/**
 * Urteil des {@link TractiveHomeResolver}: ist das Tier zu Hause, und woraus folgt das?
 *
 * <p>{@code basis} und {@code stale} sind kein Beiwerk – sie machen im Entity-Viewer und
 * im Flow-Debug nachvollziehbar, warum die Entitaet {@code on} sagt, obwohl es gar keinen
 * frischen GPS-Fix gibt.
 */
public record HomeVerdict(
        boolean atHome,
        Basis basis,
        boolean stale,
        Double distanceMeters,
        Long positionAgeMinutes
) {

    /** Woraus das Urteil folgt. */
    public enum Basis {
        /** Der Tracker laedt – eindeutig zu Hause, unabhaengig von der Position. */
        CHARGING,
        /** Abstand des letzten Positionsberichts zum Home-Punkt. */
        POSITION,
        /** Geschlossen aus: Bericht still + gesunder Akku + Heimnaehe. */
        POWERED_OFF
    }

    public static HomeVerdict charging() {
        return new HomeVerdict(true, Basis.CHARGING, false, null, null);
    }

    public static HomeVerdict fromPosition(boolean atHome, boolean stale,
                                           double distanceMeters, Long positionAgeMinutes) {
        return new HomeVerdict(atHome, Basis.POSITION, stale, distanceMeters, positionAgeMinutes);
    }

    public static HomeVerdict poweredOff(double distanceMeters, Long positionAgeMinutes) {
        return new HomeVerdict(true, Basis.POWERED_OFF, true, distanceMeters, positionAgeMinutes);
    }
}
