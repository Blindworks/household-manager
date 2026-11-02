package com.household.manager.charging.forecast;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Einzige Feiertagsregel (Hessen) für die Ladesäulen-Vorhersage; Feiertage zählen als Sonntag. */
public final class HessenHolidays {

    private HessenHolidays() {
    }

    public static boolean isHoliday(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        if ((month == 1 && day == 1) || (month == 5 && day == 1) || (month == 10 && day == 3)
                || (month == 12 && (day == 25 || day == 26))) {
            return true;
        }
        LocalDate easter = easterSunday(date.getYear());
        return date.equals(easter.minusDays(2))
                || date.equals(easter.plusDays(1))
                || date.equals(easter.plusDays(39))
                || date.equals(easter.plusDays(50))
                || date.equals(easter.plusDays(60));
    }

    public static ForecastDayType dayType(LocalDate date) {
        if (isHoliday(date) || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return ForecastDayType.SUNDAY;
        }
        return date.getDayOfWeek() == DayOfWeek.SATURDAY ? ForecastDayType.SATURDAY : ForecastDayType.WEEKDAY;
    }

    /** 0 = Montag … 6 = Sonntag; Feiertag ⇒ 6. */
    public static int heatmapDay(LocalDate date) {
        return isHoliday(date) ? 6 : date.getDayOfWeek().getValue() - 1;
    }

    // Anonymer gregorianischer Algorithmus
    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
