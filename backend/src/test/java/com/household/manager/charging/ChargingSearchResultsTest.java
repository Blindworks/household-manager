package com.household.manager.charging;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingSearchResultsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private static ChargingStation station(String id) {
        return new ChargingStation(id, id, "Op", null, 50.0, 8.0, 150.0, 2, 1);
    }

    @Test
    void findetEineStationDerLetztenSuche() {
        ChargingSearchResults results = new ChargingSearchResults(new MutableClock(NOW));

        results.remember(List.of(station("A")));

        assertThat(results.find("A")).isPresent();
        assertThat(results.find("B")).isEmpty();
    }

    @Test
    void nachDreissigMinutenIstDieSucheVergessen() {
        MutableClock clock = new MutableClock(NOW);
        ChargingSearchResults results = new ChargingSearchResults(clock);
        results.remember(List.of(station("A")));

        clock.advance(Duration.ofMinutes(29));
        assertThat(results.find("A")).isPresent();
        clock.advance(Duration.ofMinutes(2));
        assertThat(results.find("A")).isEmpty();
    }

    @Test
    void eineNeueSucheErsetztDieVorige() {
        ChargingSearchResults results = new ChargingSearchResults(new MutableClock(NOW));
        results.remember(List.of(station("A")));

        results.remember(List.of(station("B")));

        assertThat(results.find("A")).isEmpty();
        assertThat(results.find("B")).isPresent();
    }

    /** Veraenderbare Uhr fuer Ablauf- und Sperren-Tests. */
    static final class MutableClock extends Clock {
        private final ZoneId zone;
        private Instant now;

        MutableClock(Instant now) {
            this(now, ZoneOffset.UTC);
        }

        MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return other;
                }

                @Override
                public Clock withZone(ZoneId again) {
                    return MutableClock.this.withZone(again);
                }

                @Override
                public Instant instant() {
                    return MutableClock.this.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
