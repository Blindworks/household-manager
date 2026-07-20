package com.household.manager.meross.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Momentanwerte einer Meross-Steckdose mit Energiemessung, bereits in
 * SI-üblichen Einheiten (W, V, A) statt der Gerätewerte (mW, 0,1 V, mA).
 */
public record MerossElectricityReading(
        String deviceId,
        String deviceName,
        BigDecimal powerWatts,
        BigDecimal voltageVolts,
        BigDecimal currentAmps) {

    /**
     * Parst den Antwort-Payload von {@code Appliance.Control.Electricity}:
     * {@code { "electricity": { "channel", "current" (mA), "voltage" (0,1 V), "power" (mW) } }}.
     *
     * @return leer, wenn der Payload fehlt oder keinen Leistungswert enthält
     */
    public static Optional<MerossElectricityReading> fromPayload(String deviceId, String deviceName, Map<?, ?> payload) {
        if (payload == null || !(payload.get("electricity") instanceof Map<?, ?> values)) {
            return Optional.empty();
        }
        BigDecimal powerMilliwatts = toBigDecimal(values.get("power"));
        if (powerMilliwatts == null) {
            return Optional.empty();
        }
        return Optional.of(new MerossElectricityReading(
                deviceId,
                deviceName,
                scale(powerMilliwatts, 3, 1),
                scale(toBigDecimal(values.get("voltage")), 1, 1),
                scale(toBigDecimal(values.get("current")), 3, 3)));
    }

    /** Verschiebt das Komma um {@code shift} Stellen nach links und rundet auf {@code scale} Nachkommastellen. */
    private static BigDecimal scale(BigDecimal raw, int shift, int scale) {
        if (raw == null) {
            return null;
        }
        return raw.movePointLeft(shift).setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
