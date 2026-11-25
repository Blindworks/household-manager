package com.household.manager.communicator;

import com.household.manager.ai.ClaudeModels;
import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Gewaehltes Modell in {@code application_settings}; ohne Wahl gilt {@code communicator.model}. Lesen wirft nie. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommunicatorSettingsService {

    static final String CATEGORY = "COMMUNICATOR";
    static final String KEY_MODEL = "model";

    private final ApplicationSettingsService applicationSettings;
    private final CommunicatorProperties properties;
    private final AuditService auditService;

    public String getModel() {
        try {
            String saved = applicationSettings.getSettingsByCategory(CATEGORY).get(KEY_MODEL);
            if (saved != null && isSelectable(saved)) {
                return saved;
            }
        } catch (Exception ex) {
            log.warn("Communicator-Modell nicht lesbar, nutze {}", properties.getModel(), ex);
        }
        return properties.getModel();
    }

    public void saveModel(String model) {
        if (model == null || !isSelectable(model)) {
            throw new IllegalArgumentException("Unbekanntes Modell.");
        }
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_MODEL, model));
        auditService.record("communicator.model.update", model);
    }

    private static boolean isSelectable(String model) {
        return ClaudeModels.ALL.stream().anyMatch(known -> known.id().equals(model));
    }
}
