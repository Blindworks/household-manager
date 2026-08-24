package com.household.manager.network;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt den zuletzt ermittelten Erreichbarkeits-Status je LAN-Geraet ausschliesslich im
 * Speicher (Muster {@code ZigbeeStreamMonitor}) - kein Neustart-Ueberleben, keine Historie
 * in v1.
 */
@Component
public class NetworkDeviceStatusMonitor {

    public record DeviceStatus(boolean reachable, Instant lastSeenAt, Instant lastCheckedAt) {
    }

    private final Map<Long, DeviceStatus> statuses = new ConcurrentHashMap<>();

    /**
     * {@code lastSeenAt} bleibt beim Uebergang auf nicht erreichbar auf dem letzten
     * Erreichbar-Zeitpunkt stehen - das ist die Aussage "zuletzt gesehen". War ein Geraet
     * noch nie erreichbar, ist {@code lastSeenAt} {@code null}.
     */
    public void update(Long deviceId, boolean reachable, Instant now) {
        Instant previousLastSeenAt = Optional.ofNullable(statuses.get(deviceId))
                .map(DeviceStatus::lastSeenAt)
                .orElse(null);
        Instant lastSeenAt = reachable ? now : previousLastSeenAt;
        statuses.put(deviceId, new DeviceStatus(reachable, lastSeenAt, now));
    }

    public Optional<DeviceStatus> statusOf(Long deviceId) {
        return Optional.ofNullable(statuses.get(deviceId));
    }

    public void remove(Long deviceId) {
        statuses.remove(deviceId);
    }
}
