package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing a meter reading for household utilities.
 * <p>
 * Tracks readings for electricity, gas, and water meters over time
 * to enable consumption monitoring and history tracking.
 */
@Entity
@Table(name = "meter_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeterReading {

    /**
     * Unique identifier for the meter reading
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Type of meter being read (ELECTRICITY, GAS, or WATER)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "meter_type", nullable = false, length = 50)
    private MeterType meterType;

    /**
     * The meter reading value at the time of recording.
     * <p>
     * Precision: 10 digits total, 2 decimal places
     * Units depend on meter type:
     * - ELECTRICITY: kilowatt-hours (kWh)
     * - GAS: cubic meters (m³)
     * - WATER: cubic meters (m³)
     */
    @Column(name = "reading_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal readingValue;

    /**
     * Date and time when the meter reading was taken
     */
    @Column(name = "reading_date", nullable = false)
    private LocalDateTime readingDate;

    /**
     * Optional notes or comments about this reading
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

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
