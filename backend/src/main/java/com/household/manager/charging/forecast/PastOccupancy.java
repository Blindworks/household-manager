package com.household.manager.charging.forecast;

import java.time.Duration;
import java.time.LocalTime;

/** Eine abgeschlossene Belegung: Tagestyp und lokale Startzeit des Beginns sowie Dauer. */
public record PastOccupancy(ForecastDayType dayType, LocalTime start, Duration duration) {
}
