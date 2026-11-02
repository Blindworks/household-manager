package com.household.manager.charging.forecast;

import com.household.manager.model.entity.ChargingObservation;
import com.household.manager.model.entity.ChargingOccupancyRecord;
import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChargingPatternServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String STATION = "st1";

    private final ChargingOccupancyHistoryRepository history = mock(ChargingOccupancyHistoryRepository.class);
    private final ChargingObservationRepository observations = mock(ChargingObservationRepository.class);
    private MutableClock clock;
    private ChargingPatternService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(local(2026, 10, 30, 12, 0).toInstant());
        service = new ChargingPatternService(history, observations, clock);
        when(history.findByStationIdAndStartedAtGreaterThanEqual(anyString(), any())).thenReturn(List.of());
        when(observations.findByStationIdAndHourStartGreaterThanEqual(anyString(), any())).thenReturn(List.of());
    }

    @Test
    void heatmapAusBeobachtungen() {
        when(observations.findByStationIdAndHourStartGreaterThanEqual(anyString(), any()))
                .thenReturn(List.of(obs(local(2026, 10, 26, 18, 0).toInstant(), 3600, 3600, 3600)));
        when(observations.sumObservedSeconds(STATION)).thenReturn(3600L);

        StationPattern pattern = service.pattern(STATION);

        assertThat(pattern.occupancy()[0][18]).isEqualTo(0.5);
        assertThat(pattern.occupancy()[0][19]).isNull();
        assertThat(pattern.observed()).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    void pauseIstKeineNull() {
        when(observations.findByStationIdAndHourStartGreaterThanEqual(anyString(), any())).thenReturn(List.of(
                obs(local(2026, 9, 28, 10, 0).toInstant(), 3600, 3600, 0),
                obs(local(2026, 10, 26, 10, 0).toInstant(), 3600, 0, 3600)));
        when(observations.sumObservedSeconds(STATION)).thenReturn(7200L);

        StationPattern pattern = service.pattern(STATION);

        assertThat(pattern.observed()).isEqualTo(Duration.ofSeconds(7200));
        assertThat(pattern.occupancy()[0][10]).isEqualTo(0.5);
        assertThat(pattern.occupancy()[1][10]).isNull();
        assertThat(pattern.occupancy()[0][11]).isNull();
    }

    @Test
    void feiertagLandetImSonntag() {
        when(observations.findByStationIdAndHourStartGreaterThanEqual(anyString(), any()))
                .thenReturn(List.of(obs(local(2026, 5, 14, 9, 0).toInstant(), 3600, 0, 3600)));

        StationPattern pattern = service.pattern(STATION);

        assertThat(pattern.occupancy()[6][9]).isEqualTo(1.0);
        assertThat(pattern.occupancy()[3][9]).isNull();
    }

    @Test
    void cacheWirdNachEinerStundeErneuert() {
        service.pattern(STATION);
        clock.advance(Duration.ofMinutes(30));
        service.pattern(STATION);
        verify(observations, times(1)).sumObservedSeconds(STATION);

        clock.advance(Duration.ofMinutes(31));
        service.pattern(STATION);
        verify(observations, times(2)).sumObservedSeconds(STATION);
    }

    @Test
    void evictErzwingtNeuladen() {
        service.pattern(STATION);
        service.evict(STATION);
        service.pattern(STATION);
        verify(observations, times(2)).sumObservedSeconds(STATION);
    }

    @Test
    void startrateJeFreierPunktSekunde() {
        when(observations.findByStationIdAndHourStartGreaterThanEqual(anyString(), any()))
                .thenReturn(List.of(obs(local(2026, 10, 26, 18, 0).toInstant(), 3600, 7200, 0)));
        when(history.findByStationIdAndStartedAtGreaterThanEqual(anyString(), any())).thenReturn(List.of(
                record(local(2026, 10, 26, 18, 10), 30), record(local(2026, 10, 26, 18, 40), 90)));

        StationPattern pattern = service.pattern(STATION);

        assertThat(pattern.startsPerFreePointSecond()[0][18]).isEqualTo(2.0 / 7200);
        assertThat(pattern.startsPerFreePointSecond()[0][19]).isZero();
        assertThat(pattern.overallStartRate()).isEqualTo(2.0 / 7200);
    }

    @Test
    void belegungenTragenTagestypStartzeitUndDauer() {
        when(history.findByStationIdAndStartedAtGreaterThanEqual(anyString(), any())).thenReturn(List.of(
                record(local(2026, 10, 24, 23, 30), 45)));

        StationPattern pattern = service.pattern(STATION);

        assertThat(pattern.occupancies()).containsExactly(
                new PastOccupancy(ForecastDayType.SATURDAY, LocalTime.of(23, 30), Duration.ofMinutes(45)));
    }

    @Test
    void ohneBeobachtungKeineStartrate() {
        StationPattern pattern = service.pattern(STATION);
        assertThat(pattern.overallStartRate()).isZero();
        assertThat(pattern.observed()).isEqualTo(Duration.ZERO);
    }

    private static java.time.ZonedDateTime local(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(BERLIN);
    }

    private static ChargingObservation obs(Instant hourStart, int observed, long free, long occupied) {
        return ChargingObservation.builder().stationId(STATION).hourStart(hourStart)
                .observedSeconds(observed).freePointSeconds(free).occupiedPointSeconds(occupied).build();
    }

    private static ChargingOccupancyRecord record(java.time.ZonedDateTime start, int minutes) {
        return ChargingOccupancyRecord.builder().stationId(STATION).chargePointId("p")
                .startedAt(start.toInstant()).endedAt(start.toInstant().plus(Duration.ofMinutes(minutes))).build();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return BERLIN;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
