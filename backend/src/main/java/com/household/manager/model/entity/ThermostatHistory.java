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

import java.time.LocalDateTime;

/**
 * Ein Verlaufspunkt eines Thermostats. Der Fremdschluessel (ON DELETE CASCADE) lebt in der
 * Datenbank; die Entity haelt nur die Id, damit kein Thermostat mitgeladen wird.
 */
@Entity
@Table(name = "thermostat_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThermostatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thermostat_id", nullable = false)
    private Long thermostatId;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "current_temp")
    private Double currentTemp;

    @Column(name = "target_temp")
    private Double targetTemp;

    @Column(name = "heating", nullable = false)
    private boolean heating;
}
