package com.household.manager.network;

import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.model.entity.NetworkSpeedtestResult;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkDeviceRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkStatusServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Mock
    private NetworkConnectivitySampleRepository connectivityRepository;
    @Mock
    private NetworkSpeedtestResultRepository speedtestRepository;
    @Mock
    private NetworkDeviceRepository deviceRepository;
    @Mock
    private NetworkDeviceStatusMonitor monitor;

    private NetworkStatusService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"), ZONE);
        service = new NetworkStatusService(connectivityRepository, speedtestRepository, deviceRepository, monitor, clock);
    }

    @Test
    void latestSamplePresent_reflectsItInStatus() {
        LocalDateTime sampledAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        NetworkConnectivitySample sample = NetworkConnectivitySample.builder()
                .sampledAt(sampledAt).online(true).latencyMs(42).gatewayReachable(true).build();
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.of(sample));
        when(speedtestRepository.findTopBySuccessTrueOrderByTestedAtDesc()).thenReturn(Optional.empty());
        when(deviceRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        NetworkDtos.StatusResponse status = service.getStatus();

        assertThat(status.online()).isTrue();
        assertThat(status.latencyMs()).isEqualTo(42);
        assertThat(status.gatewayReachable()).isTrue();
        assertThat(status.lastCheckedAt()).isEqualTo(sampledAt);
    }

    @Test
    void noSampleAtAll_reportsOfflineWithoutGuessing() {
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.empty());
        when(speedtestRepository.findTopBySuccessTrueOrderByTestedAtDesc()).thenReturn(Optional.empty());
        when(deviceRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        NetworkDtos.StatusResponse status = service.getStatus();

        assertThat(status.online()).isFalse();
        assertThat(status.latencyMs()).isNull();
        assertThat(status.gatewayReachable()).isFalse();
        assertThat(status.lastCheckedAt()).isNull();
    }

    @Test
    void lastSpeedtest_takenFromMostRecentSuccessfulResult() {
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.empty());
        LocalDateTime testedAt = LocalDateTime.of(2026, 8, 24, 9, 0);
        NetworkSpeedtestResult result = NetworkSpeedtestResult.builder()
                .testedAt(testedAt).downloadMbps(new BigDecimal("120.00")).uploadMbps(new BigDecimal("30.00"))
                .success(true).build();
        when(speedtestRepository.findTopBySuccessTrueOrderByTestedAtDesc()).thenReturn(Optional.of(result));
        when(deviceRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        NetworkDtos.StatusResponse status = service.getStatus();

        assertThat(status.lastSpeedtest()).isNotNull();
        assertThat(status.lastSpeedtest().testedAt()).isEqualTo(testedAt);
        assertThat(status.lastSpeedtest().downloadMbps()).isEqualByComparingTo("120.00");
        assertThat(status.lastSpeedtest().uploadMbps()).isEqualByComparingTo("30.00");
        assertThat(status.lastSpeedtest().success()).isTrue();
    }

    @Test
    void noSuccessfulSpeedtestYet_lastSpeedtestIsNull() {
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.empty());
        when(speedtestRepository.findTopBySuccessTrueOrderByTestedAtDesc()).thenReturn(Optional.empty());
        when(deviceRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of());

        NetworkDtos.StatusResponse status = service.getStatus();

        assertThat(status.lastSpeedtest()).isNull();
    }

    @Test
    void devices_onlyActiveOnesIncludedWithMonitorStatus() {
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.empty());
        when(speedtestRepository.findTopBySuccessTrueOrderByTestedAtDesc()).thenReturn(Optional.empty());

        NetworkDevice router = NetworkDevice.builder().id(1L).name("Router").host("192.168.1.1").active(true).build();
        NetworkDevice printer = NetworkDevice.builder().id(2L).name("Drucker").host("192.168.1.50").active(true).build();
        when(deviceRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(router, printer));

        Instant lastSeen = Instant.parse("2026-08-24T09:55:00Z");
        when(monitor.statusOf(1L)).thenReturn(
                Optional.of(new NetworkDeviceStatusMonitor.DeviceStatus(true, lastSeen, lastSeen)));
        when(monitor.statusOf(2L)).thenReturn(Optional.empty());

        NetworkDtos.StatusResponse status = service.getStatus();

        assertThat(status.devices()).hasSize(2);
        NetworkDtos.DeviceStatusResponse routerStatus = status.devices().get(0);
        assertThat(routerStatus.id()).isEqualTo(1L);
        assertThat(routerStatus.name()).isEqualTo("Router");
        assertThat(routerStatus.host()).isEqualTo("192.168.1.1");
        assertThat(routerStatus.reachable()).isTrue();
        assertThat(routerStatus.lastSeenAt()).isEqualTo(LocalDateTime.ofInstant(lastSeen, ZONE));

        NetworkDtos.DeviceStatusResponse printerStatus = status.devices().get(1);
        assertThat(printerStatus.reachable()).isFalse();
        assertThat(printerStatus.lastSeenAt()).isNull();
    }
}
