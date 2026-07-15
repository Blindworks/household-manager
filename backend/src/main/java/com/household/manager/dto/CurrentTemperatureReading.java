package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Aktueller (jüngster) Messwert eines Temperatursensors. */
@Getter
@Builder
public class CurrentTemperatureReading {
    /** Stabile, quellenpräfixierte ID, z. B. "zigbee:12". */
    private final String sensorId;
    /** Anzeigename des Sensors bzw. "Außen". */
    private final String name;
    /** Quelle: ZIGBEE | WEATHER | ALEXA. */
    private final String source;
    /** Jüngste Temperatur. */
    private final BigDecimal temperature;
    /** Jüngste Feuchte (null, wenn nicht vorhanden). */
    private final BigDecimal humidity;
    /** Zeitpunkt der Messung. */
    private final LocalDateTime measuredAt;
}
