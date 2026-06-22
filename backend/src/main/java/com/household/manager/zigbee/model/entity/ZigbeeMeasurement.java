package com.household.manager.zigbee.model.entity;

import com.household.manager.zigbee.model.MeasurementType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein einzelner Zigbee-Messwert (generisch über alle Sensortypen).
 */
@Entity
@Table(name = "zigbee_measurement",
        indexes = @Index(name = "idx_zigbee_measurement_device_type_time",
                columnList = "device_id, measurement_type, measured_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private ZigbeeDevice device;

    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_type", nullable = false, length = 32)
    private MeasurementType measurementType;

    @Column(name = "value", nullable = false, precision = 12, scale = 3)
    private BigDecimal value;

    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
