package com.household.manager.dirigera;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DirigeraSensor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Einzige Definition der Entity-Ids der Dirigera-Sensoren und Bau der Entity-Updates aus den
 * Letztwerten der {@link DirigeraSensor}-Zeile. Die Id haengt an der relationId des Hubs, nie am
 * Namen (Umbenennen ist fuer Flows gefahrlos). Bewusst kein deviceClass (projektweit nur fuer
 * door und power ausgewertet).
 */
@Component
public class DirigeraEntityMapper {

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

    public static String entityRef(String relationId) {
        return relationId.replace("-", "").toLowerCase(Locale.ROOT);
    }

    public static String entityId(String relationId, Measurement measurement) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.DIRIGERA, entityRef(relationId), measurement.suffix());
    }

    public List<EntityStateUpdate> updates(DirigeraSensor sensor) {
        List<EntityStateUpdate> result = new ArrayList<>(2);
        if (sensor.getLastTemperature() != null) {
            result.add(update(sensor, Measurement.TEMPERATURE, plain(sensor.getLastTemperature())));
        }
        if (sensor.getLastHumidity() != null) {
            result.add(update(sensor, Measurement.HUMIDITY, plain(sensor.getLastHumidity())));
        }
        return result;
    }

    /** unavailable MIT erhaltenen Attributen (EntityStateWriter.upsert ueberschreibt sonst alles). */
    public List<EntityStateUpdate> unavailable(DirigeraSensor sensor) {
        return List.of(
                update(sensor, Measurement.TEMPERATURE, STATE_UNAVAILABLE),
                update(sensor, Measurement.HUMIDITY, STATE_UNAVAILABLE));
    }

    private EntityStateUpdate update(DirigeraSensor sensor, Measurement measurement, String state) {
        return EntityStateUpdate.builder()
                .entityId(entityId(sensor.getRelationId(), measurement))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.DIRIGERA)
                .sourceRef(entityRef(sensor.getRelationId()))
                .friendlyName(displayName(sensor) + " " + measurement.label())
                .state(state)
                .attributes(attributes(sensor, measurement))
                .build();
    }

    private static String displayName(DirigeraSensor sensor) {
        if (!isBlank(sensor.getName())) {
            return sensor.getName();
        }
        return !isBlank(sensor.getModel()) ? sensor.getModel() : sensor.getRelationId();
    }

    private Map<String, Object> attributes(DirigeraSensor sensor, Measurement measurement) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("unit", measurement.unit());
        if (sensor.getBatteryPercent() != null) {
            attrs.put("batteryPercent", sensor.getBatteryPercent());
        }
        if (!isBlank(sensor.getRoom())) {
            attrs.put("room", sensor.getRoom());
        }
        if (!isBlank(sensor.getModel())) {
            attrs.put("model", sensor.getModel());
        }
        if (sensor.getLastSeenAt() != null) {
            attrs.put("lastSeenAt", sensor.getLastSeenAt().toString());
        }
        return attrs;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
