package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "shelly_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShellyReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "power")
    private Double power;

    @Column(name = "voltage")
    private Double voltage;

    @Column(name = "current_a")
    private Double currentA;

    @Column(name = "total_energy")
    private Double totalEnergy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
