package com.household.manager.rainradar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RainRadarEvaluatorTest {

    private static final Instant MEASURED = Instant.parse("2026-11-17T13:25:00Z");
    private static final Instant NOW = MEASURED.plusSeconds(6 * 60);

    /** Messbild bei MEASURED, danach Vorhersagebilder im 5-Minuten-Takt. */
    private static List<RainRadarFrame> frames(double measured, double... forecast) {
        List<RainRadarFrame> list = new ArrayList<>();
        list.add(new RainRadarFrame(MEASURED.minusSeconds(300), 0, false));
        list.add(new RainRadarFrame(MEASURED, measured, false));
        for (int i = 0; i < forecast.length; i++) {
            list.add(new RainRadarFrame(MEASURED.plusSeconds(300L * (i + 1)), forecast[i], true));
        }
        return list;
    }

    @Test
    void trockenUndNichtsInSicht() {
        RainRadarReading r = RainRadarEvaluator.evaluate(frames(0, 0, 0.12, 0), NOW, 0.3, 15).orElseThrow();

        assertThat(r.raining()).isFalse();
        assertThat(r.rainSoon()).isFalse();
        assertThat(r.expectedRainAt()).isNull();
        assertThat(r.minutesUntilRain()).isNull();
        assertThat(r.measuredAt()).isEqualTo(MEASURED);
    }

    @Test
    void regnetJetzt() {
        RainRadarReading r = RainRadarEvaluator.evaluate(frames(2.04, 1.0), NOW, 0.3, 15).orElseThrow();

        assertThat(r.raining()).isTrue();
        assertThat(r.rainSoon()).isTrue();
        assertThat(r.expectedRainAt()).isNull();
        assertThat(r.peakNextHourMmPerHour()).isEqualTo(2.04);
    }

    @Test
    void regenInZehnMinutenIstBald() {
        // Vorhersagebilder 13:30, 13:35, 13:40 ⇒ Regen ab 13:40, jetzt 13:31 ⇒ 9 Minuten
        RainRadarReading r = RainRadarEvaluator.evaluate(frames(0, 0, 0, 1.2), NOW, 0.3, 15).orElseThrow();

        assertThat(r.raining()).isFalse();
        assertThat(r.rainSoon()).isTrue();
        assertThat(r.expectedRainAt()).isEqualTo(MEASURED.plusSeconds(900));
        assertThat(r.minutesUntilRain()).isEqualTo(9);
        assertThat(r.peakNextHourMmPerHour()).isEqualTo(1.2);
    }

    @Test
    void regenErstInEinerStundeIstNichtBald() {
        double[] forecast = new double[12];
        forecast[11] = 3.0;
        RainRadarReading r = RainRadarEvaluator.evaluate(frames(0, forecast), NOW, 0.3, 15).orElseThrow();

        assertThat(r.rainSoon()).isFalse();
        assertThat(r.minutesUntilRain()).isEqualTo(54);
    }

    @Test
    void schwelleIstInklusive() {
        assertThat(RainRadarEvaluator.evaluate(frames(0.36), NOW, 0.36, 15).orElseThrow().raining()).isTrue();
        assertThat(RainRadarEvaluator.evaluate(frames(0.24), NOW, 0.3, 15).orElseThrow().raining()).isFalse();
    }

    @Test
    void ohneMessbildKeineAussage() {
        List<RainRadarFrame> onlyForecast = List.of(new RainRadarFrame(MEASURED, 5.0, true));

        assertThat(RainRadarEvaluator.evaluate(onlyForecast, NOW, 0.3, 15)).isEmpty();
    }
}
