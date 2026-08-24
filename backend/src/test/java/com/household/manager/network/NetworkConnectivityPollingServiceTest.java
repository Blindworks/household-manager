package com.household.manager.network;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkConnectivityPollingServiceTest {

    private static final URI CLOUDFLARE = URI.create("https://1.1.1.1/cdn-cgi/trace");
    private static final URI GSTATIC = URI.create("https://www.gstatic.com/generate_204");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T10:15:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneId.of("Europe/Berlin"));

    @Mock
    private ConnectivityProbe connectivityProbe;
    @Mock
    private TcpPortProbe tcpPortProbe;
    @Mock
    private NetworkConnectivitySampleRepository repository;
    @Mock
    private EntityStateService entityStateService;

    private NetworkConnectivityPollingService service;

    @BeforeEach
    void setUp() {
        service = new NetworkConnectivityPollingService(
                connectivityProbe, tcpPortProbe, repository, entityStateService, FIXED_CLOCK, "192.168.1.1");
    }

    @Test
    void onlineWhenOneTargetReachable_usesItsLatency() {
        when(connectivityProbe.probe(eq(CLOUDFLARE), any())).thenReturn(Optional.of(Duration.ofMillis(42)));
        when(connectivityProbe.probe(eq(GSTATIC), any())).thenReturn(Optional.empty());
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(false);

        service.poll();

        ArgumentCaptor<NetworkConnectivitySample> sampleCaptor = ArgumentCaptor.forClass(NetworkConnectivitySample.class);
        verify(repository).save(sampleCaptor.capture());
        NetworkConnectivitySample saved = sampleCaptor.getValue();
        assertThat(saved.isOnline()).isTrue();
        assertThat(saved.getLatencyMs()).isEqualTo(42);
    }

    @Test
    void bothTargetsReachable_usesMinimumLatency() {
        when(connectivityProbe.probe(eq(CLOUDFLARE), any())).thenReturn(Optional.of(Duration.ofMillis(80)));
        when(connectivityProbe.probe(eq(GSTATIC), any())).thenReturn(Optional.of(Duration.ofMillis(30)));
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(true);

        service.poll();

        ArgumentCaptor<NetworkConnectivitySample> sampleCaptor = ArgumentCaptor.forClass(NetworkConnectivitySample.class);
        verify(repository).save(sampleCaptor.capture());
        assertThat(sampleCaptor.getValue().getLatencyMs()).isEqualTo(30);
    }

    @Test
    void bothTargetsUnreachable_offlineWithNullLatency() {
        when(connectivityProbe.probe(eq(CLOUDFLARE), any())).thenReturn(Optional.empty());
        when(connectivityProbe.probe(eq(GSTATIC), any())).thenReturn(Optional.empty());
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(false);

        service.poll();

        ArgumentCaptor<NetworkConnectivitySample> sampleCaptor = ArgumentCaptor.forClass(NetworkConnectivitySample.class);
        verify(repository).save(sampleCaptor.capture());
        NetworkConnectivitySample saved = sampleCaptor.getValue();
        assertThat(saved.isOnline()).isFalse();
        assertThat(saved.getLatencyMs()).isNull();
        assertThat(saved.getSampledAt()).isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, FIXED_CLOCK.getZone()));
    }

    @Test
    void gatewayCheckTriesPort80FirstThenPort443() {
        when(connectivityProbe.probe(any(), any())).thenReturn(Optional.empty());
        when(tcpPortProbe.isOpen(eq("192.168.1.1"), eq(80), any())).thenReturn(false);
        when(tcpPortProbe.isOpen(eq("192.168.1.1"), eq(443), any())).thenReturn(true);

        service.poll();

        ArgumentCaptor<NetworkConnectivitySample> sampleCaptor = ArgumentCaptor.forClass(NetworkConnectivitySample.class);
        verify(repository).save(sampleCaptor.capture());
        assertThat(sampleCaptor.getValue().isGatewayReachable()).isTrue();
        verify(tcpPortProbe).isOpen(eq("192.168.1.1"), eq(80), any());
        verify(tcpPortProbe).isOpen(eq("192.168.1.1"), eq(443), any());
    }

    @Test
    void gatewayCheckDoesNotTryPort443WhenPort80Open() {
        when(connectivityProbe.probe(any(), any())).thenReturn(Optional.empty());
        when(tcpPortProbe.isOpen(eq("192.168.1.1"), eq(80), any())).thenReturn(true);

        service.poll();

        verify(tcpPortProbe).isOpen(eq("192.168.1.1"), eq(80), any());
        verify(tcpPortProbe, org.mockito.Mockito.never()).isOpen(eq("192.168.1.1"), eq(443), any());
    }

    @Test
    void reportsOnlineEntitiesWithLatencyAttribute() {
        when(connectivityProbe.probe(eq(CLOUDFLARE), any())).thenReturn(Optional.of(Duration.ofMillis(25)));
        when(connectivityProbe.probe(eq(GSTATIC), any())).thenReturn(Optional.empty());
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(true);

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.times(2)).reportState(captor.capture());
        List<EntityStateUpdate> updates = captor.getAllValues();

        EntityStateUpdate internet = updates.stream()
                .filter(u -> u.entityId().equals("binary_sensor.network_internet"))
                .findFirst().orElseThrow();
        assertThat(internet.domain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(internet.state()).isEqualTo("on");
        assertThat(internet.attributes()).containsEntry("deviceClass", "connectivity");
        assertThat(internet.attributes()).containsEntry("gatewayReachable", true);
        assertThat(internet.attributes()).containsEntry("latencyMs", 25);

        EntityStateUpdate latency = updates.stream()
                .filter(u -> u.entityId().equals("sensor.network_latency_ms"))
                .findFirst().orElseThrow();
        assertThat(latency.domain()).isEqualTo(EntityDomain.SENSOR);
        assertThat(latency.state()).isEqualTo("25");
        assertThat(latency.attributes()).containsEntry("unit", "ms");
    }

    @Test
    void offlineReportsOnlyInternetEntityWithoutLatencyKey() {
        when(connectivityProbe.probe(any(), any())).thenReturn(Optional.empty());
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(false);

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate internet = captor.getValue();
        assertThat(internet.entityId()).isEqualTo("binary_sensor.network_internet");
        assertThat(internet.state()).isEqualTo("off");
        assertThat(internet.attributes()).doesNotContainKey("latencyMs");
        verifyNoMoreInteractions(entityStateService);
    }

    @Test
    void throwingProbeIsTreatedAsUnreachableAndDoesNotAbortPoll() {
        when(connectivityProbe.probe(eq(CLOUDFLARE), any())).thenThrow(new RuntimeException("boom"));
        when(connectivityProbe.probe(eq(GSTATIC), any())).thenReturn(Optional.of(Duration.ofMillis(10)));
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(false);

        assertDoesNotThrow(() -> service.poll());

        ArgumentCaptor<NetworkConnectivitySample> sampleCaptor = ArgumentCaptor.forClass(NetworkConnectivitySample.class);
        verify(repository).save(sampleCaptor.capture());
        assertThat(sampleCaptor.getValue().isOnline()).isTrue();
        assertThat(sampleCaptor.getValue().getLatencyMs()).isEqualTo(10);
    }

    @Test
    void throwingRepositoryDoesNotAbortScheduledPoll() {
        when(connectivityProbe.probe(any(), any())).thenReturn(Optional.of(Duration.ofMillis(5)));
        when(tcpPortProbe.isOpen(any(), anyInt(), any())).thenReturn(true);
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.poll());
    }
}
