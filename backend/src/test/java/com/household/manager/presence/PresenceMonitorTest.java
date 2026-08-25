package com.household.manager.presence;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceMonitorTest {

    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");
    private final PresenceMonitor monitor =
            new PresenceMonitor(Clock.fixed(START, ZoneId.of("Europe/Berlin")));

    @Test
    void merktSichDenStartzeitpunkt() {
        assertThat(monitor.startedAt()).isEqualTo(START);
    }

    @Test
    void lastSeenBleibtBeiStilleAufDemLetztenAntwortZeitpunktStehen() {
        Instant seen = START.plusSeconds(30);
        monitor.update(1L, true, seen);
        monitor.update(1L, false, START.plusSeconds(60));

        PresenceMonitor.DeviceProbeStatus status = monitor.statusOf(1L).orElseThrow();
        assertThat(status.lastSeenAt()).isEqualTo(seen);
        assertThat(status.lastCheckedAt()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    void nieGesehenesGeraetHatKeinLastSeen() {
        monitor.update(2L, false, START.plusSeconds(30));
        assertThat(monitor.statusOf(2L).orElseThrow().lastSeenAt()).isNull();
    }

    @Test
    void removeVergisstDenStatus() {
        monitor.update(3L, true, START.plusSeconds(30));
        monitor.remove(3L);
        assertThat(monitor.statusOf(3L)).isEmpty();
    }
}
