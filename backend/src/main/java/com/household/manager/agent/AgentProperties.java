package com.household.manager.agent;

import com.household.manager.common.TimeWindow;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;
import java.util.List;

/** Konfiguration des Hausverstands (Deploy-Ebene; der Betriebsschalter liegt in application_settings). */
@Configuration
@ConfigurationProperties(prefix = "agent")
@Data
@Slf4j
public class AgentProperties {

    private boolean enabled = true;
    private String model = "claude-sonnet-4-5";
    private int maxTokens = 2048;
    private long tickIntervalMs = 1_800_000;
    private long initialDelayMs = 120_000;
    private List<String> wakeEntities = List.of();
    private long wakeDebounceMs = 180_000;
    private String quietHours = "22:00-07:00";
    private int maxToolIterations = 8;
    private int runTimeoutSeconds = 90;
    private int maxOpenProposals = 3;
    private int proposalTtlHours = 2;
    private int maxActiveNotes = 50;
    private int runRetentionDays = 30;
    private long manualRunMinIntervalMs = 60_000;
    /** Tagesbudget für Eingabe- plus Ausgabe-Tokens; 0 = kein Limit. */
    private long dailyTokenBudget = 300_000;

    /** Ruhezeit als Fenster; ein unlesbarer Wert faellt auf 22-7 zurueck statt den Start zu kippen. */
    public TimeWindow quietWindow() {
        try {
            String[] parts = quietHours.split("-");
            return new TimeWindow(LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim()));
        } catch (Exception ex) {
            log.warn("agent.quiet-hours '{}' unlesbar, nutze 22:00-07:00", quietHours);
            return new TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0));
        }
    }
}