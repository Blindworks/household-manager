package com.household.manager.tractive;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.TractiveEntityMapper;
import com.household.manager.tractive.dto.TractiveGeofenceDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    /** Zuletzt erfolgreich gemeldete Updates; Basis fuer die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();
    /** Letzter erfolgreicher Poll-Stand fuer die Frontend-Seite. */
    private volatile List<TractivePetSnapshot> lastSnapshots = List.of();
    /** Bewertungszeitpunkt des letzten erfolgreichen Polls; Basis fuer die Haustier-API. */
    private volatile Instant lastPolledAt;

    @Scheduled(fixedDelayString = "${tractive.poll-interval-ms:60000}",
            initialDelayString = "${tractive.initial-delay-ms:20000}")
    public synchronized void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        Optional<TractiveAuth> auth = authService.getValidToken();
        if (auth.isEmpty()) {
            markUnavailable();
            return;
        }
        String token = auth.get().getAccessToken();
        String userId = auth.get().getUserId();
        try {
            Instant now = Instant.now();
            List<TractivePetSnapshot> snapshots = new ArrayList<>();
            for (var ref : apiClient.listTrackableObjects(token, userId)) {
                collectPet(token, userId, ref.id()).ifPresent(snapshots::add);
            }
            List<EntityStateUpdate> updates = new ArrayList<>();
            for (TractivePetSnapshot snapshot : snapshots) {
                try {
                    updates.addAll(mapper.map(snapshot, now));
                } catch (Exception ex) {
                    log.warn("Tractive-Mapping fuer {} fehlgeschlagen: {}",
                            snapshot.trackerId(), ex.getMessage());
                }
            }
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
            lastSnapshots = List.copyOf(snapshots);
            lastPolledAt = now;
        } catch (Exception ex) {
            log.warn("Tractive-Polling fehlgeschlagen: {}", ex.getMessage());
            markUnavailable();
        }
    }

    /** Ein einzelnes Haustier einsammeln; Fehler betreffen nur dieses Tier. */
    private Optional<TractivePetSnapshot> collectPet(String token, String userId, String trackableId) {
        try {
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
        } catch (Exception ex) {
            log.warn("Tractive-Abruf fuer Objekt {} fehlgeschlagen: {}", trackableId, ex.getMessage());
            return Optional.empty();
        }
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
