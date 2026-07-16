package com.household.manager.service;

import com.household.manager.dto.WasteCollectionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCollectionSettingsServiceTest {

    private static final String CATEGORY = "WASTE_COLLECTION";

    @Mock
    private ApplicationSettingsService settingsService;

    @Captor
    private ArgumentCaptor<Map<String, String>> settingsMapCaptor;

    private WasteCollectionSettingsService service;

    @BeforeEach
    void setUp() {
        service = new WasteCollectionSettingsService(settingsService);
    }

    @Test
    void liestSettingsAlsDto() {
        when(settingsService.getBoolean(CATEGORY, "enabled", false)).thenReturn(true);
        when(settingsService.getString(CATEGORY, "ics_url", "")).thenReturn("https://x/cal.ics");
        when(settingsService.getInt(CATEGORY, "lookahead_days", 3)).thenReturn(5);
        when(settingsService.getBoolean(CATEGORY, "reminder_enabled", true)).thenReturn(true);
        when(settingsService.getString(CATEGORY, "reminder_time", "19:00")).thenReturn("18:30");
        when(settingsService.getString(CATEGORY, "reminder_alexa_serials", ""))
                .thenReturn("DSN1,DSN2");

        WasteCollectionSettings settings = service.getSettings();

        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.getIcsUrl()).isEqualTo("https://x/cal.ics");
        assertThat(settings.getLookaheadDays()).isEqualTo(5);
        assertThat(settings.getReminderTime()).isEqualTo("18:30");
        assertThat(settings.getReminderAlexaSerials()).containsExactly("DSN1", "DSN2");
    }

    @Test
    void leereSerienlisteErgibtLeereListeStattEinesLeerenEintrags() {
        when(settingsService.getString(CATEGORY, "reminder_alexa_serials", "")).thenReturn("");

        assertThat(service.getReminderAlexaSerials()).isEmpty();
    }

    @Test
    void schreibtSettingsAtomarUndOhneDenInternenMerkerAnzutasten() {
        service.saveSettings(WasteCollectionSettings.builder()
                .enabled(true)
                .icsUrl("https://x/cal.ics")
                .lookaheadDays(4)
                .reminderEnabled(false)
                .reminderTime("20:15")
                .reminderAlexaSerials(List.of("DSN1", "DSN2"))
                .build());

        verify(settingsService).saveSettings(eq(CATEGORY), settingsMapCaptor.capture());
        Map<String, String> saved = settingsMapCaptor.getValue();

        assertThat(saved)
                .containsEntry("enabled", "true")
                .containsEntry("ics_url", "https://x/cal.ics")
                .containsEntry("lookahead_days", "4")
                .containsEntry("reminder_enabled", "false")
                .containsEntry("reminder_time", "20:15")
                .containsEntry("reminder_alexa_serials", "DSN1,DSN2")
                .doesNotContainKey("last_announced_date");

        // Der einzelne Speicherpfad darf gar nicht erst benutzt werden -
        // sonst waere die Atomaritaetsgarantie der Bulk-Speicherung wertlos.
        verify(settingsService, never()).saveSetting(anyString(), anyString(), anyString());
    }

    @Test
    void faelltBeiUnparsbarerUhrzeitAufDenDefaultZurueck() {
        when(settingsService.getString(CATEGORY, "reminder_time", "19:00")).thenReturn("abends");

        assertThat(service.getReminderTime()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void hebtZuKleinesVorschaufensterAufEinsAn() {
        when(settingsService.getInt(CATEGORY, "lookahead_days", 3)).thenReturn(0);

        assertThat(service.getLookaheadDays()).isEqualTo(1);
    }

    @Test
    void faelltBeiNichtNumerischemVorschaufensterAufDenDefaultZurueck() {
        when(settingsService.getInt(CATEGORY, "lookahead_days", 3))
                .thenThrow(new NumberFormatException("nicht numerisch"));

        assertThat(service.getLookaheadDays()).isEqualTo(3);
    }

    @Test
    void merktSichDasAnsagedatum() {
        service.markAnnounced(LocalDate.of(2026, 7, 16));

        verify(settingsService).saveSetting(CATEGORY, "last_announced_date", "2026-07-16");
    }

    @Test
    void erkenntObHeuteBereitsAngesagtWurde() {
        when(settingsService.getString(CATEGORY, "last_announced_date", ""))
                .thenReturn("2026-07-16");

        assertThat(service.wasAnnouncedOn(LocalDate.of(2026, 7, 16))).isTrue();
        assertThat(service.wasAnnouncedOn(LocalDate.of(2026, 7, 17))).isFalse();
    }
}
