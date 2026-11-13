package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Grundriss-Position eines Geraets im Digitalen Zwilling (Plan-Einheiten 0-500). Die
 * {@code deviceId} ist die Frontend-Id aus {@code house-devices.ts}; bewusst ohne
 * Fremdschluessel, die Zuordnung haengt allein an dieser Id.
 */
@Entity
@Table(name = "house_device_position")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HouseDevicePosition {

    @Id
    @Column(name = "device_id", length = 100, nullable = false)
    private String deviceId;

    @Column(name = "plan_x", nullable = false, precision = 6, scale = 1)
    private BigDecimal planX;

    @Column(name = "plan_y", nullable = false, precision = 6, scale = 1)
    private BigDecimal planY;

    /** Drehung des Geraetekoerpers in Grad (0/90/180/270), null = automatisch zur Wand. */
    @Column(name = "rotation_deg")
    private Integer rotationDeg;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
