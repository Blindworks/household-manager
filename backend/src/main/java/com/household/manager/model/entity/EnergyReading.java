package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "energy_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnergyReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "bkw_alt_w")
    private Double bkwAltW;

    @Column(name = "bkw_neu_w")
    private Double bkwNeuW;

    @Column(name = "pv_total_w")
    private Double pvTotalW;

    @Column(name = "hausverbrauch_w")
    private Double hausverbrauchW;

    @Column(name = "grid_w")
    private Double gridW;

    @Column(name = "anker_pv_w")
    private Double ankerPvW;

    @Column(name = "akku_percent")
    private Double akkuPercent;

    @Column(name = "akku_power_w")
    private Double akkuPowerW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
