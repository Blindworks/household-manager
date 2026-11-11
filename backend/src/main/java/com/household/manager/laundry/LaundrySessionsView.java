package com.household.manager.laundry;

import java.time.LocalDateTime;
import java.util.List;

/** Letzte abgeschlossene Waschgaenge plus gelernter Bedarf — zur Kontrolle des Gelernten. */
public record LaundrySessionsView(double requiredLoad, List<Entry> sessions) {

    public record Entry(LocalDateTime askedAt, LocalDateTime startedAt, LocalDateTime endedAt,
                        LaundrySessionStatus status, Double confirmedLoad, Double weakLoad,
                        Double lowerBoundLoad) {
    }
}
