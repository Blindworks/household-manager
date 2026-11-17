package com.household.manager.rainradar;

import java.util.List;

/**
 * Stand des letzten erfolgreichen Radarabrufs: alle Bilder (gemessen und Vorhersage) plus das
 * daraus abgeleitete Urteil. Lebt nur im Speicher des Pollers, nach einem Neustart leer.
 */
public record RainRadarSnapshot(List<RainRadarFrame> frames, RainRadarReading reading) {

    public RainRadarSnapshot {
        frames = List.copyOf(frames);
    }
}
