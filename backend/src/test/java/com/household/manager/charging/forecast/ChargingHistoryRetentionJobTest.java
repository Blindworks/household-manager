package com.household.manager.charging.forecast;

import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingHistoryRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-11-02T03:50:00Z");

    @Mock
    private ChargingOccupancyHistoryRepository history;
    @Mock
    private ChargingObservationRepository observations;

    private ChargingHistoryRetentionJob job() {
        return new ChargingHistoryRetentionJob(history, observations, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgeLoeschtAelterAls365Tage() {
        Instant cutoff = NOW.minus(ChargingForecastSettings.RETENTION);

        job().purge();

        verify(history).deleteEndedBefore(cutoff);
        verify(observations).deleteHoursBefore(cutoff);
    }

    @Test
    void purgeWirftNieUndLoeschtDenZweitenPfadTrotzdem() {
        when(history.deleteEndedBefore(any())).thenThrow(new RuntimeException("db"));
        ChargingHistoryRetentionJob job = job();

        assertThatCode(job::purge).doesNotThrowAnyException();
        verify(observations).deleteHoursBefore(NOW.minus(ChargingForecastSettings.RETENTION));
    }
}
