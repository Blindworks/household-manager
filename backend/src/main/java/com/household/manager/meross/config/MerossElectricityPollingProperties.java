package com.household.manager.meross.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Konfiguration des Meross-Verbrauchs-Pollings. Gepollt werden immer alle
 * messfähigen Steckdosen des Kontos; es gibt keine kuratierte Geräteliste.
 */
@Component
@ConfigurationProperties(prefix = "meross.electricity.polling")
@Getter
@Setter
public class MerossElectricityPollingProperties {

    private boolean enabled = true;
}
