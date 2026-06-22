package com.household.manager.zigbee.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Register eines bekannten Zigbee-Geräts. Schlüssel ist der zigbee2mqtt friendly name.
 */
@Entity
@Table(name = "zigbee_device")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "friendly_name", nullable = false, unique = true)
    private String friendlyName;

    @Column(name = "ieee_address")
    private String ieeeAddress;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "model")
    private String model;

    @Column(name = "last_battery_percent")
    private Integer lastBatteryPercent;

    @Column(name = "last_link_quality")
    private Integer lastLinkQuality;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
