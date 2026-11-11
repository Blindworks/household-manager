package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fenster oder Tuer auf einer Wand im Digitalen Zwilling: waagerechte oder senkrechte Strecke
 * (Plan-Einheiten 0-500) auf einer Etage. {@code deviceId} ordnet optional den Kontakt aus
 * {@code house-devices.ts} zu (bewusst ohne Fremdschluessel).
 */
@Entity
@Table(name = "house_opening")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HouseOpening {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String floor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HouseOpeningKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HouseOpeningSize size;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal x1;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal y1;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal x2;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal y2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private HouseOpeningHinge hinge;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private HouseOpeningSwing swing;

    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
