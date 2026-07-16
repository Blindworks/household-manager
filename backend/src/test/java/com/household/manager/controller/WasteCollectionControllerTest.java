package com.household.manager.controller;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.dto.WasteCollectionSettings;
import com.household.manager.service.WasteCollectionService;
import com.household.manager.service.WasteCollectionSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCollectionControllerTest {

    @Mock
    private WasteCollectionService collectionService;
    @Mock
    private WasteCollectionSettingsService settingsService;

    private WasteCollectionController controller;

    @BeforeEach
    void setUp() {
        controller = new WasteCollectionController(collectionService, settingsService);
    }

    private WasteCollectionSettings validSettings() {
        return WasteCollectionSettings.builder()
                .enabled(true)
                .icsUrl("https://calendar.google.com/x/basic.ics")
                .lookaheadDays(3)
                .reminderEnabled(true)
                .reminderTime("19:00")
                .reminderAlexaSerials(List.of("DSN1"))
                .build();
    }

    @Test
    void upcomingOhneDaysNutztDasKonfigurierteFenster() {
        when(settingsService.getLookaheadDays()).thenReturn(5);
        when(collectionService.getUpcoming(5)).thenReturn(List.of(
                WasteCollectionEventResponse.builder().label("Biotonne").daysUntil(1).build()));

        ResponseEntity<List<WasteCollectionEventResponse>> response = controller.getUpcoming(null);

        assertThat(response.getBody()).hasSize(1);
        verify(collectionService).getUpcoming(5);
    }

    @Test
    void upcomingMitDaysNutztDenParameter() {
        when(collectionService.getUpcoming(60)).thenReturn(List.of());

        controller.getUpcoming(60);

        verify(collectionService).getUpcoming(60);
        verify(settingsService, never()).getLookaheadDays();
    }

    @Test
    void speichertGueltigeSettings() {
        WasteCollectionSettings settings = validSettings();
        when(settingsService.getSettings()).thenReturn(settings);

        ResponseEntity<WasteCollectionSettings> response = controller.updateSettings(settings);

        verify(settingsService).saveSettings(settings);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void lehntUngueltigeUrlAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setIcsUrl("nicht-mal-eine-url");

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("URL");
        verify(settingsService, never()).saveSettings(settings);
    }

    @Test
    void erlaubtLeereUrl() {
        WasteCollectionSettings settings = validSettings();
        settings.setIcsUrl("");
        when(settingsService.getSettings()).thenReturn(settings);

        controller.updateSettings(settings);

        verify(settingsService).saveSettings(settings);
    }

    @Test
    void lehntNichtHttpSchemaAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setIcsUrl("file:///etc/passwd");

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("http");
        verify(settingsService, never()).saveSettings(settings);
    }

    @Test
    void lehntZuKleinesVorschaufensterAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setLookaheadDays(0);

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Vorschau");
        verify(settingsService, never()).saveSettings(settings);
    }

    @Test
    void lehntUnparsbareUhrzeitAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setReminderTime("abends");

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Uhrzeit");
        verify(settingsService, never()).saveSettings(settings);
    }
}
