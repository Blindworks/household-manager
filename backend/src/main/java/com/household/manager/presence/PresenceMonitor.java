package com.household.manager.presence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt lastSeen/lastChecked je Handy ausschliesslich im Speicher (Muster
 * {@code NetworkDeviceStatusMonitor}). Ueberlebt Neustarts bewusst nicht;
 * {@link #startedAt()} traegt deshalb die Anlauf-Karenz: bis dahin wird bei
 * Stille kein Zustand gemeldet statt "abwesend" zu raten.
 */
@Component
public class PresenceMonitor {

    public record DeviceProbeStatus(Instant lastSeenAt, Instant lastCheckedAt) {
    }

    private final Map<Long, DeviceProbeStatus> statuses = new ConcurrentHashMap<>();
    private final Instant startedAt;

    public PresenceMonitor(Clock clock) {
        this.startedAt = clock.instant();
    }

    public void update(Long deviceId, boolean responded, Instant now) {
        Instant previousLastSeenAt = Optional.ofNullable(statuses.get(deviceId))
                .map(DeviceProbeStatus::lastSeenAt)
                .orElse(null);
        Instant lastSeenAt = responded ? now : previousLastSeenAt;
        statuses.put(deviceId, new DeviceProbeStatus(lastSeenAt, now));
    }

    public Optional<DeviceProbeStatus> statusOf(Long deviceId) {
        return Optional.ofNullable(statuses.get(deviceId));
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void remove(Long deviceId) {
        statuses.remove(deviceId);
    }
}
