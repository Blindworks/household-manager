package com.household.manager.network;

import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkHistoryRetentionJobTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T03:20:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZONE);

    @Mock
    private NetworkConnectivitySampleRepository connectivitySampleRepository;
    @Mock
    private NetworkSpeedtestResultRepository speedtestResultRepository;

    private NetworkHistoryRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new NetworkHistoryRetentionJob(connectivitySampleRepository, speedtestResultRepository, FIXED_CLOCK);
    }

    @Test
    void deletesConnectivitySamplesOlderThan30Days() {
        LocalDateTime expectedCutoff = LocalDateTime.now(FIXED_CLOCK).minusDays(30);

        job.retain();

        verify(connectivitySampleRepository).deleteBySampledAtBefore(eq(expectedCutoff));
    }

    @Test
    void deletesSpeedtestResultsOlderThan365Days() {
        LocalDateTime expectedCutoff = LocalDateTime.now(FIXED_CLOCK).minusDays(365);

        job.retain();

        verify(speedtestResultRepository).deleteByTestedAtBefore(eq(expectedCutoff));
    }

    @Test
    void connectivityRepositoryFailure_doesNotPreventSpeedtestDeletion() {
        when(connectivitySampleRepository.deleteBySampledAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> job.retain());

        verify(speedtestResultRepository).deleteByTestedAtBefore(
                eq(LocalDateTime.now(FIXED_CLOCK).minusDays(365)));
    }

    @Test
    void speedtestRepositoryFailure_doesNotPreventConnectivityDeletion() {
        when(speedtestResultRepository.deleteByTestedAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> job.retain());

        verify(connectivitySampleRepository).deleteBySampledAtBefore(
                eq(LocalDateTime.now(FIXED_CLOCK).minusDays(30)));
    }

    @Test
    void bothRepositoriesFail_jobStillDoesNotThrow() {
        when(connectivitySampleRepository.deleteBySampledAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("DB weg"));
        when(speedtestResultRepository.deleteByTestedAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> job.retain());
    }
}
