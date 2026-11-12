package com.household.manager.laundry;

import java.time.LocalDateTime;

/** Frische Kellerwerte; {@code measuredAt} ist der aeltere der beiden Messzeitpunkte. */
public record KellerClimate(double tempC, double humidity, LocalDateTime measuredAt) {
}
