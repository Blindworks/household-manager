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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein ueber das Wandtablet gesehenes Bluetooth-Thermometer. Entsteht implizit mit der ersten
 * Meldung (inaktiv, ohne Namen); Historie und Entitaeten gibt es erst nach der Uebernahme
 * (active = true). Die Letztwerte stehen hier, damit die Admin-Liste auch nicht uebernommene
 * Sensoren zum Wiedererkennen zeigt, ohne dass sie eine Historie bekommen.
 */
@Entity
@Table(name = "ble_sensor")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BleSensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalform: kleingeschrieben mit Doppelpunkten. */
    @Column(nullable = false, length = 17, unique = true)
    private String mac;

    @Column(length = 100)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    @Column(name = "tablet_id", length = 100)
    private String tabletId;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_temperature", precision = 5, scale = 2)
    private BigDecimal lastTemperature;

    @Column(name = "last_humidity", precision = 5, scale = 2)
    private BigDecimal lastHumidity;

    @Column(name = "last_battery_mv")
    private Integer lastBatteryMv;

    @Column(name = "last_rssi")
    private Integer lastRssi;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
