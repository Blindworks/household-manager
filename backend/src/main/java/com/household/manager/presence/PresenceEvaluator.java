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
 * wenn ALLE aktiven Geraete laenger als die Karenzzeit still sind. Hat noch
 * KEIN aktives Geraet je geantwortet, greift die Probezeit je Geraet (siehe
 * {@link PresenceMonitor}): solange irgendein aktives Geraet noch keinen
 * Monitor-Eintrag hat (noch nie geprueft) oder seine eigene {@code
 * firstCheckedAt} noch keine volle Karenzzeit zurueckliegt, wird UNKNOWN
 * geliefert und der Aufrufer meldet nichts — die Entitaet behaelt ihren
 * letzten DB-Wert statt zu raten. Das gilt gleichermassen fuer ein frisch
 * angelegtes Geraet wie fuer den Zustand direkt nach einem Backend-Neustart.
 * Die Probezeit wird dabei nur konsultiert, solange NOCH KEIN aktives Geraet
 * je geantwortet hat ({@code lastSeen == null}) — hat mindestens eines schon
 * geantwortet, entscheidet allein dessen Stille gegen die Karenzzeit, auch
 * wenn ein danach hinzugefuegtes zweites Geraet noch in seiner eigenen
 * Probezeit steckt.
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

        // Noch nie gesehen: solange irgendein aktives Geraet noch in seiner
        // eigenen Probezeit steckt (kein Eintrag, oder firstCheckedAt liegt noch
        // keine volle Karenzzeit zurueck), ist Stille kein Beleg fuer Abwesenheit.
        boolean anyDeviceStillInProbeGrace = active.stream().anyMatch(device -> {
            Optional<PresenceMonitor.DeviceProbeStatus> status = monitor.statusOf(device.getId());
            if (status.isEmpty()) {
                return true;
            }
            Instant firstCheckedAt = status.get().firstCheckedAt();
            return Duration.between(firstCheckedAt, now).compareTo(grace) < 0;
        });
        if (anyDeviceStillInProbeGrace) {
            return new PersonPresence(PersonState.UNKNOWN, null);
        }
        return new PersonPresence(PersonState.AWAY, null);
    }

    /**
     * Aggregat "Jemand zu Hause": on sobald irgendwer anwesend ist; off nur,
     * wenn ALLE erfassten Personen abwesend sind; unavailable nur, wenn alle
     * blind sind. Jede Mischung ohne PRESENT ergibt keine Aussage — dann wird
     * bewusst nichts gemeldet.
     *
     * <p><strong>Stille Falle:</strong> Eine dauerhaft {@code UNAVAILABLE}
     * gemeldete Person (deaktiviertes oder geloeschtes Handy) laesst eine
     * Mischung wie {@code [AWAY, UNAVAILABLE]} auf {@link Optional#empty()}
     * fallen — es wird nichts gemeldet, und {@code binary_sensor.
     * presence_household} friert dauerhaft auf seinem letzten Wert ein, ohne
     * Log und ohne Fehler. Die Regel ist bewusst so (die sichere Richtung),
     * aber wer das Handy einer Person deaktiviert, schaltet damit
     * stillschweigend einen darauf gebauten "Alle weg"-Flow ab.
     */
    public Optional<String> aggregateState(Collection<PersonState> states) {
        if (states.isEmpty()) {
            return Optional.empty();
        }
        if (states.contains(PersonState.PRESENT)) {
            return Optional.of(entityState(PersonState.PRESENT));
        }
        if (states.stream().allMatch(state -> state == PersonState.AWAY)) {
            return Optional.of(entityState(PersonState.AWAY));
        }
        if (states.stream().allMatch(state -> state == PersonState.UNAVAILABLE)) {
            return Optional.of(entityState(PersonState.UNAVAILABLE));
        }
        return Optional.empty();
    }

    /**
     * Bildet einen Personenzustand auf den Entity-State-String ab — die
     * einzige Definition, damit Poller, Status-API und {@link #aggregateState}
     * nicht je eine eigene, womoeglich widerspruechliche Abbildung mitbringen.
     * UNKNOWN wird von den Aufrufern nicht gemeldet (die Entitaet behaelt ihren
     * zuletzt bekannten DB-Wert), bekommt hier aber trotzdem einen Text, damit
     * niemand ihn selbst erfinden muss. Bewusst OHNE {@code default}-Zweig: eine
     * kuenftige fuenfte Konstante muss ein Compilerfehler werden, statt still zu
     * "unavailable" zu werden.
     *
     * <p>Bewusst {@code static}: der Poller mockt {@link PresenceEvaluator} als
     * Ganzes, eine Instanzmethode wuerde dort {@code null} liefern und der Test
     * pruefte am Ende eine gestubbte statt der echten Abbildung. Als statische,
     * abhaengigkeitsfreie Funktion kann Mockito sie nicht abfangen.
     */
    public static String entityState(PersonState state) {
        return switch (state) {
            case PRESENT -> "on";
            case AWAY -> "off";
            case UNAVAILABLE -> "unavailable";
            case UNKNOWN -> "unknown";
        };
    }
}
