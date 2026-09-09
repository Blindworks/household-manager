package com.household.manager.dto;

/** Pflegbare Preis-Einstellungen. {@code gasKwhPerM3}: Brennwert × Zustandszahl. */
public record UtilityPricingSettingsDto(Double gasKwhPerM3) {
}
