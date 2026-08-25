package com.household.manager.presence;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Probt alle 30 s die aktiven Handys und spiegelt das Ergebnis in den
 * Entity-State-Layer: eine Entitaet je Person plus das Aggregat "Jemand zu
 * Hause". Wirft nie; Fehler pro Geraet sind isoliert.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PresencePollingService {

    /**
     * 62078 (lockdownd) antwortet auf iPhones fast immer; 80/443 sind Fallbacks.
     * Auch ein "refused" auf jedem dieser Ports beweist Anwesenheit — die Liste
     * muss also nicht vollstaendig sein, nur eine Antwort provozieren.
     */
    static final List<Integer> PROBE_PORTS = List.of(62078, 80, 443);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    static final String HOUSEHOLD_REF = "household";
    static final String HOUSEHOLD_FRIENDLY_NAME = "Jemand zu Hause";

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceProbe probe;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final EntityStateService entityStateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${presence.poll-interval-ms:30000}")
    public void poll() {
        List<PresenceDevice> devices;
        try {
            devices = deviceRepository.findAll();
        } catch (Exception e) {
            // Zustaende unveraendert lassen: lastSeen bleibt stehen, nichts wird
            // faelschlich "off".
            log.warn("Laden der Anwesenheits-Geraete fehlgeschlagen, Zyklus uebersprungen", e);
            return;
        }

        for (PresenceDevice device : devices) {
            if (!device.isActive()) {
                // Ein inaktives Geraet hat keine Messung mehr - und wird sein
                // Monitor-Eintrag nicht los, waere es weiterhin drin: eine spaetere
                // Reaktivierung wuerde dann sofort als "laengst gesehen" gelten,
                // statt ihre eigene Probezeit neu zu durchlaufen (gleiche Bugklasse
                // wie die per-Geraet-Probezeit selbst).
                monitor.remove(device.getId());
                continue;
            }
            monitor.update(device.getId(), probeSafely(device), clock.instant());
        }

        try {
            evaluateAndReport(devices);
        } catch (Exception e) {
            log.warn("Auswertung der Anwesenheit fehlgeschlagen", e);
        }
    }

    private boolean probeSafely(PresenceDevice device) {
        try {
            return probe.probe(device.getHost(), PROBE_PORTS, PROBE_TIMEOUT) == ProbeResult.RESPONDED;
        } catch (Exception e) {
            log.debug("Probe fuer Geraet {} ({}) fehlgeschlagen: {}",
                    device.getId(), device.getHost(), e.getMessage());
            return false;
        }
    }

    private void evaluateAndReport(List<PresenceDevice> devices) {
        // TreeMap: stabile Reihenfolge der Meldungen (nach userId)
        Map<Long, List<PresenceDevice>> byUser = devices.stream()
                .collect(Collectors.groupingBy(PresenceDevice::getUserId, TreeMap::new,
                        Collectors.toList()));
        Instant now = clock.instant();

        List<PresenceEvaluator.PersonState> states = new ArrayList<>();
        byUser.forEach((userId, userDevices) -> {
            PresenceEvaluator.PersonPresence presence = evaluator.evaluate(userDevices, now);
            states.add(presence.state());
            if (presence.state() == PresenceEvaluator.PersonState.UNKNOWN) {
                // Anlauf-Karenz: kein Update, die Entitaet behaelt ihren DB-Wert.
                return;
            }
            reportPersonState(userId, presence);
        });

        evaluator.aggregateState(states).ifPresent(this::reportHouseholdState);
    }

    private void reportPersonState(Long userId, PresenceEvaluator.PersonPresence presence) {
        String state = PresenceEvaluator.entityState(presence.state());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("deviceClass", "presence");
        attributes.put("personUserId", userId);
        if (presence.lastSeenAt() != null) {
            // Schluessel fehlt statt null zu tragen (Muster Netzwerk-Monitoring)
            attributes.put("lastSeenAt",
                    LocalDateTime.ofInstant(presence.lastSeenAt(), clock.getZone()).toString());
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE,
                        String.valueOf(userId), "home"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.PRESENCE)
                .sourceRef(String.valueOf(userId))
                .friendlyName(displayNameOf(userId) + " anwesend")
                .state(state)
                .attributes(attributes)
                .build());
    }

    private void reportHouseholdState(String state) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE,
                        HOUSEHOLD_REF, null))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.PRESENCE)
                .sourceRef(HOUSEHOLD_REF)
                .friendlyName(HOUSEHOLD_FRIENDLY_NAME)
                .state(state)
                .attributes(Map.of("deviceClass", "presence"))
                .build());
    }

    private String displayNameOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getDisplayName)
                .orElse("Person " + userId);
    }
}
