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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Ein Vorrat fuer Toni — eine Zeile je Artikel (Futter, VomiSan-Tabletten).
 * <p>
 * {@link #supplyKey} adressiert den Vorrat in der API UND traegt seine Entity-Id
 * ({@code sensor.pet_food_<supplyKey>}). Der Schluessel des Futters lautet
 * deshalb {@code toni_cans} — daraus entsteht buchstaeblich die bestehende Id
 * {@code sensor.pet_food_toni_cans}. Wer einen Schluessel aendert, aendert die
 * Entity-Id, und ein darauf gebauter Flow laeuft danach still ins Leere.
 * <p>
 * {@link #perFeeding} ist die Menge, die zu JEDER Fuetterungszeit abgezogen wird
 * (Futter 0,5 Dosen, Tabletten 1 Stueck); {@link #stepSize} ist das Eingaberaster
 * der API — halbe Dosen sind erlaubt, halbe Tabletten nicht.
 * <p>
 * {@link #deductionMarker} ist die Hochwassermarke der automatischen Abzuege als
 * Instant: bis zu diesem Zeitpunkt sind alle Fuetterungen (7:00/16:00) dieses
 * Vorrats verbucht. Sie ist bewusst JE VORRAT eigen — ein neu angelegter Vorrat
 * startet mit NULL, und der erste Lauf setzt nur die Marke ohne Abzug. Eine
 * gemeinsame Marke wuerde einen spaeter ergaenzten Vorrat rueckwirkend ab dem
 * Deploy des ersten leerbuchen. Die Marke wird sekundengenau abgeschnitten
 * gespeichert — MariaDB wuerde Bruchsekunden in DATETIME sonst RUNDEN und
 * koennte die Marke in die Zukunft schieben (verlorene Fuetterung).
 * <p>
 * Instant statt Wandzeit: Hibernate konvertiert Instant&harr;DATETIME ueber die
 * JVM-Zeitzone, die nur wegen TZ=Europe/Berlin im Docker-Deployment stabil ist
 * (siehe ClockConfig und die dokumentierte UTC-Falle). Instant ist hier trotzdem
 * noetig, weil die Berliner Wandzeit bei der Oktober-Zeitumstellung nicht monoton
 * ist — eine Wandzeit-Marke wuerde die wiederholte Stunde doppelt abziehen.
 */
@Entity
@Table(name = "pet_supply")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetSupply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supply_key", nullable = false, length = 50, unique = true)
    private String supplyKey;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(name = "amount_remaining", nullable = false, precision = 6, scale = 1)
    private BigDecimal amountRemaining;

    @Column(name = "target_amount", nullable = false, precision = 6, scale = 1)
    private BigDecimal targetAmount;

    @Column(name = "per_feeding", nullable = false, precision = 6, scale = 1)
    private BigDecimal perFeeding;

    @Column(name = "step_size", nullable = false, precision = 6, scale = 1)
    private BigDecimal stepSize;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

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
