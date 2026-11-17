package com.household.manager.rainradar;

import java.time.Instant;

/**
 * Urteil des Regenradars fuer das Haus.
 *
 * @param measuredAt       Zeitpunkt des juengsten gemessenen Bildes
 * @param mmPerHour        gemessene Intensitaet dort
 * @param raining          Intensitaet erreicht die Schwelle
 * @param rainSoon         es regnet oder Regen wird binnen {@code soonMinutes} erwartet
 * @param expectedRainAt   erstes Vorhersagebild ueber der Schwelle ({@code null}, wenn es schon regnet
 *                         oder in den naechsten zwei Stunden nichts kommt)
 * @param minutesUntilRain Minuten bis dahin ab jetzt, nie negativ ({@code null} wie oben)
 * @param peakNextHourMmPerHour hoechste Intensitaet von jetzt bis eine Stunde nach der Messung
 */
public record RainRadarReading(Instant measuredAt, double mmPerHour, boolean raining, boolean rainSoon,
                               Instant expectedRainAt, Integer minutesUntilRain, double peakNextHourMmPerHour) {
}
