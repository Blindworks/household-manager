package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Schreibt jede Zustandsaenderung eines Power-Sensors in die Verbrauchshistorie.
 * <p>
 * Haengt am {@link EntityStateChangedEvent} statt an den einzelnen Integrationen:
 * so bekommt jede Quelle mit deviceClass "power" ohne eigenen Code eine Historie.
 * Das Event feuert nur bei Wertaenderung — ein konstant ausgeschaltetes Geraet
 * kostet daher nichts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PowerHistoryRecorder {

    private static final String DEVICE_CLASS_POWER = "power";

    private final EntityPowerHistoryRepository repository;

    @EventListener
    public void onStateChanged(EntityStateChangedEvent event) {
        if (!isPowerSensor(event.attributes())) {
            return;
        }
        try {
            repository.save(EntityPowerHistory.builder()
                    .entityId(event.entityId())
                    .measuredAt(event.timestamp())
                    .powerWatts(parseWatts(event.newState()))
                    .build());
        } catch (Exception ex) {
            // Bewusst geschluckt: der Recorder haengt am Schreibpfad der Integrationen,
            // ein Historie-Fehler darf das Polling nie brechen.
            log.warn("Failed to record power history for {}: {}", event.entityId(), ex.getMessage());
        }
    }

    private boolean isPowerSensor(Map<String, Object> attributes) {
        return attributes != null && DEVICE_CLASS_POWER.equals(attributes.get("deviceClass"));
    }

    /** Nicht-numerische Zustaende ("unavailable", "unknown") ergeben null = Luecke. */
    private Double parseWatts(String state) {
        if (state == null) {
            return null;
        }
        try {
            return Double.valueOf(state.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
