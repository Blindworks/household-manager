package com.household.manager.network;

import com.household.manager.dto.TimeValue;
import com.household.manager.model.entity.NetworkSpeedtestResult;
import com.household.manager.repository.NetworkConnectivitySampleRepository;
import com.household.manager.repository.NetworkSpeedtestResultRepository;
import com.household.manager.service.SeriesDownsampler;
import com.household.manager.service.SeriesRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Liefert die Verlaufsreihen fuer die Netzwerk-Ansicht: Latenz (nur aus Online-Samples,
 * gedownsampled ueber den bestehenden {@link SeriesDownsampler}) und erfolgreiche
 * Speedtest-Punkte im gewaehlten Zeitraum.
 */
@Service
@RequiredArgsConstructor
public class NetworkHistoryService {

    private final NetworkConnectivitySampleRepository connectivityRepository;
    private final NetworkSpeedtestResultRepository speedtestRepository;
    private final Clock clock;
    private final SeriesDownsampler seriesDownsampler;

    @Transactional(readOnly = true)
    public NetworkDtos.HistoryResponse getHistory(SeriesRange range) {
        LocalDateTime after = LocalDateTime.now(clock).minusDays(range.getDays());

        List<TimeValue> latencyPoints = connectivityRepository.findBySampledAtAfterOrderBySampledAtAsc(after)
                .stream()
                .filter(sample -> sample.isOnline() && sample.getLatencyMs() != null)
                .map(sample -> TimeValue.builder()
                        .time(sample.getSampledAt())
                        .value(BigDecimal.valueOf(sample.getLatencyMs()))
                        .build())
                .toList();
        List<TimeValue> latency = seriesDownsampler.downsample(latencyPoints, range);

        List<NetworkDtos.SpeedtestPoint> speedtests = speedtestRepository
                .findByTestedAtAfterOrderByTestedAtAsc(after).stream()
                .filter(NetworkSpeedtestResult::isSuccess)
                .map(result -> new NetworkDtos.SpeedtestPoint(
                        result.getTestedAt(), result.getDownloadMbps(), result.getUploadMbps()))
                .toList();

        return new NetworkDtos.HistoryResponse(latency, speedtests);
    }
}
