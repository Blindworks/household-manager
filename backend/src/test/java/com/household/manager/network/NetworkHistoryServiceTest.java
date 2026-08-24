package com.household.manager.network;

import com.household.manager.model.entity.NetworkConnectivitySample;
import com.household.manager.model.entity.NetworkSpeedtestResult;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import com.household.manager.service.SeriesRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NetworkHistoryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-24T10:00:00Z");

    @Mock
    private NetworkConnectivitySampleRepository connectivityRepository;
    @Mock
    private NetworkSpeedtestResultRepository speedtestRepository;

    private Clock clock;
    private NetworkHistoryService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZONE);
        service = new NetworkHistoryService(
                connectivityRepository, speedtestRepository, clock, new com.household.manager.service.SeriesDownsampler());
    }

    @Test
    void loadsSamplesForTheLastRangeDays() {
        when(connectivityRepository.findBySampledAtAfterOrderBySampledAtAsc(any())).thenReturn(List.of());
        when(speedtestRepository.findByTestedAtAfterOrderByTestedAtAsc(any())).thenReturn(List.of());

        service.getHistory(SeriesRange.WEEK);

        ArgumentCaptor<LocalDateTime> afterCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(connectivityRepository).findBySampledAtAfterOrderBySampledAtAsc(afterCaptor.capture());
        LocalDateTime expected = LocalDateTime.now(clock).minusDays(SeriesRange.WEEK.getDays());
        assertThat(afterCaptor.getValue()).isEqualTo(expected);

        verify(speedtestRepository).findByTestedAtAfterOrderByTestedAtAsc(eq(expected));
    }

    @Test
    void latency_onlyOnlineSamplesWithLatencyAreIncluded() {
        LocalDateTime t1 = LocalDateTime.of(2026, 8, 20, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 8, 20, 10, 1);
        LocalDateTime t3 = LocalDateTime.of(2026, 8, 20, 10, 2);
        NetworkConnectivitySample onlineWithLatency = NetworkConnectivitySample.builder()
                .sampledAt(t1).online(true).latencyMs(20).gatewayReachable(true).build();
        NetworkConnectivitySample offline = NetworkConnectivitySample.builder()
                .sampledAt(t2).online(false).latencyMs(null).gatewayReachable(false).build();
        NetworkConnectivitySample onlineNullLatency = NetworkConnectivitySample.builder()
                .sampledAt(t3).online(true).latencyMs(null).gatewayReachable(true).build();
        when(connectivityRepository.findBySampledAtAfterOrderBySampledAtAsc(any()))
                .thenReturn(List.of(onlineWithLatency, offline, onlineNullLatency));
        when(speedtestRepository.findByTestedAtAfterOrderByTestedAtAsc(any())).thenReturn(List.of());

        NetworkDtos.HistoryResponse history = service.getHistory(SeriesRange.DAY);

        assertThat(history.latency()).hasSize(1);
        assertThat(history.latency().get(0).getValue()).isEqualByComparingTo("20");
    }

    @Test
    void speedtests_onlySuccessfulResultsAreIncluded() {
        when(connectivityRepository.findBySampledAtAfterOrderBySampledAtAsc(any())).thenReturn(List.of());
        LocalDateTime t1 = LocalDateTime.of(2026, 8, 20, 12, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 8, 20, 13, 0);
        NetworkSpeedtestResult success = NetworkSpeedtestResult.builder()
                .testedAt(t1).downloadMbps(new BigDecimal("100.00")).uploadMbps(new BigDecimal("20.00"))
                .success(true).build();
        NetworkSpeedtestResult failure = NetworkSpeedtestResult.builder()
                .testedAt(t2).success(false).errorMessage("boom").build();
        when(speedtestRepository.findByTestedAtAfterOrderByTestedAtAsc(any()))
                .thenReturn(List.of(success, failure));

        NetworkDtos.HistoryResponse history = service.getHistory(SeriesRange.DAY);

        assertThat(history.speedtests()).hasSize(1);
        assertThat(history.speedtests().get(0).time()).isEqualTo(t1);
        assertThat(history.speedtests().get(0).downloadMbps()).isEqualByComparingTo("100.00");
        assertThat(history.speedtests().get(0).uploadMbps()).isEqualByComparingTo("20.00");
    }
}
