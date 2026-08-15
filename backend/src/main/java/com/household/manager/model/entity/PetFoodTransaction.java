package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Eine Bewegung im Futtervorrat. {@link #amount} ist die tatsaechlich wirksame,
 * vorzeichenbehaftete Bestandsaenderung (Fuetterung negativ, Einkauf positiv,
 * Korrektur als Differenz); {@link #occurredAt} ist der fachliche Zeitpunkt —
 * bei nachgeholten Fuetterungen der Fuetterungszeitpunkt, nicht die Laufzeit
 * des Schedulers.
 */
@Entity
@Table(name = "pet_food_transaction")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetFoodTransaction {

    public enum Type { FEEDING, PURCHASE, CORRECTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal amount;

    @Column(name = "cans_after", nullable = false, precision = 6, scale = 1)
    private BigDecimal cansAfter;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
