package com.household.manager.grocery.entity;

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
import java.time.LocalDateTime;

/** Ein beobachtetes Produkt; {@code ownerUserId} null = Haushalt. Wortlisten sind kommagetrennt und klein geschrieben. */
@Entity
@Table(name = "grocery_product")
@Getter
@Setter
@NoArgsConstructor
public class GroceryProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", nullable = false, length = 10)
    private BaseUnit baseUnit;

    @Column(name = "search_words", nullable = false, length = 500)
    private String searchWords;

    @Column(name = "must_words", nullable = false, length = 500)
    private String mustWords;

    @Column(name = "exclude_words", nullable = false, length = 500)
    private String excludeWords;

    @Column(name = "normal_price_per_unit", precision = 8, scale = 3)
    private BigDecimal normalPricePerUnit;

    /** Von Claude geschätzter regulärer Ladenpreis je Bezugseinheit; nur über die Repository-Abfrage geschrieben. */
    @Column(name = "estimated_price_per_unit", precision = 8, scale = 3)
    private BigDecimal estimatedPricePerUnit;

    @Column(name = "estimated_at")
    private LocalDateTime estimatedAt;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
