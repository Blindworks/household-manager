package com.household.manager.zigbee.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Schwellen der Zigbee-Ausfallerkennung.
 * <p>
 * Bewusst in application.properties und nicht in der Datenbank: anders als bei der
 * Tractive-Home-Definition gibt es keinen Grund, diese Werte im laufenden Betrieb
 * zu verstellen.
 */
@Component
@ConfigurationProperties(prefix = "zigbee.watchdog")
@Getter
@Setter
public class ZigbeeWatchdogProperties {

    private boolean enabled = true;

    /**
     * Stille, ab der ein Ausfall vermutet wird. Abgeleitet aus den PROD-Daten:
     * die sieben Temperatursensoren melden im Minutenabstand, totale Stille ueber
     * 15 Minuten ist damit sicher ein Ausfall. Nach einigen Tagen Betrieb gegen die
     * tatsaechlichen Melde-Abstaende nachziehen.
     */
    private int staleAfterMinutes = 15;

    /** Frist nach dem Selbstheilungsversuch, bevor Alarm geschlagen wird. */
    private int recoverGraceMinutes = 5;

    public Duration staleAfter() {
        return Duration.ofMinutes(staleAfterMinutes);
    }

    public Duration recoverGrace() {
        return Duration.ofMinutes(recoverGraceMinutes);
    }
}
