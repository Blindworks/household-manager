package com.household.manager.charging.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HessenHolidaysTest {

    @ParameterizedTest
    @CsvSource({"2026-04-03", "2026-04-06", "2026-05-14", "2026-05-25", "2026-06-04",
            "2027-03-26", "2027-03-29", "2027-05-06", "2027-05-17", "2027-05-27",
            "2026-01-01", "2026-05-01", "2026-10-03", "2026-12-25", "2026-12-26"})
    void hessischeFeiertage(LocalDate date) {
        assertThat(HessenHolidays.isHoliday(date)).isTrue();
    }

    @Test
    void keinFeiertag() {
        assertThat(HessenHolidays.isHoliday(LocalDate.of(2026, 11, 1))).isFalse();
        assertThat(HessenHolidays.isHoliday(LocalDate.of(2026, 1, 6))).isFalse();
    }

    @Test
    void feiertagZaehltAlsSonntag() {
        assertThat(HessenHolidays.dayType(LocalDate.of(2026, 5, 14))).isEqualTo(ForecastDayType.SUNDAY);
        assertThat(HessenHolidays.heatmapDay(LocalDate.of(2026, 5, 14))).isEqualTo(6);
        assertThat(HessenHolidays.dayType(LocalDate.of(2026, 11, 7))).isEqualTo(ForecastDayType.SATURDAY);
        assertThat(HessenHolidays.dayType(LocalDate.of(2026, 11, 2))).isEqualTo(ForecastDayType.WEEKDAY);
        assertThat(HessenHolidays.heatmapDay(LocalDate.of(2026, 11, 2))).isZero();
    }
}
