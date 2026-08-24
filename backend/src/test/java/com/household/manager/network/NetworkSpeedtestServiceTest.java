package com.household.manager.network;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.model.entity.NetworkSpeedtestResult;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkSpeedtestServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T10:15:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(10);

    @Mock
    private SpeedtestClient speedtestClient;
    @Mock
    private NetworkSpeedtestResultRepository repository;
    @Mock
    private NetworkConnectivitySampleRepository connectivityRepository;
    @Mock
    private EntityStateService entityStateService;

    private java.util.concurrent.atomic.AtomicReference<Instant> clockInstant;
    private NetworkSpeedtestService service;

    @BeforeEach
    void setUp() {
        clockInstant = new java.util.concurrent.atomic.AtomicReference<>(FIXED_INSTANT);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZONE;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instant instant() {
                return clockInstant.get();
            }
        };
        service = new NetworkSpeedtestService(
                speedtestClient, repository, connectivityRepository, entityStateService, clock, 10);
        // Standardfall: online, sofern nicht ueberschrieben.
        NetworkConnectivitySample online = NetworkConnectivitySample.builder().online(true).build();
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.of(online));
    }

    @Test
    void bothSucceed_savesSuccessRowAndReportsBothEntities() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenReturn(new BigDecimal("123.45"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("45.60"));

        service.runScheduled();

        ArgumentCaptor<NetworkSpeedtestResult> resultCaptor = ArgumentCaptor.forClass(NetworkSpeedtestResult.class);
        verify(repository).save(resultCaptor.capture());
        NetworkSpeedtestResult saved = resultCaptor.getValue();
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getDownloadMbps()).isEqualByComparingTo("123.45");
        assertThat(saved.getUploadMbps()).isEqualByComparingTo("45.60");
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getTestedAt()).isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, ZONE));

        ArgumentCaptor<EntityStateUpdate> updateCaptor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(updateCaptor.capture());
        List<EntityStateUpdate> updates = updateCaptor.getAllValues();

        EntityStateUpdate download = updates.stream()
                .filter(u -> u.entityId().equals("sensor.network_download_mbps"))
                .findFirst().orElseThrow();
        assertThat(download.domain()).isEqualTo(EntityDomain.SENSOR);
        assertThat(download.source()).isEqualTo(com.household.manager.entitystate.EntitySource.NETWORK);
        assertThat(download.sourceRef()).isEqualTo("speedtest");
        assertThat(download.friendlyName()).isEqualTo("Download-Geschwindigkeit");
        assertThat(download.state()).isEqualTo("123.45");
        assertThat(download.attributes()).containsEntry("unit", "Mbit/s");

        EntityStateUpdate upload = updates.stream()
                .filter(u -> u.entityId().equals("sensor.network_upload_mbps"))
                .findFirst().orElseThrow();
        assertThat(upload.friendlyName()).isEqualTo("Upload-Geschwindigkeit");
        assertThat(upload.state()).isEqualTo("45.6");
        assertThat(upload.attributes()).containsEntry("unit", "Mbit/s");
    }

    @Test
    void downloadFailsUploadSucceeds_successTrueOnlyUploadEntityReported() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenThrow(new java.io.IOException("timeout"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("45.60"));

        service.runScheduled();

        ArgumentCaptor<NetworkSpeedtestResult> resultCaptor = ArgumentCaptor.forClass(NetworkSpeedtestResult.class);
        verify(repository).save(resultCaptor.capture());
        NetworkSpeedtestResult saved = resultCaptor.getValue();
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getDownloadMbps()).isNull();
        assertThat(saved.getUploadMbps()).isEqualByComparingTo("45.60");
        assertThat(saved.getErrorMessage()).contains("Download");

        ArgumentCaptor<EntityStateUpdate> updateCaptor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(updateCaptor.capture());
        assertThat(updateCaptor.getValue().entityId()).isEqualTo("sensor.network_upload_mbps");
    }

    @Test
    void bothFail_savesFailureRowWithoutEntityReports() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenThrow(new java.io.IOException("down"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenThrow(new java.io.IOException("up"));

        service.runScheduled();

        ArgumentCaptor<NetworkSpeedtestResult> resultCaptor = ArgumentCaptor.forClass(NetworkSpeedtestResult.class);
        verify(repository).save(resultCaptor.capture());
        NetworkSpeedtestResult saved = resultCaptor.getValue();
        assertThat(saved.isSuccess()).isFalse();
        assertThat(saved.getDownloadMbps()).isNull();
        assertThat(saved.getUploadMbps()).isNull();
        assertThat(saved.getErrorMessage()).contains("Download").contains("Upload");

        verifyNoInteractions(entityStateService);
    }

    @Test
    void lastConnectivitySampleOffline_scheduledRunDoesNothing() throws Exception {
        when(connectivityRepository.findTopByOrderBySampledAtDesc())
                .thenReturn(Optional.of(NetworkConnectivitySample.builder().online(false).build()));

        service.runScheduled();

        verifyNoInteractions(repository);
        verifyNoInteractions(entityStateService);
        verifyNoInteractions(speedtestClient);
    }

    @Test
    void noConnectivitySampleYet_runProceeds() throws Exception {
        when(connectivityRepository.findTopByOrderBySampledAtDesc()).thenReturn(Optional.empty());
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenReturn(new BigDecimal("10.00"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("5.00"));

        service.runScheduled();

        verify(repository).save(any());
    }

    @Test
    void runManual_secondCallWithinCooldown_throwsTooManyRequests() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenReturn(new BigDecimal("10.00"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("5.00"));

        service.runManual();
        clockInstant.set(FIXED_INSTANT.plusSeconds(30));

        assertThatThrownBy(() -> service.runManual())
                .isInstanceOf(TooManyRequestsException.class);

        verify(repository, times(1)).save(any());
    }

    @Test
    void runManual_secondCallWhileFirstStillMeasuring_throwsImmediately() throws Exception {
        // Der Cooldown-Slot muss VOR runMeasurement() reserviert werden, nicht erst danach -
        // sonst laesst ein zweiter Aufruf, der eintrifft waehrend der erste noch misst (Doppelklick,
        // zwei Tabs), beide durch den Cooldown-Check und misst doppelt. Simuliert durch einen
        // verschachtelten Aufruf aus der Mitte der ersten Messung heraus statt echter Nebenlaeufigkeit.
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenAnswer(invocation -> {
            assertThatThrownBy(() -> service.runManual())
                    .isInstanceOf(TooManyRequestsException.class);
            return new BigDecimal("10.00");
        });
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("5.00"));

        service.runManual();

        verify(repository, times(1)).save(any());
    }

    @Test
    void runManual_afterCooldownElapsed_isAllowedAgain() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenReturn(new BigDecimal("10.00"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("5.00"));

        service.runManual();
        clockInstant.set(FIXED_INSTANT.plusSeconds(61));

        assertDoesNotThrow(() -> service.runManual());

        verify(repository, times(2)).save(any());
    }

    @Test
    void runManual_offline_throwsIllegalStateException() {
        when(connectivityRepository.findTopByOrderBySampledAtDesc())
                .thenReturn(Optional.of(NetworkConnectivitySample.builder().online(false).build()));

        assertThatThrownBy(() -> service.runManual())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Kein Internet — Speedtest nicht möglich.");

        verifyNoInteractions(repository);
        verifyNoInteractions(speedtestClient);
    }

    @Test
    void scheduledRun_repositoryThrows_doesNotPropagate() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenReturn(new BigDecimal("10.00"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenReturn(new BigDecimal("5.00"));
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.runScheduled());
    }

    @Test
    void scheduledRun_clientThrowsUnexpectedRuntimeException_doesNotPropagate() throws Exception {
        when(speedtestClient.measureDownloadMbps(BUDGET)).thenThrow(new RuntimeException("boom"));
        when(speedtestClient.measureUploadMbps(BUDGET)).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> service.runScheduled());
    }
}
