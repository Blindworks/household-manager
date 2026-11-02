package com.household.manager.charging.forecast;

import com.household.manager.model.entity.ChargingObservation;
import com.household.manager.model.entity.ChargingOccupancyRecord;
import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lernt je Station Heatmap, Startraten und vergangene Belegungen; Ergebnis wird {@code CACHE_TTL} lang gehalten. */
@Service
@RequiredArgsConstructor
public class ChargingPatternService {

    private static final int DAYS = 7;
    private static final int HOURS = 24;

    private final ChargingOccupancyHistoryRepository history;
    private final ChargingObservationRepository observations;
    private final Clock clock;

    private final Map<String, CachedPattern> cache = new ConcurrentHashMap<>();

    public StationPattern pattern(String stationId) {
        Instant now = clock.instant();
        CachedPattern cached = cache.get(stationId);
        if (cached != null && Duration.between(cached.loadedAt(), now).compareTo(ChargingForecastSettings.CACHE_TTL) < 0) {
            return cached.pattern();
        }
        StationPattern fresh = load(stationId, now);
        cache.put(stationId, new CachedPattern(now, fresh));
        return fresh;
    }

    public void evict(String stationId) {
        cache.remove(stationId);
    }

    private StationPattern load(String stationId, Instant now) {
        ZoneId zone = clock.getZone();
        Instant from = now.minus(ChargingForecastSettings.PATTERN_WINDOW);
        List<ChargingObservation> hours = observations.findByStationIdAndHourStartGreaterThanEqual(stationId, from);
        List<ChargingOccupancyRecord> records = history.findByStationIdAndStartedAtGreaterThanEqual(stationId, from);

        long[][] free = new long[DAYS][HOURS];
        long[][] occupied = new long[DAYS][HOURS];
        for (ChargingObservation hour : hours) {
            ZonedDateTime local = hour.getHourStart().atZone(zone);
            int day = HessenHolidays.heatmapDay(local.toLocalDate());
            free[day][local.getHour()] += hour.getFreePointSeconds();
            occupied[day][local.getHour()] += hour.getOccupiedPointSeconds();
        }

        int[][] starts = new int[DAYS][HOURS];
        List<PastOccupancy> occupancies = records.stream().map(record -> {
            ZonedDateTime start = record.getStartedAt().atZone(zone);
            starts[HessenHolidays.heatmapDay(start.toLocalDate())][start.getHour()]++;
            LocalDate date = start.toLocalDate();
            return new PastOccupancy(HessenHolidays.dayType(date), start.toLocalTime(),
                    Duration.between(record.getStartedAt(), record.getEndedAt()));
        }).toList();

        Double[][] occupancy = new Double[DAYS][HOURS];
        double[][] rates = new double[DAYS][HOURS];
        long totalFree = 0;
        for (int d = 0; d < DAYS; d++) {
            for (int h = 0; h < HOURS; h++) {
                long denominator = free[d][h] + occupied[d][h];
                occupancy[d][h] = denominator == 0 ? null : (double) occupied[d][h] / denominator;
                rates[d][h] = free[d][h] == 0 ? 0 : (double) starts[d][h] / free[d][h];
                totalFree += free[d][h];
            }
        }
        double overall = totalFree == 0 ? 0 : (double) records.size() / totalFree;
        return new StationPattern(Duration.ofSeconds(observations.sumObservedSeconds(stationId)),
                occupancy, rates, overall, occupancies);
    }

    private record CachedPattern(Instant loadedAt, StationPattern pattern) {
    }
}
