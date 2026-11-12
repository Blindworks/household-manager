package com.household.manager.laundry;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/** Liest Temperatur und Feuchte des Kellerfuehlers; ohne frische, endliche Werte gibt es nichts. */
@Component
@RequiredArgsConstructor
public class LaundryClimateReader {

    private final EntityStateService entityStateService;
    private final LaundryProperties properties;
    private final Clock clock;

    public Optional<KellerClimate> current() {
        Optional<Reading> temperature = read(properties.getTemperatureEntity());
        Optional<Reading> humidity = read(properties.getHumidityEntity());
        if (temperature.isEmpty() || humidity.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime measuredAt = temperature.get().at().isBefore(humidity.get().at())
                ? temperature.get().at() : humidity.get().at();
        if (measuredAt.isBefore(LocalDateTime.now(clock).minus(LaundryRules.SENSOR_STALE))) {
            return Optional.empty();
        }
        return Optional.of(new KellerClimate(temperature.get().value(), humidity.get().value(), measuredAt));
    }

    private Optional<Reading> read(String entityId) {
        return entityStateService.getByEntityId(entityId)
                .flatMap(LaundryClimateReader::toReading);
    }

    private static Optional<Reading> toReading(EntityState state) {
        if (state.getState() == null || state.getLastUpdated() == null) {
            return Optional.empty();
        }
        try {
            double value = Double.parseDouble(state.getState().trim());
            // "NaN" und "Infinity" parsen als Double und duerfen nie in die Rechnung laufen
            return Double.isFinite(value) ? Optional.of(new Reading(value, state.getLastUpdated())) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private record Reading(double value, LocalDateTime at) {
    }
}
