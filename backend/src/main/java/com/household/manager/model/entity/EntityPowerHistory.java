package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ein Messpunkt der Verbrauchshistorie eines Power-Sensors.
 * Gefuellt vom PowerHistoryRecorder, verdichtet vom PowerHistoryAggregationJob.
 */
@Entity
@Table(name = "entity_power_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityPowerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Entity-ID des Power-Sensors, z. B. "sensor.meross_<uuid>_power". */
    @Column(name = "entity_id", nullable = false, length = 150)
    private String entityId;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    /** Leistung in Watt; null = Sensor war nicht erreichbar (Luecke im Graphen). */
    @Column(name = "power_watts")
    private Double powerWatts;
}
