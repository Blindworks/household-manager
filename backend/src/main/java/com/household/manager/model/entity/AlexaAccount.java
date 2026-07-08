package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Persistiertes Amazon-Konto der Alexa-Integration. Es existiert hoechstens eine Zeile. */
@Entity
@Table(name = "alexa_account")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Langlebiges Refresh-Token; einziger dauerhaft gespeicherter Zugangsschluessel. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "amazon_domain", nullable = false, length = 64)
    private String amazonDomain;

    @Column(name = "account_name", length = 255)
    private String accountName;

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
