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
    /**
     * Entity-ID der zugehoerigen Temperatur-Entity, z. B.
     * "sensor.zigbee_temperatur_aqara_garten_temperature". Stabil gegenueber einem
     * Umbenennen in der App (anders als {@link #name}, der den Custom-Namen traegt) -
     * deshalb der Schluessel fuer die Aussenfuehler-Erkennung.
     */
    private final String entityId;
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
