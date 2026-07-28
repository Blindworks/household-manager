package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.model.ZigbeeStreamStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeStreamMonitorTest {

    private static final Instant START = Instant.parse("2026-07-28T12:00:00Z");

    /** Verstellbare Uhr, damit Stille ohne echtes Warten testbar ist. */
    private static final class TestClock extends Clock {
        private Instant now = START;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final TestClock clock = new TestClock();
    private final ZigbeeWatchdogProperties properties = new ZigbeeWatchdogProperties();
    private final ZigbeeStreamMonitor monitor = new ZigbeeStreamMonitor(properties, clock);

    @Test
    void istDirektNachDemStartGesund() {
        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void bleibtGesundSolangeNachrichtenKommen() {
        clock.advance(Duration.ofMinutes(14));
        monitor.recordMessage("Temperatur Buero");
        clock.advance(Duration.ofMinutes(14));

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void meldetStilleNachDerSchwelle() {
        clock.advance(Duration.ofMinutes(16));

        ZigbeeStreamStatus status = monitor.status();

        assertThat(status.health()).isEqualTo(ZigbeeStreamStatus.Health.STILL);
        assertThat(status.silentMinutes()).isEqualTo(16);
    }

    @Test
    void meldetStilleGenauAufDerSchwelle() {
        clock.advance(Duration.ofMinutes(15));

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.STILL);
    }

    @Test
    void bleibtGesundKurzVorDerSchwelle() {
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void meldetBridgeOfflineSofort() {
        monitor.recordBridgeState("offline");

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.BRIDGE_OFFLINE);
    }

    @Test
    void bridgeOnlineHebtDenOfflineZustandWiederAuf() {
        monitor.recordBridgeState("offline");
        monitor.recordBridgeState("online");

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void bridgeOfflineGewinntGegenueberStille() {
        monitor.recordBridgeState("offline");
        clock.advance(Duration.ofMinutes(16));

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.BRIDGE_OFFLINE);
    }

    @Test
    void unerwarteterBridgeTextLoestKeinenAlarmAus() {
        monitor.recordBridgeState("restarting");

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void geraetenachrichtNachOfflineMeldungHebtDenAlarmAuf() {
        monitor.recordBridgeState("offline");
        clock.advance(Duration.ofMinutes(1));
        monitor.recordMessage("Temperatur Buero");

        assertThat(monitor.status().health()).isEqualTo(ZigbeeStreamStatus.Health.OK);
    }

    @Test
    void fuehrtOfflineGemeldeteGeraete() {
        monitor.recordAvailability("Motion Flur", false);
        monitor.recordAvailability("Temperatur Buero", true);

        assertThat(monitor.status().offlineDevices()).containsExactly("Motion Flur");
    }

    @Test
    void geraetDasWiederOnlineIstVerschwindetAusDerListe() {
        monitor.recordAvailability("Motion Flur", false);
        monitor.recordAvailability("Motion Flur", true);

        assertThat(monitor.status().offlineDevices()).isEmpty();
    }
}
