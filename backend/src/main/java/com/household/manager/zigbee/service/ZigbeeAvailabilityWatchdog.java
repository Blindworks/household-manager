package com.household.manager.zigbee.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.EntityState;
import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Erkennt den Ausfall der Zigbee-Anbindung, versucht ihn zuerst selbst zu heilen und
 * meldet ihn erst danach.
 * <p>
 * Der Zwischenzustand RECOVERING existiert, damit ein kurzer Aussetzer nicht nachts
 * das Handy weckt: erst wird ein Reconnect erzwungen, und nur wenn danach innerhalb
 * der Gnadenfrist immer noch nichts ankommt, gilt die Anbindung als ausgefallen.
 * <p>
 * Gemeldet wird ueber die EVENT-Entitaet {@code event.zigbee_bridge_status}; die
 * eigentliche Telegram-Warnung ist ein Flow. Das haelt Wortlaut und Empfaenger ohne
 * Redeploy aenderbar — hat aber den offengelegten Preis, dass die Ausfallmeldung
 * selbst an der Flow-Engine haengt. Fuer einen Zigbee-Ausfall traegt das, weil das
 * Backend dabei laeuft; fuer einen Backend-Ausfall waere dieser Weg untauglich.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZigbeeAvailabilityWatchdog {

    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String EVENT_SOURCE_REF = "bridge";

    /** Die Melde-Entitaet selbst darf nie unavailable werden. */
    private static final String STATUS_ENTITY_ID =
            EntityIds.build(EntityDomain.EVENT, EntitySource.ZIGBEE, EVENT_SOURCE_REF, "status");

    private enum Phase { HEALTHY, RECOVERING, FAILED }

    private final ZigbeeStreamMonitor monitor;
    private final ZigbeeWatchdogProperties properties;
    private final EntityStateService entityStateService;
    private final ZigbeeConnectionControl connectionControl;
    private final ObjectMapper objectMapper;

    private Phase phase = Phase.HEALTHY;
    private long silentAtRecoveryStart;

    /** Wirft nie — ein Fehler hier darf den Scheduler nicht stilllegen. */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    public void check() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            evaluate();
        } catch (Exception ex) {
            log.warn("Zigbee-Watchdog fehlgeschlagen: {}", ex.getMessage(), ex);
        }
    }

    private void evaluate() {
        ZigbeeStreamStatus status = monitor.status();

        if (status.healthy()) {
            if (phase == Phase.FAILED) {
                log.info("Zigbee-Anbindung ist zurueck");
                reportStatusEvent("recovered", status);
            } else if (phase == Phase.RECOVERING) {
                log.info("Zigbee-Anbindung hat sich nach dem erzwungenen Reconnect selbst erholt");
            }
            phase = Phase.HEALTHY;
            return;
        }

        switch (phase) {
            case HEALTHY -> {
                log.warn("Zigbee still seit {} Minuten (Zustand {}) — erzwinge Reconnect",
                        status.silentMinutes(), status.health());
                silentAtRecoveryStart = status.silentMinutes();
                phase = Phase.RECOVERING;
                connectionControl.forceReconnect();
            }
            case RECOVERING -> {
                long waited = status.silentMinutes() - silentAtRecoveryStart;
                if (waited >= properties.recoverGrace().toMinutes()) {
                    log.error("Zigbee-Anbindung ausgefallen: seit {} Minuten keine Nachricht (Zustand {})",
                            status.silentMinutes(), status.health());
                    phase = Phase.FAILED;
                    markEntitiesUnavailable();
                    reportStatusEvent("failed", status);
                }
            }
            case FAILED -> {
                // Bewusst still: einmal melden, nicht minuetlich wiederholen. Sonst wird
                // die Warnung stummgeschaltet und hilft beim naechsten Mal nicht mehr.
            }
        }
    }

    /**
     * EVENT-Entitaeten werden ausgenommen: ein Ereignis hat keinen fortdauernden
     * Zustand, "unavailable" waere dort bedeutungslos.
     */
    private void markEntitiesUnavailable() {
        for (EntityState entity : entityStateService.find(null, EntitySource.ZIGBEE)) {
            if (entity.getDomain() == EntityDomain.EVENT) {
                continue;
            }
            if (STATE_UNAVAILABLE.equals(entity.getState())) {
                continue;
            }
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(entity.getEntityId())
                    .domain(entity.getDomain())
                    .source(EntitySource.ZIGBEE)
                    .sourceRef(entity.getSourceRef())
                    .friendlyName(entity.getFriendlyName())
                    .state(STATE_UNAVAILABLE)
                    .attributes(readAttributes(entity))
                    .build());
        }
    }

    /**
     * EntityStateWriter.upsert ueberschreibt die Attribute bei JEDEM Update. Ohne das
     * Zurueckreichen der gespeicherten Attribute wuerden unit, deviceClass und
     * batteryPercent aller Zigbee-Entitaeten beim Ausfall geloescht.
     */
    private Map<String, Object> readAttributes(EntityState entity) {
        String raw = entity.getAttributes();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("Attribute von {} nicht lesbar, werden beim Ausfall verworfen: {}",
                    entity.getEntityId(), ex.getMessage());
            return Map.of();
        }
    }

    private void reportStatusEvent(String state, ZigbeeStreamStatus status) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("reason", status.health() == ZigbeeStreamStatus.Health.BRIDGE_OFFLINE
                ? "bridge_offline" : "stream_silent");
        attributes.put("silentMinutes", status.silentMinutes());
        attributes.put("offlineDevices", status.offlineDevices());
        if (status.bridgeState() != null) {
            attributes.put("bridgeState", status.bridgeState());
        }

        entityStateService.reportEvent(EntityStateUpdate.builder()
                .entityId(STATUS_ENTITY_ID)
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef(EVENT_SOURCE_REF)
                .friendlyName("Zigbee-Anbindung")
                .state(state)
                .attributes(attributes)
                .build());
    }
}
