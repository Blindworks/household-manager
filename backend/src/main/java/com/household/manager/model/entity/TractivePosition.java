package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Ein gespeicherter Positionspunkt eines Tractive-Trackers.
 * <p>
 * Gefuellt vom TractivePositionRecorder aus dem minuetlichen Poll-Zyklus. Diese
 * Tabelle ist die Grundlage der Spaziergangserkennung: die Tractive-Cloud liefert
 * beim Basic-Abo nur rund 24 Stunden Historie, laengere Zeitraeume entstehen
 * ausschliesslich dadurch, dass wir selbst mitschreiben.
 * <p>
 * {@code positionTime} ist der Zeitpunkt des Berichts, nicht des Polls — nur so
 * bleiben die Funkpausen erhalten, an denen der Detektor die Runden trennt.
 */
@Entity
@Table(name = "tractive_position",
        uniqueConstraints = @UniqueConstraint(name = "uk_tractive_position_tracker_time",
                columnNames = {"tracker_id", "position_time"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TractivePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hardware-Id des Trackers, z. B. "dev-9". */
    @Column(name = "tracker_id", nullable = false, length = 100)
    private String trackerId;

    @Column(name = "position_time", nullable = false)
    private Instant positionTime;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "accuracy")
    private Double accuracy;

    /** Wie die Position bestimmt wurde, z. B. "GPS" oder "KNOWN_WIFI". */
    @Column(name = "sensor_used", length = 50)
    private String sensorUsed;
}
