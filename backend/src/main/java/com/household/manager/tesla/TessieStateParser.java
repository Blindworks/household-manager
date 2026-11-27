package com.household.manager.tesla;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Liest den Tessie-Zustand ({@code GET /{vin}/state}, auch {@code last_state} der
 * Fahrzeugliste) in einen {@link TeslaVehicleSnapshot}. Einzige Stelle, die Feldnamen
 * und Einheiten der Tesla-Antwort kennt.
 *
 * <p>Tesla liefert Reichweiten immer in Meilen, unabhängig von der Anzeigeeinheit im
 * Auto; Temperaturen in °C. Fehlende Felder bleiben {@code null}.
 */
public final class TessieStateParser {

    static final double KM_PER_MILE = 1.609344;

    private TessieStateParser() {
    }

    public static TeslaVehicleSnapshot parse(JsonNode state, String fallbackVin) {
        JsonNode charge = state.path("charge_state");
        JsonNode climate = state.path("climate_state");
        JsonNode vehicle = state.path("vehicle_state");
        JsonNode drive = state.path("drive_state");
        JsonNode config = state.path("vehicle_config");

        String vin = text(state, "vin");
        Double rangeMiles = number(charge, "battery_range");
        String chargingState = text(charge, "charging_state");
        Boolean scheduledPending = bool(charge, "scheduled_charging_pending");
        Long scheduledStart = longValue(charge, "scheduled_charging_start_time");

        return new TeslaVehicleSnapshot(
                vin != null ? vin : fallbackVin,
                text(state, "display_name"),
                text(state, "state"),
                integer(charge, "battery_level"),
                rangeMiles == null ? null : round1(rangeMiles * KM_PER_MILE),
                integer(charge, "charge_limit_soc"),
                chargingState,
                chargingState == null ? null : !"Disconnected".equalsIgnoreCase(chargingState),
                number(charge, "charger_power"),
                integer(charge, "minutes_to_full_charge"),
                scheduledPending,
                Boolean.TRUE.equals(scheduledPending) && scheduledStart != null && scheduledStart > 0
                        ? Instant.ofEpochSecond(scheduledStart) : null,
                bool(charge, "charge_port_door_open"),
                bool(vehicle, "locked"),
                bool(climate, "is_climate_on"),
                defrost(climate),
                number(climate, "inside_temp"),
                number(climate, "outside_temp"),
                number(drive, "latitude"),
                number(drive, "longitude"),
                text(drive, "shift_state"),
                text(config, "car_type"),
                text(config, "exterior_color"),
                text(config, "wheel_type"),
                text(state, "option_codes"),
                timestamp(charge));
    }

    /** Enteisen gilt als an, wenn der Max-Defrost-Modus läuft oder die Frontscheibenheizung an ist. */
    private static Boolean defrost(JsonNode climate) {
        Integer mode = integer(climate, "defrost_mode");
        Boolean front = bool(climate, "is_front_defroster_on");
        if (mode == null && front == null) {
            return null;
        }
        return (mode != null && mode > 0) || Boolean.TRUE.equals(front);
    }

    private static Instant timestamp(JsonNode charge) {
        Long value = longValue(charge, "timestamp");
        if (value == null || value <= 0) {
            return null;
        }
        // Tesla liefert Millisekunden; ein Wert in Sekunden wäre vor 1971.
        return value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer integer(JsonNode node, String field) {
        Double value = number(node, field);
        return value == null ? null : (int) Math.round(value);
    }

    private static Long longValue(JsonNode node, String field) {
        Double value = number(node, field);
        return value == null ? null : value.longValue();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }
}
