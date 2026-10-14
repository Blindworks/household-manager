package com.household.manager.blesensor;

import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.BleSensorReading;
import com.household.manager.repository.BleSensorReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Schreibt einen Verlaufspunkt nur bei Aenderung von Temperatur oder Feuchte oder wenn seit
 * {@value #HEARTBEAT_MINUTES} Minuten keiner entstand — hoechstens ein Punkt pro Minute und
 * Sensor. Wirft nie (Muster ThermostatHistoryRecorder): dieselbe Meldung versorgt die Entitaeten.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BleSensorReadingRecorder {

    static final int HEARTBEAT_MINUTES = 5;

    private final BleSensorReadingRepository repository;

    public void record(BleSensor sensor, LocalDateTime measuredAt) {
        try {
            BleSensorReading last = repository.findTopBySensorIdOrderByMeasuredAtDesc(sensor.getId()).orElse(null);
            if (last != null && !isDue(last, sensor, measuredAt)) {
                return;
            }
            repository.save(BleSensorReading.builder()
                    .sensorId(sensor.getId())
                    .measuredAt(measuredAt)
                    .temperature(sensor.getLastTemperature())
                    .humidity(sensor.getLastHumidity())
                    .batteryMv(sensor.getLastBatteryMv())
                    .build());
        } catch (Exception ex) {
            log.warn("BLE-Verlauf fuer {} nicht gespeichert: {}", sensor.getMac(), ex.getMessage());
        }
    }

    private boolean isDue(BleSensorReading last, BleSensor sensor, LocalDateTime now) {
        boolean changed = !sameValue(last.getTemperature(), sensor.getLastTemperature())
                || !sameValue(last.getHumidity(), sensor.getLastHumidity());
        boolean heartbeatDue = !Duration.between(last.getMeasuredAt(), now).minusMinutes(HEARTBEAT_MINUTES).isNegative();
        return changed || heartbeatDue;
    }

    /** compareTo statt equals: 21.5 und 21.50 sind derselbe Wert. */
    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
