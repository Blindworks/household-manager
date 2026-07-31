package com.household.manager.service;

import lombok.Getter;

/** Auswählbarer Zeitraum der Temperaturgraphen inkl. der dazu passenden Mittelungs-Bucketlänge. */
@Getter
public enum TemperatureRange {
    DAY(1, 5 * 60L),
    WEEK(7, 30 * 60L),
    MONTH(30, 2 * 60 * 60L);

    private final int days;
    /** Länge eines Mittelungs-Buckets in Sekunden; je länger der Zeitraum, desto gröber. */
    private final long bucketSeconds;

    TemperatureRange(int days, long bucketSeconds) {
        this.days = days;
        this.bucketSeconds = bucketSeconds;
    }
}
