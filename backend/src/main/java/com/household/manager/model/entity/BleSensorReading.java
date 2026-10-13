package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Ein Verlaufspunkt eines Bluetooth-Thermometers. Der Fremdschluessel (ON DELETE CASCADE)
 * lebt in der Datenbank; die Entity haelt nur die Id (Muster ThermostatHistory).
 */
@Entity
@Table(name = "ble_sensor_reading")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BleSensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", nullable = false)
    private Long sensorId;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "battery_mv")
    private Integer batteryMv;
}
