package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity representing polling settings for Tasmota devices.
 */
@Entity
@Table(name = "tasmota_polling_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaPollingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Key for the polling settings (e.g., ELECTRICITY, GAS, WATER).
     */
    @Column(name = "polling_key", nullable = false, unique = true, length = 50)
    private String pollingKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "interval_ms", nullable = false)
    private long intervalMs;

    @Column(name = "url", nullable = false, length = 512)
    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
