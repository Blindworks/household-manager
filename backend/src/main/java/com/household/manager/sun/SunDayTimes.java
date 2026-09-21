package com.household.manager.sun;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Die vier Sonnenzeiten eines Kalendertages (Haushaltszeit) und die daraus
 * abgeleitete Phasenregel — eine reine Funktion ohne Uhr, damit sie testbar ist.
 *
 * <p>Fenster sind halboffen wie {@code TimeWindow}: {@code [sunrise, sunset)} = DAY,
 * {@code [sunset, dusk)} = DUSK, {@code [dawn, sunrise)} = DAWN, sonst NIGHT.
 */
public record SunDayTimes(
        LocalDate date,
        ZonedDateTime dawn,
        ZonedDateTime sunrise,
        ZonedDateTime sunset,
        ZonedDateTime dusk
) {

    public SunDayTimes {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dawn, "dawn");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(sunset, "sunset");
        Objects.requireNonNull(dusk, "dusk");
    }

    public ZonedDateTime timeOf(SunEvent event) {
        return switch (event) {
            case DAWN -> dawn;
            case SUNRISE -> sunrise;
            case SUNSET -> sunset;
            case DUSK -> dusk;
        };
    }

    /**
     * Nur fuer Momente dieses Kalendertages definiert; Aufrufer nutzen
     * {@link SunTimesService#phaseAt}, das den Tag aus dem Moment ableitet.
     */
    public SunPhase phaseAt(ZonedDateTime moment) {
        if (!moment.isBefore(sunrise) && moment.isBefore(sunset)) {
            return SunPhase.DAY;
        }
        if (!moment.isBefore(sunset) && moment.isBefore(dusk)) {
            return SunPhase.DUSK;
        }
        if (!moment.isBefore(dawn) && moment.isBefore(sunrise)) {
            return SunPhase.DAWN;
        }
        return SunPhase.NIGHT;
    }
}
