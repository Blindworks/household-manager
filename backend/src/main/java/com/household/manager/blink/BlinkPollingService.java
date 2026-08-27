package com.household.manager.blink;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.BlinkEntityMapper;
import com.household.manager.vision.VisionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pollt die Kameraliste des blink-vision-Sidecars und spiegelt die
 * Scharf-Zustände in den Entity-State-Layer. Bei Fehlern (Sidecar down,
 * nicht bei Blink angemeldet) werden die zuletzt gemeldeten Entitäten
 * {@code unavailable} — mit erhaltenen Attributen, denn
 * {@code EntityStateWriter.upsert} überschreibt sie sonst mit null
 * (Muster NukiPollingService/ZigbeeAvailabilityWatchdog).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlinkPollingService {

    private final VisionProperties properties;
    private final BlinkSidecarClient client;
    private final BlinkEntityMapper mapper;
    private final EntityStateService entityStateService;

    /** Zuletzt erfolgreich gemeldete Updates; Basis für die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();

    @Scheduled(fixedDelayString = "${blink.poll-interval-ms:60000}",
            initialDelayString = "${blink.initial-delay-ms:20000}")
    public synchronized void poll() {
        poll(false);
    }

    /**
     * Nachpollen direkt nach einer Schaltaktion. Muss erzwungen sein:
     * blinkpys {@code async_arm()} aendert den lokalen Zustand nicht, und ein
     * ungezwungener Refresh laeuft in die 30-Sekunden-Drossel — das Dashboard
     * zeigte sonst nach dem Schalten weiter den alten Zustand.
     */
    public synchronized void pollForced() {
        poll(true);
    }

    private void poll(boolean force) {
        // Bewusst VisionProperties: es ist derselbe Sidecar-Prozess, ein zweiter
        // Schalter waere eine Luege. Kehrseite, die der Property-Name nicht
        // verraet: wer die Gesichtserkennung abschaltet (etwa aus Datenschutz-
        // gruenden), legt damit auch die Kamera-Entitaeten still — und jeden
        // Flow, der darauf triggert. Soll je nur eines von beiden abschaltbar
        // sein, braucht es ein eigenes blink.enabled.
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<EntityStateUpdate> updates = mapper.map(client.listCameras(force));
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
        } catch (Exception ex) {
            log.warn("Blink polling failed: {}", ex.getMessage());
            markUnavailable();
        }
    }

    private void markUnavailable() {
        for (EntityStateUpdate update : lastUpdates) {
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
