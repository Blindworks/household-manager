package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Registrierter Bewohner fuer die Gesichtserkennung. */
@Entity
@Table(name = "vision_person")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionPerson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    /**
     * Inaktive Personen bleiben erhalten, werden aber nicht mehr erkannt.
     * Builder-Default true: Hibernate schreibt die Spalte immer mit, der
     * DB-Default greift also nie — ohne ihn entstuenden inaktive Personen.
     */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
