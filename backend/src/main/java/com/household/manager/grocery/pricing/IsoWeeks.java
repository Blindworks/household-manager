package com.household.manager.grocery.pricing;

import java.time.LocalDate;
import java.time.temporal.IsoFields;

/** Einzige Definition des ISO-Wochenschlüssels ({@code 2026-W47}). */
final class IsoWeeks {

    private IsoWeeks() {
    }

    static String of(LocalDate date) {
        return String.format("%d-W%02d", date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
