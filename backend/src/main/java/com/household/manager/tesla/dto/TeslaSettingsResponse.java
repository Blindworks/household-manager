package com.household.manager.tesla.dto;

/** Einstellungen für die Admin-Seite; der Token selbst verlässt das Backend nie. */
public record TeslaSettingsResponse(boolean tokenConfigured, String vin, int pollIntervalMinutes,
                                    int minPollIntervalMinutes, int maxPollIntervalMinutes) {
}
