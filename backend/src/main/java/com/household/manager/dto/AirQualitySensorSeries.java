package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Luftqualitaets-Zeitreihen genau eines Sensors.
 *
 * <p>Die Messgroessen stehen in einer Map statt in festen Feldern, weil die Quellen
 * disjunkte Mengen liefern: der Airrohr-Sensor kennt kein IAQ, die Amazon-Monitore
 * kein PM10. Feste Felder waeren fuer die Mehrzahl der Kombinationen dauerhaft leer,
 * und jede weitere Messgroesse erzwaenge eine Vertragsaenderung.
 *
 * <p>Eine Groesse ohne Werte fehlt in der Map, statt als leere Liste zu erscheinen.
 */
@Getter
@Builder
public class AirQualitySensorSeries {

    /** Stabile, quellenpraefixierte ID: "airrohr:local" oder "alexa:&lt;applianceId&gt;". */
    private final String sensorId;

    /** Anzeigename des Sensors. */
    private final String name;

    /** Quelle: AIRROHR | ALEXA. */
    private final String source;

    /** Messgroessen-Schluessel ("pm25", "pm10", "iaq", "voc", "co") auf Zeitreihe. */
    private final Map<String, List<TimeValue>> metrics;
}
