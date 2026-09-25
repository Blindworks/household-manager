package com.household.manager.appearance;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Hell/Dunkel des Dashboards in {@code application_settings}, Kategorie APPEARANCE.
 * Global statt pro Geraet: das Wandtablet hat kein Admin-Menue, es wird von hier
 * aus mit umgestellt.
 *
 * <p>Lesen wirft nie: das Wandtablet fragt den Wert regelmaessig ab, ein kaputter
 * Eintrag darf es nicht lahmlegen — dann gilt das bisherige dunkle Design
 * (Muster {@code UtilityPricingSettingsService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppearanceSettingsService {

    static final String CATEGORY = "APPEARANCE";
    static final String KEY_DASHBOARD_THEME = "dashboard_theme";
    public static final DashboardTheme DEFAULT_THEME = DashboardTheme.DARK;

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public DashboardTheme getDashboardTheme() {
        String raw;
        try {
            raw = applicationSettings.getSettingsByCategory(CATEGORY).get(KEY_DASHBOARD_THEME);
        } catch (Exception ex) {
            log.warn("Dashboard-Design konnte nicht gelesen werden, nutze {}", DEFAULT_THEME, ex);
            return DEFAULT_THEME;
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_THEME;
        }
        try {
            return DashboardTheme.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("Unbekannter Wert '{}' fuer {}, nutze {}", raw, KEY_DASHBOARD_THEME, DEFAULT_THEME);
            return DEFAULT_THEME;
        }
    }

    public void saveDashboardTheme(DashboardTheme theme) {
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_DASHBOARD_THEME, theme.name()));
        auditService.record("appearance.settings.update", KEY_DASHBOARD_THEME + "=" + theme.name());
    }
}
