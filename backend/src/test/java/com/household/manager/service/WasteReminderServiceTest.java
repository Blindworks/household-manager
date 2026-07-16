package com.household.manager.service;

import com.household.manager.alexa.AlexaException;
import com.household.manager.alexa.AlexaTtsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WasteReminderServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);

    @Mock
    private WasteCollectionService collectionService;
    @Mock
    private WasteCollectionSettingsService settingsService;
    @Mock
    private AlexaAnnouncementService announcementService;

    private final WasteAnnouncementTextBuilder textBuilder = new WasteAnnouncementTextBuilder();

    /** Baut den Service mit einer auf {@code time} fixierten Uhr. */
    private WasteReminderService serviceAt(LocalTime time) {
        Clock clock = Clock.fixed(TODAY.atTime(time).atZone(ZONE).toInstant(), ZONE);
        return new WasteReminderService(
                collectionService, settingsService, announcementService, textBuilder, clock);
    }

    /** Standardfall: alles aktiv, 19:00 konfiguriert, morgen steht die Biotonne an. */
    private void givenReadyToAnnounce() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.isReminderEnabled()).thenReturn(true);
        when(settingsService.getReminderTime()).thenReturn(LocalTime.of(19, 0));
        when(settingsService.getReminderAlexaSerials()).thenReturn(List.of("DSN1"));
        when(settingsService.wasAnnouncedOn(TODAY)).thenReturn(false);
        when(collectionService.today()).thenReturn(TODAY);
        when(collectionService.getLabelsForTomorrow()).thenReturn(List.of("Biotonne"));
    }

    @Test
    void sagtAnWennAlleBedingungenErfuelltSind() {
        givenReadyToAnnounce();

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verify(announcementService).announce(
                "Erinnerung: Morgen wird abgeholt: Biotonne.",
                List.of("DSN1"),
                AlexaTtsMode.ANNOUNCE);
        verify(settingsService).markAnnounced(TODAY);
    }

    @Test
    void sagtNichtAnVorDerKonfiguriertenUhrzeit() {
        givenReadyToAnnounce();

        serviceAt(LocalTime.of(18, 59)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnNachDemEinstuendigenFenster() {
        givenReadyToAnnounce();

        // Ein Neustart um 23:00 Uhr darf nicht nachtraeglich eine Durchsage ausloesen.
        serviceAt(LocalTime.of(23, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtInnerhalbDesFenstersNochAn() {
        givenReadyToAnnounce();

        serviceAt(LocalTime.of(19, 59)).checkAndAnnounce();

        verify(announcementService).announce(anyString(), anyList(), any());
    }

    @Test
    void sagtNichtZweimalAmSelbenTagAn() {
        givenReadyToAnnounce();
        when(settingsService.wasAnnouncedOn(TODAY)).thenReturn(true);

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnWennMorgenNichtsAnsteht() {
        givenReadyToAnnounce();
        when(collectionService.getLabelsForTomorrow()).thenReturn(List.of());

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
        verify(settingsService, never()).markAnnounced(any());
    }

    @Test
    void sagtNichtAnWennDieErinnerungAbgeschaltetIst() {
        givenReadyToAnnounce();
        when(settingsService.isReminderEnabled()).thenReturn(false);

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnWennDasFeatureAbgeschaltetIst() {
        givenReadyToAnnounce();
        when(settingsService.isEnabled()).thenReturn(false);

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnWennKeinZielgeraetKonfiguriertIst() {
        givenReadyToAnnounce();
        when(settingsService.getReminderAlexaSerials()).thenReturn(List.of());

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void merktSichNichtsWennDieAnsageScheitert() {
        givenReadyToAnnounce();
        doThrow(new AlexaException("Sidecar offline"))
                .when(announcementService).announce(anyString(), anyList(), any());

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        // Ohne Merker versucht es der naechste Lauf innerhalb des Fensters erneut.
        verify(settingsService, never()).markAnnounced(any());
    }
}
