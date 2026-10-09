package com.household.manager.thermostat;

import com.household.manager.dto.TimeValue;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ThermostatHistory;
import com.household.manager.repository.ThermostatHistoryRepository;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.service.SeriesDownsampler;
import com.household.manager.service.SeriesRange;
import com.household.manager.thermostat.dto.HistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Liefert den Verlauf eines Thermostats im Raster des {@link SeriesDownsampler}: Ist und Soll
 * werden je Bucket gemittelt, "heizt" gilt fuer den Bucket, sobald ein Punkt darin heizte.
 */
@Service
@RequiredArgsConstructor
public class ThermostatHistoryService {

    private final ThermostatRepository thermostatRepository;
    private final ThermostatHistoryRepository historyRepository;
    private final SeriesDownsampler downsampler;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HistoryResponse history(Long thermostatId, SeriesRange range) {
        if (!thermostatRepository.existsById(thermostatId)) {
            throw new ResourceNotFoundException("Thermostat", "id", thermostatId);
        }
        LocalDateTime to = LocalDateTime.now(clock);
        List<ThermostatHistory> rows = historyRepository
                .findByThermostatIdAndRecordedAtBetweenOrderByRecordedAtAsc(
                        thermostatId, to.minusDays(range.getDays()), to);

        Map<LocalDateTime, Double> current = averaged(rows, range, ThermostatHistory::getCurrentTemp);
        Map<LocalDateTime, Double> target = averaged(rows, range, ThermostatHistory::getTargetTemp);
        Map<LocalDateTime, Boolean> heating = heatingBuckets(rows, range);

        Map<LocalDateTime, HistoryResponse.Point> points = new TreeMap<>();
        for (LocalDateTime bucket : heating.keySet()) {
            points.put(bucket, new HistoryResponse.Point(bucket, current.get(bucket), target.get(bucket),
                    heating.get(bucket)));
        }
        return new HistoryResponse(List.copyOf(points.values()));
    }

    private Map<LocalDateTime, Double> averaged(List<ThermostatHistory> rows, SeriesRange range,
                                                Function<ThermostatHistory, Double> value) {
        List<TimeValue> series = rows.stream()
                .filter(row -> value.apply(row) != null)
                .map(row -> TimeValue.builder().time(row.getRecordedAt())
                        .value(BigDecimal.valueOf(value.apply(row))).build())
                .toList();
        Map<LocalDateTime, Double> result = new TreeMap<>();
        downsampler.downsample(series, range)
                .forEach(point -> result.put(point.getTime(), point.getValue().doubleValue()));
        return result;
    }

    /** Gleiche Bucket-Grenzen wie der {@link SeriesDownsampler} (Abrunden auf die Bucketlaenge). */
    private Map<LocalDateTime, Boolean> heatingBuckets(List<ThermostatHistory> rows, SeriesRange range) {
        Map<LocalDateTime, Boolean> result = new TreeMap<>();
        for (ThermostatHistory row : rows) {
            long seconds = row.getRecordedAt().toEpochSecond(ZoneOffset.UTC);
            long start = Math.floorDiv(seconds, range.getBucketSeconds()) * range.getBucketSeconds();
            result.merge(LocalDateTime.ofEpochSecond(start, 0, ZoneOffset.UTC), row.isHeating(), Boolean::logicalOr);
        }
        return result;
    }
}
