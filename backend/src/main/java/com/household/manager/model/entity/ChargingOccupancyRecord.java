package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Eine abgeschlossene Belegung eines Ladepunkts (Historie fuer die Vorhersage). */
@Entity
@Table(name = "charging_occupancy_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingOccupancyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Column(name = "chargepoint_id", nullable = false, length = 191)
    private String chargePointId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;
}
