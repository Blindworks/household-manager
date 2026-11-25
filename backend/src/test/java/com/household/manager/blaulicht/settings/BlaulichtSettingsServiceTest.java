package com.household.manager.blaulicht.settings;

import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlaulichtSettingsServiceTest {

    private final Map<String, String> stored = new HashMap<>();
    private final ApplicationSettingsService applicationSettings = mock(ApplicationSettingsService.class);
    private final BlaulichtSettingsService service = new BlaulichtSettingsService(applicationSettings);

    @BeforeEach
    void setUp() {
        when(applicationSettings.getSettingsByCategory("BLAULICHT")).thenAnswer(inv -> Map.copyOf(stored));
        doAnswer(inv -> {
            stored.putAll(inv.getArgument(1));
            return null;
        }).when(applicationSettings).saveSettings(eq("BLAULICHT"), anyMap());
    }

    @Test
    void ohneGespeicherteWerteGeltenDieDefaults() {
        assertThat(service.current()).isEqualTo(BlaulichtSettings.defaults());
    }

    @Test
    void speichertUndLiestDenGanzenSatz() {
        BlaulichtSettings next = new BlaulichtSettings(
                new BlaulichtSettings.Sources(false, "", true, "064400000000", "064400003003", List.of()),
                new BlaulichtSettings.Localities("Karben", List.of(" Okarben ", "Rendel", "Rendel", ""), List.of("Bad Vilbel")),
                new BlaulichtSettings.Briefing(LocalTime.of(21, 0), LocalTime.of(7, 30), LocalTime.of(16, 30),
                        LocalTime.of(21, 0)));

        BlaulichtSettings saved = service.update(next);

        assertThat(saved.sources().presseportalEnabled()).isFalse();
        assertThat(saved.sources().titleFilter()).isEmpty();
        assertThat(saved.sources().excludedProviders()).isEmpty();
        assertThat(saved.localities().main()).isEqualTo("Karben");
        assertThat(saved.localities().districts()).containsExactly("Okarben", "Rendel");
        assertThat(saved.briefing().addendumAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(stored).containsEntry("addendum_at", "16:30").containsEntry("locality_districts", "Okarben,Rendel");
    }

    @Test
    void unlesbareWerteFallenEinzelnAufDenDefault() {
        stored.put("presseportal_enabled", "vielleicht");
        stored.put("nina_local_ars", "123");
        stored.put("night_end", "acht");
        stored.put("locality_main", "  ");
        stored.put("locality_neighbors", "Karben");

        BlaulichtSettings current = service.current();

        BlaulichtSettings d = BlaulichtSettings.defaults();
        assertThat(current.sources().presseportalEnabled()).isTrue();
        assertThat(current.sources().localArs()).isEqualTo(d.sources().localArs());
        assertThat(current.briefing()).isEqualTo(d.briefing());
        assertThat(current.localities().main()).isEqualTo("Bad Vilbel");
        assertThat(current.localities().neighbors()).containsExactly("Karben");
    }

    @Test
    void widerspruechlicheGespeicherteUhrzeitenFallenGemeinsamAufDieDefaults() {
        stored.put("addendum_at", "07:00");

        assertThat(service.current().briefing()).isEqualTo(BlaulichtSettings.defaults().briefing());
    }

    @Test
    void dbFehlerLiefertDieDefaults() {
        when(applicationSettings.getSettingsByCategory(any())).thenThrow(new IllegalStateException("weg"));

        assertThat(service.current()).isEqualTo(BlaulichtSettings.defaults());
    }

    @Test
    void lehntNachtragVorDemBriefingAb() {
        BlaulichtSettings d = BlaulichtSettings.defaults();
        BlaulichtSettings next = new BlaulichtSettings(d.sources(), d.localities(),
                new BlaulichtSettings.Briefing(LocalTime.of(20, 0), LocalTime.of(8, 0), LocalTime.of(7, 0),
                        LocalTime.of(20, 0)));

        assertThatThrownBy(() -> service.update(next)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nachtrag");
        verify(applicationSettings, never()).saveSettings(any(), anyMap());
    }

    @Test
    void lehntUngueltigeEingabenAb() {
        BlaulichtSettings d = BlaulichtSettings.defaults();
        assertThatThrownBy(() -> service.update(new BlaulichtSettings(
                new BlaulichtSettings.Sources(true, "", true, "06440", "064400003003", List.of()),
                d.localities(), d.briefing()))).hasMessageContaining("ARS");
        assertThatThrownBy(() -> service.update(new BlaulichtSettings(d.sources(),
                new BlaulichtSettings.Localities(" ", List.of(), List.of()), d.briefing())))
                .hasMessageContaining("Hauptort");
        assertThatThrownBy(() -> service.update(new BlaulichtSettings(d.sources(),
                new BlaulichtSettings.Localities("Bad Vilbel", List.of("A,B"), List.of()), d.briefing())))
                .hasMessageContaining("Komma");
        assertThatThrownBy(() -> service.update(new BlaulichtSettings(d.sources(), d.localities(),
                new BlaulichtSettings.Briefing(LocalTime.of(2, 0), LocalTime.of(8, 0), LocalTime.of(12, 0),
                        LocalTime.of(20, 0))))).hasMessageContaining("Mitternacht");
    }
}
