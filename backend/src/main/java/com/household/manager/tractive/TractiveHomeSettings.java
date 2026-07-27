package com.household.manager.tractive;

/**
 * Die Definition von "zu Hause", wie sie in der Datenbank steht.
 *
 * <p>{@code homeLatitude}/{@code homeLongitude} sind {@code null}, solange nichts
 * konfiguriert ist – dann entsteht keine Home-Entitaet. Alle uebrigen Werte sind
 * immer belegt, notfalls mit dem Default.
 */
public record TractiveHomeSettings(
        Double homeLatitude,
        Double homeLongitude,
        double homeRadiusMeters,
        double homeArrivalRadiusMeters,
        long poweredOffAfterMinutes,
        int poweredOffMinBatteryPercent,
        String homeZoneName
) {

    /** Nur ein vollstaendiges Koordinatenpaar zaehlt als konfiguriert. */
    public boolean hasHomeCoordinates() {
        return homeLatitude != null && homeLongitude != null;
    }

    /** Ein kleiner konfigurierter Ankunftsradius darf Regel 4 nicht unwirksam machen. */
    public double effectiveArrivalRadiusMeters() {
        return Math.max(homeArrivalRadiusMeters, homeRadiusMeters);
    }
}
