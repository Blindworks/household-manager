package com.household.manager.tractive;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.TractiveEntityMapper;
import com.household.manager.tractive.dto.TractiveGeofenceDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import com.household.manager.tractive.dto.TractiveTrackableRefDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pollt die Tractive-Cloud und spiegelt die Haustiere in den Entity-State-Layer.
 * Bei Cloud-Fehlern oder abgelaufenem Token werden die zuletzt gemeldeten
 * Entitaeten auf {@code unavailable} gesetzt; das Polling bricht nie ab.
 *
 * <p>Live-Tracking wird bewusst nicht aktiviert – gelesen wird nur der zuletzt
 * regulaer gemeldete Positionsbericht, um den Tracker-Akku zu schonen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractivePollingService {

    private final TractiveProperties properties;
    private final TractiveApiClient apiClient;
    private final TractiveAuthService authService;
    private final TractiveEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final TractivePositionRecorder positionRecorder;

    /** Zuletzt erfolgreich gemeldete Updates; Basis fuer die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();
    /** Letzter erfolgreicher Poll-Stand fuer die Frontend-Seite. */
    private volatile List<TractivePetSnapshot> lastSnapshots = List.of();
    /** Bewertungszeitpunkt des letzten erfolgreichen Polls; Basis fuer die Haustier-API. */
    private volatile Instant lastPolledAt;
    /** Letzter erzwungener Abruf; begrenzt das Nachdruecken bei ausbleibenden Daten. */
    private volatile Instant lastForcedRefreshAt;
    /** Sperre nach einem gemeldeten Rate-Limit; {@code null} = keine Sperre. */
    private volatile Instant forcedRefreshBlockedUntil;

    private static final Duration MIN_FORCED_REFRESH_GAP = Duration.ofSeconds(15);
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(60);

    @Scheduled(fixedDelayString = "${tractive.poll-interval-ms:60000}",
            initialDelayString = "${tractive.initial-delay-ms:20000}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            pollOnce();
        } catch (TractiveAuthException ex) {
            // Ohne Anmeldung ist das der Dauerzustand — als Warnung pro Minute waere es Laerm.
            log.debug("Tractive-Polling ohne gueltiges Token: {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("Tractive-Polling fehlgeschlagen: {}", ex.getMessage());
        }
    }

    /**
     * Erzwingt einen sofortigen Abruf (manuelle Aktualisierung im Frontend) und reicht die
     * Ursache eines Fehlschlags an den Aufrufer durch, statt sie wie der Scheduler nur zu
     * loggen — genau das war der Grund, dass die Seite "noch keine Daten" behauptete, ohne
     * zu verraten, warum.
     *
     * <p>Eine {@link TractiveAuthException} wird bewusst NICHT nach aussen gegeben: sie
     * wuerde als 401 beim Frontend landen, und der dortige Auth-Interceptor wirft den
     * Nutzer daraufhin aus der Haushalts-Session — obwohl nur die Tractive-Anmeldung fehlt.
     */
    public void refreshNow() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "Die Tractive-Anbindung ist deaktiviert (tractive.enabled=false).");
        }
        Instant now = Instant.now();
        guardAgainstHammering(now);
        lastForcedRefreshAt = now;
        try {
            PollOutcome outcome = pollOnce();
            if (outcome.pets() == 0) {
                throw new TractiveException(describeEmptyOutcome(outcome));
            }
        } catch (TractiveRateLimitException ex) {
            // Weitere Versuche wuerden das Limit nur hochschaukeln.
            forcedRefreshBlockedUntil = Instant.now().plus(RATE_LIMIT_BACKOFF);
            throw new TractiveRateLimitException("Tractive hat das Rate-Limit gemeldet (zu viele "
                    + "Abrufe in kurzer Zeit). Weitere Abrufe sind für "
                    + RATE_LIMIT_BACKOFF.toSeconds() + " Sekunden gesperrt. Details: " + ex.getMessage());
        } catch (TractiveAuthException ex) {
            throw new IllegalStateException(ex.getMessage());
        }
    }

    /**
     * Ein erzwungener Abruf ist genau das Werkzeug, das man bei „keine Daten" mehrfach
     * drückt — und genau damit treibt man ein Tractive-Rate-Limit weiter hoch. Deshalb ein
     * Mindestabstand, und nach einem gemeldeten Limit eine echte Sperre.
     */
    private void guardAgainstHammering(Instant now) {
        Instant blockedUntil = forcedRefreshBlockedUntil;
        if (blockedUntil != null && now.isBefore(blockedUntil)) {
            throw new TractiveRateLimitException("Tractive hat zuletzt das Rate-Limit gemeldet. "
                    + "Nächster Abruf in " + secondsUntil(now, blockedUntil) + " Sekunden möglich.");
        }
        Instant last = lastForcedRefreshAt;
        if (last != null) {
            Instant nextAllowed = last.plus(MIN_FORCED_REFRESH_GAP);
            if (now.isBefore(nextAllowed)) {
                throw new TractiveRateLimitException("Der letzte Abruf war gerade eben. Bitte "
                        + secondsUntil(now, nextAllowed) + " Sekunden warten — häufigere Abrufe "
                        + "treiben nur das Rate-Limit von Tractive hoch.");
            }
        }
    }

    private long secondsUntil(Instant now, Instant target) {
        return Math.max(1, Duration.between(now, target).toSeconds() + 1);
    }

    /**
     * Sagt, was tatsächlich passiert ist, statt es zu deuten: „kein Tracker im Konto" und
     * „jeder Tracker-Abruf ist gescheitert" sehen im Ergebnis identisch aus, haben aber
     * völlig verschiedene Ursachen.
     */
    private String describeEmptyOutcome(PollOutcome outcome) {
        if (outcome.trackableObjects() == 0) {
            return "Tractive hat auf die Konto-Abfrage eine leere Liste geantwortet — "
                    + "es ist kein Trackable Object lesbar.";
        }
        return "Tractive meldet " + outcome.trackableObjects()
                + " Objekt(e), aber keines davon war lesbar: "
                + String.join("; ", outcome.problems());
    }

    /**
     * Ein Abruf-Durchlauf. Markiert die Entitaeten bei jedem Fehlschlag {@code unavailable}
     * und wirft danach — der Scheduler faengt alles, die manuelle Aktualisierung zeigt es an.
     */
    private synchronized PollOutcome pollOnce() {
        Optional<TractiveAuth> auth = authService.getValidToken();
        if (auth.isEmpty()) {
            markUnavailable();
            throw new TractiveAuthException("Keine gültige Tractive-Anmeldung. Ein Administrator "
                    + "muss sich unter Admin → Hundetracker-Einstellungen neu anmelden.");
        }
        String token = auth.get().getAccessToken();
        String userId = auth.get().getUserId();
        try {
            Instant now = Instant.now();
            List<TractivePetSnapshot> snapshots = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            List<TractiveTrackableRefDto> refs = apiClient.listTrackableObjects(token, userId);
            for (TractiveTrackableRefDto ref : refs) {
                if (ref.id() == null || ref.id().isBlank()) {
                    problems.add("Ein Eintrag in trackable_objects hat kein Feld _id");
                    continue;
                }
                try {
                    collectPet(token, userId, ref.id())
                            .ifPresentOrElse(snapshots::add, () -> problems.add(
                                    "Objekt " + ref.id() + " hat kein device_id (kein Tracker zugeordnet)"));
                } catch (TractiveRateLimitException ex) {
                    // Beim Limit sofort raus: jedes weitere Tier wuerde es hochschaukeln.
                    throw ex;
                } catch (Exception ex) {
                    // Ein kaputter Tracker darf die anderen nicht stoppen, aber die Ursache
                    // muss den Aufrufer erreichen — sonst sieht ein Abruf, bei dem JEDES Tier
                    // scheitert, wie ein Erfolg mit null Tieren aus.
                    log.warn("Tractive-Abruf fuer Objekt {} fehlgeschlagen: {}", ref.id(), ex.getMessage());
                    problems.add("Objekt " + ref.id() + ": " + ex.getMessage());
                }
            }
            // Bewusst VOR dem Mapping: die Historie soll auch dann entstehen, wenn
            // das Mapping der Entitaeten scheitert. Der Recorder wirft nie.
            positionRecorder.record(snapshots);
            List<EntityStateUpdate> updates = new ArrayList<>();
            for (TractivePetSnapshot snapshot : snapshots) {
                try {
                    updates.addAll(mapper.map(snapshot, now));
                } catch (Exception ex) {
                    log.warn("Tractive-Mapping fuer {} fehlgeschlagen: {}",
                            snapshot.trackerId(), ex.getMessage());
                    problems.add("Mapping fuer " + snapshot.trackerId() + ": " + ex.getMessage());
                }
            }
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
            lastSnapshots = List.copyOf(snapshots);
            lastPolledAt = now;
            return new PollOutcome(refs.size(), snapshots.size(), List.copyOf(problems));
        } catch (TractiveRateLimitException ex) {
            // Typ erhalten: der Aufrufer muss ein Rate-Limit von einem Transportfehler
            // unterscheiden koennen, um nicht sofort weiterzuprobieren.
            markUnavailable();
            throw ex;
        } catch (Exception ex) {
            markUnavailable();
            throw new TractiveException("Abruf bei Tractive fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    /**
     * Ein einzelnes Haustier einsammeln. Wirft bei Cloud-/Parse-Fehlern; leer bedeutet
     * ausschliesslich "dieses Objekt hat keinen Tracker zugeordnet". Der Aufrufer muss
     * beide Faelle unterscheiden koennen, sonst sind sie von aussen nicht auseinanderzuhalten.
     */
    private Optional<TractivePetSnapshot> collectPet(String token, String userId, String trackableId) {
        TractiveTrackableDto trackable = apiClient.getTrackable(token, userId, trackableId);
        if (trackable.deviceId() == null || trackable.deviceId().isBlank()) {
            log.debug("Tractive-Objekt {} hat keinen Tracker, wird uebersprungen", trackableId);
            return Optional.empty();
        }
        String trackerId = trackable.deviceId();
        List<GeoZone> zones = apiClient.listGeofences(token, userId, trackerId).stream()
                .map(TractiveGeofenceDto::toZone)
                .flatMap(Optional::stream)
                .toList();
        return Optional.of(new TractivePetSnapshot(trackable,
                apiClient.getPosition(token, userId, trackerId),
                apiClient.getHardware(token, userId, trackerId),
                zones));
    }

    /**
     * Ergebnis eines Abruf-Durchlaufs. Die Zaehler und Gruende sind der einzige Weg, einen
     * echten Erfolg von "Cloud antwortet, aber es kam nichts Verwertbares durch" zu
     * unterscheiden — ohne sie sieht der zweite Fall wie ein Konto ohne Tracker aus.
     */
    private record PollOutcome(int trackableObjects, int pets, List<String> problems) {
    }

    /** Letzter bekannter Stand fuer die Haustier-Seite. */
    public List<TractivePetSnapshot> latestSnapshots() {
        return lastSnapshots;
    }

    /** {@code null}, solange noch kein Poll erfolgreich war. */
    public Instant lastPolledAt() {
        return lastPolledAt;
    }

    /**
     * Die Home-Entitaet ist bewusst ausgenommen: Sie behaelt ihren letzten Wert, weil der
     * Tracker zu Hause absichtlich aus ist und "keine Daten" dort der Normalfall ist.
     */
    private void markUnavailable() {
        for (EntityStateUpdate update : lastUpdates) {
            if (mapper.isHomeEntity(update)) {
                continue;
            }
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(update.entityId())
                    .domain(update.domain())
                    .source(update.source())
                    .sourceRef(update.sourceRef())
                    .friendlyName(update.friendlyName())
                    .state("unavailable")
                    .attributes(update.attributes())
                    .build());
        }
    }
}
