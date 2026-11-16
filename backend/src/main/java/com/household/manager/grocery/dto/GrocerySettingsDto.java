package com.household.manager.grocery.dto;

/** Einstellungen der Supermarkt-Angebote: Standort und Schwelle für „Bestpreis“-Hinweise. */
public record GrocerySettingsDto(String zip, Double lat, Double lng, Integer dealThresholdPercent) {
}
