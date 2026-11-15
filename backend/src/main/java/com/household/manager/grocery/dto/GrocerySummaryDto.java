package com.household.manager.grocery.dto;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.pricing.BaseUnit;

import java.math.BigDecimal;
import java.util.List;

/** Sichere Schnäppchen aller Personen und des Haushalts für die Hub-Karte. */
public record GrocerySummaryDto(List<SummaryItem> items) {

    /** {@code ownerUserId} null = Haushalt; {@code basePrice} ist der bewertete Grundpreis (bei appOnly der App-Preis). */
    public record SummaryItem(String productName, Long ownerUserId, String ownerName, Chain chain, String chainName,
                              BigDecimal basePrice, BaseUnit baseUnit, int savingPercent) {
    }
}
