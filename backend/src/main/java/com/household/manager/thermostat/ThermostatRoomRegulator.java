package com.household.manager.thermostat;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.service.TemperatureSeriesService;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Raumregelung: stellt den Sollwert eines Thermostats so nach, dass der zugeordnete Raumfuehler
 * die Raum-Solltemperatur erreicht ({@link RoomRegulationRule}). Wirkt nur fuer Thermostate mit
 * Raumfuehler UND Raum-Soll; alle anderen bleiben unberuehrt.
 *
 * <p>Greift bewusst nicht ein, wenn
 * <ul>
 *   <li>das Thermostat offline ist oder keine Werte meldet,</li>
 *   <li>es aus ist (Modus nicht MANUAL oder Ziel &lt;= 5,0 - so schaltet z. B. der Fenster-Flow
 *       #thermostat-set ab; die Regelung darf das nie zurueckdrehen),</li>
 *   <li>der Raumwert fehlt oder aelter als {@link #STALE_AFTER} ist (nie auf einen toten Fuehler
 *       regeln),</li>
 *   <li>jemand den Sollwert von Hand geaendert hat: dann pausiert die Regelung fuer dieses
 *       Thermostat {@link #MANUAL_PAUSE}.</li>
 * </ul>
 *
 * <p>Der zuletzt geschriebene Wert lebt nur im Speicher; nach einem Neustart regelt der erste Lauf
 * einfach wieder (ohne Handaenderungs-Erkennung fuer diesen einen Lauf).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThermostatRoomRegulator {

    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    static final Duration MANUAL_PAUSE = Duration.ofHours(2);
    /** Kleinere Abweichungen schreibt die Regelung nicht (Hub-Funk und Batterie schonen). */
    static final double MIN_CHANGE = 0.5;

    private final ThermostatRepository repository;
    private final ThermostatStateCache cache;
    private final ThermostatService thermostatService;
    private final TemperatureSeriesService temperatureSeriesService;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;

    /** Je Thermostat: zuletzt geschriebener Wert, der Wert davor und eine Pause nach Handaenderung. */
    private final Map<Long, Memory> memory = new ConcurrentHashMap<>();

    record Memory(Double lastWritten, Double before, LocalDateTime pausedUntil) {
    }

    @Scheduled(fixedDelayString = "${thermostat.room-regulation.interval-ms:300000}",
            initialDelayString = "${thermostat.room-regulation.initial-delay-ms:180000}")
    public void regulate() {
        List<Thermostat> regulated = repository.findByActiveTrue().stream()
                .filter(t -> t.getRoomSensorId() != null && t.getRoomTarget() != null)
                .toList();
        if (regulated.isEmpty()) {
            jobMonitor.reportInactive("Keine Raumregelung eingerichtet");
            return;
        }
        Map<String, CurrentTemperatureReading> rooms = loadRoomReadings();
        int failures = 0;
        for (Thermostat thermostat : regulated) {
            try {
                regulateOne(thermostat, rooms.get(thermostat.getRoomSensorId()));
            } catch (RuntimeException ex) {
                failures++;
                log.warn("Raumregelung fuer Thermostat {} fehlgeschlagen: {}", thermostat.getName(), ex.getMessage());
            }
        }
        if (failures == regulated.size()) {
            jobMonitor.reportFailure("Raumregelung: kein Thermostat erreichbar");
        }
    }

    /** Vergisst den Stand eines Thermostats, z. B. nach Aendern des Raum-Solls. */
    void forget(Long thermostatId) {
        memory.remove(thermostatId);
    }

    private Map<String, CurrentTemperatureReading> loadRoomReadings() {
        return temperatureSeriesService.getCurrent().stream()
                .filter(r -> r.getSensorId() != null)
                .collect(Collectors.toMap(CurrentTemperatureReading::getSensorId, Function.identity(), (a, b) -> a));
    }

    private void regulateOne(Thermostat thermostat, CurrentTemperatureReading room) {
        Optional<ThermostatReading> reading = cache.find(thermostat.getId()).map(ThermostatStateCache.Entry::reading);
        if (reading.isEmpty() || !isRegulatable(reading.get())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!isFresh(room, now)) {
            log.debug("Raumregelung {}: Raumwert fehlt oder ist veraltet", thermostat.getName());
            return;
        }
        double currentTarget = reading.get().targetTemp();
        Memory known = memory.get(thermostat.getId());
        if (known != null && known.pausedUntil() != null) {
            if (now.isBefore(known.pausedUntil())) {
                return;
            }
            known = null;
            memory.remove(thermostat.getId());
        }
        if (known != null && wasChangedByHand(known, currentTarget)) {
            log.info("Raumregelung {}: Sollwert von Hand auf {} geaendert, pausiert bis {}",
                    thermostat.getName(), currentTarget, now.plus(MANUAL_PAUSE));
            memory.put(thermostat.getId(), new Memory(null, null, now.plus(MANUAL_PAUSE)));
            return;
        }

        double desired = RoomRegulationRule.setpoint(thermostat.getRoomTarget(),
                room.getTemperature().doubleValue(), reading.get().currentTemp());
        if (Math.abs(desired - currentTarget) < MIN_CHANGE) {
            return;
        }
        write(thermostat, desired);
        memory.put(thermostat.getId(), new Memory(desired, currentTarget, null));
    }

    private static boolean isRegulatable(ThermostatReading reading) {
        return reading.online()
                && reading.currentTemp() != null
                && reading.targetTemp() != null
                && reading.mode() == ThermostatMode.MANUAL
                && reading.targetTemp() > ThermostatService.OFF_TARGET_TEMP;
    }

    private static boolean isFresh(CurrentTemperatureReading room, LocalDateTime now) {
        return room != null
                && room.getTemperature() != null
                && room.getMeasuredAt() != null
                && !room.getMeasuredAt().isBefore(now.minus(STALE_AFTER));
    }

    /**
     * Der Hub meldet einen geschriebenen Wert verzoegert; bis dahin steht im Cache noch der alte.
     * Weder der neue noch der alte Wert ist deshalb ein Hinweis auf eine Handaenderung.
     */
    private static boolean wasChangedByHand(Memory known, double currentTarget) {
        return !same(currentTarget, known.lastWritten()) && !same(currentTarget, known.before());
    }

    private static boolean same(double value, Double other) {
        return other != null && Math.abs(value - other) < 0.05;
    }

    private void write(Thermostat thermostat, double target) {
        AuditActorContext.set(AuditActor.roomRegulation());
        try {
            thermostatService.setTarget(thermostat.getId(), target);
        } finally {
            AuditActorContext.clear();
        }
    }
}
