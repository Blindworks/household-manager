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

    private static final String BRIDGE_OFFLINE = "offline";

    private final ZigbeeWatchdogProperties properties;
    private final Clock clock;

    private volatile Instant lastMessageAt;
    private volatile String bridgeState;
    private volatile Instant lastBridgeStateAt;

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
        lastBridgeStateAt = clock.instant();
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
        String currentBridgeState = bridgeState;
        Instant currentBridgeStateAt = lastBridgeStateAt;
        long silentMinutes = Duration.between(last, clock.instant()).toMinutes();
        List<String> offline = deviceAvailability.entrySet().stream()
                .filter(entry -> Boolean.FALSE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        // Fail-safe wie bei den Tractive-Geofences: nur ein EXPLIZITES "offline" zaehlt
        // als Ausfall-Indiz. Jeder andere/unerwartete Text (ein kuenftiger Zwischenzustand,
        // ein falsch codiertes Payload) wird fuers Urteil ignoriert, bleibt aber im Record
        // sichtbar. Sonst wuerde ein unerwarteter Text bei laufender Anbindung einen
        // Daueralarm ausloesen - Stille wird ohnehin ueber die eigene Schwelle erkannt.
        boolean explicitlyOffline = currentBridgeState != null
                && BRIDGE_OFFLINE.equalsIgnoreCase(currentBridgeState.trim());

        // Verrast-Schutz: eine offline-Meldung zaehlt nur, solange seitdem keine
        // Geraetenachricht mehr eintraf. Kam danach noch eine Nachricht, ist die Bridge
        // erwiesenermassen am Leben, auch wenn nie ein neues bridge/state kam - sonst
        // wuerde eine einmal verlorene Nachricht das Urteil dauerhaft verrasten.
        boolean supersededByNewerMessage =
                currentBridgeStateAt != null && last.isAfter(currentBridgeStateAt);

        ZigbeeStreamStatus.Health health;
        if (explicitlyOffline && !supersededByNewerMessage) {
            health = ZigbeeStreamStatus.Health.BRIDGE_OFFLINE;
        } else if (silentMinutes >= properties.staleAfter().toMinutes()) {
            health = ZigbeeStreamStatus.Health.STILL;
        } else {
            health = ZigbeeStreamStatus.Health.OK;
        }
        return new ZigbeeStreamStatus(
                health, last, silentMinutes, currentBridgeState, currentBridgeStateAt, offline);
    }
}
