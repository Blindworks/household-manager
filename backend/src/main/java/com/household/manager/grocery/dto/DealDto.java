package com.household.manager.grocery.dto;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.matching.ReasonKind;
import com.household.manager.grocery.matching.Verdict;
import com.household.manager.grocery.pricing.BaseUnit;
import com.household.manager.grocery.pricing.Deal.ReferenceKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ein zugeordnetes Angebot. {@code basePrice} ist der Grundpreis ohne App, {@code appBasePrice} der mit App.
 * Bei einem passenden Angebot ohne Schnäppchen (Endpunkt {@code /products/{id}/offers}) sind
 * {@code reference}, {@code referenceKind} und {@code savingPercent} null und {@code deal} false.
 */
public record DealDto(Long productId, String productName, Long ownerUserId, Chain chain, String chainName,
                      String marketName, String title, String packaging, BigDecimal price, BigDecimal basePrice,
                      BaseUnit baseUnit, BigDecimal reference, ReferenceKind referenceKind, Integer savingPercent,
                      BigDecimal appPrice, BigDecimal appBasePrice, boolean appOnly, Verdict verdict,
                      ReasonKind reasonKind, String reason, boolean userDecided, boolean warmingUp, boolean deal,
                      LocalDate validFrom, LocalDate validTo, String imageUrl, String fingerprint) {
}
