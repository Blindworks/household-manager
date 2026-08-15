package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Der Toni-Futtervorrat als Ein-Zeilen-Tabelle (id fest 1, per Liquibase geseedet).
 * {@link #deductionMarker} ist die Hochwassermarke der automatischen Abzuege als
 * Instant: bis zu diesem Zeitpunkt sind alle Fuetterungen (7:00/16:00) verbucht.
 * NULL bedeutet Erstinbetriebnahme — der erste Lauf setzt die Marke ohne Abzug,
 * sonst wuerde ab Epochenbeginn nachgeholt.
 */
@Entity
@Table(name = "pet_food_stock")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetFoodStock {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "cans_remaining", nullable = false, precision = 6, scale = 1)
    private BigDecimal cansRemaining;

    @Column(name = "target_cans", nullable = false, precision = 6, scale = 1)
    private BigDecimal targetCans;

    @Column(name = "deduction_marker")
    private Instant deductionMarker;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
