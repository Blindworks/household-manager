package com.household.manager.tractive;

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
class TractiveHomeSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;

    private TractiveHomeSettings settingsFrom(Map<String, String> stored) {
        when(applicationSettings.getSettingsByCategory("TRACTIVE_HOME")).thenReturn(stored);
        return new TractiveHomeSettingsService(applicationSettings, auditService).getSettings();
    }

    @Test
    void withoutStoredValuesTheDefaultsApply() {
        TractiveHomeSettings settings = settingsFrom(Map.of());

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeRadiusMeters()).isEqualTo(100);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(500);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(60);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(15);
        assertThat(settings.homeZoneName()).isEqualTo("Zuhause");
    }

    @Test
    void storedValuesAreRead() {
        TractiveHomeSettings settings = settingsFrom(Map.of(
                "home_latitude", "48.2082",
                "home_longitude", "16.3738",
                "home_radius_meters", "120.0",
                "home_arrival_radius_meters", "400.0",
                "powered_off_after_minutes", "45",
                "powered_off_min_battery_percent", "20",
                "home_zone_name", "Daheim"));

        assertThat(settings.homeLatitude()).isEqualTo(48.2082);
        assertThat(settings.homeLongitude()).isEqualTo(16.3738);
        assertThat(settings.homeRadiusMeters()).isEqualTo(120.0);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(400.0);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(45);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(20);
        assertThat(settings.homeZoneName()).isEqualTo("Daheim");
    }

    /**
     * Der Poller laeuft jede Minute – ein Tippfehler in der Datenbank darf ihn nicht
     * mit einer NumberFormatException lahmlegen.
     */
    @Test
    void unreadableValuesFallBackToDefaultsInsteadOfThrowing() {
        TractiveHomeSettings settings = settingsFrom(Map.of(
                "home_latitude", "keine Zahl",
                "home_longitude", "auch nicht",
                "home_radius_meters", "abc",
                "home_arrival_radius_meters", "",
                "powered_off_after_minutes", "x",
                "powered_off_min_battery_percent", "y",
                "home_zone_name", ""));

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeRadiusMeters()).isEqualTo(100);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(500);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(60);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(15);
        assertThat(settings.homeZoneName()).isEqualTo("Zuhause");
    }

    /** Direkt in der DB geschriebene Ausreisser umgeht die Controller-Validierung. */
    @Test
    void implausibleStoredValuesFallBackToDefaults() {
        TractiveHomeSettings settings = settingsFrom(Map.of(
                "home_latitude", "480.0",
                "home_longitude", "16.3738",
                "home_radius_meters", "0",
                "home_arrival_radius_meters", "-5",
                "powered_off_after_minutes", "0",
                "powered_off_min_battery_percent", "150"));

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeRadiusMeters()).isEqualTo(100);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(500);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(60);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(15);
    }

    /**
     * Eine halbe Koordinate ist keine Position: sonst zeigte das Formular einen Wert,
     * waehrend der Resolver still auf "nicht konfiguriert" steht.
     */
    @Test
    void aSingleCoordinateCountsAsNotConfigured() {
        TractiveHomeSettings settings = settingsFrom(Map.of("home_latitude", "48.2082"));

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeLatitude()).isNull();
        assertThat(settings.homeLongitude()).isNull();
    }

    @Test
    void savingWritesAllKeysInOneCallAndAudits() {
        var service = new TractiveHomeSettingsService(applicationSettings, auditService);

        service.saveSettings(new TractiveHomeSettings(
                48.2082, 16.3738, 120, 400, 45, 20, "Daheim"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(applicationSettings).saveSettings(eq("TRACTIVE_HOME"), captor.capture());
        Map<String, String> written = captor.getValue();
        assertThat(written).containsKeys("home_latitude", "home_longitude", "home_radius_meters",
                "home_arrival_radius_meters", "powered_off_after_minutes",
                "powered_off_min_battery_percent", "home_zone_name");
        assertThat(written.get("home_latitude")).isEqualTo("48.2082");
        assertThat(written.get("home_zone_name")).isEqualTo("Daheim");
        verify(auditService).record(eq("tractive.home-settings.update"), org.mockito.ArgumentMatchers.anyString());
    }

    /** Leere Koordinaten muessen als leerer String landen – die Spalte ist NOT NULL. */
    @Test
    void clearingTheCoordinatesStoresEmptyStrings() {
        var service = new TractiveHomeSettingsService(applicationSettings, auditService);

        service.saveSettings(new TractiveHomeSettings(null, null, 100, 500, 60, 15, "Zuhause"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(applicationSettings).saveSettings(eq("TRACTIVE_HOME"), captor.capture());
        assertThat(captor.getValue().get("home_latitude")).isEmpty();
        assertThat(captor.getValue().get("home_longitude")).isEmpty();
    }
}
