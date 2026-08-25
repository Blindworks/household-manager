package com.household.manager.presence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceMonitorTest {

    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");
    private final PresenceMonitor monitor = new PresenceMonitor();

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

    @Test
    void erholungNachStilleAktualisiertLastSeen() {
        Instant t1 = START.plusSeconds(30);
        Instant t2 = START.plusSeconds(60);
        Instant t3 = START.plusSeconds(90);

        monitor.update(4L, true, t1);
        monitor.update(4L, false, t2);
        monitor.update(4L, true, t3);

        assertThat(monitor.statusOf(4L).orElseThrow().lastSeenAt()).isEqualTo(t3);
    }

    @Test
    void firstCheckedAtWirdEinmaligBeimErstenUpdateGesetztUndBleibtDanachUnveraendert() {
        Instant first = START.plusSeconds(10);
        monitor.update(5L, true, first);
        assertThat(monitor.statusOf(5L).orElseThrow().firstCheckedAt()).isEqualTo(first);

        // Weitere Aufrufe, auch mit wechselndem responded, duerfen firstCheckedAt nicht mehr aendern.
        monitor.update(5L, false, START.plusSeconds(20));
        monitor.update(5L, true, START.plusSeconds(30));

        assertThat(monitor.statusOf(5L).orElseThrow().firstCheckedAt()).isEqualTo(first);
    }
}
