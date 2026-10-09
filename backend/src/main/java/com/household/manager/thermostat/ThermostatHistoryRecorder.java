package com.household.manager.thermostat;

import com.household.manager.model.entity.Thermostat;
import com.household.manager.model.entity.ThermostatHistory;
import com.household.manager.repository.ThermostatHistoryRepository;
import com.household.manager.tapo.hub.ThermostatReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Schreibt einen Verlaufspunkt je Poll, aber nur bei Aenderung von Ist/Soll/Heizen oder wenn
 * seit {@value #HEARTBEAT_MINUTES} Minuten kein Punkt entstand. Wirft nie (Muster
 * {@code PowerHistoryRecorder}): derselbe Poll versorgt Entitaeten und API.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThermostatHistoryRecorder {

    static final int HEARTBEAT_MINUTES = 5;

    private final ThermostatHistoryRepository repository;

    public void record(Thermostat thermostat, ThermostatReading reading, LocalDateTime now) {
        if (!reading.online()) {
            return;
        }
        try {
            ThermostatHistory last = repository.findTopByThermostatIdOrderByRecordedAtDesc(thermostat.getId())
                    .orElse(null);
            if (last != null && !isDue(last, reading, now)) {
                return;
            }
            repository.save(ThermostatHistory.builder()
                    .thermostatId(thermostat.getId())
                    .recordedAt(now)
                    .currentTemp(reading.currentTemp())
                    .targetTemp(reading.targetTemp())
                    .heating(reading.heating())
                    .build());
        } catch (Exception ex) {
            log.warn("Thermostat-Verlauf fuer {} nicht gespeichert: {}", thermostat.getId(), ex.getMessage());
        }
    }

    private boolean isDue(ThermostatHistory last, ThermostatReading reading, LocalDateTime now) {
        boolean changed = !Objects.equals(last.getCurrentTemp(), reading.currentTemp())
                || !Objects.equals(last.getTargetTemp(), reading.targetTemp())
                || last.isHeating() != reading.heating();
        boolean heartbeatDue = !Duration.between(last.getRecordedAt(), now).minusMinutes(HEARTBEAT_MINUTES).isNegative();
        return changed || heartbeatDue;
    }
}
