package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.dto.ZigbeeBridgeEventResponse;
import com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceEntityResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceHealthResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Baut die Geraeteliste der Zigbee-Seite: Grundlage ist zigbee2mqtt's Verzeichnis,
 * dazu Batterie/LQ/lastSeen aus unserer Tabelle, die Entitaeten aus dem Entity-State-Layer
 * und das Urteil aus {@link ZigbeeDeviceHealthResolver}. Geraete, die nur in unserer
 * Tabelle stehen, bleiben als {@code knownToBridge=false} sichtbar statt zu verschwinden.
 */
@Service
@RequiredArgsConstructor
public class ZigbeeDeviceQueryService {

    private final ZigbeeDeviceRegistry registry;
    private final ZigbeeStreamMonitor streamMonitor;
    private final ZigbeeDeviceRepository deviceRepository;
    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;
    private final ZigbeeDeviceHealthResolver healthResolver;
    private final ZigbeeBridgeCommands commands;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ZigbeeDeviceResponse> listDevices() {
        Map<String, ZigbeeDevice> dbByName = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(ZigbeeDevice::getFriendlyName, Function.identity(), (a, b) -> a));
        Map<String, List<EntityState>> entitiesByRef = entityStateService.find(null, EntitySource.ZIGBEE).stream()
                .collect(Collectors.groupingBy(EntityState::getSourceRef));

        List<ZigbeeDeviceResponse> result = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (ZigbeeBridgeDevice bridgeDevice : registry.devices()) {
            ZigbeeDevice db = dbByName.get(bridgeDevice.friendlyName());
            covered.add(bridgeDevice.friendlyName());
            result.add(build(bridgeDevice, db, bridgeDevice.friendlyName(), entitiesByRef));
        }
        for (ZigbeeDevice db : dbByName.values()) {
            if (!covered.contains(db.getFriendlyName())) {
                result.add(build(null, db, db.getFriendlyName(), entitiesByRef));
            }
        }
        result.sort(Comparator.comparing(ZigbeeDeviceResponse::getFriendlyName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private ZigbeeDeviceResponse build(ZigbeeBridgeDevice bridge, ZigbeeDevice db, String friendlyName,
                                       Map<String, List<EntityState>> entitiesByRef) {
        Instant lastHeard = lastHeard(friendlyName, db);
        DeviceHealth health = healthResolver.resolve(new ZigbeeDeviceHealthResolver.Input(
                streamMonitor.availability(friendlyName).orElse(null),
                bridge != null ? bridge.interviewCompleted() : null,
                bridge == null || bridge.battery(),
                lastHeard));
        List<ZigbeeDeviceEntityResponse> entities = entitiesByRef.getOrDefault(friendlyName, List.of()).stream()
                .map(entity -> ZigbeeDeviceEntityResponse.builder()
                        .entityId(entity.getEntityId())
                        .displayName(responseMapper.displayName(entity))
                        .domain(entity.getDomain())
                        .state(entity.getState())
                        .lastChanged(entity.getLastChanged())
                        .lastUpdated(entity.getLastUpdated())
                        .build())
                .toList();
        return ZigbeeDeviceResponse.builder()
                .id(db != null ? db.getId() : null)
                .friendlyName(friendlyName)
                .ieeeAddress(bridge != null ? bridge.ieeeAddress() : (db != null ? db.getIeeeAddress() : null))
                .knownToBridge(bridge != null)
                .type(bridge != null ? bridge.type() : null)
                .powerSource(bridge != null ? bridge.powerSource() : null)
                .battery(bridge == null || bridge.battery())
                .interviewCompleted(bridge != null ? bridge.interviewCompleted() : null)
                .supported(bridge != null ? bridge.supported() : null)
                .model(bridge != null ? bridge.model() : (db != null ? db.getModel() : null))
                .vendor(bridge != null ? bridge.vendor() : null)
                .description(bridge != null ? bridge.description() : null)
                .lastBatteryPercent(db != null ? db.getLastBatteryPercent() : null)
                .lastLinkQuality(db != null ? db.getLastLinkQuality() : null)
                .lastSeen(db != null ? db.getLastSeen() : null)
                .health(ZigbeeDeviceHealthResponse.from(health))
                .entities(entities)
                .build();
    }

    /** Juengster Zeitpunkt aus Speicher-Uhr (seit Start) und DB-lastSeen (ueberlebt Neustarts). */
    private Instant lastHeard(String friendlyName, ZigbeeDevice db) {
        Optional<Instant> inMemory = streamMonitor.lastMessageAt(friendlyName);
        LocalDateTime lastSeen = db != null ? db.getLastSeen() : null;
        Instant fromDb = lastSeen != null ? lastSeen.atZone(clock.getZone()).toInstant() : null;
        if (inMemory.isPresent() && fromDb != null) {
            return inMemory.get().isAfter(fromDb) ? inMemory.get() : fromDb;
        }
        return inMemory.orElse(fromDb);
    }

    public ZigbeeBridgeStatusResponse bridgeStatus() {
        Optional<ZigbeeBridgeInfo> info = registry.info();
        return ZigbeeBridgeStatusResponse.builder()
                .version(info.map(ZigbeeBridgeInfo::version).orElse(null))
                .connected(commands.isConnected())
                .registryLoaded(registry.loaded())
                .permitJoin(info.map(ZigbeeBridgeInfo::permitJoin).orElse(false))
                .permitJoinEnd(info.map(ZigbeeBridgeInfo::permitJoinEnd).orElse(null))
                .availabilityCheckEnabled(info.map(ZigbeeBridgeInfo::availabilityCheckEnabled).orElse(null))
                .build();
    }

    public List<ZigbeeBridgeEventResponse> bridgeEvents() {
        return registry.events().stream().map(ZigbeeBridgeEventResponse::from).toList();
    }
}
