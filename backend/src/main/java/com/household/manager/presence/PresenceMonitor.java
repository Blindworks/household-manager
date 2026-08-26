package com.household.manager.presence;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt firstChecked/lastSeen/lastChecked je Handy ausschliesslich im Speicher
 * (Muster {@code NetworkDeviceStatusMonitor}). Ueberlebt Neustarts bewusst
 * nicht. Die Anlauf-Karenz haengt deshalb NICHT an einem Prozess-Startzeitpunkt,
 * sondern an {@code firstCheckedAt} je Geraet (siehe {@link DeviceProbeStatus}):
 * bis zur ersten Pruefung, und danach bis die Karenzzeit seit dieser ersten
 * Pruefung verstrichen ist, wird bei Stille kein Zustand gemeldet statt
 * "abwesend" zu raten. Das subsumiert die Neustart-Karenz automatisch — nach
 * einem Neustart hat kein Geraet einen Eintrag, die erste Pruefung passiert
 * direkt danach.
 */
@Component
public class PresenceMonitor {

    public record DeviceProbeStatus(Instant firstCheckedAt, Instant lastSeenAt, Instant lastCheckedAt) {
    }

    private final Map<Long, DeviceProbeStatus> statuses = new ConcurrentHashMap<>();

    /**
     * Traegt das Ergebnis einer Probe nach. {@code firstCheckedAt} wird beim
     * allerersten Aufruf fuer dieses Geraet gesetzt und danach nie wieder
     * geaendert. {@code lastCheckedAt} wird immer auf {@code now} gesetzt;
     * {@code lastSeenAt} nur bei {@code responded == true} — bei Stille bleibt es
     * auf dem letzten Antwortzeitpunkt stehen (oder {@code null}, wenn das
     * Geraet noch nie geantwortet hat).
     *
     * <p>Ueber {@link Map#compute} statt get-dann-put: so bleibt das
     * Schreibe-einmal-Invariant von {@code firstCheckedAt} auch dann intakt,
     * wenn der Poller Geraete je einmal parallel probt statt sequentiell.
     */
    public void update(Long deviceId, boolean responded, Instant now) {
        statuses.compute(deviceId, (id, previous) -> {
            Instant firstCheckedAt = previous != null ? previous.firstCheckedAt() : now;
            Instant lastSeenAt = responded ? now : (previous != null ? previous.lastSeenAt() : null);
            return new DeviceProbeStatus(firstCheckedAt, lastSeenAt, now);
        });
    }

    public Optional<DeviceProbeStatus> statusOf(Long deviceId) {
        return Optional.ofNullable(statuses.get(deviceId));
    }

    /**
     * Entfernt den Status eines geloeschten Geraets. Akzeptierter Wettlauf: ein
     * Poll-Zyklus, der die Geraeteliste vor dem Loeschen geladen hat, kann den
     * Eintrag danach per {@link #update} wieder einfuegen — ein verwaister
     * Eintrag bis zum naechsten Neustart, der nie gelesen wird (alle Lesepfade
     * zaehlen Geraete aus der DB auf), bewusst akzeptiert (Muster
     * {@code NetworkDeviceStatusMonitor}). Theoretische Zuspitzung: MariaDB/
     * InnoDB berechnet AUTO_INCREMENT beim Neustart neu, eine geloeschte Id
     * koennte also neu vergeben werden, solange der Prozess mit seinem
     * Waisen-Eintrag lebt — Richtung waere dann ein falsches PRESENT.
     * <strong>Mittlerweile entschaerft</strong>: {@code PresenceDeviceService.create}
     * ruft nach jedem Anlegen {@code monitor.remove(saved.getId())} fuer genau die neu
     * vergebene Id, bevor der erste Poll-Zyklus sie ueberhaupt sehen kann — ein
     * wiederverwendeter Waisen-Eintrag kann ein frisch angelegtes Geraet also nicht
     * mehr anstecken.
     */
    public void remove(Long deviceId) {
        statuses.remove(deviceId);
    }
}
