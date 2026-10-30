package com.household.manager.dirigera;

import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.DirigeraSensorReading;
import com.household.manager.repository.DirigeraSensorReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Schreibt einen Verlaufspunkt nur bei Aenderung von Temperatur oder Feuchte oder wenn seit
 * {@value #HEARTBEAT_MINUTES} Minuten keiner entstand. Wirft nie (Muster BleSensorReadingRecorder):
 * derselbe Poll versorgt die Entitaeten.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DirigeraReadingRecorder {

    static final int HEARTBEAT_MINUTES = 5;

    private final DirigeraSensorReadingRepository repository;

    public void record(DirigeraSensor sensor, LocalDateTime measuredAt) {
        try {
            DirigeraSensorReading last = repository.findTopBySensorIdOrderByMeasuredAtDesc(sensor.getId()).orElse(null);
            if (last != null && !isDue(last, sensor, measuredAt)) {
                return;
            }
            repository.save(DirigeraSensorReading.builder()
                    .sensorId(sensor.getId())
                    .measuredAt(measuredAt)
                    .temperature(sensor.getLastTemperature())
                    .humidity(sensor.getLastHumidity())
                    .build());
        } catch (Exception ex) {
            log.warn("Dirigera-Verlauf fuer {} nicht gespeichert: {}", sensor.getRelationId(), ex.getMessage());
        }
    }

    private boolean isDue(DirigeraSensorReading last, DirigeraSensor sensor, LocalDateTime now) {
        boolean changed = !sameValue(last.getTemperature(), sensor.getLastTemperature())
                || !sameValue(last.getHumidity(), sensor.getLastHumidity());
        boolean heartbeatDue = !Duration.between(last.getMeasuredAt(), now).minusMinutes(HEARTBEAT_MINUTES).isNegative();
        return changed || heartbeatDue;
    }

    /** compareTo statt equals: 18.79 und 18.790 sind derselbe Wert. */
    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
