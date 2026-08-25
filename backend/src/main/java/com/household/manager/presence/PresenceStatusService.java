package com.household.manager.presence;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Baut die Status-Antwort fuer Admin-Seite und Dashboard-Kachel. Rechnet bei
 * jedem Abruf frisch ueber den {@link PresenceEvaluator} — dieselbe DEFINITION
 * von "anwesend" wie der Poller (Muster {@code TractiveHomeResolver}: geteilt
 * ist die Berechnungsvorschrift, damit Kachel und Entitaet nie eine je eigene,
 * womoeglich widerspruechliche Vorstellung von "anwesend" mitbringen).
 *
 * <p><strong>Geteilt ist die Definition, NICHT der gemeldete Wert</strong> — in
 * zwei bewusst so gebauten Fenstern weicht die Antwort dieses Service vom
 * zuletzt in den Entity-State-Layer geschriebenen Wert ab:
 * <ul>
 *   <li><em>Anlauf-Karenz:</em> bei {@code UNKNOWN} meldet der Poller bewusst
 *   nichts, die Entitaet behaelt ihren letzten DB-Wert (typischerweise
 *   {@code "on"}). Dieser Service liefert fuer dieselbe Person ehrlich
 *   {@code "unknown"} — bis zu einer vollen Karenzzeit lang ein Widerspruch
 *   zwischen API-Antwort und Entitaet.</li>
 *   <li><em>Eingefrorenes Aggregat:</em> bei einer Mischung wie
 *   {@code [AWAY, UNAVAILABLE]} liefert {@link PresenceEvaluator#aggregateState}
 *   {@code Optional.empty()}; der Poller meldet ueber {@code .ifPresent} nichts
 *   und {@code binary_sensor.presence_household} friert auf seinem letzten Wert
 *   ein (z. B. {@code "on"}), waehrend {@link #getStatus()} dasselbe
 *   {@code Optional.empty()} ehrlich zu {@code "unknown"} macht.</li>
 * </ul>
 * Beide Seiten sind richtig: die Entitaet darf nicht raten (sie behaelt lieber
 * einen veralteten Wert als einen erfundenen), und dass dieser Service "keine
 * Aussage" statt eines geratenen Werts liefert, ist genau der Weg, auf dem eine
 * eingefrorene Entitaet ueberhaupt sichtbar wird.
 */
@Service
@RequiredArgsConstructor
public class PresenceStatusService {

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PresenceDtos.StatusResponse getStatus() {
        Map<Long, List<PresenceDevice>> byUser = deviceRepository.findAll().stream()
                .collect(Collectors.groupingBy(PresenceDevice::getUserId, TreeMap::new,
                        Collectors.toList()));
        Instant now = clock.instant();

        List<PresenceDtos.PersonStatus> persons = new ArrayList<>();
        List<PresenceEvaluator.PersonState> states = new ArrayList<>();
        byUser.forEach((userId, devices) -> {
            // Zwei getrennte Monitor-Zugriffe fuer dieselbe Person: evaluate()
            // liest lastSeenAt fuer die Personen-Aussage, deviceStatus() gleich
            // danach je Geraet erneut. Schreibt der Poller in diesem winzigen
            // Fenster dazwischen, kann die Antwort fuer die Person "off" zeigen,
            // waehrend ein Geraet darunter ein lastSeenAt von vor Sekunden traegt.
            // Bewusst akzeptiert (Muster PresenceDevice-Waisen-Kommentar in
            // PresenceMonitor.remove): PresenceMonitor haelt keinen Schnappschuss
            // ueber alle Geraete gleichzeitig, das Fenster ist Millisekunden
            // gross und heilt spaetestens beim naechsten 30-s-Poll von selbst.
            PresenceEvaluator.PersonPresence presence = evaluator.evaluate(devices, now);
            states.add(presence.state());
            persons.add(new PresenceDtos.PersonStatus(
                    userId,
                    displayNameOf(userId),
                    PresenceEvaluator.entityState(presence.state()),
                    toLocal(presence.lastSeenAt()),
                    devices.stream().map(this::deviceStatus).toList()));
        });

        String householdState = evaluator.aggregateState(states).orElse("unknown");
        return new PresenceDtos.StatusResponse(householdState, persons);
    }

    private PresenceDtos.DeviceStatusResponse deviceStatus(PresenceDevice device) {
        PresenceMonitor.DeviceProbeStatus status = monitor.statusOf(device.getId()).orElse(null);
        return new PresenceDtos.DeviceStatusResponse(
                device.getId(), device.getName(), device.isActive(),
                status == null ? null : toLocal(status.lastSeenAt()),
                status == null ? null : toLocal(status.lastCheckedAt()));
    }

    private String displayNameOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getDisplayName)
                .orElse("Person " + userId);
    }

    private LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
