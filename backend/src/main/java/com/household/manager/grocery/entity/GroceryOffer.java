package com.household.manager.grocery.entity;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.pricing.BaseUnit;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Zusammengeführtes Angebot; {@code marketId} null = gilt kettenweit. */
@Entity
@Table(name = "grocery_offer")
@Getter
@Setter
@NoArgsConstructor
public class GroceryOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Chain chain;

    @Column(name = "market_id")
    private Long marketId;

    /** Kommagetrennte SourceKeys. */
    @Column(nullable = false, length = 100)
    private String sources;

    /** Kommagetrennt, je Eintrag {@code QUELLE:id}. */
    @Column(name = "source_offer_ids", length = 500)
    private String sourceOfferIds;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 150)
    private String brand;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 200)
    private String packaging;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal price;

    @Column(name = "old_price", precision = 8, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "loyalty_price", precision = 8, scale = 2)
    private BigDecimal loyaltyPrice;

    @Column(name = "lowest_price_30d", precision = 8, scale = 2)
    private BigDecimal lowestPrice30d;

    @Column(name = "base_price", precision = 9, scale = 3)
    private BigDecimal basePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", length = 10)
    private BaseUnit baseUnit;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String fingerprint;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;
}
