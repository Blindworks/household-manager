package com.household.manager.nuki;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.NukiEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pollt die Nuki Web API und spiegelt die Schlösser in den Entity-State-Layer.
 * Bei Cloud-Fehlern werden die zuletzt gemeldeten Entitäten auf
 * {@code unavailable} gesetzt; das Polling bricht dadurch nie.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NukiPollingService {

    private final NukiProperties properties;
    private final NukiApiClient apiClient;
    private final NukiEntityMapper mapper;
    private final EntityStateService entityStateService;

    /** Zuletzt erfolgreich gemeldete Updates; Basis für die unavailable-Markierung. */
    private volatile List<EntityStateUpdate> lastUpdates = List.of();

    @Scheduled(fixedDelayString = "${nuki.poll-interval-ms:30000}",
            initialDelayString = "${nuki.initial-delay-ms:15000}")
    public void poll() {
        if (!properties.isConfigured()) {
            return;
        }
        try {
            List<EntityStateUpdate> updates = new ArrayList<>();
            for (var smartlock : apiClient.listSmartlocks()) {
                try {
                    updates.addAll(mapper.map(smartlock));
                } catch (Exception ex) {
                    log.warn("Failed to map Nuki smartlock {}: {}", smartlock.smartlockId(), ex.getMessage());
                }
            }
            updates.forEach(entityStateService::reportState);
            lastUpdates = List.copyOf(updates);
        } catch (NukiException ex) {
            log.warn("Nuki polling failed: {}", ex.getMessage());
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
