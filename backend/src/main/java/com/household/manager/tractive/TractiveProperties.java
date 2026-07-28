package com.household.manager.tractive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Verbindungs- und Poll-Einstellungen der Tractive-Integration.
 *
 * <p>Was "zu Hause" bedeutet, steht bewusst NICHT hier, sondern in der Datenbank
 * ({@link TractiveHomeSettingsService}) und wird im Admin-Bereich gepflegt.
 */
@Configuration
@ConfigurationProperties(prefix = "tractive")
@Data
public class TractiveProperties {

    private boolean enabled = true;
    private String baseUrl = "https://graph.tractive.com/4";
    /** Oeffentliche Client-ID der Tractive-App; kein Geheimnis. */
    private String clientId = "625e533dc3c3b41c28a669f0";
    private long pollIntervalMs = 30000;
    private long initialDelayMs = 20000;
    private int httpTimeoutMs = 10000;
}
