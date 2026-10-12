package com.household.manager.telegram;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Konfiguration des Telegram-KI-Assistenten. Secrets nur per Env, nie loggen. Der Anthropic-Key liegt in {@code AiProperties}. */
@Configuration
@ConfigurationProperties(prefix = "telegram")
@Data
public class TelegramProperties {

    private boolean enabled = true;
    @ToString.Exclude
    private String botToken = "";
    /** Nur diese Chat-IDs dürfen mit dem Bot sprechen; alle anderen werden ignoriert. */
    private List<Long> allowedChatIds = List.of();
    private String model = "claude-haiku-4-5-20251001";
    private String telegramBaseUrl = "https://api.telegram.org";
    private int maxTokens = 1024;
    /** Obergrenze der Tool-Runden pro Nutzernachricht (Endlosschleifen-Schutz). */
    private int maxToolIterations = 8;
    private int historyMaxMessages = 20;
    private long historyTtlMinutes = 30;
    private int pollTimeoutSeconds = 30;
    private int httpTimeoutMs = 10000;
    private long errorBackoffMs = 5000;

    /** True, wenn Bot-Token und mindestens ein erlaubter Chat gesetzt sind. */
    public boolean isConfigured() {
        return enabled
                && botToken != null && !botToken.isBlank()
                && allowedChatIds != null && !allowedChatIds.isEmpty();
    }
}
