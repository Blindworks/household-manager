package com.household.manager.charging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Verbindungs- und Poll-Einstellungen der Ladesaeulen-Anbindung. Zuhause, Radius und
 * Mindestleistung stehen bewusst NICHT hier, sondern in der DB ({@link ChargingSettingsService}).
 */
@Configuration
@ConfigurationProperties(prefix = "charging")
@Data
public class ChargingProperties {

    private boolean enabled = true;
    private Enbw enbw = new Enbw();
    private long areaPollSeconds = 300;
    private long favoritePollSeconds = 60;
    private long initialDelayMs = 25000;
    private int httpTimeoutMs = 10000;

    @Data
    public static class Enbw {
        private String baseUrl = "https://api.emp.emob-enbw.com/emobility-public-api/api/v1";
        /** Oeffentlich in der EnBW-Web-App eingebetteter Key; kein Geheimnis, aber per Env nachziehbar. */
        private String apiKey = "90a67b9900364009b588e100e4b1cc64";
        private String origin = "https://www.enbw.com";
    }
}
