package com.household.manager.zigbee.service;

import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * zigbee2mqtt's Geraeteverzeichnis, Bridge-Info und die letzten Bridge-Ereignisse —
 * rein im Speicher (Muster {@link ZigbeeStreamMonitor}). Nach einem Neustart leer,
 * bis der Broker die retained Topics {@code bridge/devices} und {@code bridge/info}
 * nachspielt (Sekunden); {@link #loaded()} macht dieses Fenster fuer die Seite sichtbar.
 * <p>
 * Das Verzeichnis wird bei jeder Nachricht als Ganzes ersetzt (unveraenderlicher
 * Snapshot hinter einem volatile-Feld) — z2m publiziert immer die komplette Liste.
 */
@Component
public class ZigbeeDeviceRegistry {

    static final int MAX_EVENTS = 50;

    private final Clock clock;

    private volatile Map<String, ZigbeeBridgeDevice> byIeee = Map.of();
    private volatile boolean loaded;
    private volatile ZigbeeBridgeInfo info;
    private final Deque<ZigbeeBridgeEvent> events = new ArrayDeque<>();

    @Autowired
    public ZigbeeDeviceRegistry() {
        this(Clock.systemUTC());
    }

    ZigbeeDeviceRegistry(Clock clock) {
        this.clock = clock;
    }

    public void replaceDevices(List<ZigbeeBridgeDevice> devices) {
        Map<String, ZigbeeBridgeDevice> next = new LinkedHashMap<>();
        for (ZigbeeBridgeDevice device : devices) {
            next.put(device.ieeeAddress(), device);
        }
        byIeee = Collections.unmodifiableMap(next);
        loaded = true;
    }

    public void updateInfo(ZigbeeBridgeInfo bridgeInfo) {
        info = bridgeInfo;
    }

    public void recordEvent(ZigbeeBridgeEvent event) {
        ZigbeeBridgeEvent stamped = event.at(clock.instant());
        synchronized (events) {
            events.addFirst(stamped);
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    public boolean loaded() {
        return loaded;
    }

    public List<ZigbeeBridgeDevice> devices() {
        return List.copyOf(byIeee.values());
    }

    public Optional<ZigbeeBridgeDevice> findByIeee(String ieeeAddress) {
        return Optional.ofNullable(byIeee.get(ieeeAddress));
    }

    public Optional<ZigbeeBridgeDevice> findByFriendlyName(String friendlyName) {
        return byIeee.values().stream()
                .filter(device -> device.friendlyName().equals(friendlyName))
                .findFirst();
    }

    public Optional<ZigbeeBridgeInfo> info() {
        return Optional.ofNullable(info);
    }

    /** Neueste zuerst. */
    public List<ZigbeeBridgeEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }
}
