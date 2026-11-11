package com.household.manager.laundry;

import com.household.manager.common.TimeWindow;
import java.time.Duration;
import java.time.LocalTime;

/** Einzige Definition der Schwellen und Fristen der Waeschetrocknung. */
public final class LaundryRules {

    public static final double DEFAULT_REQUIRED_LOAD = 17.5;
    public static final double HUMIDITY_RISE_PP = 3.0;
    public static final Duration RISE_WINDOW = Duration.ofHours(3);
    public static final Duration ASK_EXPIRY = Duration.ofHours(6);
    public static final double CURVE_FRACTION = 0.25;
    public static final Duration CURVE_HOLD = Duration.ofMinutes(60);
    public static final Duration CURVE_EARLIEST = Duration.ofHours(6);
    public static final double CURVE_MIN_PEAK_PP = 3.0;
    public static final Duration SENSOR_STALE = Duration.ofMinutes(60);
    public static final Duration REASK_AFTER = Duration.ofHours(2);
    public static final Duration SESSION_TIMEOUT = Duration.ofHours(72);
    public static final int LEARN_WINDOW = 10;
    public static final int MIN_CONFIRMED = 3;
    public static final TimeWindow QUIET_HOURS = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0));

    private LaundryRules() {
    }
}
