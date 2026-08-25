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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p><strong>Kostenrechnung:</strong> ein abwesendes Geraet durchlaeuft alle
 * drei {@link #PROBE_PORTS} sequentiell mit je {@code presence.probe-timeout-ms}
 * Timeout (Default 3 x 2 s = 6 s). Bei N Geraeten kostet ein leerer Haushalt
 * also bis zu N x 6 s eines der wenigen geteilten Scheduler-Threads —
 * {@code fixedDelay} misst dabei erst ab dem ENDE des vorherigen Laufs, ein
 * langsamer Zyklus schiebt den naechsten also nach hinten statt sich zu
 * ueberlappen. Paralleles Proben bauen wir bewusst (noch) nicht (bei wenigen
 * Handys tragbar); der Timeout ist deshalb ohne Redeploy ueber
 * {@code presence.probe-timeout-ms} nachziehbar.
 *
 * <p><strong>Blinder Fleck:</strong> faellt die LAN-Anbindung des Servers
 * selbst aus, schweigen alle Geraete gleichermassen — nach Ablauf der
 * Karenzzeit laufen alle Personen auf "off" und das Aggregat meldet "niemand
 * zu Hause", obwohl niemand gegangen ist. Dieser Poller kann einen eigenen
 * Netzwerkausfall nicht von echter Abwesenheit unterscheiden; die Absicherung
 * gehoert auf Flow-Ebene (Bedingung auf die Netzwerk-Entitaeten des
 * {@code network}-Moduls), nicht in diesen Service.
 */
@Service
@Slf4j
public class PresencePollingService {

    /**
     * 62078 (lockdownd) antwortet auf iPhones fast immer; 80/443 sind Fallbacks.
     * Auch ein "refused" auf jedem dieser Ports beweist Anwesenheit — die Liste
     * muss also nicht vollstaendig sein, nur eine Antwort provozieren.
     */
    private static final List<Integer> PROBE_PORTS = List.of(62078, 80, 443);

    private static final String HOUSEHOLD_REF = "household";
    private static final String HOUSEHOLD_FRIENDLY_NAME = "Jemand zu Hause";

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceProbe probe;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final EntityStateService entityStateService;
    private final Clock clock;
    private final Duration probeTimeout;

    /**
     * Einziger Konstruktor mit allen Abhaengigkeiten inklusive dem
     * {@code @Value}-Parameter (Muster {@code NetworkConnectivityPollingService}):
     * Spring waehlt bei genau einem Konstruktor automatisch diesen, und Tests
     * koennen den Timeout als normales Literal uebergeben statt per Reflection
     * (kein {@code @RequiredArgsConstructor} mehr moeglich, sobald ein
     * {@code @Value}-Parameter dazukommt).
     */
    public PresencePollingService(
            PresenceDeviceRepository deviceRepository,
            AppUserRepository userRepository,
            PresenceProbe probe,
            PresenceMonitor monitor,
            PresenceEvaluator evaluator,
            EntityStateService entityStateService,
            Clock clock,
            @Value("${presence.probe-timeout-ms:2000}") long probeTimeoutMs) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.probe = probe;
        this.monitor = monitor;
        this.evaluator = evaluator;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.probeTimeout = Duration.ofMillis(probeTimeoutMs);
    }

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

        // Ein Instant fuer den ganzen Zyklus (Muster NetworkDevicePollingService,
        // Lehre aus der Tractive-Integration: nie ein Instant.now() pro Geraet).
        Instant now = clock.instant();

        for (PresenceDevice device : devices) {
            if (!device.isActive()) {
                // Messwert vergessen, nicht nur ueberspringen: sonst erbt das
                // Geraet beim Wiedereinschalten sein altes firstCheckedAt und
                // waere nach einer einzigen stillen Probe "abwesend", ohne je
                // Probezeit gehabt zu haben.
                //
                // Kehrseite: mit dem Monitor-Eintrag verschwindet auch dessen
                // lastSeenAt. Die Status-API aus Task 8 zeigt fuer ein
                // deaktiviertes Geraet deshalb dauerhaft "-". Das ist bewusst
                // so — ein deaktiviertes Geraet wird nicht mehr gemessen, und
                // ein eingefrorener Wert waere irrefuehrender als gar keiner
                // (die In-Memory-Werte ueberleben ohnehin keinen Neustart).
                monitor.remove(device.getId());
                continue;
            }
            monitor.update(device.getId(), probeSafely(device), now);
        }

        try {
            evaluateAndReport(devices, now);
        } catch (Exception e) {
            log.warn("Auswertung der Anwesenheit fehlgeschlagen", e);
        }
    }

    private boolean probeSafely(PresenceDevice device) {
        try {
            return probe.probe(device.getHost(), PROBE_PORTS, probeTimeout) == ProbeResult.RESPONDED;
        } catch (Exception e) {
            log.debug("Probe fuer Geraet {} ({}) fehlgeschlagen: {}",
                    device.getId(), device.getHost(), e.getMessage());
            return false;
        }
    }

    private void evaluateAndReport(List<PresenceDevice> devices, Instant now) {
        // TreeMap: stabile Reihenfolge der Meldungen (nach userId)
        Map<Long, List<PresenceDevice>> byUser = devices.stream()
                .collect(Collectors.groupingBy(PresenceDevice::getUserId, TreeMap::new,
                        Collectors.toList()));

        List<PresenceEvaluator.PersonState> states = new ArrayList<>();
        byUser.forEach((userId, userDevices) -> {
            PresenceEvaluator.PersonPresence presence = evaluator.evaluate(userDevices, now);
            states.add(presence.state());
            if (presence.state() == PresenceEvaluator.PersonState.UNKNOWN) {
                // Anlauf-Karenz: kein Update, die Entitaet behaelt ihren DB-Wert.
                // Der Zustand steht trotzdem schon in "states" (oben) - das
                // Aggregat sieht ihn und darf ihn nicht ignorieren.
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
            // Schluessel fehlt statt null zu tragen (Muster Netzwerk-Monitoring).
            // Instant.toString() statt LocalDateTime: Entity-Attribute sind kein
            // API-Antwortbaum (dort gilt die Haushaltszeit-Regel), sondern
            // brauchen einen eindeutigen, zonenbehafteten Zeitstempel (Muster
            // TractiveEntityMapper) — die Umrechnung in Haushaltszeit passiert
            // erst in Task 8 im Status-Service.
            attributes.put("lastSeenAt", presence.lastSeenAt().toString());
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
