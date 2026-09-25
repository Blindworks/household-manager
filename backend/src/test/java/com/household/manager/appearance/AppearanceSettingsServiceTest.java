package com.household.manager.appearance;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppearanceSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private AppearanceSettingsService service;

    @Test
    void ohneEintragBleibtDasDunkleDesign() {
        when(applicationSettings.getSettingsByCategory("APPEARANCE")).thenReturn(Map.of());
        assertThat(service.getDashboardTheme()).isEqualTo(DashboardTheme.DARK);
    }

    @Test
    void gespeicherterWertWirdGelesen() {
        when(applicationSettings.getSettingsByCategory("APPEARANCE"))
                .thenReturn(Map.of("dashboard_theme", "LIGHT"));
        assertThat(service.getDashboardTheme()).isEqualTo(DashboardTheme.LIGHT);
    }

    @Test
    void unbekannterWertFaelltAufDunkelZurueck() {
        when(applicationSettings.getSettingsByCategory("APPEARANCE"))
                .thenReturn(Map.of("dashboard_theme", "hell"));
        assertThat(service.getDashboardTheme()).isEqualTo(DashboardTheme.DARK);
    }

    /** Das Wandtablet fragt regelmaessig ab — ein DB-Fehler darf nicht durchschlagen. */
    @Test
    void lesefehlerFaelltAufDunkelZurueck() {
        when(applicationSettings.getSettingsByCategory(anyString())).thenThrow(new IllegalStateException("db"));
        assertThat(service.getDashboardTheme()).isEqualTo(DashboardTheme.DARK);
    }

    @Test
    void speichernSchreibtDenEnumNamenUndAuditiert() {
        service.saveDashboardTheme(DashboardTheme.LIGHT);
        verify(applicationSettings).saveSettings("APPEARANCE", Map.of("dashboard_theme", "LIGHT"));
        verify(auditService).record("appearance.settings.update", "dashboard_theme=LIGHT");
    }
}
