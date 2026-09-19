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

/** Eine favorisierte Ladesaeule (stationId = stabile Id der Quelle). */
@Entity
@Table(name = "charging_favorite")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(length = 255)
    private String operator;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
