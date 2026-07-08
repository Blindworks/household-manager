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
import java.util.Set;

/**
 * Übersetzt eine geparste zigbee2mqtt-Nachricht in Sensor-/Binärsensor-Entitäten
 * (eine Entität pro Messgröße).
 * <p>
 * Identität: Entitäten werden — wie die bestehende Zigbee-Integration selbst
 * (ZigbeeDevice.findByFriendlyName) — über den zigbee2mqtt-friendlyName gebildet.
 * Zwei Geräte, deren Namen auf denselben Slug abbilden, würden kollidieren;
 * das ist eine bewusst geteilte Annahme mit dem bestehenden Identitätsmodell.
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

    public List<EntityStateUpdate> map(ParsedZigbeeMessage message) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        for (ZigbeeMeasurementValue value : message.measurements()) {
            boolean binary = BINARY_TYPES.contains(value.type());
            EntityDomain domain = binary ? EntityDomain.BINARY_SENSOR : EntityDomain.SENSOR;

            Map<String, Object> attributes = new HashMap<>();
            if (value.unit() != null && !value.unit().isBlank()) {
                attributes.put("unit", value.unit());
            }
            attributes.put("deviceClass", value.type().name().toLowerCase(java.util.Locale.ROOT));
            if (message.batteryPercent() != null) {
                attributes.put("batteryPercent", message.batteryPercent());
            }
            if (message.linkQuality() != null) {
                attributes.put("linkQuality", message.linkQuality());
            }

            String state = binary ? toOnOff(value.value()) : value.value().toPlainString();
            String suffix = value.type().name().toLowerCase(java.util.Locale.ROOT);

            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(domain, EntitySource.ZIGBEE, message.friendlyName(), suffix))
                    .domain(domain)
                    .source(EntitySource.ZIGBEE)
                    .sourceRef(message.friendlyName())
                    .friendlyName(message.friendlyName() + " " + GERMAN_LABELS.get(value.type()))
                    .state(state)
                    .attributes(attributes)
                    .build());
        }
        return updates;
    }

    private String toOnOff(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) != 0 ? "on" : "off";
    }
}
