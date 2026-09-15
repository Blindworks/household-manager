package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.config.ZigbeeDeviceHealthProperties;
import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeDeviceQueryServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = LocalDateTime.of(2026, 9, 15, 12, 0).atZone(BERLIN).toInstant();

    @Mock private ZigbeeDeviceRepository deviceRepository;
    @Mock private EntityStateService entityStateService;
    @Mock private EntityStateResponseMapper responseMapper;
    @Mock private ZigbeeBridgeCommands commands;

    private final Clock clock = Clock.fixed(NOW, BERLIN);
    private final ZigbeeDeviceRegistry registry = new ZigbeeDeviceRegistry(clock);
    private final ZigbeeStreamMonitor monitor = new ZigbeeStreamMonitor(new ZigbeeWatchdogProperties(), clock);
    private ZigbeeDeviceQueryService service;

    @BeforeEach
    void setUp() {
        service = new ZigbeeDeviceQueryService(registry, monitor, deviceRepository, entityStateService,
                responseMapper, new ZigbeeDeviceHealthResolver(new ZigbeeDeviceHealthProperties(), clock),
                commands, clock);
        // lenient: nur der Test mit Entitaeten ruft den Mapper, die anderen wuerden sonst an
        // Mockitos Strict-Stubs-Pruefung scheitern
        lenient().when(responseMapper.displayName(any())).thenAnswer(inv -> ((EntityState) inv.getArgument(0)).getFriendlyName());
    }

    private static ZigbeeBridgeDevice bridgeDevice(String ieee, String name, String powerSource) {
        return new ZigbeeBridgeDevice(ieee, name, "EndDevice", powerSource, true, true, "SNZB-03", "SONOFF", "Motion", 1);
    }

    private static ZigbeeDevice dbDevice(long id, String name, LocalDateTime lastSeen) {
        return ZigbeeDevice.builder().id(id).friendlyName(name).lastBatteryPercent(80)
                .lastLinkQuality(120).lastSeen(lastSeen).build();
    }

    private static EntityState entity(String id, String sourceRef, String state, LocalDateTime updated) {
        return EntityState.builder().entityId(id).domain(EntityDomain.BINARY_SENSOR).source(EntitySource.ZIGBEE)
                .sourceRef(sourceRef).friendlyName(id).state(state).lastChanged(updated).lastUpdated(updated).build();
    }

    @Test
    void verbindetRegistryTabelleUndEntitaeten() {
        registry.replaceDevices(List.of(bridgeDevice("0x1", "Motion Büro", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of(
                dbDevice(7, "Motion Büro", LocalDateTime.of(2026, 8, 21, 16, 36))));
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of(
                entity("binary_sensor.zigbee_motion_buero_occupancy", "Motion Büro", "on", LocalDateTime.of(2026, 8, 21, 16, 36)),
                entity("binary_sensor.zigbee_anderes_contact", "Anderes", "off", LocalDateTime.of(2026, 9, 15, 11, 0))));

        List<ZigbeeDeviceResponse> devices = service.listDevices();

        assertThat(devices).hasSize(1);
        ZigbeeDeviceResponse motion = devices.get(0);
        assertThat(motion.getId()).isEqualTo(7);
        assertThat(motion.getIeeeAddress()).isEqualTo("0x1");
        assertThat(motion.isKnownToBridge()).isTrue();
        assertThat(motion.isBattery()).isTrue();
        assertThat(motion.getModel()).isEqualTo("SNZB-03");
        assertThat(motion.getLastBatteryPercent()).isEqualTo(80);
        assertThat(motion.getHealth().getStatus()).isEqualTo(DeviceHealthStatus.SILENT);
        assertThat(motion.getHealth().getSilentSeconds()).isGreaterThan(20L * 24 * 3600);
        assertThat(motion.getEntities()).extracting("entityId")
                .containsExactly("binary_sensor.zigbee_motion_buero_occupancy");
    }

    @Test
    void geraetNurInDerTabelleBleibtSichtbarAlsNichtBekannt() {
        registry.replaceDevices(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of(
                dbDevice(3, "Alter Sensor", LocalDateTime.of(2026, 1, 1, 0, 0))));
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());

        List<ZigbeeDeviceResponse> devices = service.listDevices();

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).isKnownToBridge()).isFalse();
        assertThat(devices.get(0).getId()).isEqualTo(3);
        assertThat(devices.get(0).getHealth().getStatus()).isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void geraetNurImRegistryHatKeineIdUndIstUnbekannt() {
        registry.replaceDevices(List.of(bridgeDevice("0x9", "0x9", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());

        ZigbeeDeviceResponse fresh = service.listDevices().get(0);

        assertThat(fresh.getId()).isNull();
        assertThat(fresh.getHealth().getStatus()).isEqualTo(DeviceHealthStatus.UNKNOWN);
    }

    @Test
    void speicherUhrSchlaegtDbLastSeenWennJuenger() {
        registry.replaceDevices(List.of(bridgeDevice("0x1", "Temp Keller", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of(
                dbDevice(1, "Temp Keller", LocalDateTime.of(2026, 9, 14, 12, 0))));
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());
        monitor.recordMessage("Temp Keller");

        ZigbeeDeviceResponse device = service.listDevices().get(0);

        assertThat(device.getHealth().getStatus()).isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(device.getHealth().getLastHeardAt()).isEqualTo(NOW);
    }

    @Test
    void z2mOfflineWirdDurchgereicht() {
        registry.replaceDevices(List.of(bridgeDevice("0x1", "Motion Flur", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());
        monitor.recordMessage("Motion Flur");
        monitor.recordAvailability("Motion Flur", false);

        assertThat(service.listDevices().get(0).getHealth().getStatus()).isEqualTo(DeviceHealthStatus.OFFLINE);
    }

    @Test
    void bridgeStatusSpiegeltRegistryUndVerbindung() {
        when(commands.isConnected()).thenReturn(true);
        registry.updateInfo(new ZigbeeBridgeInfo("2.1.3", true, NOW.plusSeconds(200), false));
        registry.replaceDevices(List.of());

        ZigbeeBridgeStatusResponse status = service.bridgeStatus();

        assertThat(status.getVersion()).isEqualTo("2.1.3");
        assertThat(status.isConnected()).isTrue();
        assertThat(status.isRegistryLoaded()).isTrue();
        assertThat(status.isPermitJoin()).isTrue();
        assertThat(status.getPermitJoinEnd()).isEqualTo(NOW.plusSeconds(200));
        assertThat(status.getAvailabilityCheckEnabled()).isFalse();
    }

    @Test
    void bridgeStatusOhneInfoHatNullFuerDiePruefung() {
        assertThat(service.bridgeStatus().getAvailabilityCheckEnabled()).isNull();
        assertThat(service.bridgeStatus().isRegistryLoaded()).isFalse();
    }
}
