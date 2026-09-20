package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Seit wann ein Ladepunkt belegt ist. {@code occupiedSince == null} heisst: beim ersten
 * Poll schon belegt, der Beginn ist unbekannt - die Anzeige sagt dann "seit mind.".
 */
@Entity
@Table(name = "charging_point_occupancy")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingPointOccupancy {

    @Id
    @Column(name = "chargepoint_id", nullable = false, length = 191)
    private String chargePointId;

    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Column(name = "occupied_since")
    private Instant occupiedSince;

    @Column(name = "first_seen_occupied_at", nullable = false)
    private Instant firstSeenOccupiedAt;
}
