package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private PresenceSettingsService service;

    @Test
    void ohneEintragGiltDerDefault() {
        when(applicationSettings.getSettingsByCategory("PRESENCE")).thenReturn(Map.of());
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);
    }

    @Test
    void gespeicherterWertWirdGelesen() {
        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "25"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(25L);
    }

    @Test
    void unplausibleWerteFallenAufDenDefaultZurueck() {
        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "0"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);

        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "99999"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);

        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "abc"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);
    }

    @Test
    void speichernSchreibtWertUndAudit() {
        service.saveAwayGraceMinutes(15L);
        verify(applicationSettings).saveSettings("PRESENCE", Map.of("away_grace_minutes", "15"));
        verify(auditService).record(eq("presence.settings.update"), eq("away_grace_minutes=15"));
    }
}
