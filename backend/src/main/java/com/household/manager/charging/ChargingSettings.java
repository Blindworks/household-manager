package com.household.manager.charging;

/** Zuhause, Radius und Mindestleistung, wie sie in der DB stehen. Koordinaten null = nicht konfiguriert. */
public record ChargingSettings(Double homeLatitude, Double homeLongitude, double radiusKm, double minPowerKw) {

    public boolean isConfigured() {
        return homeLatitude != null && homeLongitude != null;
    }
}
