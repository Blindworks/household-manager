package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Persistiertes Echo-Geraet. Identitaet ueber die stabile serialNumber, nie ueber IP/Reihenfolge. */
@Entity
@Table(name = "alexa_device")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "serial_number", nullable = false, unique = true, length = 128)
    private String serialNumber;

    @Column(name = "device_type", length = 64)
    private String deviceType;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** true, wenn das Geraet Text-to-Speech/Announcement unterstuetzt. */
    @Column(name = "tts_capable", nullable = false)
    private boolean ttsCapable;

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
