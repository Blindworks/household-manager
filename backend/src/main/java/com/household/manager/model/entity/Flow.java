package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ein Automatisierungs-Flow (Node-RED-Stil): Graph aus Nodes und Wires als JSON.
 * draft = Arbeitsstand des Editors, deployed = von der Engine ausgeführte Version.
 */
@Entity
@Table(name = "flows")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Flow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    /** Kill-Switch: deaktivierte Flows werden nicht ausgeführt. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Arbeitsstand des Editors (JSON, nebenwirkungsfrei speicherbar). */
    @Column(name = "draft_definition", columnDefinition = "LONGTEXT")
    private String draftDefinition;

    /** Von der Engine ausgeführte Version (JSON); NULL = nie deployt. */
    @Column(name = "deployed_definition", columnDefinition = "LONGTEXT")
    private String deployedDefinition;

    @Column(name = "deployed_at")
    private LocalDateTime deployedAt;

    /**
     * Wann ein Trigger des Flows zuletzt ausgelöst hat. Schreibt ausschließlich
     * {@code FlowTriggerRecorder} per Bulk-Update — deshalb für JPA nicht
     * schreibbar: ein {@code save()} eines zuvor geladenen Flows (Editor-Speichern,
     * Deploy) würde sonst einen zwischenzeitlich gesetzten Zeitpunkt mit dem
     * alten Wert überschreiben.
     */
    @Column(name = "last_triggered_at", insertable = false, updatable = false)
    private LocalDateTime lastTriggeredAt;

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
