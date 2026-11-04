package com.household.manager.dirigera;

import com.household.manager.dirigera.client.DirigeraCertificateMismatchException;
import com.household.manager.dirigera.client.DirigeraHttpClient;
import com.household.manager.dirigera.client.DirigeraUnauthorizedException;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.DirigeraSensorRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fragt den Dirigera-Hub minuetlich ab und spiegelt die Sensoren in DB, Verlauf und Entity-State-Layer.
 * Ein Hubfehler macht alle Sensor-Entitaeten unavailable (einmalig, mit erhaltenen Attributen);
 * die Scheduled-Methode wirft nie. Fenster-/Tuerkontakte kommen zusaetzlich sofort ueber den
 * Ereignisstrom ({@link #applyContactChange}); der Poll ist dafuer nur noch der Abgleich.
 */
@Service
@Slf4j
public class DirigeraPollingService {

    public record PollStatus(Instant lastSuccessAt, String lastError) {
    }

    // Spaltenbreite von name/room/model (VARCHAR(100)); ein laengerer IKEA-Name liesse jedes Speichern scheitern
    private static final int MAX_TEXT_LENGTH = 100;

    private final DirigeraSettingsService settingsService;
    private final DirigeraHttpClient httpClient;
    private final DirigeraDeviceParser parser;
    private final DirigeraSensorRepository repository;
    private final DirigeraEntityMapper mapper;
    private final DirigeraReadingRecorder recorder;
    private final DirigeraButtonRegistry buttons;
    private final EntityStateService entityStateService;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;
    private final boolean enabled;
    private final int staleAfterMinutes;

    // Nur im Speicher, fuer die Admin-Seite
    private volatile PollStatus status = new PollStatus(null, null);
    /**
     * Zeitpunkt des letzten Kontakt-Ereignisses je Sensor. Ein Poll, dessen Abruf vor diesem
     * Zeitpunkt begann, traegt einen aelteren Stand und darf {@code lastOpen} nicht ueberschreiben —
     * sonst flackerte der Kontakt auf → zu → auf und Flows feuerten doppelt.
     */
    private final Map<String, Instant> contactEventAt = new ConcurrentHashMap<>();

    public DirigeraPollingService(DirigeraSettingsService settingsService,
                                  DirigeraHttpClient httpClient,
                                  DirigeraDeviceParser parser,
                                  DirigeraSensorRepository repository,
                                  DirigeraEntityMapper mapper,
                                  DirigeraReadingRecorder recorder,
                                  DirigeraButtonRegistry buttons,
                                  EntityStateService entityStateService,
                                  SystemJobMonitor jobMonitor,
                                  Clock clock,
                                  @Value("${dirigera.enabled:true}") boolean enabled,
                                  @Value("${dirigera.stale-after-minutes:60}") int staleAfterMinutes) {
        this.settingsService = settingsService;
        this.httpClient = httpClient;
        this.parser = parser;
        this.repository = repository;
        this.mapper = mapper;
        this.recorder = recorder;
        this.buttons = buttons;
        this.entityStateService = entityStateService;
        this.jobMonitor = jobMonitor;
        this.clock = clock;
        this.enabled = enabled;
        this.staleAfterMinutes = staleAfterMinutes;
    }

    public PollStatus lastStatus() {
        return status;
    }

    @Scheduled(fixedDelayString = "${dirigera.poll-interval-ms:60000}", initialDelayString = "30000")
    public void poll() {
        try {
            DirigeraHubSettings settings = settingsService.get();
            if (!enabled || !settings.isPaired()) {
                // Auch hier auf unavailable: ein Poll, der vor dem Entkoppeln las, kann nach
                // markAllUnavailable noch Werte gemeldet haben (idempotent, meldet nur Wechsel)
                markAllUnavailable();
                jobMonitor.reportInactive("nicht gekoppelt");
                return;
            }
            DirigeraDevices devices;
            Instant fetchStartedAt = Instant.now(clock);
            try {
                devices = parser.parse(httpClient.getDevices(
                        settings.host(), settings.certSha256(), settings.accessToken()));
            } catch (DirigeraCertificateMismatchException e) {
                fail("Zertifikat geändert – neu koppeln");
                return;
            } catch (DirigeraUnauthorizedException e) {
                fail("Hub lehnt Token ab – neu koppeln");
                return;
            } catch (RuntimeException e) {
                fail(describe(e));
                return;
            }
            if (!Objects.equals(devices.gatewayId(), settings.hubId())) {
                fail("Anderes Gerät unter der Hub-Adresse");
                return;
            }
            apply(devices, fetchStartedAt);
            buttons.replaceAll(devices.buttons());
            status = new PollStatus(Instant.now(clock), null);
        } catch (Exception e) {
            fail(describe(e));
        }
    }

    /** Hub nicht erreichbar/nicht mehr gekoppelt: alle bekannten Sensor-Entitaeten auf unavailable. */
    public void markAllUnavailable() {
        for (DirigeraSensor row : repository.findByKnownToHubTrue()) {
            try {
                reportUnavailableOnce(row);
            } catch (Exception e) {
                log.warn("Dirigera-Sensor {} nicht auf unavailable gesetzt: {}", row.getRelationId(), e.getMessage());
            }
        }
    }

    /** Nach dem Entkoppeln: alte Fehlermeldung/Erfolgszeit verwerfen, Entitaeten auf unavailable. */
    public void onUnpaired() {
        status = new PollStatus(null, null);
        markAllUnavailable();
    }

    private static String truncate(String value) {
        return value != null && value.length() > MAX_TEXT_LENGTH ? value.substring(0, MAX_TEXT_LENGTH) : value;
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void fail(String reason) {
        log.warn("Dirigera-Poll fehlgeschlagen: {}", reason);
        jobMonitor.reportFailure(reason);
        status = new PollStatus(status.lastSuccessAt(), reason);
        try {
            markAllUnavailable();
        } catch (Exception e) {
            log.warn("Dirigera-Sensoren nicht auf unavailable gesetzt: {}", e.getMessage());
        }
    }

    /**
     * Zustandswechsel eines Kontakts aus dem Ereignisstrom: sofort in Zeile und Entitaet. Ein dem
     * Poll noch unbekannter Sensor wird ignoriert (der naechste Poll legt ihn an). Wirft nie.
     */
    public synchronized void applyContactChange(DirigeraContactChange change) {
        try {
            Optional<DirigeraSensor> found = repository.findByRelationId(change.key())
                    .filter(row -> row.getKind() == DirigeraSensorKind.CONTACT);
            if (found.isEmpty()) {
                log.debug("Dirigera-Kontakt {} noch unbekannt, wartet auf den Poll", change.key());
                return;
            }
            DirigeraSensor row = found.get();
            contactEventAt.put(change.key(), Instant.now(clock));
            row.setLastOpen(change.open());
            if (change.lastSeen() != null) {
                row.setLastSeenAt(LocalDateTime.ofInstant(change.lastSeen(), clock.getZone()));
            }
            if (change.reachable() != null) {
                row.setReachable(change.reachable());
            }
            DirigeraSensor saved = repository.save(row);
            DirigeraSensor current = saved != null ? saved : row;
            if (current.isKnownToHub() && isFresh(current)) {
                mapper.updates(current).forEach(entityStateService::reportState);
            }
        } catch (Exception e) {
            log.warn("Dirigera-Kontakt {} nicht aus dem Ereignis uebernommen: {}", change.key(), e.getMessage());
        }
    }

    private synchronized void apply(DirigeraDevices devices, Instant fetchStartedAt) {
        Set<String> seen = new HashSet<>();
        for (DirigeraSensorSnapshot snapshot : devices.sensors()) {
            seen.add(snapshot.relationId());
            try {
                applySnapshot(snapshot, fetchStartedAt);
            } catch (Exception e) {
                log.warn("Dirigera-Sensor {} nicht verarbeitet: {}", snapshot.relationId(), e.getMessage());
            }
        }
        for (DirigeraSensor row : repository.findByKnownToHubTrue()) {
            if (seen.contains(row.getRelationId())) {
                continue;
            }
            try {
                row.setKnownToHub(false);
                repository.save(row);
                reportUnavailableOnce(row);
            } catch (Exception e) {
                log.warn("Dirigera-Sensor {} nicht als entfernt markiert: {}", row.getRelationId(), e.getMessage());
            }
        }
    }

    private void applySnapshot(DirigeraSensorSnapshot s, Instant fetchStartedAt) {
        DirigeraSensor row = repository.findByRelationId(s.relationId())
                .orElseGet(() -> DirigeraSensor.builder().relationId(s.relationId()).build());
        row.setKnownToHub(true);
        row.setKind(s.kind());
        Instant eventAt = contactEventAt.get(s.relationId());
        if (eventAt == null || eventAt.isBefore(fetchStartedAt)) {
            row.setLastOpen(s.open());
        }
        row.setName(truncate(s.name()));
        row.setRoom(truncate(s.room()));
        row.setModel(truncate(s.model()));
        row.setLastTemperature(s.temperature());
        row.setLastHumidity(s.humidity());
        row.setBatteryPercent(s.batteryPercent());
        row.setReachable(s.reachable());
        row.setLastSeenAt(s.lastSeen() == null ? null : LocalDateTime.ofInstant(s.lastSeen(), clock.getZone()));
        DirigeraSensor saved = repository.save(row);
        // save liefert die verwaltete Instanz mit Id (Recorder braucht sie fuer die Historie)
        DirigeraSensor current = saved != null ? saved : row;

        if (isFresh(current)) {
            // Verlauf nur fuer Thermometer; Kontakte haben keine Messwertreihe
            if (current.getKind() == DirigeraSensorKind.ENVIRONMENT) {
                recorder.record(current, current.getLastSeenAt());
            }
            mapper.updates(current).forEach(entityStateService::reportState);
        } else {
            reportUnavailableOnce(current);
        }
    }

    /**
     * Thermometer melden laufend, bei ihnen ist ein altes {@code lastSeen} ein Ausfall. Kontakte
     * (MYGGBETT) aktualisieren {@code lastSeen} am Hub nur bei einem Auf/Zu — ein ruhendes Fenster
     * waere sonst nach {@code stale-after-minutes} {@code unavailable} (real 2026-11-04). Fuer sie
     * zaehlt deshalb allein die Erreichbarkeit, die der Hub selbst meldet.
     */
    private boolean isFresh(DirigeraSensor row) {
        if (!row.isReachable()) {
            return false;
        }
        if (row.getKind() == DirigeraSensorKind.CONTACT) {
            return true;
        }
        if (row.getLastSeenAt() == null) {
            return false;
        }
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(staleAfterMinutes);
        return !row.getLastSeenAt().isBefore(threshold);
    }

    /** Nur melden, wenn die Entitaet nicht schon unavailable ist (Muster BleSensorAvailabilityWatchdog). */
    private void reportUnavailableOnce(DirigeraSensor row) {
        for (EntityStateUpdate update : mapper.unavailable(row)) {
            boolean already = entityStateService.getByEntityId(update.entityId())
                    .map(EntityState::getState)
                    .filter(DirigeraEntityMapper.STATE_UNAVAILABLE::equals)
                    .isPresent();
            if (!already) {
                entityStateService.reportState(update);
            }
        }
    }
}
