package com.household.manager.tractive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * Persistiertes Tractive-Zugangstoken. Es gibt hoechstens eine Zeile ({@link #SINGLETON_ID}).
 * Zugangsdaten werden bewusst nicht gespeichert – Tractive kennt kein Refresh-Token,
 * nach Ablauf ist ein erneuter Login noetig.
 */
@Entity
@Table(name = "tractive_auth")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TractiveAuth {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @ToString.Exclude
    @Column(name = "access_token", nullable = false, length = 1024)
    private String accessToken;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(nullable = false)
    private String email;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
