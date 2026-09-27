package com.household.manager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * Schwellen der Lüftungsempfehlung. Bewusst in application.properties statt in der DB
 * (wie beim Zigbee-Watchdog): kein Grund, das im laufenden Betrieb zu verstellen.
 */
@Configuration
@ConfigurationProperties(prefix = "ventilation")
@Data
public class VentilationProperties {

    /**
     * Temperatur-Entities, die trotz Quelle ZIGBEE/ALEXA draußen hängen (Pendant zum
     * DWD-Wert). Sie zählen nie als Raum und liefern — in dieser Reihenfolge, erster
     * frischer gewinnt — die Außentemperatur; erst wenn keiner davon frisch meldet,
     * greift der DWD-Wert als Fallback.
     *
     * <p>Bewusst die Entity-ID, nicht der Anzeigename: die Temperatur-API liefert den
     * Custom-Namen, und nach dem Umbenennen des Gartenfühlers in „Garten" griff die
     * frühere Namensliste still nicht mehr (Garten zählte als Raum). Die Entity-ID
     * entsteht aus dem zigbee2mqtt-Namen und ändert sich nur, wenn das Gerät dort
     * umbenannt wird.
     *
     * <p>Zweite Kopie: das Frontend führt dieselbe Liste in
     * {@code shared/temperature-comfort.util.ts} (OUTDOOR_SENSOR_ENTITY_IDS) für die
     * Außenfühler-Chips im Dashboard-Kopf. Ein neuer Außenfühler gehört an beide Stellen.
     */
    private List<String> outdoorSensorEntityIds = List.of("sensor.zigbee_temperatur_aqara_garten_temperature");
    /** Ab dieser Raumtemperatur gilt ein Raum als "zu warm". */
    private BigDecimal roomThresholdCelsius = new BigDecimal("24");
    /** Draußen muss es mindestens so viel kühler sein, damit die Empfehlung entsteht. */
    private BigDecimal minDifferenceCelsius = new BigDecimal("2");
    /** Eine bestehende Empfehlung erlischt erst unter dieser Differenz (Hysterese). */
    private BigDecimal offDifferenceCelsius = new BigDecimal("1");
    /** Messwerte, die älter sind, werden ignoriert (eingefrorener Sensor). */
    private int staleAfterMinutes = 30;
    /** Takt des Entity-Reporters. */
    private long reportIntervalMs = 300_000;
    /** Wartezeit nach dem Start, bevor der Reporter erstmals läuft. */
    private long initialDelayMs = 60_000;
}
