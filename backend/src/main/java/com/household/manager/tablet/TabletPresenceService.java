package com.household.manager.tablet;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nimmt Präsenz-Meldungen der Wandtablet-App entgegen und spiegelt sie als
 * Binärsensor in den Entity-State-Layer. Tablets registrieren sich implizit
 * mit der ersten Meldung; bleibt der Heartbeat aus, geht die Entität auf
 * "unavailable".
 */
@Service
@Slf4j
public class TabletPresenceService {

    static final String STATE_ON = "on";
    static final String STATE_OFF = "off";
    static final String STATE_UNAVAILABLE = "unavailable";
    static final Duration OFFLINE_THRESHOLD = Duration.ofSeconds(180);

    private record TabletPresence(boolean present, Instant lastSeen, boolean unavailable) {
    }

    private final EntityStateService entityStateService;
    private final Clock clock;
    private final Map<String, TabletPresence> tablets = new ConcurrentHashMap<>();

    @Autowired
    public TabletPresenceService(EntityStateService entityStateService) {
        this(entityStateService, Clock.systemDefaultZone());
    }

    TabletPresenceService(EntityStateService entityStateService, Clock clock) {
        this.entityStateService = entityStateService;
        this.clock = clock;
    }

    public void reportPresence(String tabletId, boolean present) {
        tablets.put(tabletId, new TabletPresence(present, clock.instant(), false));
        reportEntityState(tabletId, present ? STATE_ON : STATE_OFF);
    }

    @Scheduled(fixedDelayString = "${tablet.presence.offline-check-ms:60000}")
    public void markStaleTabletsUnavailable() {
        Instant threshold = clock.instant().minus(OFFLINE_THRESHOLD);
        tablets.forEach((tabletId, presence) -> {
            if (!presence.unavailable() && presence.lastSeen().isBefore(threshold)) {
                tablets.put(tabletId, new TabletPresence(presence.present(), presence.lastSeen(), true));
                log.warn("Tablet '{}' sendet keinen Heartbeat mehr, Entität geht auf unavailable", tabletId);
                reportEntityState(tabletId, STATE_UNAVAILABLE);
            }
        });
    }

    private void reportEntityState(String tabletId, String state) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TABLET, tabletId, "presence"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.TABLET)
                .sourceRef(tabletId)
                .friendlyName(tabletId + " Präsenz")
                .state(state)
                .attributes(Map.of("deviceClass", "presence"))
                .build());
    }
}
