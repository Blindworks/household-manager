package com.household.manager.service;

import lombok.Getter;

/** Auswählbarer Zeitraum der Temperaturgraphen. */
@Getter
public enum TemperatureRange {
    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int days;

    TemperatureRange(int days) {
        this.days = days;
    }
}
