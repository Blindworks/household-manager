package com.household.manager.dirigera;

import com.household.manager.model.entity.DirigeraSensor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/** Antwort- und Anfrage-Typen der Dirigera-Admin-API. Bewusst ohne Token und Fingerabdruck. */
public final class DirigeraDtos {

    private DirigeraDtos() {
    }

    public record HubView(String host, boolean paired, String hubId, Instant lastSuccessAt, String lastError) {
    }

    public record HostRequest(String host) {
    }

    public record SensorView(Long id, DirigeraSensorKind kind, String name, String room, String model,
                             BigDecimal temperature, BigDecimal humidity, Boolean open, Integer batteryPercent,
                             boolean reachable, boolean knownToHub, LocalDateTime lastSeenAt) {

        static SensorView from(DirigeraSensor sensor) {
            return new SensorView(sensor.getId(), sensor.getKind(), sensor.getName(), sensor.getRoom(),
                    sensor.getModel(), sensor.getLastTemperature(), sensor.getLastHumidity(), sensor.getLastOpen(),
                    sensor.getBatteryPercent(), sensor.isReachable(), sensor.isKnownToHub(), sensor.getLastSeenAt());
        }
    }
}
