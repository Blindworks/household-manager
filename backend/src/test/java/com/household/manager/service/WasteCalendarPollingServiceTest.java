package com.household.manager.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.repository.WasteCollectionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCalendarPollingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock
    private WasteCalendarIcsClient icsClient;
    @Mock
    private WasteCalendarIcsParser icsParser;
    @Mock
    private WasteCollectionResyncService resyncService;
    @Mock
    private WasteCollectionEventRepository repository;
    @Mock
    private WasteCollectionSettingsService settingsService;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private TaskScheduler taskScheduler;

    private WasteCalendarPollingService service;

    @BeforeEach
    void setUp() {
        service = new WasteCalendarPollingService(
                icsClient, icsParser, resyncService, repository, settingsService,
                entityStateService, taskScheduler, CLOCK);
    }

    @Test
    void ruftNichtAbWennDasFeatureAusgeschaltetIst() {
        when(settingsService.isEnabled()).thenReturn(false);

        service.scheduledPoll();

        verifyNoInteractions(icsClient);
        verifyNoInteractions(resyncService);
    }

    @Test
    void ruftNichtAbWennKeineUrlHinterlegtIst() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("");

        service.scheduledPoll();

        verifyNoInteractions(icsClient);
        verifyNoInteractions(resyncService);
    }

    @Test
    void setztLastErrorUndResynchedNichtWennDerAbrufScheitert() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString()))
                .thenThrow(new WasteCalendarException("Kalender ist nicht erreichbar: timeout"));

        service.scheduledPoll();

        assertThat(service.getStatus().getLastError()).contains("nicht erreichbar");
        // Entscheidend: Ein Ausfall der Quelle darf die Tabelle nicht anfassen.
        verifyNoInteractions(resyncService);
    }

    @Test
    void resynchedNichtWennDasParsenKeineTermineLiefert() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString())).thenReturn("BEGIN:VCALENDAR\nEND:VCALENDAR");
        when(icsParser.parse(anyString(), any(), any())).thenReturn(List.of());

        service.scheduledPoll();

        verifyNoInteractions(resyncService);
        assertThat(service.getStatus().getLastError()).isNull();
    }

    @Test
    void resynchedBeiErfolgreichemAbruf() {
        List<ParsedWasteEvent> parsed = List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Restmuell"));
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString())).thenReturn("ics");
        when(icsParser.parse(anyString(), any(), any())).thenReturn(parsed);

        service.scheduledPoll();

        verify(resyncService).resync(TODAY, parsed);
        assertThat(service.getStatus().getLastError()).isNull();
        assertThat(service.getStatus().getLastPollTime()).isNotNull();
    }

    @Test
    void meldetDenFruehestenTerminAlsEntityStateNichtDenErstenInDerListe() {
        // Der spaetere Termin steht bewusst zuerst in der Liste: der Test soll beweisen,
        // dass das fruehste Datum gewinnt, nicht die Reihenfolge im Parse-Ergebnis.
        List<ParsedWasteEvent> parsed = List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 24), "Restmuell"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"));
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString())).thenReturn("ics");
        when(icsParser.parse(anyString(), any(), any())).thenReturn(parsed);

        service.scheduledPoll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();

        assertThat(update.entityId()).isEqualTo(
                EntityIds.build(EntityDomain.SENSOR, EntitySource.WASTE, "calendar", "next_collection"));
        assertThat(update.friendlyName()).isEqualTo("Naechste Muellabfuhr");
        assertThat(update.state()).isEqualTo("2026-07-17");
        assertThat(update.attributes()).containsEntry("label", "Biotonne");
        assertThat(update.attributes()).containsEntry("daysUntil", 1L);
    }

    @Test
    void blockiertEinenZweitenAufrufWaehrendEinPollBereitsLaeuft() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        // Re-entranter Aufruf mitten im laufenden Poll: pollInProgress ist zu diesem
        // Zeitpunkt bereits gesetzt, der zweite scheduledPoll() muss ohne fetch zurueckkehren.
        when(icsClient.fetch(anyString())).thenAnswer(invocation -> {
            service.scheduledPoll();
            return "BEGIN:VCALENDAR\nEND:VCALENDAR";
        });
        when(icsParser.parse(anyString(), any(), any())).thenReturn(List.of());

        service.scheduledPoll();

        verify(icsClient, times(1)).fetch(anyString());
    }
}
