package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die einzige Definition von "die Zigbee-Anbindung lebt". Watchdog, Health-Endpunkt
 * und Meldungstext fragen alle diese Klasse, damit sie nicht auseinanderlaufen koennen
 * (gleiches Muster wie {@code TractiveHomeResolver} fuer "zu Hause").
 * <p>
 * Rein im Speicher, kein DB-Zugriff. Der Zustand ueberlebt einen Neustart bewusst
 * NICHT: die Stille-Uhr startet bei jedem Deploy neu, sonst loeste jeder Neustart
 * sofort einen Fehlalarm aus.
 */
@Component
public class ZigbeeStreamMonitor {

    private static final String BRIDGE_ONLINE = "online";

    private final ZigbeeWatchdogProperties properties;
    private final Clock clock;

    private volatile Instant lastMessageAt;
    private volatile String bridgeState;

    /** friendlyName -> online. Nur Geraete, zu denen zigbee2mqtt etwas gemeldet hat. */
    private final Map<String, Boolean> deviceAvailability = new ConcurrentHashMap<>();

    @Autowired
    public ZigbeeStreamMonitor(ZigbeeWatchdogProperties properties) {
        this(properties, Clock.systemUTC());
    }

    // Package-private fuer Tests mit verstellbarer Uhr.
    ZigbeeStreamMonitor(ZigbeeWatchdogProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.lastMessageAt = clock.instant();
    }

    /** Von jeder eingehenden Geraetenachricht aufzurufen. */
    public void recordMessage(String friendlyName) {
        lastMessageAt = clock.instant();
        if (friendlyName != null && !friendlyName.isBlank()) {
            deviceAvailability.put(friendlyName, Boolean.TRUE);
        }
    }

    public void recordBridgeState(String state) {
        bridgeState = state;
    }

    public void recordAvailability(String friendlyName, boolean online) {
        if (friendlyName != null && !friendlyName.isBlank()) {
            deviceAvailability.put(friendlyName, online);
        }
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public ZigbeeStreamStatus status() {
        Instant last = lastMessageAt;
        long silentMinutes = Duration.between(last, clock.instant()).toMinutes();
        List<String> offline = deviceAvailability.entrySet().stream()
                .filter(entry -> Boolean.FALSE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        ZigbeeStreamStatus.Health health;
        if (bridgeState != null && !BRIDGE_ONLINE.equalsIgnoreCase(bridgeState)) {
            health = ZigbeeStreamStatus.Health.BRIDGE_OFFLINE;
        } else if (silentMinutes >= properties.staleAfter().toMinutes()) {
            health = ZigbeeStreamStatus.Health.STILL;
        } else {
            health = ZigbeeStreamStatus.Health.OK;
        }
        return new ZigbeeStreamStatus(health, last, silentMinutes, bridgeState, offline);
    }
}
