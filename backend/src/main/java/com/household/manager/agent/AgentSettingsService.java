package com.household.manager.agent;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Betriebsschalter des Hausverstands in {@code application_settings}, Kategorie AGENT.
 * Lesen wirft nie und faellt auf "aus" zurueck: ein kaputter Eintrag darf den Agenten
 * nie versehentlich einschalten (Muster {@code AppearanceSettingsService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentSettingsService {

    static final String CATEGORY = "AGENT";
    static final String KEY_ACTIVE = "agent_active";

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public boolean isActive() {
        try {
            return Boolean.parseBoolean(applicationSettings.getSettingsByCategory(CATEGORY).get(KEY_ACTIVE));
        } catch (Exception ex) {
            log.warn("Betriebsschalter des Hausverstands nicht lesbar, nutze 'aus'", ex);
            return false;
        }
    }

    public void setActive(boolean active) {
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_ACTIVE, String.valueOf(active)));
        auditService.record("agent.settings.update", KEY_ACTIVE + "=" + active);
    }
}
