package com.household.manager.dto;

import java.math.BigDecimal;

/**
 * Pflegbare Preis-Einstellungen. {@code gasKwhPerM3}: Brennwert × Zustandszahl.
 * <p>
 * {@code BigDecimal} statt {@code Double}, damit NaN/Infinity strukturell
 * ausgeschlossen sind (Jackson lehnt einen nicht-numerischen Wert beim
 * Deserialisieren mit 400 ab statt ihn klaglos in NaN zu verwandeln).
 */
public record UtilityPricingSettingsDto(BigDecimal gasKwhPerM3) {
}
