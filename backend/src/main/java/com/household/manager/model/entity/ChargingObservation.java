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

/** Beobachtete Zeit einer Station in einer Stunde samt freien/belegten Ladepunkt-Sekunden. */
@Entity
@Table(name = "charging_observation")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Column(name = "hour_start", nullable = false)
    private Instant hourStart;

    @Column(name = "observed_seconds", nullable = false)
    private int observedSeconds;

    @Column(name = "free_point_seconds", nullable = false)
    private long freePointSeconds;

    @Column(name = "occupied_point_seconds", nullable = false)
    private long occupiedPointSeconds;
}
