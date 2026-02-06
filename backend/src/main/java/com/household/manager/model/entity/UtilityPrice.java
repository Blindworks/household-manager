package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing utility prices for electricity and water.
 * <p>
 * Tracks price information with validity periods to enable
 * historical price tracking and cost calculations.
 * Only supports ELECTRICITY and GAS meter types.
 */
@Entity
@Table(name = "utility_prices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilityPrice {

    /**
     * Unique identifier for the utility price record
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Type of meter (ELECTRICITY or GAS only)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "meter_type", nullable = false, length = 50)
    private MeterType meterType;

    /**
     * Price per unit for the utility.
     * <p>
     * Precision: 10 digits total, 4 decimal places
     * Units depend on meter type:
     * - ELECTRICITY: price per kWh
     * - GAS: price per m³
     */
    @Column(name = "price", nullable = false, precision = 10, scale = 4)
    private BigDecimal price;

    /**
     * Start date when this price becomes valid (inclusive)
     */
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    /**
     * End date when this price is valid until (exclusive).
     * <p>
     * If null, the price is considered valid indefinitely.
     */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /**
     * Timestamp when this record was created in the database
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Automatically set creation timestamp before persisting
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Automatically update the updated timestamp before updating
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
