package com.household.manager.rainradar;

import java.time.Instant;

/**
 * Ein 5-Minuten-Bild des Regenradars fuer das Pixel ueber dem Haus.
 *
 * @param time        Ende des 5-Minuten-Intervalls
 * @param mmPerHour   Niederschlagsintensitaet, hochgerechnet auf mm/h
 * @param forecast    {@code true} = Vorhersage (Nowcast), {@code false} = gemessen
 */
public record RainRadarFrame(Instant time, double mmPerHour, boolean forecast) {
}
