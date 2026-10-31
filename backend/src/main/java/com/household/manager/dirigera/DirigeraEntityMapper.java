package com.household.manager.dirigera;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DirigeraSensor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Einzige Definition der Entity-Ids der Dirigera-Sensoren und Bau der Entity-Updates aus den
 * Letztwerten der {@link DirigeraSensor}-Zeile. Die Id haengt an der relationId des Hubs, nie am
 * Namen (Umbenennen ist fuer Flows gefahrlos). Thermometer bewusst ohne deviceClass (projektweit
 * nur fuer door und power ausgewertet); Kontakte tragen dagegen {@code door} wie die
 * Zigbee-Kontakte, damit der Fenster-Check vor Abwesend/Toni allein sie mitzaehlt.
 */
@Component
public class DirigeraEntityMapper {

    public static final String STATE_UNAVAILABLE = "unavailable";
    private static final String CONTACT_SUFFIX = "contact";
    private static final String CONTACT_LABEL = "Kontakt";
    private static final String CONTACT_DEVICE_CLASS = "door";

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

    public static String contactEntityId(String relationId) {
        return EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.DIRIGERA, entityRef(relationId), CONTACT_SUFFIX);
    }

    /** Alle Entity-Ids, die zu dieser Zeile gehoeren (je nach Art). */
    public static List<String> entityIds(DirigeraSensor sensor) {
        if (sensor.getKind() == DirigeraSensorKind.CONTACT) {
            return List.of(contactEntityId(sensor.getRelationId()));
        }
        return Arrays.stream(Measurement.values())
                .map(measurement -> entityId(sensor.getRelationId(), measurement))
                .toList();
    }

    public List<EntityStateUpdate> updates(DirigeraSensor sensor) {
        if (sensor.getKind() == DirigeraSensorKind.CONTACT) {
            // ohne bekannten Zustand nichts melden, nie ein geratenes "zu"
            return sensor.getLastOpen() == null
                    ? List.of()
                    : List.of(contactUpdate(sensor, sensor.getLastOpen() ? "on" : "off"));
        }
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
        if (sensor.getKind() == DirigeraSensorKind.CONTACT) {
            return List.of(contactUpdate(sensor, STATE_UNAVAILABLE));
        }
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

    private EntityStateUpdate contactUpdate(DirigeraSensor sensor, String state) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("deviceClass", CONTACT_DEVICE_CLASS);
        attrs.putAll(commonAttributes(sensor));
        return EntityStateUpdate.builder()
                .entityId(contactEntityId(sensor.getRelationId()))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.DIRIGERA)
                .sourceRef(entityRef(sensor.getRelationId()))
                .friendlyName(displayName(sensor) + " " + CONTACT_LABEL)
                .state(state)
                .attributes(attrs)
                .build();
    }

    /** Anzeigename: Name, sonst Modell, sonst relationId (auch von der Temperaturansicht genutzt). */
    public static String displayName(DirigeraSensor sensor) {
        if (!isBlank(sensor.getName())) {
            return sensor.getName();
        }
        return !isBlank(sensor.getModel()) ? sensor.getModel() : sensor.getRelationId();
    }

    private Map<String, Object> attributes(DirigeraSensor sensor, Measurement measurement) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("unit", measurement.unit());
        attrs.putAll(commonAttributes(sensor));
        return attrs;
    }

    private Map<String, Object> commonAttributes(DirigeraSensor sensor) {
        Map<String, Object> attrs = new LinkedHashMap<>();
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
