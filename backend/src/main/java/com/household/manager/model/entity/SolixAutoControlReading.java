package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "solix_auto_control_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolixAutoControlReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "grid_power_w")
    private Integer gridPowerW;

    @Column(name = "current_output_w")
    private Integer currentOutputW;

    @Column(name = "target_output_w")
    private Integer targetOutputW;

    @Column(name = "clamped_output_w")
    private Integer clampedOutputW;

    @Column(name = "min_load_w")
    private Integer minLoadW;

    @Column(name = "max_load_w")
    private Integer maxLoadW;

    @Column(name = "applied", nullable = false)
    private boolean applied;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
