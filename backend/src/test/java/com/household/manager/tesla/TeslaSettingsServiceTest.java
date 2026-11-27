package com.household.manager.tesla;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeslaSettingsServiceTest {

    private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
    private final AuditService audit = mock(AuditService.class);
    private final TeslaSettingsService service = new TeslaSettingsService(settings, audit);

    @Test
    void lesenWirftNieUndNimmtDefaults() {
        when(settings.getSettingsByCategory("TESLA")).thenThrow(new RuntimeException("DB weg"));

        TeslaSettings current = service.current();

        assertThat(current.hasToken()).isFalse();
        assertThat(current.pollIntervalMinutes()).isEqualTo(5);
    }

    @Test
    void ungueltigesIntervallFaelltAufDefault() {
        when(settings.getSettingsByCategory("TESLA"))
                .thenReturn(Map.of("api_token", "abc", "poll_interval_minutes", "999"));

        TeslaSettings current = service.current();

        assertThat(current.hasToken()).isTrue();
        assertThat(current.pollIntervalMinutes()).isEqualTo(5);
    }

    @Test
    void tokenNullLaesstIhnUnveraendert() {
        when(settings.getSettingsByCategory("TESLA")).thenReturn(Map.of());

        service.update(null, "5yj3e7eb2nf000001", 10);

        verify(settings).saveSettings(eq("TESLA"), argThat(values ->
                !values.containsKey("api_token")
                        && "5YJ3E7EB2NF000001".equals(values.get("vin"))
                        && "10".equals(values.get("poll_interval_minutes"))));
        verify(audit).record(eq("tesla.settings.update"), anyString());
    }

    @Test
    void leererTokenEntferntIhn() {
        when(settings.getSettingsByCategory("TESLA")).thenReturn(Map.of());

        service.update("", null, 5);

        verify(settings).saveSettings(eq("TESLA"), argThat(values -> "".equals(values.get("api_token"))));
    }

    @Test
    void lehntFalscheVinUndIntervallAb() {
        assertThatThrownBy(() -> service.update(null, "ABC", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(null, null, 61)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenStehtNieImToString() {
        assertThat(new TeslaSettings("geheim", null, 5).toString()).doesNotContain("geheim");
    }
}
