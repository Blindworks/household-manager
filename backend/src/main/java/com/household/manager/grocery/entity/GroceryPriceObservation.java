package com.household.manager.grocery.entity;

import com.household.manager.grocery.Chain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Beobachteter Grundpreis eines Produkts bei einer Kette in einer ISO-Woche. */
@Entity
@Table(name = "grocery_price_observation")
@Getter
@Setter
@NoArgsConstructor
public class GroceryPriceObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Chain chain;

    /** Format {@code 2026-W47}. */
    @Column(name = "iso_week", nullable = false, length = 8)
    private String isoWeek;

    @Column(name = "base_price", nullable = false, precision = 9, scale = 3)
    private BigDecimal basePrice;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;
}
