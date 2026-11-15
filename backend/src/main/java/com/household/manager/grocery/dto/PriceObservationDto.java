package com.household.manager.grocery.dto;

import com.household.manager.grocery.Chain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Ein Punkt des Preisverlaufs: niedrigster Grundpreis je Kette und ISO-Woche. */
public record PriceObservationDto(Chain chain, String chainName, String isoWeek, BigDecimal basePrice,
                                  LocalDateTime observedAt) {
}
