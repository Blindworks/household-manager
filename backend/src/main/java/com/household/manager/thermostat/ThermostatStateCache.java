package com.household.manager.thermostat;

import com.household.manager.model.entity.Thermostat;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Letzter gelesener Zustand je Thermostat, rein im Speicher (Muster
 * {@code NetworkDeviceStatusMonitor}). Ueberlebt keinen Neustart: bis zum ersten Poll
 * liefert die API "offline ohne Werte" statt eines geratenen Zustands.
 */
@Component
public class ThermostatStateCache {

    public record Entry(ThermostatReading reading, LocalDateTime lastUpdated) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public Optional<Entry> find(Long thermostatId) {
        return Optional.ofNullable(entries.get(thermostatId));
    }

    /**
     * Uebernimmt eine Hub-Antwort fuer die Thermostate dieses Hubs. Ein Kindgeraet, das in der
     * Antwort fehlt, wird als offline ohne Werte gespeichert.
     *
     * @return der je Thermostat-Id gespeicherte Zustand (fuer Entity-Spiegelung und Historie)
     */
    public Map<Long, ThermostatReading> applyHubReadings(List<Thermostat> hubThermostats,
                                                         List<ThermostatReading> readings,
                                                         LocalDateTime now) {
        Map<String, ThermostatReading> byChild = new HashMap<>();
        readings.forEach(reading -> byChild.put(reading.childDeviceId(), reading));

        Map<Long, ThermostatReading> applied = new HashMap<>();
        for (Thermostat thermostat : hubThermostats) {
            ThermostatReading reading = byChild.getOrDefault(thermostat.getChildDeviceId(),
                    missingChild(thermostat));
            entries.put(thermostat.getId(), new Entry(reading, now));
            applied.put(thermostat.getId(), reading);
        }
        return applied;
    }

    /**
     * Hub nicht erreichbar: die Thermostate gelten als offline ohne Werte (nie eine veraltete
     * Zahl als aktuell), der Zeitpunkt der letzten erfolgreichen Abfrage bleibt erhalten.
     */
    public void markOffline(List<Thermostat> thermostats) {
        for (Thermostat thermostat : thermostats) {
            LocalDateTime lastUpdated = find(thermostat.getId()).map(Entry::lastUpdated).orElse(null);
            entries.put(thermostat.getId(), new Entry(missingChild(thermostat), lastUpdated));
        }
    }

    private ThermostatReading missingChild(Thermostat thermostat) {
        return new ThermostatReading(thermostat.getChildDeviceId(), thermostat.getName(), false,
                null, null, ThermostatMode.MANUAL, false, false);
    }
}
