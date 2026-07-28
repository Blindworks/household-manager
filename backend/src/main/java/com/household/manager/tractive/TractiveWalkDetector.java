package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Leitet Spaziergaenge aus der rohen Positionshistorie ab: zusammenhaengende
 * Zeitraeume ausserhalb des Home-Radius. Bewusste Unschaerfe (siehe Spec):
 * jede Abwesenheit zaehlt, und im Stromsparmodus meldet der Tracker selten –
 * kurze Runden koennen verschluckt werden.
 */
public final class TractiveWalkDetector {

    /** Berichte kommen unregelmaessig; kuerzere Luecken gelten als derselbe Spaziergang. */
    static final Duration MAX_GAP = Duration.ofMinutes(10);
    /** GPS-Jitter am Radiusrand erzeugt Sekunden-"Spaziergaenge" – die fliegen raus. */
    static final Duration MIN_DURATION = Duration.ofMinutes(5);

    private TractiveWalkDetector() {
    }

    public static List<TractiveWalkDto> detectWalks(List<TractivePositionDto> points, GeoZone home) {
        List<TractivePositionDto> usable = points.stream()
                .filter(TractiveWalkDetector::isUsable)
                .sorted(Comparator.comparing(TractivePositionDto::time))
                .toList();

        List<TractiveWalkDto> walks = new ArrayList<>();
        List<TractivePositionDto> current = new ArrayList<>();
        for (TractivePositionDto point : usable) {
            boolean away = !home.contains(point.latitude(), point.longitude());
            if (!away) {
                closeWalk(current, walks);
                continue;
            }
            if (!current.isEmpty() && gapTooLarge(current.get(current.size() - 1), point)) {
                closeWalk(current, walks);
            }
            current.add(point);
        }
        closeWalk(current, walks);

        walks.sort(Comparator.comparing(TractiveWalkDto::start).reversed());
        return walks;
    }

    private static boolean isUsable(TractivePositionDto point) {
        // Double.isFinite ist an der API-Grenze tragend: Jackson macht aus dem
        // String "NaN" klaglos ein Double.NaN.
        return point.time() != null
                && point.hasCoordinates()
                && Double.isFinite(point.latitude())
                && Double.isFinite(point.longitude())
                && Math.abs(point.latitude()) <= 90
                && Math.abs(point.longitude()) <= 180;
    }

    private static boolean gapTooLarge(TractivePositionDto previous, TractivePositionDto next) {
        // Strikt groesser als MAX_GAP: eine Luecke von genau MAX_GAP gilt noch
        // als ueberbrueckt, sonst wuerde ein exakt zehnminuetiges Reporting-
        // intervall einen einzelnen Spaziergang in lauter Einzelpunkte zerlegen.
        return Duration.between(previous.reportedAt(), next.reportedAt())
                .compareTo(MAX_GAP) > 0;
    }

    private static void closeWalk(List<TractivePositionDto> current, List<TractiveWalkDto> walks) {
        if (current.isEmpty()) {
            return;
        }
        Instant start = current.get(0).reportedAt();
        Instant end = current.get(current.size() - 1).reportedAt();
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(MIN_DURATION) >= 0) {
            walks.add(new TractiveWalkDto(start, end, duration.toMinutes(), distance(current)));
        }
        current.clear();
    }

    private static double distance(List<TractivePositionDto> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += GeoZone.distanceMeters(
                    points.get(i - 1).latitude(), points.get(i - 1).longitude(),
                    points.get(i).latitude(), points.get(i).longitude());
        }
        return total;
    }
}
