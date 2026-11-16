package com.household.manager.grocery.dto;

import com.household.manager.grocery.pricing.BaseUnit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Anlegen/Ändern eines Produkts. {@code household = true} legt es als Haushaltsprodukt an (null beim Ändern:
 * Besitz bleibt). Fehlen beim Anlegen {@code searchWords} oder {@code baseUnit}, schlägt Claude sie vor.
 * {@code active} null = aktiv (anlegen) bzw. unverändert (ändern).
 */
public record ProductRequest(String name, Boolean household, BaseUnit baseUnit, List<String> searchWords,
                             List<String> mustWords, List<String> excludeWords, BigDecimal normalPricePerUnit,
                             Boolean active) {
}
