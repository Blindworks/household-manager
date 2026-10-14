package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Einzige Definition der Entity-Ids der Bluetooth-Thermometer und Bau der Entity-Updates aus
 * den Letztwerten der {@link BleSensor}-Zeile. Die Id haengt an der MAC, nie am Namen.
 * Bewusst kein deviceClass (projektweit nur fuer door und power ausgewertet).
 */
@Component
public class BleSensorEntityMapper {

    public static final String STATE_UNAVAILABLE = "unavailable";

    public enum Measurement {
        TEMPERATURE("temperature", "°C", "Temperatur"),
        HUMIDITY("humidity", "%", "Luftfeuchte");

        private final String suffix;
        private final String unit;
        private final String label;

        Measurement(String suffix, String unit, String label) {
            this.suffix = suffix;
            this.unit = unit;
            this.label = label;
        }

        public String suffix() {
            return suffix;
        }

        public String unit() {
            return unit;
        }

        public String label() {
            return label;
        }
    }

    public static String entityId(String mac, Measurement measurement) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.BLE, BleMacs.entityRef(mac), measurement.suffix());
    }

    public List<EntityStateUpdate> updates(BleSensor sensor) {
        List<EntityStateUpdate> result = new ArrayList<>(2);
        if (sensor.getLastTemperature() != null) {
            result.add(update(sensor, Measurement.TEMPERATURE, plain(sensor.getLastTemperature())));
        }
        if (sensor.getLastHumidity() != null) {
            result.add(update(sensor, Measurement.HUMIDITY, plain(sensor.getLastHumidity())));
        }
        return result;
    }

    /** unavailable MIT erhaltenen Attributen aus den Letztwerten der Zeile (EntityStateWriter.upsert ueberschreibt sonst alles). */
    public List<EntityStateUpdate> unavailable(BleSensor sensor) {
        return List.of(
                update(sensor, Measurement.TEMPERATURE, STATE_UNAVAILABLE),
                update(sensor, Measurement.HUMIDITY, STATE_UNAVAILABLE));
    }

    private EntityStateUpdate update(BleSensor sensor, Measurement measurement, String state) {
        String displayName = sensor.getName() != null && !sensor.getName().isBlank() ? sensor.getName() : sensor.getMac();
        return EntityStateUpdate.builder()
                .entityId(entityId(sensor.getMac(), measurement))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.BLE)
                .sourceRef(BleMacs.entityRef(sensor.getMac()))
                .friendlyName(displayName + " " + measurement.label())
                .state(state)
                .attributes(attributes(sensor, measurement))
                .build();
    }

    private Map<String, Object> attributes(BleSensor sensor, Measurement measurement) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("unit", measurement.unit());
        Integer percent = BleBatteryEstimator.percent(sensor.getLastBatteryMv());
        if (percent != null) {
            attrs.put("batteryPercent", percent);
            attrs.put("batteryMillivolts", sensor.getLastBatteryMv());
        }
        if (sensor.getLastRssi() != null) {
            attrs.put("rssi", sensor.getLastRssi());
        }
        if (sensor.getTabletId() != null) {
            attrs.put("tabletId", sensor.getTabletId());
        }
        return attrs;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
