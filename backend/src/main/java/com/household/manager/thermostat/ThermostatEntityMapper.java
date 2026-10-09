package com.household.manager.thermostat;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.tapo.hub.ThermostatReading;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Bildet ein Thermostat auf seine drei Entitaeten ab:
 * {@code sensor.thermostat_<id>_temperature}, {@code sensor.thermostat_<id>_target} und
 * {@code binary_sensor.thermostat_<id>_heating}. {@link #entityId} ist die einzige
 * Id-Definition. Die Id haengt an der DB-Id, nicht am Namen: Umbenennen aendert sie nicht.
 *
 * <p>Bewusst KEIN {@code deviceClass}: projektweit wird er nur fuer "door" und "power"
 * ausgewertet, ein erfundener Wert waere schlechter als keiner (Begruendung siehe Blink).
 */
@Component
public class ThermostatEntityMapper {

    public static final String TEMPERATURE = "temperature";
    public static final String TARGET = "target";
    public static final String HEATING = "heating";

    private static final List<String> SUFFIXES = List.of(TEMPERATURE, TARGET, HEATING);

    public static String entityId(Thermostat thermostat, String suffix) {
        EntityDomain domain = HEATING.equals(suffix) ? EntityDomain.BINARY_SENSOR : EntityDomain.SENSOR;
        return EntityIds.build(domain, EntitySource.THERMOSTAT, String.valueOf(thermostat.getId()), suffix);
    }

    /**
     * @param previousAttributes liefert die zuletzt gespeicherten Attribute einer Entitaet, damit
     *                           {@code unavailable} sie erhaelt ({@code EntityStateWriter.upsert}
     *                           ueberschreibt Attribute sonst komplett)
     */
    public List<EntityStateUpdate> map(Thermostat thermostat, ThermostatReading reading,
                                       Function<String, Map<String, Object>> previousAttributes) {
        if (!reading.online()) {
            return unavailable(thermostat, previousAttributes);
        }
        Map<String, Object> attributes = attributes(reading);
        return List.of(
                update(thermostat, TEMPERATURE, SENSOR_NAME_TEMPERATURE, temperatureState(reading.currentTemp()),
                        withUnit(attributes)),
                update(thermostat, TARGET, SENSOR_NAME_TARGET, temperatureState(reading.targetTemp()),
                        withUnit(attributes)),
                update(thermostat, HEATING, SENSOR_NAME_HEATING, reading.heating() ? "on" : "off", attributes));
    }

    public List<EntityStateUpdate> unavailable(Thermostat thermostat,
                                               Function<String, Map<String, Object>> previousAttributes) {
        return SUFFIXES.stream()
                .map(suffix -> update(thermostat, suffix, friendlySuffix(suffix), "unavailable",
                        previousAttributes.apply(entityId(thermostat, suffix))))
                .toList();
    }

    private static final String SENSOR_NAME_TEMPERATURE = " Temperatur";
    private static final String SENSOR_NAME_TARGET = " Soll";
    private static final String SENSOR_NAME_HEATING = " heizt";

    private String friendlySuffix(String suffix) {
        return switch (suffix) {
            case TEMPERATURE -> SENSOR_NAME_TEMPERATURE;
            case TARGET -> SENSOR_NAME_TARGET;
            default -> SENSOR_NAME_HEATING;
        };
    }

    private EntityStateUpdate update(Thermostat thermostat, String suffix, String nameSuffix,
                                     String state, Map<String, Object> attributes) {
        EntityDomain domain = HEATING.equals(suffix) ? EntityDomain.BINARY_SENSOR : EntityDomain.SENSOR;
        return EntityStateUpdate.builder()
                .entityId(entityId(thermostat, suffix))
                .domain(domain)
                .source(EntitySource.THERMOSTAT)
                .sourceRef(String.valueOf(thermostat.getId()))
                .friendlyName(thermostat.getName() + nameSuffix)
                .state(state)
                .attributes(attributes == null ? Map.of() : attributes)
                .build();
    }

    private Map<String, Object> attributes(ThermostatReading reading) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("mode", reading.mode().name());
        attributes.put("batteryLow", reading.batteryLow());
        return attributes;
    }

    private Map<String, Object> withUnit(Map<String, Object> base) {
        Map<String, Object> attributes = new LinkedHashMap<>(base);
        attributes.put("unit", "°C");
        return attributes;
    }

    /** "21.0" -> "21": ohne Nachkomma-Null, damit der State wie bei den anderen Sensoren aussieht. */
    private String temperatureState(Double value) {
        if (value == null) {
            return "unknown";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
