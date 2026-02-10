package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing a Tasmota electricity meter reading.
 * <p>
 * Stores timestamp, positive active energy (tariff-less),
 * and instantaneous active power.
 */
@Entity
@Table(name = "tasmota_electricity_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaElectricityReading {

    /**
     * Unique identifier for the Tasmota electricity reading.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Timestamp reported by the device.
     */
    @Column(name = "reading_time", nullable = false)
    private LocalDateTime readingTime;

    /**
     * Positive active energy (tariflos).
     */
    @Column(name = "pos_wirkenergie_tariflos", nullable = false, precision = 12, scale = 2)
    private BigDecimal posWirkenergieTariflos;

    /**
     * Instantaneous active power.
     */
    @Column(name = "momentane_wirkleistung", nullable = false, precision = 12, scale = 2)
    private BigDecimal momentaneWirkleistung;

    /**
     * Timestamp when this record was created in the database.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Automatically set creation timestamp before persisting.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Automatically update the updated timestamp before updating.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
