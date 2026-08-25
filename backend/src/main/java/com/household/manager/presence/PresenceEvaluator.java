package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Die EINZIGE Definition von "anwesend" (Muster {@code TractiveHomeResolver}):
 * Poller und Status-API fragen dieselbe Klasse, damit Dashboard-Kachel und
 * Flow-Trigger nicht auseinanderlaufen koennen.
 *
 * <p>Regeln: Antwort eines aktiven Geraets => sofort anwesend. Abwesend erst,
 * wenn ALLE aktiven Geraete laenger als die Karenzzeit still sind. Nach einem
 * Neustart (lastSeen ist nur im Speicher) gilt die Karenz ab Startzeitpunkt:
 * bis dahin wird bei Stille UNKNOWN geliefert und der Aufrufer meldet nichts —
 * die Entitaet behaelt ihren letzten DB-Wert statt zu raten.
 */
@Component
@RequiredArgsConstructor
public class PresenceEvaluator {

    public enum PersonState { PRESENT, AWAY, UNAVAILABLE, UNKNOWN }

    public record PersonPresence(PersonState state, Instant lastSeenAt) {
    }

    private final PresenceMonitor monitor;
    private final PresenceSettingsService settings;

    public PersonPresence evaluate(List<PresenceDevice> devices, Instant now) {
        List<PresenceDevice> active = devices.stream().filter(PresenceDevice::isActive).toList();
        if (active.isEmpty()) {
            return new PersonPresence(PersonState.UNAVAILABLE, null);
        }

        Instant lastSeen = active.stream()
                .map(device -> monitor.statusOf(device.getId())
                        .map(PresenceMonitor.DeviceProbeStatus::lastSeenAt)
                        .orElse(null))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Duration grace = Duration.ofMinutes(settings.getAwayGraceMinutes());
        if (lastSeen != null) {
            PersonState state = Duration.between(lastSeen, now).compareTo(grace) <= 0
                    ? PersonState.PRESENT
                    : PersonState.AWAY;
            return new PersonPresence(state, lastSeen);
        }

        // Noch nie gesehen seit dem Start: Anlauf-Karenz — erst danach ist
        // Stille ein Beleg fuer Abwesenheit.
        if (Duration.between(monitor.startedAt(), now).compareTo(grace) < 0) {
            return new PersonPresence(PersonState.UNKNOWN, null);
        }
        return new PersonPresence(PersonState.AWAY, null);
    }

    /**
     * Aggregat "Jemand zu Hause": on sobald irgendwer anwesend ist; off nur,
     * wenn ALLE erfassten Personen abwesend sind; unavailable nur, wenn alle
     * blind sind. Jede Mischung ohne PRESENT ergibt keine Aussage — dann wird
     * bewusst nichts gemeldet.
     */
    public Optional<String> aggregateState(Collection<PersonState> states) {
        if (states.isEmpty()) {
            return Optional.empty();
        }
        if (states.contains(PersonState.PRESENT)) {
            return Optional.of("on");
        }
        if (states.stream().allMatch(state -> state == PersonState.AWAY)) {
            return Optional.of("off");
        }
        if (states.stream().allMatch(state -> state == PersonState.UNAVAILABLE)) {
            return Optional.of("unavailable");
        }
        return Optional.empty();
    }
}
