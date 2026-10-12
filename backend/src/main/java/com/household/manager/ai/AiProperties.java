package com.household.manager.ai;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Gemeinsame Anthropic-Anbindung fuer Telegram-Bot und Hausverstand. Secret nur per Env, nie loggen. */
@Configuration
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    @ToString.Exclude
    private String anthropicApiKey = "";
    private String anthropicBaseUrl = "https://api.anthropic.com";
    private int httpTimeoutMs = 10000;

    public boolean isConfigured() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
