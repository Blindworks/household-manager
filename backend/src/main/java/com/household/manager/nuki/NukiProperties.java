package com.household.manager.nuki;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nuki")
@Data
public class NukiProperties {

    private boolean enabled = true;
    /** Persönlicher API-Token von https://web.nuki.io (Smartlock lesen + bedienen). */
    private String apiToken = "";
    private String baseUrl = "https://api.nuki.io";
    private long pollIntervalMs = 30000;
    private long initialDelayMs = 15000;
    private int httpTimeoutMs = 5000;

    /** True, wenn die Integration aktiv und ein Token hinterlegt ist. */
    public boolean isConfigured() {
        return enabled && apiToken != null && !apiToken.isBlank();
    }
}
