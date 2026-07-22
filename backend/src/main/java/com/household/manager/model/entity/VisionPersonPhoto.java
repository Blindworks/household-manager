package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Referenzfoto eines Bewohners inkl. vom Sidecar berechnetem Embedding (JSON-Float-Array). */
@Entity
@Table(name = "vision_person_photo")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionPersonPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Lob
    @Column(name = "photo", nullable = false)
    private byte[] photo;

    /** Gesichts-Embedding als JSON-Array (z. B. "[0.01, -0.2, ...]"), Quelle: Sidecar. */
    @Column(name = "embedding", nullable = false, columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
