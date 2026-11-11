package com.household.manager.laundry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Reine Entscheidungslogik: schiebt einen offenen Waschgang um einen Takt weiter.
 * Keine Speicherung, keine Seiteneffekte ausser am uebergebenen Waschgang.
 */
public final class LaundrySessionStepper {

    private LaundrySessionStepper() {
    }

    /**
     * @param climate leer, wenn der Kellerfuehler nichts Frisches liefert — dann wird pausiert, nicht gezaehlt
     * @param requiredLoad gelernter Bedarf K (kPa*h)
     */
    public static void advance(LaundryDryingSession session, Optional<KellerClimate> climate,
                               LocalDateTime now, double requiredLoad) {
        switch (session.getStatus()) {
            case ASKING -> expireUnansweredQuestion(session, now);
            case DRYING, MAYBE_DRY -> advanceDrying(session, climate, now, requiredLoad);
            default -> { /* abgeschlossene Waschgaenge bleiben stehen */ }
        }
    }

    /** Verschiebt einen Zeitpunkt in der Ruhezeit auf deren Ende (07:00). */
    public static LocalDateTime outsideQuietHours(LocalDateTime time) {
        if (!LaundryRules.QUIET_HOURS.contains(time.toLocalTime())) {
            return time;
        }
        LocalTime end = LaundryRules.QUIET_HOURS.to();
        return time.toLocalTime().isBefore(end)
                ? time.toLocalDate().atTime(end)
                : time.toLocalDate().plusDays(1).atTime(end);
    }

    private static void expireUnansweredQuestion(LaundryDryingSession session, LocalDateTime now) {
        if (!now.isBefore(session.getAskedAt().plus(LaundryRules.ASK_EXPIRY))) {
            session.setStatus(LaundrySessionStatus.EXPIRED);
            session.setEndedAt(now);
        }
    }

    private static void advanceDrying(LaundryDryingSession session, Optional<KellerClimate> climate,
                                      LocalDateTime now, double requiredLoad) {
        if (!now.isBefore(session.getStartedAt().plus(LaundryRules.SESSION_TIMEOUT))) {
            session.setStatus(LaundrySessionStatus.EXPIRED);
            session.setEndedAt(now);
            session.setDryQuestionOpen(false);
            // Schwache Probe nur bis zum Greifen des Kurvenkriteriums, nicht bis 72 h
            session.setWeakLoad(session.getCurveCriterionLoad());
            return;
        }
        if (climate.isPresent()) {
            accumulate(session, climate.get(), now);
        } else {
            // Eine Funkpause darf nicht als Haltezeit des Kurvenkriteriums zaehlen
            session.setCurveLowSince(null);
        }
        // Ohne Klima nur die Uhr nachziehen, damit die Luecke spaeter nicht nachgezaehlt wird
        session.setLastAccumulatedAt(now);

        boolean quiet = LaundryRules.QUIET_HOURS.contains(now.toLocalTime());
        if (session.getStatus() == LaundrySessionStatus.DRYING && maybeDry(session, requiredLoad) && !quiet) {
            session.setStatus(LaundrySessionStatus.MAYBE_DRY);
            session.setMaybeDryAt(now);
            session.setDryQuestionOpen(true);
        } else if (session.getStatus() == LaundrySessionStatus.MAYBE_DRY && !session.isDryQuestionOpen()
                && session.getDryQuestionAt() != null && !session.getDryQuestionAt().isAfter(now) && !quiet) {
            session.setDryQuestionOpen(true);
        }
    }

    private static void accumulate(LaundryDryingSession session, KellerClimate climate, LocalDateTime now) {
        double hours = elapsedHours(session.getLastAccumulatedAt(), now);
        double vpd = DryingPhysics.vaporPressureDeficitKpa(climate.tempC(), climate.humidity());
        double load = valueOrZero(session.getAccumulatedLoad()) + vpd * hours;
        session.setAccumulatedLoad(load);

        double excess = climate.humidity() - valueOrZero(session.getBaselineHumidity());
        double peak = Math.max(valueOrZero(session.getPeakExcess()), excess);
        session.setPeakExcess(peak);
        if (session.getCurveCriterionAt() == null) {
            trackCurve(session, excess, peak, load, now);
        }
    }

    /**
     * Ein Schritt zaehlt hoechstens SENSOR_STALE: nach einem Backend-Neustart wird die Ausfallzeit
     * nicht voll mit dem aktuellen Defizit nachgerechnet (Anti-Ziel "zu frueh").
     */
    private static double elapsedHours(LocalDateTime last, LocalDateTime now) {
        if (last == null || !now.isAfter(last)) {
            return 0.0;
        }
        Duration step = Duration.between(last, now);
        if (step.compareTo(LaundryRules.SENSOR_STALE) > 0) {
            step = LaundryRules.SENSOR_STALE;
        }
        return step.toSeconds() / 3600.0;
    }

    private static void trackCurve(LaundryDryingSession session, double excess, double peak,
                                   double load, LocalDateTime now) {
        boolean low = peak >= LaundryRules.CURVE_MIN_PEAK_PP && excess <= LaundryRules.CURVE_FRACTION * peak;
        if (!low) {
            session.setCurveLowSince(null);
            return;
        }
        if (session.getCurveLowSince() == null) {
            session.setCurveLowSince(now);
        }
        boolean heldLongEnough = !now.isBefore(session.getCurveLowSince().plus(LaundryRules.CURVE_HOLD));
        boolean lateEnough = !now.isBefore(session.getStartedAt().plus(LaundryRules.CURVE_EARLIEST));
        if (heldLongEnough && lateEnough) {
            session.setCurveCriterionAt(now);
            session.setCurveCriterionLoad(load);
        }
    }

    /** K erreicht oder Kurvenkriterium gegriffen; Letzteres bleibt stehen (Ruhezeit-Verschiebung). */
    private static boolean maybeDry(LaundryDryingSession session, double requiredLoad) {
        return valueOrZero(session.getAccumulatedLoad()) >= requiredLoad || session.getCurveCriterionAt() != null;
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }
}
