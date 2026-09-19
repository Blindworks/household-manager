package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;

    private ChargingSettings settingsFrom(Map<String, String> stored) {
        when(applicationSettings.getSettingsByCategory("CHARGING")).thenReturn(stored);
        return new ChargingSettingsService(applicationSettings, auditService).getSettings();
    }

    @Test
    void ohneWerteGeltenDieDefaultsUndNichtsIstKonfiguriert() {
        ChargingSettings settings = settingsFrom(Map.of());

        assertThat(settings.isConfigured()).isFalse();
        assertThat(settings.radiusKm()).isEqualTo(10.0);
        assertThat(settings.minPowerKw()).isEqualTo(50.0);
    }

    @Test
    void unlesbarerRadiusFaelltAufDenDefaultZurueck() {
        ChargingSettings settings = settingsFrom(Map.of("radius_km", "zehn"));

        assertThat(settings.radiusKm()).isEqualTo(10.0);
    }

    @Test
    void radiusAusserhalbDerSchrankenFaelltAufDenDefaultZurueck() {
        assertThat(settingsFrom(Map.of("radius_km", "500")).radiusKm()).isEqualTo(10.0);
        assertThat(settingsFrom(Map.of("radius_km", "0")).radiusKm()).isEqualTo(10.0);
    }

    @Test
    void halbeKoordinateZaehltAlsNichtKonfiguriert() {
        ChargingSettings settings = settingsFrom(Map.of("home_lat", "50.1"));

        assertThat(settings.isConfigured()).isFalse();
        assertThat(settings.homeLongitude()).isNull();
    }

    @Test
    void nanKoordinateZaehltAlsNichtKonfiguriert() {
        ChargingSettings settings = settingsFrom(Map.of("home_lat", "NaN", "home_lon", "8.5"));

        assertThat(settings.isConfigured()).isFalse();
    }

    @Test
    void speichernSchreibtAlleVierWerteUndAuditiert() {
        ChargingSettingsService service = new ChargingSettingsService(applicationSettings, auditService);

        service.saveSettings(new ChargingSettings(50.1, 8.5, 12.0, 100.0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(applicationSettings).saveSettings(eq("CHARGING"), captor.capture());
        assertThat(captor.getValue()).containsEntry("home_lat", "50.1").containsEntry("home_lon", "8.5")
                .containsEntry("radius_km", "12.0").containsEntry("min_power_kw", "100.0");
        verify(auditService).record(eq("charging.settings.update"), org.mockito.ArgumentMatchers.anyString());
    }
}
