package com.household.manager.service;

import com.household.manager.dto.TimeValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemperatureSeriesDownsamplerTest {

    private final TemperatureSeriesDownsampler downsampler = new TemperatureSeriesDownsampler();

    private TimeValue point(String time, String value) {
        return TimeValue.builder()
                .time(LocalDateTime.parse(time))
                .value(new BigDecimal(value))
                .build();
    }

    @Test
    void mitteltPunkteInnerhalbEinesBuckets() {
        List<TimeValue> input = List.of(
                point("2026-07-31T10:00:00", "20.0"),
                point("2026-07-31T10:02:00", "22.0"),
                point("2026-07-31T10:04:59", "24.0"));

        List<TimeValue> result = downsampler.downsample(input, TemperatureRange.DAY);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:00:00"));
        assertThat(result.get(0).getValue()).isEqualByComparingTo("22.00");
    }

    @Test
    void punktAufDerBucketGrenzeBeginntDenNaechstenBucket() {
        List<TimeValue> input = List.of(
                point("2026-07-31T10:04:59", "20.0"),
                point("2026-07-31T10:05:00", "30.0"));

        List<TimeValue> result = downsampler.downsample(input, TemperatureRange.DAY);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:00:00"));
        assertThat(result.get(1).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:05:00"));
    }
}
