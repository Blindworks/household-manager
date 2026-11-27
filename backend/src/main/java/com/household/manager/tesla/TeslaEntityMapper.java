package com.household.manager.tesla;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bildet ein Fahrzeug auf Entitäten ab (Id hängt an der VIN, Umbenennen in der
 * Tesla-App ist für Flows gefahrlos):
 * {@code sensor.tesla_<vin>_battery} (%), {@code sensor.tesla_<vin>_range} (km),
 * {@code sensor.tesla_<vin>_charging} (Ladezustand klein geschrieben, z. B. {@code charging},
 * {@code complete}, {@code disconnected}) und {@code lock.tesla_<vin>} ({@code locked}/{@code unlocked}).
 * Bewusst kein {@code deviceClass} (siehe Blink). Ohne bekannten Wert wird die Entität nicht gemeldet.
 */
@Component
public class TeslaEntityMapper {

    public List<EntityStateUpdate> map(TeslaVehicleSnapshot vehicle, Boolean atHome) {
        String ref = vehicle.vin();
        String name = vehicle.displayName() != null ? vehicle.displayName() : "Tesla";
        Map<String, Object> attributes = attributes(vehicle, atHome);
        List<EntityStateUpdate> updates = new ArrayList<>();

        if (vehicle.batteryLevel() != null) {
            updates.add(update(EntityDomain.SENSOR, ref, "battery", name + " Akku",
                    String.valueOf(vehicle.batteryLevel()), withUnit(attributes, "%")));
        }
        if (vehicle.rangeKm() != null) {
            updates.add(update(EntityDomain.SENSOR, ref, "range", name + " Reichweite",
                    String.valueOf(Math.round(vehicle.rangeKm())), withUnit(attributes, "km")));
        }
        if (vehicle.chargingState() != null) {
            updates.add(update(EntityDomain.SENSOR, ref, "charging", name + " Laden",
                    vehicle.chargingState().toLowerCase(Locale.ROOT), attributes));
        }
        if (vehicle.locked() != null) {
            updates.add(update(EntityDomain.LOCK, ref, null, name,
                    vehicle.locked() ? "locked" : "unlocked", attributes));
        }
        return updates;
    }

    public static String entityId(EntityDomain domain, String vin, String suffix) {
        return EntityIds.build(domain, EntitySource.TESLA, vin, suffix);
    }

    private static EntityStateUpdate update(EntityDomain domain, String ref, String suffix, String name,
                                            String state, Map<String, Object> attributes) {
        return EntityStateUpdate.builder()
                .entityId(entityId(domain, ref, suffix))
                .domain(domain)
                .source(EntitySource.TESLA)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }

    private static Map<String, Object> attributes(TeslaVehicleSnapshot vehicle, Boolean atHome) {
        Map<String, Object> attributes = new HashMap<>();
        putIfPresent(attributes, "batteryLevel", vehicle.batteryLevel());
        putIfPresent(attributes, "rangeKm", vehicle.rangeKm());
        putIfPresent(attributes, "chargeLimit", vehicle.chargeLimit());
        putIfPresent(attributes, "pluggedIn", vehicle.pluggedIn());
        putIfPresent(attributes, "chargePortOpen", vehicle.chargePortOpen());
        putIfPresent(attributes, "climateOn", vehicle.climateOn());
        putIfPresent(attributes, "insideTemp", vehicle.insideTemp());
        putIfPresent(attributes, "atHome", atHome);
        putIfPresent(attributes, "vehicleState", vehicle.vehicleState());
        return attributes;
    }

    private static Map<String, Object> withUnit(Map<String, Object> attributes, String unit) {
        Map<String, Object> copy = new HashMap<>(attributes);
        copy.put("unit", unit);
        return copy;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
