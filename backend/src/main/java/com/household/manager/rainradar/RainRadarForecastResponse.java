package com.household.manager.rainradar;

import java.util.List;

/**
 * Antwort von {@code GET /v1/rain-radar/forecast} fuer den Detaildialog im Dashboard.
 * Zeiten sind lokale Haushaltszeit ({@code yyyy-MM-dd'T'HH:mm:ss}) wie die Entity-Attribute.
 * Ohne erfolgreichen Abruf seit dem Start sind {@code frames} leer und {@code measuredAt} {@code null}.
 */
public record RainRadarForecastResponse(
        String measuredAt,
        Double mmPerHour,
        boolean raining,
        String expectedRainAt,
        Double peakNextHourMmPerHour,
        double thresholdMmPerHour,
        String source,
        List<Frame> frames) {

    /** Ein 5-Minuten-Bild; {@code forecast} = Vorhersage (Nowcast) statt Messung. */
    public record Frame(String time, double mmPerHour, boolean forecast) {
    }
}
