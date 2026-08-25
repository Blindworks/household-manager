package com.household.manager.presence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt lastSeen/lastChecked je Handy ausschliesslich im Speicher (Muster
 * {@code NetworkDeviceStatusMonitor}). Ueberlebt Neustarts bewusst nicht;
 * {@link #startedAt()} traegt deshalb die Anlauf-Karenz: bis dahin wird bei
 * Stille kein Zustand gemeldet statt "abwesend" zu raten.
 */
@Component
public class PresenceMonitor {

    public record DeviceProbeStatus(Instant lastSeenAt, Instant lastCheckedAt) {
    }

    private final Map<Long, DeviceProbeStatus> statuses = new ConcurrentHashMap<>();
    private final Instant startedAt;

    public PresenceMonitor(Clock clock) {
        this.startedAt = clock.instant();
    }

    /**
     * Traegt das Ergebnis einer Probe nach. {@code lastCheckedAt} wird immer auf
     * {@code now} gesetzt; {@code lastSeenAt} nur bei {@code responded == true} —
     * bei Stille bleibt es auf dem letzten Antwortzeitpunkt stehen (oder
     * {@code null}, wenn das Geraet noch nie geantwortet hat).
     */
    public void update(Long deviceId, boolean responded, Instant now) {
        Instant previousLastSeenAt = Optional.ofNullable(statuses.get(deviceId))
                .map(DeviceProbeStatus::lastSeenAt)
                .orElse(null);
        Instant lastSeenAt = responded ? now : previousLastSeenAt;
        statuses.put(deviceId, new DeviceProbeStatus(lastSeenAt, now));
    }

    public Optional<DeviceProbeStatus> statusOf(Long deviceId) {
        return Optional.ofNullable(statuses.get(deviceId));
    }

    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Entfernt den Status eines geloeschten Geraets. Akzeptierter Wettlauf: ein
     * Poll-Zyklus, der die Geraeteliste vor dem Loeschen geladen hat, kann den
     * Eintrag danach per {@link #update} wieder einfuegen — ein verwaister
     * Eintrag bis zum naechsten Neustart, der nie gelesen wird (alle Lesepfade
     * zaehlen Geraete aus der DB auf), bewusst akzeptiert (Muster
     * {@code NetworkDeviceStatusMonitor}).
     */
    public void remove(Long deviceId) {
        statuses.remove(deviceId);
    }
}
