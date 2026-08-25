package com.household.manager.presence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.EntityState;
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
import java.util.Set;
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
 *
 * <p>Verliert eine Person ihre letzte Geraetezeile (geloescht statt nur
 * deaktiviert, auch direkt in der DB oder waehrend die Anwendung stand), faellt
 * sie aus der Gruppierung und wird deshalb hier — nicht im CRUD-Service —
 * separat als "unavailable" gemeldet, ohne ins Aggregat einzugehen.
 */
@Service
@Slf4j
public class PresencePollingService {

    private static final String STATE_UNAVAILABLE = "unavailable";

    /**
     * 62078 (lockdownd) antwortet auf iPhones fast immer; 80/443 sind Fallbacks.
     * Auch ein "refused" auf jedem dieser Ports beweist Anwesenheit — die Liste
     * muss also nicht vollstaendig sein, nur eine Antwort provozieren.
     */
    private static final List<Integer> PROBE_PORTS = List.of(62078, 80, 443);

    private static final String HOUSEHOLD_REF = "household";
    private static final String HOUSEHOLD_FRIENDLY_NAME = "Jemand zu Hause";

    /**
     * Klemm-Bereich fuer {@code presence.probe-timeout-ms}: 0 bedeutet fuer
     * {@code Socket.connect} "unendlich" (ein unerreichbares Handy bloeckte
     * einen der wenigen geteilten Scheduler-Threads minutenlang, dreimal ueber
     * die drei {@link #PROBE_PORTS}), und ein negativer oder ueber
     * {@code Integer.MAX_VALUE} liegender Wert laesst den {@code (int)}-Cast in
     * {@code SocketPresenceProbe} ueberlaufen bzw. {@code Socket.connect} mit
     * {@link IllegalArgumentException} scheitern — die dort gefangen und zu
     * {@link ProbeResult#SILENT} wird: JEDE Person meldete dauerhaft "off",
     * ohne einen einzigen Log-Eintrag oberhalb von debug. Ein Tippfehler in
     * der Konfiguration darf das nicht auslösen (Muster
     * {@code PresenceSettingsService}: defensiver Fallback statt Absturz).
     *
     * <p><strong>Obergrenze 5 s, nicht 30 s:</strong> ein Zyklus probt alle
     * aktiven Geraete sequentiell auf einem einzigen Scheduler-Thread, jedes
     * Geraet wiederum sequentiell ueber alle drei {@link #PROBE_PORTS} — die
     * Kosten eines abwesenden Geraets sind also {@code Ports x Timeout}, und
     * eines ganzen Zyklus {@code Geraete x Ports x Timeout}. 30 s Timeout
     * ergaeben schon bei einem einzigen unerreichbaren Handy 90 s (3 Ports),
     * genau das Vielfache eines geteilten Threads, das diese Klemme verhindern
     * soll. 5 s begrenzt zusaetzlich den wahrscheinlichsten Tippfehler — eine
     * Null zu viel, {@code 20000} statt {@code 2000} — der bei 30 s Obergrenze
     * unveraendert durchgegangen waere.
     */
    private static final long MIN_PROBE_TIMEOUT_MS = 100;
    private static final long MAX_PROBE_TIMEOUT_MS = 5_000;

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceProbe probe;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final EntityStateService entityStateService;
    private final ObjectMapper objectMapper;
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
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${presence.probe-timeout-ms:2000}") long probeTimeoutMs) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.probe = probe;
        this.monitor = monitor;
        this.evaluator = evaluator;
        this.entityStateService = entityStateService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.probeTimeout = Duration.ofMillis(clampProbeTimeoutMs(probeTimeoutMs));
    }

    private static long clampProbeTimeoutMs(long probeTimeoutMs) {
        long clamped = Math.max(MIN_PROBE_TIMEOUT_MS, Math.min(probeTimeoutMs, MAX_PROBE_TIMEOUT_MS));
        if (clamped != probeTimeoutMs) {
            log.warn("Unplausibler Wert '{}' fuer presence.probe-timeout-ms, nutze {}",
                    probeTimeoutMs, clamped);
        }
        return clamped;
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

        try {
            cleanupOrphanedPersons(byUser.keySet());
        } catch (Exception e) {
            log.warn("Aufraeumen verwaister Personen-Entitaeten fehlgeschlagen", e);
        }
    }

    /**
     * Meldet Personen-Entitaeten als unavailable, deren letzte Geraetezeile in
     * diesem Zyklus fehlt (geloescht statt nur deaktiviert — der Deaktivieren-Pfad
     * behaelt seine Zeile, hier gibt es gar keine mehr). Ohne diesen Schritt wuerde
     * eine solche Person komplett aus der Gruppierung fallen und ihre Entitaet
     * fuer immer auf ihrem letzten Wert einfrieren, ohne Log und ohne Fehler.
     *
     * <p>Bewusst NICHT ins Aggregat aufgenommen (siehe
     * {@link PresenceEvaluator#aggregateState}): eine hier gemeldete
     * UNAVAILABLE-Person wuerde dort mit PRESENT/AWAY-Personen gemischt dauerhaft
     * {@code Optional.empty()} ergeben und das Haushalts-Aggregat einfrieren —
     * genau die im Evaluator-Javadoc dokumentierte stille Falle, die wir hier
     * nicht selbst ausloesen duerfen.
     */
    private void cleanupOrphanedPersons(Set<Long> userIdsWithDeviceRows) {
        for (EntityState entity : entityStateService.find(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE)) {
            Long userId = parsePersonUserId(entity.getSourceRef());
            if (userId == null) {
                // Die Haushalts-Entitaet traegt sourceRef "household" (keine Zahl)
                // und gehoert nicht zu den Personen-Entitaeten.
                continue;
            }
            if (userIdsWithDeviceRows.contains(userId)) {
                continue;
            }
            if (STATE_UNAVAILABLE.equals(entity.getState())) {
                continue;
            }
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(entity.getEntityId())
                    .domain(entity.getDomain())
                    .source(EntitySource.PRESENCE)
                    .sourceRef(entity.getSourceRef())
                    .friendlyName(entity.getFriendlyName())
                    .state(STATE_UNAVAILABLE)
                    .attributes(readAttributes(entity))
                    .build());
        }
    }

    private Long parsePersonUserId(String sourceRef) {
        try {
            return Long.valueOf(sourceRef);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * EntityStateWriter.upsert ueberschreibt die Attribute bei JEDEM Update
     * (Muster {@code ZigbeeAvailabilityWatchdog}). Ohne das Zurueckreichen der
     * gespeicherten Attribute verloere die Entitaet deviceClass/personUserId/
     * lastSeenAt beim Aufraeumen.
     */
    private Map<String, Object> readAttributes(EntityState entity) {
        String raw = entity.getAttributes();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("Attribute von {} nicht lesbar, werden beim Aufraeumen verworfen: {}",
                    entity.getEntityId(), ex.getMessage());
            return Map.of();
        }
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
