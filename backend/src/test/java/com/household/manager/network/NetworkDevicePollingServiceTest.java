package com.household.manager.network;

import com.household.manager.model.entity.NetworkDevice;
import com.household.manager.repository.NetworkDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkDevicePollingServiceTest {

    private static final Instant FIRST_INSTANT = Instant.parse("2026-08-24T10:15:00Z");
    private static final Instant SECOND_INSTANT = Instant.parse("2026-08-24T10:16:00Z");
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Mock
    private NetworkDeviceRepository repository;
    @Mock
    private TcpPortProbe tcpPortProbe;

    private NetworkDeviceStatusMonitor monitor;
    private Clock clock;
    private NetworkDevicePollingService service;

    @BeforeEach
    void setUp() {
        monitor = new NetworkDeviceStatusMonitor();
        clock = Clock.fixed(FIRST_INSTANT, ZONE);
        service = new NetworkDevicePollingService(repository, tcpPortProbe, monitor, clock);
    }

    private static NetworkDevice device(Long id, String host, Integer tcpPort) {
        return NetworkDevice.builder()
                .id(id)
                .name("Geraet " + id)
                .host(host)
                .tcpPort(tcpPort)
                .sortOrder(0)
                .active(true)
                .build();
    }

    @Test
    void deviceWithConfiguredPort_probesOnlyThatPort() {
        NetworkDevice device = device(1L, "192.168.1.50", 8123);
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(device));
        when(tcpPortProbe.isOpen(eq("192.168.1.50"), eq(8123), any())).thenReturn(true);

        service.poll();

        verify(tcpPortProbe).isOpen(eq("192.168.1.50"), eq(8123), any());
        verify(tcpPortProbe, never()).isOpen(eq("192.168.1.50"), eq(80), any());
        assertThat(monitor.statusOf(1L)).hasValueSatisfying(status -> assertThat(status.reachable()).isTrue());
    }

    @Test
    void deviceWithoutPort_stopsAtFirstOpenFallbackPort() {
        NetworkDevice device = device(2L, "192.168.1.51", null);
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(device));
        when(tcpPortProbe.isOpen(eq("192.168.1.51"), eq(80), any())).thenReturn(false);
        when(tcpPortProbe.isOpen(eq("192.168.1.51"), eq(443), any())).thenReturn(true);

        service.poll();

        verify(tcpPortProbe).isOpen(eq("192.168.1.51"), eq(80), any());
        verify(tcpPortProbe).isOpen(eq("192.168.1.51"), eq(443), any());
        verify(tcpPortProbe, never()).isOpen(eq("192.168.1.51"), eq(22), any());
        assertThat(monitor.statusOf(2L)).hasValueSatisfying(status -> assertThat(status.reachable()).isTrue());
    }

    @Test
    void deviceWithoutPort_noneOpen_marksUnreachableAndProbesAllSix() {
        NetworkDevice device = device(3L, "192.168.1.52", null);
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(device));
        when(tcpPortProbe.isOpen(eq("192.168.1.52"), anyInt(), any())).thenReturn(false);

        service.poll();

        for (int port : List.of(80, 443, 22, 1883, 8080, 8443)) {
            verify(tcpPortProbe).isOpen(eq("192.168.1.52"), eq(port), any());
        }
        assertThat(monitor.statusOf(3L)).hasValueSatisfying(status -> assertThat(status.reachable()).isFalse());
    }

    @Test
    void onlyActiveDevicesFromRepositoryAreChecked() {
        NetworkDevice device = device(4L, "192.168.1.53", 22);
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(device));
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(true);

        service.poll();

        verify(repository).findByActiveTrueOrderBySortOrderAscIdAsc();
        verify(repository, never()).findAllByOrderBySortOrderAscIdAsc();
        verify(repository, never()).findAll();
    }

    @Test
    void lastSeenAtFreezesOnFailure_lastCheckedAtKeepsMoving() {
        NetworkDevice device = device(5L, "192.168.1.54", 22);
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(device));
        when(tcpPortProbe.isOpen(eq("192.168.1.54"), eq(22), any())).thenReturn(true);

        service.poll();
        assertThat(monitor.statusOf(5L)).hasValueSatisfying(status -> {
            assertThat(status.reachable()).isTrue();
            assertThat(status.lastSeenAt()).isEqualTo(FIRST_INSTANT);
            assertThat(status.lastCheckedAt()).isEqualTo(FIRST_INSTANT);
        });

        Clock secondClock = Clock.fixed(SECOND_INSTANT, ZONE);
        NetworkDevicePollingService serviceSecondRun =
                new NetworkDevicePollingService(repository, tcpPortProbe, monitor, secondClock);
        when(tcpPortProbe.isOpen(eq("192.168.1.54"), eq(22), any())).thenReturn(false);

        serviceSecondRun.poll();

        assertThat(monitor.statusOf(5L)).hasValueSatisfying(status -> {
            assertThat(status.reachable()).isFalse();
            assertThat(status.lastSeenAt()).isEqualTo(FIRST_INSTANT);
            assertThat(status.lastCheckedAt()).isEqualTo(SECOND_INSTANT);
        });
    }

    @Test
    void throwingProbe_marksDeviceUnreachableAndContinuesWithNextDevice() {
        NetworkDevice failing = device(6L, "192.168.1.55", 22);
        NetworkDevice next = device(7L, "192.168.1.56", 22);
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(failing, next));
        when(tcpPortProbe.isOpen(eq("192.168.1.55"), eq(22), any())).thenThrow(new RuntimeException("boom"));
        when(tcpPortProbe.isOpen(eq("192.168.1.56"), eq(22), any())).thenReturn(true);

        assertDoesNotThrow(() -> service.poll());

        assertThat(monitor.statusOf(6L)).hasValueSatisfying(status -> assertThat(status.reachable()).isFalse());
        assertThat(monitor.statusOf(7L)).hasValueSatisfying(status -> assertThat(status.reachable()).isTrue());
    }

    @Test
    void throwingRepository_pollDoesNotThrow() {
        when(repository.findByActiveTrueOrderBySortOrderAscIdAsc()).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.poll());
    }
}
