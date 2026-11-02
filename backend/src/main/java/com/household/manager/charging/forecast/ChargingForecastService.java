package com.household.manager.charging.forecast;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.model.entity.ChargingPointOccupancy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.household.manager.charging.forecast.ChargingForecastSettings.HORIZONS_MINUTES;
import static com.household.manager.charging.forecast.ChargingForecastSettings.LATEST;
import static com.household.manager.charging.forecast.ChargingForecastSettings.MEDIAN;
import static com.household.manager.charging.forecast.ChargingForecastSettings.MIN_OBSERVED;
import static com.household.manager.charging.forecast.ChargingForecastSettings.MIN_SAMPLES;
import static com.household.manager.charging.forecast.ChargingForecastSettings.START_WINDOW;

/** Rechnet aus dem gelernten Muster „frei bei Ankunft“ und die Restdauer belegter Ladepunkte. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargingForecastService {

    private static final Duration DAY = Duration.ofDays(1);

    private final ChargingPatternService patternService;
    private final Clock clock;
    private final Map<String, ChargingForecast> latest = new ConcurrentHashMap<>();

    /** Rechnet die Vorhersage neu und merkt sie sich; wirft nie. */
    public Optional<ChargingForecast> update(String stationId, ChargingStationDetails details,
                                             Map<String, ChargingPointOccupancy> occupancy) {
        try {
            ChargingForecast forecast = compute(patternService.pattern(stationId), details, occupancy);
            latest.put(stationId, forecast);
            return Optional.of(forecast);
        } catch (RuntimeException e) {
            log.warn("Ladesäulen-Vorhersage für Station {} fehlgeschlagen: {}", stationId, e.toString());
            return Optional.empty();
        }
    }

    public Optional<ChargingForecast> latest(String stationId) {
        return Optional.ofNullable(latest.get(stationId));
    }

    public void forget(String stationId) {
        latest.remove(stationId);
    }

    private ChargingForecast compute(StationPattern pattern, ChargingStationDetails details,
                                     Map<String, ChargingPointOccupancy> occupancy) {
        long observedDays = pattern.observed().toDays();
        if (pattern.observed().compareTo(MIN_OBSERVED) < 0) {
            return new ChargingForecast(ChargingForecast.Status.INSUFFICIENT_DATA, observedDays, Map.of(),
                    List.of(), null, null, pattern.occupancy());
        }
        Instant now = clock.instant();
        ZonedDateTime localNow = now.atZone(clock.getZone());

        List<double[]> freeProbabilities = new ArrayList<>();
        List<ChargingForecast.PointForecast> points = new ArrayList<>();
        boolean anyFree = false;
        for (ChargePoint point : details.chargePoints()) {
            if (point.status() == ChargePointStatus.FREE) {
                anyFree = true;
                freeProbabilities.add(staysFree(pattern, localNow));
            } else if (point.status() == ChargePointStatus.OCCUPIED) {
                OccupiedResult result = occupied(pattern, point, occupancy.get(point.chargePointId()), now);
                points.add(result.forecast());
                freeProbabilities.add(result.freeProbabilities());
            }
            // OUT_OF_SERVICE/UNKNOWN tragen nie zu „frei“ bei
        }

        Map<Integer, Integer> percent = new LinkedHashMap<>();
        for (int i = 0; i < HORIZONS_MINUTES.size(); i++) {
            // Bewusste Vereinfachung: die Ladepunkte gelten als voneinander unabhängig.
            double allBusy = 1.0;
            for (double[] p : freeProbabilities) {
                allBusy *= 1.0 - p[i];
            }
            percent.put(HORIZONS_MINUTES.get(i), (int) Math.round((1.0 - allBusy) * 100));
        }

        Instant expectedFreeAt = anyFree ? null : earliest(points, ChargingForecast.PointForecast::expectedFreeAt);
        Instant expectedFreeLatest = anyFree ? null
                : earliest(points, ChargingForecast.PointForecast::expectedFreeLatest);
        return new ChargingForecast(ChargingForecast.Status.OK, observedDays, Collections.unmodifiableMap(percent),
                List.copyOf(points), expectedFreeAt, expectedFreeLatest, pattern.occupancy());
    }

    /** P(bleibt frei bis now+h) = exp(−λ·h) mit der Startrate der aktuellen Wochentag-/Stundenzelle. */
    private double[] staysFree(StationPattern pattern, ZonedDateTime localNow) {
        int day = HessenHolidays.heatmapDay(localNow.toLocalDate());
        int hour = localNow.getHour();
        double rate = pattern.startsPerFreePointSecond()[day][hour];
        Double cellOccupancy = pattern.occupancy()[day][hour];
        // Unbeobachtete oder voll belegte Stunde: keine freie Zeit, aus der eine Rate lernbar wäre
        if (rate == 0 && (cellOccupancy == null || cellOccupancy >= 1.0)) {
            rate = pattern.overallStartRate();
        }
        double[] result = new double[HORIZONS_MINUTES.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.exp(-rate * HORIZONS_MINUTES.get(i) * 60.0);
        }
        return result;
    }

    private OccupiedResult occupied(StationPattern pattern, ChargePoint point, ChargingPointOccupancy row,
                                    Instant now) {
        double[] probabilities = new double[HORIZONS_MINUTES.size()];
        if (row == null) {
            return new OccupiedResult(
                    new ChargingForecast.PointForecast(point.chargePointId(), null, null, null), probabilities);
        }
        Instant begin = row.getOccupiedSince() != null ? row.getOccupiedSince() : row.getFirstSeenOccupiedAt();
        Duration elapsed = Duration.between(begin, now);
        ZonedDateTime localBegin = begin.atZone(clock.getZone());
        List<Duration> durations = comparisonDurations(pattern.occupancies(), elapsed,
                HessenHolidays.dayType(localBegin.toLocalDate()), localBegin.toLocalTime());
        if (durations.isEmpty()) {
            return new OccupiedResult(
                    new ChargingForecast.PointForecast(point.chargePointId(), begin, null, null), probabilities);
        }
        for (int i = 0; i < probabilities.length; i++) {
            Duration limit = elapsed.plusMinutes(HORIZONS_MINUTES.get(i));
            long freeBy = durations.stream().filter(d -> d.compareTo(limit) <= 0).count();
            probabilities[i] = (double) freeBy / durations.size();
        }
        Instant freeAt = notBefore(begin.plus(quantile(durations, MEDIAN)), now);
        Instant freeLatest = notBefore(begin.plus(quantile(durations, LATEST)), now);
        return new OccupiedResult(
                new ChargingForecast.PointForecast(point.chargePointId(), begin, freeAt, freeLatest), probabilities);
    }

    /**
     * Vergangene Belegungen, die mindestens so lange liefen wie die laufende, aufsteigend sortiert.
     * Stufe 1: gleicher Tagestyp und Beginn im Fenster; Stufe 2: nur Beginnfenster; Stufe 3: alle.
     * Die erste Stufe mit genügend Vergleichen gewinnt, sonst leer.
     */
    private static List<Duration> comparisonDurations(List<PastOccupancy> occupancies, Duration elapsed,
                                                      ForecastDayType dayType, LocalTime start) {
        List<PastOccupancy> candidates = occupancies.stream()
                .filter(o -> o.duration().compareTo(elapsed) >= 0)
                .toList();
        Predicate<PastOccupancy> inWindow = o -> withinStartWindow(o.start(), start);
        List<Predicate<PastOccupancy>> stages = List.of(
                inWindow.and(o -> o.dayType() == dayType),
                inWindow,
                o -> true);
        for (Predicate<PastOccupancy> stage : stages) {
            List<Duration> durations = candidates.stream().filter(stage)
                    .map(PastOccupancy::duration).sorted().toList();
            if (durations.size() >= MIN_SAMPLES) {
                return durations;
            }
        }
        return List.of();
    }

    /** Zirkulärer Abstand über Mitternacht: 23:30 und 00:40 liegen 70 min auseinander. */
    private static boolean withinStartWindow(LocalTime a, LocalTime b) {
        Duration d = Duration.between(a, b).abs();
        Duration circular = d.compareTo(DAY.minus(d)) <= 0 ? d : DAY.minus(d);
        return circular.compareTo(START_WINDOW) <= 0;
    }

    /** Nearest-Rank auf aufsteigend sortierten Dauern. */
    private static Duration quantile(List<Duration> sorted, double q) {
        int rank = (int) Math.ceil(q * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static Instant notBefore(Instant value, Instant now) {
        return value.isBefore(now) ? now : value;
    }

    private static Instant earliest(List<ChargingForecast.PointForecast> points,
                                    Function<ChargingForecast.PointForecast, Instant> field) {
        return points.stream().map(field).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private record OccupiedResult(ChargingForecast.PointForecast forecast, double[] freeProbabilities) {
    }
}
