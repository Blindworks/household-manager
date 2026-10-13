package com.household.manager.blesensor;

import com.household.manager.model.entity.BleSensor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Request-/Response-Records des Moduls. */
public final class BleSensorDtos {

    private BleSensorDtos() {
    }

    /** Sammelmeldung des Tablets. */
    public record ReadingsRequest(String tabletId, List<ReadingItem> readings) {
    }

    public record ReadingItem(String mac, BigDecimal temperature, BigDecimal humidity,
                              Integer batteryMillivolts, Integer rssi, Long ageSeconds) {
    }

    /** Admin-Liste: auch nicht uebernommene Sensoren, zum Wiedererkennen mit Letztwerten. */
    public record SensorAdminResponse(Long id, String mac, String name, boolean active, String tabletId,
                                      LocalDateTime lastSeenAt, BigDecimal lastTemperature, BigDecimal lastHumidity,
                                      Integer batteryPercent, Integer lastRssi) {

        public static SensorAdminResponse from(BleSensor s) {
            return new SensorAdminResponse(s.getId(), s.getMac(), s.getName(), s.isActive(), s.getTabletId(),
                    s.getLastSeenAt(), s.getLastTemperature(), s.getLastHumidity(),
                    BleBatteryEstimator.percent(s.getLastBatteryMv()), s.getLastRssi());
        }
    }

    /** Voll-PUT: fehlendes active gilt als true (Muster Netzwerk-Geraete). */
    public record SensorUpdateRequest(String name, Boolean active) {
    }
}
