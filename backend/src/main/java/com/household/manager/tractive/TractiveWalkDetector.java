package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Leitet Spaziergaenge aus der rohen Positionshistorie ab — ueber den
 * Einschalt-Indikator dieses Haushalts: der Tracker ist zu Hause aus und wird
 * nur fuer die Runde eingeschaltet. Ein Spaziergang ist deshalb ein
 * zusammenhaengender Block von Positionsberichten zwischen zwei langen
 * Funkpausen; die Blockraender entsprechen dem Ein- und Ausschalten.
 *
 * <p>Als Absicherung zaehlt ein Block nur, wenn mindestens ein Punkt ausserhalb
 * des Home-Radius liegt — sonst wuerde ein zu Hause eingeschalteter Tracker
 * (etwa auf der Ladeschale) als Spaziergang erscheinen. Kehrseite: bleibt der
 * Tracker unterwegs versehentlich dauerhaft an, wird der ganze Zeitraum ein
 * einziger langer "Spaziergang".
 */
public final class TractiveWalkDetector {

    /**
     * Erst eine echt laengere Funkpause gilt als Ausschalten — der Sparmodus
     * meldet unregelmaessig, und ein exakt 30-minuetiges Meldeintervall darf
     * eine Runde nicht zerteilen.
     */
    static final Duration OFF_GAP = Duration.ofMinutes(30);
    /** GPS-Jitter und Test-Einschalten erzeugen Mini-Blocks – die fliegen raus. */
    static final Duration MIN_DURATION = Duration.ofMinutes(5);

    private TractiveWalkDetector() {
    }

    public static List<TractiveWalkDto> detectWalks(List<TractivePositionDto> points, GeoZone home) {
        List<TractivePositionDto> usable = points.stream()
                .filter(TractiveWalkDetector::isUsable)
                .sorted(Comparator.comparing(TractivePositionDto::time))
                .toList();

        List<TractiveWalkDto> walks = new ArrayList<>();
        List<TractivePositionDto> cluster = new ArrayList<>();
        for (TractivePositionDto point : usable) {
            if (!cluster.isEmpty() && offGap(cluster.get(cluster.size() - 1), point)) {
                closeCluster(cluster, walks, home);
            }
            cluster.add(point);
        }
        closeCluster(cluster, walks, home);

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

    private static boolean offGap(TractivePositionDto previous, TractivePositionDto next) {
        return Duration.between(previous.reportedAt(), next.reportedAt())
                .compareTo(OFF_GAP) > 0;
    }

    private static void closeCluster(List<TractivePositionDto> cluster, List<TractiveWalkDto> walks,
                                     GeoZone home) {
        if (cluster.isEmpty()) {
            return;
        }
        Instant start = cluster.get(0).reportedAt();
        Instant end = cluster.get(cluster.size() - 1).reportedAt();
        Duration duration = Duration.between(start, end);
        boolean leftHome = cluster.stream()
                .anyMatch(p -> !home.contains(p.latitude(), p.longitude()));
        if (duration.compareTo(MIN_DURATION) >= 0 && leftHome) {
            walks.add(new TractiveWalkDto(start, end, duration.toMinutes(), distance(cluster)));
        }
        cluster.clear();
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
