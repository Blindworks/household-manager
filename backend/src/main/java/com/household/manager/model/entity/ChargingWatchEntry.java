package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/** Auftrag „Benachrichtigen, wenn frei“ einer Person an einer Ladestation. */
@Entity
@Table(name = "charging_watch")
@IdClass(ChargingWatchEntry.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChargingWatchEntry {

    @Id
    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "station_name", nullable = false)
    private String stationName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String stationId;
        private Long userId;
    }
}
