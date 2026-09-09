package com.household.manager.service;

import com.household.manager.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UtilityPricingSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private UtilityPricingSettingsService service;

    @Test
    void ohneEintragGiltDerDefault() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING")).thenReturn(Map.of());
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    @Test
    void gespeicherterWertWirdGelesen() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenReturn(Map.of("gas_kwh_per_m3", "10.63"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.63");
    }

    @Test
    void unlesbarerWertFaelltAufDenDefaultZurueck() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenReturn(Map.of("gas_kwh_per_m3", "zehn"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    /** Ein Faktor 100 waere ein Tippfehler und verzehnfachte still die Gaskosten. */
    @Test
    void unplausiblerWertFaelltAufDenDefaultZurueck() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenReturn(Map.of("gas_kwh_per_m3", "100"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    /** Der Serien-Service laeuft bei jedem Tablet-Abruf; ein DB-Fehler darf ihn nicht kippen. */
    @Test
    void leseFehlerWirftNicht() {
        when(applicationSettings.getSettingsByCategory("UTILITY_PRICING"))
                .thenThrow(new RuntimeException("db weg"));
        assertThat(service.getGasKwhPerM3()).isEqualByComparingTo("10.0");
    }

    @Test
    void speichernSchreibtUndAuditiert() {
        service.saveGasKwhPerM3(new BigDecimal("11.2"));
        verify(applicationSettings).saveSettings("UTILITY_PRICING", Map.of("gas_kwh_per_m3", "11.2"));
        verify(auditService).record(eq("utility-pricing.settings.update"), eq("gas_kwh_per_m3=11.2"));
    }
}
