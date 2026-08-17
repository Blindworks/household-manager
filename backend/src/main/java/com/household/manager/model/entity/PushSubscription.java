package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Web-Push-Subscription eines Geraets (PWA). Ein Geraet = eine Zeile, Schluessel: endpoint. */
@Entity
@Table(name = "push_subscription")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500, unique = true)
    private String endpoint;

    @Column(name = "p256dh_key", nullable = false)
    private String p256dhKey;

    @Column(name = "auth_secret", nullable = false)
    private String authSecret;

    @Column(name = "device_label")
    private String deviceLabel;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
