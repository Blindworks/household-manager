package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity representing a smart home device (Kasa, Tapo, or Meross).
 * <p>
 * Provides unified storage for devices discovered via network scanning,
 * allowing persistent device management and control across sessions.
 */
@Entity
@Table(name = "smart_devices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartDevice {

    /**
     * Unique identifier for the smart device record
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Type of smart device (KASA, TAPO, or MEROSS)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 20)
    private DeviceType deviceType;

    /**
     * Device's native identifier from the device ecosystem.
     * <p>
     * - KASA: IP address
     * - TAPO: deviceId from cloud API
     * - MEROSS: deviceId from cloud API
     */
    @Column(name = "external_device_id", nullable = false, length = 255)
    private String externalDeviceId;

    /**
     * User-friendly name for the device
     */
    @Column(name = "device_name", nullable = false, length = 255)
    private String deviceName;

    /**
     * Device model identifier
     */
    @Column(name = "model", length = 100)
    private String model;

    /**
     * IP address for local network devices
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Current online/offline status of the device
     */
    @Column(name = "is_online", nullable = false)
    private boolean isOnline;

    /**
     * Current powered on/off state of the device
     */
    @Column(name = "is_powered_on", nullable = false)
    private boolean isPoweredOn;

    /**
     * Comma-separated list of device capabilities (e.g., "SWITCH,DIMMER,COLOR,ENERGY_MONITOR")
     */
    @Column(name = "capabilities", length = 500)
    private String capabilities;

    /**
     * Device-specific metadata stored as JSON string.
     * <p>
     * Contains additional device properties that vary by device type.
     * Serialized/deserialized by the service layer.
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /**
     * Timestamp when this record was created in the database
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Automatically set creation timestamp before persisting
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Automatically update the updated timestamp before updating
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
