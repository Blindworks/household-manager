package com.household.manager.entitystate;

import lombok.Getter;

/** Auswaehlbarer Zeitraum des Verbrauchsgraphen. */
@Getter
public enum PowerRange {
    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int days;

    PowerRange(int days) {
        this.days = days;
    }
}
