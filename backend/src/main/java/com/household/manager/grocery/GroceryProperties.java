package com.household.manager.grocery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.household.manager.grocery.source.SourceKey;

import java.util.EnumMap;
import java.util.Map;

/** Konfiguration der Supermarkt-Angebote (Deploy-Ebene). */
@Configuration
@ConfigurationProperties(prefix = "grocery")
@Data
public class GroceryProperties {

    private boolean enabled = true;
    private String aiModel = "claude-haiku-4-5-20251001";
    private int maxAiCallsPerRun = 50;
    private int httpTimeoutMs = 20_000;
    private Map<SourceKey, Boolean> sources = defaultSources();

    private static Map<SourceKey, Boolean> defaultSources() {
        Map<SourceKey, Boolean> map = new EnumMap<>(SourceKey.class);
        for (SourceKey key : SourceKey.values()) {
            map.put(key, true);
        }
        return map;
    }

    /** Eine nicht eingetragene Quelle gilt als eingeschaltet. */
    public boolean isSourceEnabled(SourceKey key) {
        return sources.getOrDefault(key, true);
    }
}
