package com.household.manager.grocery.dto;

import com.household.manager.grocery.pricing.BaseUnit;

import java.math.BigDecimal;
import java.util.List;

/** Ein beobachtetes Produkt; {@code ownerUserId} null = Haushalt. {@code editable}: eigenes oder Haushaltsprodukt. */
public record ProductDto(Long id, Long ownerUserId, String ownerName, String name, BaseUnit baseUnit,
                         List<String> searchWords, List<String> mustWords, List<String> excludeWords,
                         BigDecimal normalPricePerUnit, boolean active, boolean editable) {
}
