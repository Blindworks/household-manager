package com.household.manager.charging.forecast;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChargingForecastServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String STATION = "st1";

    private final ChargingPatternService patterns = mock(ChargingPatternService.class);
    private ChargingForecastService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(17, 15);
    }

    private ChargingForecastService serviceAt(int hour, int minute) {
        Clock clock = Clock.fixed(at(hour, minute), BERLIN);
        return new ChargingForecastService(patterns, clock);
    }

    /** Montag, 2026-11-02, lokale Zeit Berlin. */
    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(2026, 11, 2, hour, minute, 0, 0, BERLIN).toInstant();
    }

    private static StationPattern pattern(long observedDays, List<PastOccupancy> occupancies) {
        return pattern(observedDays, occupancies, new double[7][24], 0.0);
    }

    private static StationPattern pattern(long observedDays, List<PastOccupancy> occupancies,
                                          double[][] rates, double overallRate) {
        Double[][] heatmap = new Double[7][24];
        heatmap[0][17] = 0.5;
        return new StationPattern(Duration.ofDays(observedDays), heatmap, rates, overallRate, occupancies);
    }

    private static List<PastOccupancy> durations(ForecastDayType type, LocalTime start, int... minutes) {
        List<PastOccupancy> list = new ArrayList<>();
        for (int m : minutes) {
            list.add(new PastOccupancy(type, start, Duration.ofMinutes(m)));
        }
        return list;
    }

    private static ChargePoint point(String id, ChargePointStatus status) {
        return new ChargePoint(id, status, 150.0, "CCS", null, null);
    }

    private static ChargingStationDetails details(ChargePoint... points) {
        return new ChargingStationDetails(STATION, List.of(points));
    }

    private static Map<String, ChargingPointOccupancy> occupied(String id, Instant since) {
        Map<String, ChargingPointOccupancy> map = new HashMap<>();
        map.put(id, ChargingPointOccupancy.builder().chargePointId(id).stationId(STATION)
                .occupiedSince(since).firstSeenOccupiedAt(since).build());
        return map;
    }

    @Test
    void zuWenigDaten() {
        StationPattern p = pattern(13, List.of());
        when(patterns.pattern(STATION)).thenReturn(p);

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.FREE)), Map.of())
                .orElseThrow();

        assertThat(forecast.status()).isEqualTo(ChargingForecast.Status.INSUFFICIENT_DATA);
        assertThat(forecast.observedDays()).isEqualTo(13);
        assertThat(forecast.heatmap()).isSameAs(p.occupancy());
        assertThat(forecast.freeProbabilityPercent()).isEmpty();
        assertThat(forecast.points()).isEmpty();
        assertThat(forecast.expectedFreeAt()).isNull();
        assertThat(forecast.expectedFreeLatest()).isNull();
    }

    @Test
    void restdauerMedianUnd80Prozent() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20,
                durations(ForecastDayType.WEEKDAY, LocalTime.of(17, 0), 20, 30, 40, 50, 60, 70, 80, 90, 100, 110)));

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.OCCUPIED)),
                occupied("a", at(17, 0))).orElseThrow();

        assertThat(forecast.status()).isEqualTo(ChargingForecast.Status.OK);
        assertThat(forecast.expectedFreeAt()).isEqualTo(at(18, 0));
        assertThat(forecast.expectedFreeLatest()).isEqualTo(at(18, 30));
        assertThat(forecast.points()).singleElement().satisfies(pf -> {
            assertThat(pf.chargePointId()).isEqualTo("a");
            assertThat(pf.occupiedSince()).isEqualTo(at(17, 0));
            assertThat(pf.expectedFreeAt()).isEqualTo(at(18, 0));
            assertThat(pf.expectedFreeLatest()).isEqualTo(at(18, 30));
        });
        // elapsed 15: Dauer ≤ 30 / 45 / 75 ⇒ 2, 3, 6 von 10
        assertThat(forecast.freeProbabilityPercent()).containsExactly(
                Map.entry(15, 20), Map.entry(30, 30), Map.entry(60, 60));
    }

    @Test
    void bedingtAufBisherigeDauer() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20,
                durations(ForecastDayType.WEEKDAY, LocalTime.of(16, 35),
                        10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)));

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.OCCUPIED)),
                occupied("a", at(16, 35))).orElseThrow();

        // Vergleichsmenge 40…130 (10 Werte): Median Rang 5 = 80 min
        assertThat(forecast.expectedFreeAt()).isEqualTo(at(17, 55));
        assertThat(forecast.expectedFreeLatest()).isEqualTo(at(18, 25));
    }

    @Test
    void lockerungBeiZuWenigVergleichen() {
        List<PastOccupancy> all = new ArrayList<>(
                durations(ForecastDayType.WEEKDAY, LocalTime.of(17, 0), 20, 30, 40, 50));
        all.addAll(durations(ForecastDayType.SATURDAY, LocalTime.of(5, 0), 60, 70, 80, 90, 100, 110, 120, 130));
        when(patterns.pattern(STATION)).thenReturn(pattern(20, all));

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.OCCUPIED)),
                occupied("a", at(17, 0))).orElseThrow();

        // alle 12: Rang 6 = 70, Rang ⌈9,6⌉=10 = 110
        assertThat(forecast.expectedFreeAt()).isEqualTo(at(18, 10));
        assertThat(forecast.expectedFreeLatest()).isEqualTo(at(18, 50));
    }

    @Test
    void mitternachtImBeginnfenster() {
        ChargingForecastService late = serviceAt(23, 45);
        List<PastOccupancy> all = new ArrayList<>(durations(ForecastDayType.WEEKDAY, LocalTime.of(0, 40),
                60, 60, 60, 60, 60, 60, 60, 60, 60, 60));
        all.addAll(durations(ForecastDayType.SATURDAY, LocalTime.of(23, 30),
                120, 120, 120, 120, 120, 120, 120, 120, 120, 120));
        when(patterns.pattern(STATION)).thenReturn(pattern(20, all));

        ChargingForecast forecast = late.update(STATION, details(point("a", ChargePointStatus.OCCUPIED)),
                occupied("a", at(23, 30))).orElseThrow();

        assertThat(forecast.expectedFreeAt()).isEqualTo(at(23, 30).plus(Duration.ofMinutes(60)));
    }

    @Test
    void freierPunktNachPoisson() {
        double[][] rates = new double[7][24];
        rates[0][17] = 1.0 / 3600;
        when(patterns.pattern(STATION)).thenReturn(pattern(20, List.of(), rates, 0.0));

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.FREE)), Map.of())
                .orElseThrow();

        assertThat(forecast.freeProbabilityPercent()).containsExactly(
                Map.entry(15, 78), Map.entry(30, 61), Map.entry(60, 37));
    }

    @Test
    void unbeobachteteZelleNutztGesamtrate() {
        double[][] rates = new double[7][24];
        StationPattern p = pattern(20, List.of(), rates, 1.0 / 3600);
        p.occupancy()[0][17] = null;
        when(patterns.pattern(STATION)).thenReturn(p);

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.FREE)), Map.of())
                .orElseThrow();

        assertThat(forecast.freeProbabilityPercent()).containsEntry(30, 61);
    }

    @Test
    void kombinationMindestensEiner() {
        double[][] rates = new double[7][24];
        rates[0][17] = Math.log(2) / 1800;
        when(patterns.pattern(STATION)).thenReturn(pattern(20, List.of(), rates, 0.0));

        ChargingForecast forecast = service.update(STATION,
                details(point("a", ChargePointStatus.FREE), point("b", ChargePointStatus.FREE)), Map.of())
                .orElseThrow();

        assertThat(forecast.freeProbabilityPercent()).containsEntry(30, 75);
    }

    @Test
    void alleAusserBetrieb() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20, List.of()));

        ChargingForecast forecast = service.update(STATION,
                details(point("a", ChargePointStatus.OUT_OF_SERVICE), point("b", ChargePointStatus.UNKNOWN)),
                Map.of()).orElseThrow();

        assertThat(forecast.status()).isEqualTo(ChargingForecast.Status.OK);
        assertThat(forecast.freeProbabilityPercent()).containsExactly(
                Map.entry(15, 0), Map.entry(30, 0), Map.entry(60, 0));
        assertThat(forecast.points()).isEmpty();
        assertThat(forecast.expectedFreeAt()).isNull();
        assertThat(forecast.expectedFreeLatest()).isNull();
    }

    @Test
    void belegterPunktOhneBelegungszeile() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20,
                durations(ForecastDayType.WEEKDAY, LocalTime.of(17, 0), 20, 30, 40, 50, 60, 70, 80, 90, 100, 110)));

        ChargingForecast forecast = service.update(STATION, details(point("a", ChargePointStatus.OCCUPIED)),
                Map.of()).orElseThrow();

        assertThat(forecast.freeProbabilityPercent()).containsEntry(30, 0);
        assertThat(forecast.expectedFreeAt()).isNull();
        assertThat(forecast.points()).singleElement().satisfies(pf -> {
            assertThat(pf.expectedFreeAt()).isNull();
            assertThat(pf.expectedFreeLatest()).isNull();
        });
    }

    @Test
    void freierPunktLoeschtStationszeit() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20,
                durations(ForecastDayType.WEEKDAY, LocalTime.of(17, 0), 20, 30, 40, 50, 60, 70, 80, 90, 100, 110)));

        ChargingForecast forecast = service.update(STATION,
                details(point("a", ChargePointStatus.FREE), point("b", ChargePointStatus.OCCUPIED)),
                occupied("b", at(17, 0))).orElseThrow();

        assertThat(forecast.expectedFreeAt()).isNull();
        assertThat(forecast.expectedFreeLatest()).isNull();
        assertThat(forecast.points()).singleElement()
                .satisfies(pf -> assertThat(pf.expectedFreeAt()).isEqualTo(at(18, 0)));
    }

    @Test
    void updateWirftNie() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20, List.of()));
        ChargingForecast first = service.update(STATION, details(point("a", ChargePointStatus.FREE)), Map.of())
                .orElseThrow();
        when(patterns.pattern(STATION)).thenThrow(new IllegalStateException("db weg"));

        assertThat(service.update(STATION, details(point("a", ChargePointStatus.FREE)), Map.of())).isEmpty();
        assertThat(service.latest(STATION)).containsSame(first);
    }

    @Test
    void forgetEntferntLetzteVorhersage() {
        when(patterns.pattern(STATION)).thenReturn(pattern(20, List.of()));
        service.update(STATION, details(point("a", ChargePointStatus.FREE)), Map.of());

        service.forget(STATION);

        assertThat(service.latest(STATION)).isEmpty();
    }
}
