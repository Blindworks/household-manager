package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Nutzungszähler einer schaltbaren Entität (eine Zeile je Entity-ID).
 * Grundlage für die nutzungsbasierte Sortierung der Schalter-Kachel.
 */
@Entity
@Table(name = "entity_usage")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Entity-ID der geschalteten Entität (Fremdschlüssel-Semantik, bewusst ohne FK-Constraint). */
    @Column(name = "entity_id", nullable = false, unique = true, length = 150)
    private String entityId;

    /** Anzahl erfolgreicher Schaltvorgänge. */
    @Column(name = "toggle_count", nullable = false)
    private long toggleCount;

    /** Zeitpunkt des letzten erfolgreichen Schaltvorgangs. */
    @Column(name = "last_toggled_at")
    private LocalDateTime lastToggledAt;
}
