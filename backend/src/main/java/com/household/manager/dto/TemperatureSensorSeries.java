package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Zeitreihe eines Temperatursensors inkl. optionaler Luftfeuchtigkeit. */
@Getter
@Builder
public class TemperatureSensorSeries {
    /** Stabile, quellenpräfixierte ID, z. B. "zigbee:12". */
    private final String sensorId;
    /** Anzeigename des Sensors. */
    private final String name;
    /** Quelle: ZIGBEE | WEATHER | ALEXA. */
    private final String source;
    /** Temperaturpunkte (immer vorhanden). */
    private final List<TimeValue> temperature;
    /** Feuchtepunkte (leer, wenn der Sensor keine Feuchte liefert). */
    private final List<TimeValue> humidity;
}
