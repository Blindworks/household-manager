package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein Umgebungssensor am IKEA-Dirigera-Hub (TIMMERFLOTTE). Die Zeile haengt an der Geraete-Id des
 * Hubs (relationId); verschwindet das Geraet dort, bleibt sie mit knownToHub = false stehen,
 * damit die Historie nicht verloren geht.
 */
@Entity
@Table(name = "dirigera_sensor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirigeraSensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "relation_id", nullable = false, length = 64, unique = true)
    private String relationId;

    @Column(length = 100)
    private String name;

    @Column(length = 100)
    private String room;

    @Column(length = 100)
    private String model;

    @Column(name = "last_temperature", precision = 5, scale = 2)
    private BigDecimal lastTemperature;

    @Column(name = "last_humidity", precision = 5, scale = 2)
    private BigDecimal lastHumidity;

    @Column(name = "battery_percent")
    private Integer batteryPercent;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean reachable = false;

    @Column(name = "known_to_hub", nullable = false)
    @Builder.Default
    private boolean knownToHub = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
