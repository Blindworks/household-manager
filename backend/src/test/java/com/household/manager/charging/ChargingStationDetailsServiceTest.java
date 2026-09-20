package com.household.manager.charging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingStationDetailsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private ChargingStationSource source;

    private MutableClock clock;
    private ChargingStationDetailsService service;

    /** Verstellbare Uhr, damit der Cache-Ablauf ohne Warten pruefbar ist. */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
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

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new ChargingStationDetailsService(source, clock);
    }

    private static ChargingStationDetails details(Instant since) {
        return new ChargingStationDetails("S1", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS", since, 0.34),
                new ChargePoint("P2", ChargePointStatus.FREE, 150.0, "CCS", null, 0.34)));
    }

    @Test
    void bildetLadepunkteMitBelegungsbeginnAusDerQuelleAb() {
        Instant since = Instant.parse("2026-09-20T09:20:00Z");
        when(source.stationDetails("S1")).thenReturn(details(since));

        List<ChargingDtos.ChargePointResponse> points = service.chargePoints("S1");

        assertThat(points).hasSize(2);
        assertThat(points.get(0).occupiedSince()).isEqualTo(LocalDateTime.ofInstant(since, BERLIN));
        assertThat(points.get(0).minimumDuration()).isFalse();
        assertThat(points.get(0).pricePerKwh()).isEqualTo(0.34);
        assertThat(points.get(1).occupiedSince()).isNull();
    }

    @Test
    void belegtOhneZeitstempelBleibtOhneBeginn() {
        when(source.stationDetails("S1")).thenReturn(details(null));

        List<ChargingDtos.ChargePointResponse> points = service.chargePoints("S1");

        assertThat(points.get(0).occupiedSince()).isNull();
        assertThat(points.get(0).minimumDuration()).isFalse();
    }

    @Test
    void zweiterAbrufInnerhalbDerCacheZeitFragtDieQuelleNicht() {
        when(source.stationDetails("S1")).thenReturn(details(null));

        service.chargePoints("S1");
        clock.advanceSeconds(30);
        service.chargePoints("S1");

        verify(source, times(1)).stationDetails("S1");
    }

    @Test
    void nachAblaufDerCacheZeitWirdNeuGeladen() {
        when(source.stationDetails("S1")).thenReturn(details(null));

        service.chargePoints("S1");
        clock.advanceSeconds(61);
        service.chargePoints("S1");

        verify(source, times(2)).stationDetails("S1");
    }

    @Test
    void quellenfehlerWirdDurchgereichtUndNichtGecacht() {
        when(source.stationDetails("S1")).thenThrow(new ChargingSourceException("weg"));

        assertThatThrownBy(() -> service.chargePoints("S1")).isInstanceOf(ChargingSourceException.class);
        assertThatThrownBy(() -> service.chargePoints("S1")).isInstanceOf(ChargingSourceException.class);
        verify(source, times(2)).stationDetails("S1");
    }
}
