package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeDeviceHealthProperties;
import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeDeviceHealthResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private final ZigbeeDeviceHealthResolver resolver = new ZigbeeDeviceHealthResolver(
            new ZigbeeDeviceHealthProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static ZigbeeDeviceHealthResolver.Input input(Boolean availability, Boolean interviewCompleted,
                                                          boolean battery, Instant lastHeard) {
        return new ZigbeeDeviceHealthResolver.Input(availability, interviewCompleted, battery, lastHeard);
    }

    @Test
    void offlineVonZigbee2mqttSchlaegtAllesAndere() {
        DeviceHealth health = resolver.resolve(input(false, true, true, NOW.minusSeconds(10)));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.OFFLINE);
        assertThat(health.basis()).isEqualTo("zigbee2mqtt");
        assertThat(health.lastHeardAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    void onlineVonZigbee2mqttEntscheidetNichtAlterZaehltTrotzdem() {
        DeviceHealth health = resolver.resolve(input(true, true, true, NOW.minus(Duration.ofHours(30))));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void unfertigesInterviewVorStille() {
        DeviceHealth health = resolver.resolve(input(null, false, true, null));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.INTERVIEW);
        assertThat(health.basis()).isEqualTo("registry");
    }

    @Test
    void nieGehoertIstUnbekanntNichtAktiv() {
        DeviceHealth health = resolver.resolve(input(null, true, true, null));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.UNKNOWN);
        assertThat(health.basis()).isNull();
        assertThat(health.silentFor()).isNull();
    }

    @Test
    void batterieSchwelleIst25Stunden() {
        assertThat(resolver.resolve(input(null, true, true, NOW.minus(Duration.ofHours(24)))).status())
                .isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(resolver.resolve(input(null, true, true, NOW.minus(Duration.ofHours(25)))).status())
                .isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void netzSchwelleIst15Minuten() {
        assertThat(resolver.resolve(input(null, true, false, NOW.minus(Duration.ofMinutes(14)))).status())
                .isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(resolver.resolve(input(null, true, false, NOW.minus(Duration.ofMinutes(15)))).status())
                .isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void ohneRegistryEintragZaehltNurDieStilleUhr() {
        // Geraet nur in unserer Tabelle (knownToBridge=false): interviewCompleted null, battery true
        DeviceHealth health = resolver.resolve(input(null, null, true, NOW.minus(Duration.ofDays(20))));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.SILENT);
        assertThat(health.silentFor()).isEqualTo(Duration.ofDays(20));
        assertThat(health.basis()).isEqualTo("last-message");
    }

    @Test
    void zukuenftigerZeitstempelWirdAufNullGeklemmt() {
        DeviceHealth health = resolver.resolve(input(null, true, true, NOW.plusSeconds(120)));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(health.silentFor()).isEqualTo(Duration.ZERO);
    }
}
