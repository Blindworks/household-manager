package com.household.manager.meross.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Konfiguration des Meross-Verbrauchs-Pollings. Ohne konfigurierte
 * device-ids ist das Polling ein No-op.
 */
@Component
@ConfigurationProperties(prefix = "meross.electricity.polling")
@Getter
@Setter
public class MerossElectricityPollingProperties {

    private boolean enabled = true;

    /** Meross-Geräte-UUIDs, deren Verbrauch gespiegelt werden soll. */
    private List<String> deviceIds = new ArrayList<>();
}
