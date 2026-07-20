package com.household.manager.model.entity;

import com.household.manager.entitystate.TileVisibility;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Benutzergepflegte Sichtbarkeitsregel einer Entität auf einer Dashboard-Kachel.
 * Eine Zeile je (Entität, Kachel); kein Eintrag bedeutet AUTO. Wird ausschließlich
 * benutzerinitiiert geschrieben, nie vom Polling-Upsert der Integrationen.
 */
@Entity
@Table(name = "entity_tile_visibility")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityTileVisibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Entity-ID der Spiegel-Schicht (Fremdschlüssel-Semantik, bewusst ohne FK-Constraint). */
    @Column(name = "entity_id", nullable = false, length = 150)
    private String entityId;

    /** Stabiler Kachel-Schlüssel, siehe {@link com.household.manager.entitystate.DashboardTiles}. */
    @Column(name = "tile_key", nullable = false, length = 50)
    private String tileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private TileVisibility visibility;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
