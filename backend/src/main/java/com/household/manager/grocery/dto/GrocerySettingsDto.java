package com.household.manager.grocery.dto;

/** Einstellungen der Supermarkt-Angebote: Standort und Schwelle für „Bestpreis“-Hinweise. */
public record GrocerySettingsDto(String zip, double lat, double lng, int dealThresholdPercent) {
}
