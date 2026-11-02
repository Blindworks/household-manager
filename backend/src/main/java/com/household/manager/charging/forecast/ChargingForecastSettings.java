package com.household.manager.charging.forecast;

import java.time.Duration;
import java.util.List;

/** Einzige Definition der Schwellen für die Ladesäulen-Vorhersage. */
public final class ChargingForecastSettings {

    public static final Duration MIN_OBSERVED = Duration.ofDays(14);
    public static final Duration PATTERN_WINDOW = Duration.ofDays(56);
    public static final int MIN_SAMPLES = 10;
    public static final Duration START_WINDOW = Duration.ofHours(2);
    public static final Duration MAX_GAP = Duration.ofMinutes(10);
    public static final Duration CACHE_TTL = Duration.ofHours(1);
    public static final double MEDIAN = 0.5;
    public static final double LATEST = 0.8;
    public static final List<Integer> HORIZONS_MINUTES = List.of(15, 30, 60);
    public static final Duration RETENTION = Duration.ofDays(365);

    private ChargingForecastSettings() {
    }
}
