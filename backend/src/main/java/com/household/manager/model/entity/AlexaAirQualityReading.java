package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Messwert eines Amazon Smart Air Quality Monitors (via Alexa-Sidecar).
 * Geraete-Identitaet ueber die stabile applianceId, nie ueber Namen.
 */
@Entity
@Table(name = "alexa_air_quality_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAirQualityReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appliance_id", nullable = false)
    private String applianceId;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "reading_time", nullable = false)
    private LocalDateTime readingTime;

    @Column(name = "iaq")
    private Integer iaq;

    @Column(name = "pm25", precision = 10, scale = 2)
    private BigDecimal pm25;

    @Column(name = "voc", precision = 10, scale = 2)
    private BigDecimal voc;

    @Column(name = "co", precision = 10, scale = 3)
    private BigDecimal co;

    @Column(name = "temperature", precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(name = "humidity", precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
