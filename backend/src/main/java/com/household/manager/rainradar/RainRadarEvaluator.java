package com.household.manager.rainradar;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Einzige Definition von „es regnet“ und „gleich regnet es“ am Haus (reine Funktion).
 *
 * <p>Massgeblich ist das juengste <b>gemessene</b> Bild; die Vorhersagebilder danach
 * liefern, wann Regen erwartet wird. Ohne gemessenes Bild gibt es keine Aussage.
 */
public final class RainRadarEvaluator {

    private static final Duration PEAK_WINDOW = Duration.ofHours(1);

    private RainRadarEvaluator() {
    }

    public static Optional<RainRadarReading> evaluate(List<RainRadarFrame> frames, Instant now,
                                                      double thresholdMmPerHour, int soonMinutes) {
        Optional<RainRadarFrame> latest = frames.stream()
                .filter(f -> !f.forecast())
                .max(Comparator.comparing(RainRadarFrame::time));
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        RainRadarFrame measured = latest.get();
        List<RainRadarFrame> ahead = frames.stream()
                .filter(RainRadarFrame::forecast)
                .filter(f -> f.time().isAfter(measured.time()))
                .sorted(Comparator.comparing(RainRadarFrame::time))
                .toList();

        boolean raining = measured.mmPerHour() >= thresholdMmPerHour;
        Instant expectedRainAt = raining ? null : ahead.stream()
                .filter(f -> f.mmPerHour() >= thresholdMmPerHour)
                .map(RainRadarFrame::time)
                .findFirst()
                .orElse(null);
        Integer minutesUntilRain = expectedRainAt == null ? null
                : (int) Math.max(0, Duration.between(now, expectedRainAt).toMinutes());
        boolean rainSoon = raining || (minutesUntilRain != null && minutesUntilRain <= soonMinutes);

        Instant peakEnd = measured.time().plus(PEAK_WINDOW);
        double peakNextHour = ahead.stream()
                .filter(f -> !f.time().isAfter(peakEnd))
                .mapToDouble(RainRadarFrame::mmPerHour)
                .reduce(measured.mmPerHour(), Math::max);

        return Optional.of(new RainRadarReading(measured.time(), measured.mmPerHour(), raining, rainSoon,
                expectedRainAt, minutesUntilRain, peakNextHour));
    }
}
