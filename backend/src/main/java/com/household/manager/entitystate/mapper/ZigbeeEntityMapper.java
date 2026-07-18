package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.*;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Übersetzt eine geparste zigbee2mqtt-Nachricht in Sensor-/Binärsensor-Entitäten
 * (eine Entität pro Messgröße).
 * <p>
 * Identität: Entitäten werden — wie die bestehende Zigbee-Integration selbst
 * (ZigbeeDevice.findByFriendlyName) — über den zigbee2mqtt-friendlyName gebildet.
 * Zwei Geräte, deren Namen auf denselben Slug abbilden, würden kollidieren;
 * das ist eine bewusst geteilte Annahme mit dem bestehenden Identitätsmodell.
 * <p>
 * Binärzustands-Semantik folgt der HA-Konvention "on = offen/erkannt":
 * für CONTACT ist das gegenüber dem rohen zigbee2mqtt-Wert invertiert
 * (zigbee2mqtt: {@code contact=true} = Magnet anliegend = Tür GESCHLOSSEN),
 * für OCCUPANCY/WATER_LEAK stimmt truthy=erkannt bereits mit HA überein.
 */
@Component
public class ZigbeeEntityMapper {

    private static final Set<MeasurementType> BINARY_TYPES =
            EnumSet.of(MeasurementType.CONTACT, MeasurementType.OCCUPANCY, MeasurementType.WATER_LEAK);

    private static final Map<MeasurementType, String> GERMAN_LABELS = Map.of(
            MeasurementType.TEMPERATURE, "Temperatur",
            MeasurementType.HUMIDITY, "Luftfeuchtigkeit",
            MeasurementType.PRESSURE, "Luftdruck",
            MeasurementType.CONTACT, "Kontakt",
            MeasurementType.OCCUPANCY, "Bewegung",
            MeasurementType.ILLUMINANCE, "Helligkeit",
            MeasurementType.WATER_LEAK, "Wasserleck"
    );

    private static final Map<MeasurementType, String> HA_DEVICE_CLASSES = Map.of(
            MeasurementType.TEMPERATURE, "temperature",
            MeasurementType.HUMIDITY, "humidity",
            MeasurementType.PRESSURE, "pressure",
            MeasurementType.CONTACT, "door",
            MeasurementType.OCCUPANCY, "motion",
            MeasurementType.ILLUMINANCE, "illuminance",
            MeasurementType.WATER_LEAK, "moisture"
    );

    public List<EntityStateUpdate> map(ParsedZigbeeMessage message) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        for (ZigbeeMeasurementValue value : message.measurements()) {
            boolean binary = BINARY_TYPES.contains(value.type());
            EntityDomain domain = binary ? EntityDomain.BINARY_SENSOR : EntityDomain.SENSOR;

            Map<String, Object> attributes = new HashMap<>();
            if (value.unit() != null && !value.unit().isBlank()) {
                attributes.put("unit", value.unit());
            }
            attributes.put("deviceClass",
                    HA_DEVICE_CLASSES.getOrDefault(value.type(), value.type().name().toLowerCase(java.util.Locale.ROOT)));
            if (message.batteryPercent() != null) {
                attributes.put("batteryPercent", message.batteryPercent());
            }
            if (message.linkQuality() != null) {
                attributes.put("linkQuality", message.linkQuality());
            }

            String state = binary ? toOnOff(value.type(), value.value()) : value.value().toPlainString();
            String suffix = value.type().name().toLowerCase(java.util.Locale.ROOT);

            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(domain, EntitySource.ZIGBEE, message.friendlyName(), suffix))
                    .domain(domain)
                    .source(EntitySource.ZIGBEE)
                    .sourceRef(message.friendlyName())
                    .friendlyName(message.friendlyName() + " " + GERMAN_LABELS.getOrDefault(value.type(), value.type().name()))
                    .state(state)
                    .attributes(attributes)
                    .build());
        }
        return updates;
    }

    /**
     * Erzeugt aus einer Taster-Aktion eine EVENT-Entität ("<Name> Taster").
     * Leer, wenn die Nachricht keine Aktion enthält. Konsumenten melden das
     * Ergebnis über {@code EntityStateService.reportEvent} (nicht reportState).
     */
    public Optional<EntityStateUpdate> mapAction(ParsedZigbeeMessage message) {
        if (message.action() == null) {
            return Optional.empty();
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", "button");
        if (message.batteryPercent() != null) {
            attributes.put("batteryPercent", message.batteryPercent());
        }
        if (message.linkQuality() != null) {
            attributes.put("linkQuality", message.linkQuality());
        }
        return Optional.of(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.EVENT, EntitySource.ZIGBEE, message.friendlyName(), "action"))
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef(message.friendlyName())
                .friendlyName(message.friendlyName() + " Taster")
                .state(message.action())
                .attributes(attributes)
                .build());
    }

    private String toOnOff(MeasurementType type, BigDecimal value) {
        boolean truthy = value != null && value.compareTo(BigDecimal.ZERO) != 0;
        if (type == MeasurementType.CONTACT) {
            // zigbee2mqtt: contact=true = Magnet anliegend = Tür geschlossen.
            // HA-Konvention fuer binary_sensor.door: on = offen -> invertieren.
            return truthy ? "off" : "on";
        }
        // OCCUPANCY / WATER_LEAK: truthy = erkannt = "on" (HA-konform, keine Inversion).
        return truthy ? "on" : "off";
    }
}
