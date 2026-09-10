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

import java.time.LocalTime;

/**
 * Zeitfenster, in dem ein Haus-Modus im Tablet-Dashboard direkt als Knopf steht.
 *
 * <p>Bewusst ohne Fremdschluessel auf {@code entity_states} (Muster
 * {@code entity_tile_visibility}): die Zugehoerigkeit haengt allein an der stabilen
 * Entity-ID. Ein Fenster fuer einen Modus, den es nicht mehr gibt, bleibt wirkungslos
 * stehen; die Admin-Seite macht das sichtbar, indem sie dann die rohe ID zeigt.
 */
@Entity
@Table(name = "mode_quick_access")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModeQuickAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false, length = 255, unique = true)
    private String entityId;

    /** Beginn des Fensters, inklusive. */
    @Column(name = "from_time", nullable = false)
    private LocalTime fromTime;

    /** Ende des Fensters, exklusiv. Liegt es vor {@link #fromTime}, ueberspannt das Fenster Mitternacht. */
    @Column(name = "to_time", nullable = false)
    private LocalTime toTime;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
