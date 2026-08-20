package com.household.manager.model.entity;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Aktueller Zustand einer generischen Entität (Spiegel-Schicht über den Integrationen).
 * Eine Zeile pro Entität; Historie liegt in den Fachtabellen der Integrationen.
 */
@Entity
@Table(name = "entity_states")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Eindeutige, stabile Entity-ID, z. B. "sensor.zigbee_wohnzimmer_temperature". */
    @Column(name = "entity_id", nullable = false, unique = true, length = 150)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 20)
    private EntityDomain domain;

    /** Anzeigename; wird bei jedem Update mitaktualisiert. */
    @Column(name = "friendly_name", nullable = false, length = 255)
    private String friendlyName;

    /** Optionaler, vom Benutzer gesetzter Kurzname. Wird vom Polling-Upsert nie überschrieben. */
    @Column(name = "custom_name", length = 255)
    private String customName;

    /**
     * Bestätigungspflicht beim AUSschalten (reiner UI-Schutz in Dashboard und Geräteliste;
     * Einschalten läuft immer direkt, Flows/Telegram/API schalten ungefragt).
     * Benutzergepflegt wie {@link #customName}; wird vom Polling-Upsert nie überschrieben.
     */
    @Column(name = "confirm_required", nullable = false)
    private boolean confirmRequired;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private EntitySource source;

    /** Stabile ID im Quellsystem (deviceId, Seriennummer, Sensor-ID). */
    @Column(name = "source_ref", nullable = false, length = 255)
    private String sourceRef;

    /** Aktueller Zustand als String ("on", "21.5", "unavailable", "unknown"). */
    @Column(name = "state", nullable = false, length = 255)
    private String state;

    /** Attribute als JSON-String (unit, deviceClass, Zusatzwerte). */
    @Column(name = "attributes", columnDefinition = "TEXT")
    private String attributes;

    /** Zeitpunkt der letzten Wertänderung. */
    @Column(name = "last_changed", nullable = false)
    private LocalDateTime lastChanged;

    /** Zeitpunkt des letzten Updates (auch ohne Wertänderung). */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
