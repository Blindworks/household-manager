package com.household.manager.telegram;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Konfiguration des Telegram-KI-Assistenten. Secrets nur per Env, nie loggen. */
@Configuration
@ConfigurationProperties(prefix = "telegram")
@Data
public class TelegramProperties {

    private boolean enabled = true;
    @ToString.Exclude
    private String botToken = "";
    /** Nur diese Chat-IDs dürfen mit dem Bot sprechen; alle anderen werden ignoriert. */
    private List<Long> allowedChatIds = List.of();
    @ToString.Exclude
    private String anthropicApiKey = "";
    private String model = "claude-haiku-4-5-20251001";
    private String telegramBaseUrl = "https://api.telegram.org";
    private String anthropicBaseUrl = "https://api.anthropic.com";
    private int maxTokens = 1024;
    /** Obergrenze der Tool-Runden pro Nutzernachricht (Endlosschleifen-Schutz). */
    private int maxToolIterations = 8;
    private int historyMaxMessages = 20;
    private long historyTtlMinutes = 30;
    private int pollTimeoutSeconds = 30;
    private int httpTimeoutMs = 10000;
    private long errorBackoffMs = 5000;

    /** True, wenn Bot-Token, Anthropic-Key und mindestens ein erlaubter Chat gesetzt sind. */
    public boolean isConfigured() {
        return enabled
                && botToken != null && !botToken.isBlank()
                && anthropicApiKey != null && !anthropicApiKey.isBlank()
                && allowedChatIds != null && !allowedChatIds.isEmpty();
    }
}
