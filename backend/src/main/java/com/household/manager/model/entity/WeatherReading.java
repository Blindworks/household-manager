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

/** Snapshot der tatsächlichen Wetterbedingungen zu einem Abrufzeitpunkt. */
@Entity
@Table(name = "weather_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reading_time", nullable = false)
    private LocalDateTime readingTime;

    @Column(name = "temperature", precision = 10, scale = 2)
    private BigDecimal temperature;

    @Column(name = "precipitation", precision = 10, scale = 2)
    private BigDecimal precipitation;

    @Column(name = "wind_speed", precision = 10, scale = 2)
    private BigDecimal windSpeed;

    @Column(name = "wind_direction")
    private Integer windDirection;

    @Column(name = "humidity")
    private Integer humidity;

    @Column(name = "pressure", precision = 10, scale = 2)
    private BigDecimal pressure;

    @Column(name = "icon")
    private Integer icon;

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
