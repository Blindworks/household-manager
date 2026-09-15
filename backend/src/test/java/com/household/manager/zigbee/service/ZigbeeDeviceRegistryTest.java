package com.household.manager.zigbee.service;

import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeDeviceRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private final ZigbeeDeviceRegistry registry =
            new ZigbeeDeviceRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

    private static ZigbeeBridgeDevice device(String ieee, String name) {
        return new ZigbeeBridgeDevice(ieee, name, "EndDevice", "Battery", true, true,
                "SNZB-03", "SONOFF", "Motion sensor", 1);
    }

    @Test
    void istVorDemErstenVerzeichnisNichtGeladen() {
        assertThat(registry.loaded()).isFalse();
        assertThat(registry.devices()).isEmpty();
        assertThat(registry.info()).isEmpty();
    }

    @Test
    void ersetztDasVerzeichnisKomplett() {
        registry.replaceDevices(List.of(device("0x1", "A"), device("0x2", "B")));
        registry.replaceDevices(List.of(device("0x2", "B umbenannt")));

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.devices()).extracting(ZigbeeBridgeDevice::friendlyName)
                .containsExactly("B umbenannt");
        assertThat(registry.findByIeee("0x1")).isEmpty();
        assertThat(registry.findByIeee("0x2")).map(ZigbeeBridgeDevice::friendlyName).contains("B umbenannt");
        assertThat(registry.findByFriendlyName("B umbenannt")).isPresent();
        assertThat(registry.findByFriendlyName("B")).isEmpty();
    }

    @Test
    void stempeltEreignisseUndBehaeltNurDieLetzten50NeuesteZuerst() {
        IntStream.rangeClosed(1, 55).forEach(i -> registry.recordEvent(
                new ZigbeeBridgeEvent("device_joined", "0x" + i, "0x" + i, null, null, null, null)));

        List<ZigbeeBridgeEvent> events = registry.events();
        assertThat(events).hasSize(50);
        assertThat(events.get(0).friendlyName()).isEqualTo("0x55");
        assertThat(events.get(49).friendlyName()).isEqualTo("0x6");
        assertThat(events.get(0).receivedAt()).isEqualTo(NOW);
    }

    @Test
    void haeltDieBridgeInfo() {
        registry.updateInfo(new ZigbeeBridgeInfo("2.1.3", true, NOW.plusSeconds(240), true));

        assertThat(registry.info()).isPresent();
        assertThat(registry.info().get().permitJoin()).isTrue();
    }
}
