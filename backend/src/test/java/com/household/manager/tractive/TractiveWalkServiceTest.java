package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TractiveWalkServiceTest {

    private static final TractiveHomeSettings HOME = new TractiveHomeSettings(
            48.2082, 16.3738, 100, 500, 60, 15, "Zuhause");
    private static final TractiveHomeSettings NO_HOME = new TractiveHomeSettings(
            null, null, 100, 500, 60, 15, "Zuhause");

    @Mock
    private TractiveApiClient apiClient;
    @Mock
    private TractiveAuthService authService;
    @Mock
    private TractiveHomeSettingsService homeSettingsService;

    private TractiveWalkService service;

    @BeforeEach
    void setUp() {
        service = new TractiveWalkService(apiClient, authService, homeSettingsService);
    }

    private TractiveAuth auth() {
        return TractiveAuth.builder().accessToken("tok-1").userId("u-1").build();
    }

    private void stubHappyAuth() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
    }

    /**
     * Vier Unterwegs-Punkte im 10-Minuten-Abstand (die Obergrenze der
     * Gap-Toleranz des Detektors) – zusammen ein 30-minuetiger Spaziergang.
     * Ein einzelner Sprung von 30 Minuten wuerde die Gap-Schwelle verletzen
     * und in mehrere zu kurze Segmente zerfallen.
     */
    private List<List<TractivePositionDto>> walkSegments() {
        long t = Instant.now().minusSeconds(3600).getEpochSecond();
        return List.of(List.of(
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", t),
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", t + 600),
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", t + 1200),
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", t + 1800)));
    }

    @Test
    void ohneZuhauseKommtEineKlareFehlermeldung() {
        when(homeSettingsService.getSettings()).thenReturn(NO_HOME);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getWalks("dev-9", 7));
        assertTrue(ex.getMessage().contains("Zuhause"));
    }

    @Test
    void ohneTokenKommtEinAuthFehler() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.empty());

        assertThrows(TractiveAuthException.class, () -> service.getWalks("dev-9", 7));
    }

    @Test
    void liefertSpaziergaengeAusDerPositionshistorie() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(eq("tok-1"), eq("u-1"), eq("dev-9"),
                any(Instant.class), any(Instant.class))).thenReturn(walkSegments());

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void holtDieHistorieInTagesHaeppchenNeuesteZuerst() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 7);

        var fromCaptor = ArgumentCaptor.forClass(Instant.class);
        var toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(apiClient, times(7)).getPositionHistory(anyString(), anyString(), anyString(),
                fromCaptor.capture(), toCaptor.capture());
        List<Instant> froms = fromCaptor.getAllValues();
        List<Instant> tos = toCaptor.getAllValues();

        // Erster Aufruf ist der heutige (angebrochene) Tag und endet jetzt.
        assertTrue(Duration.between(tos.get(0), Instant.now()).abs()
                .compareTo(Duration.ofMinutes(1)) < 0, "erstes Haeppchen endet nicht 'jetzt'");
        for (int i = 0; i < 7; i++) {
            Duration window = Duration.between(froms.get(i), tos.get(i));
            assertTrue(window.compareTo(Duration.ofHours(24)) <= 0,
                    "Fenster " + i + " ist groesser als 24 h: " + window);
            assertFalse(window.isNegative(), "Fenster " + i + " ist negativ");
        }
        for (int i = 0; i + 1 < 7; i++) {
            // Rueckwaerts lueckenlos; am 25-h-Umstellungstag darf maximal
            // eine Stunde fehlen (24-h-Fenster-Kappung).
            Duration gap = Duration.between(tos.get(i + 1), froms.get(i));
            assertFalse(gap.isNegative(), "Haeppchen " + i + " ueberlappt Vortag");
            assertTrue(gap.compareTo(Duration.ofHours(1)) <= 0,
                    "Luecke zwischen Haeppchen " + (i + 1) + " und " + i + ": " + gap);
        }
    }

    @Test
    void abgeschlosseneTageKommenBeimZweitenAbrufAusDemCache() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(walkSegments());

        service.getWalks("dev-9", 7);
        service.getWalks("dev-9", 7);

        // Vortage sind unveraenderlich, der heutige Tag hat eine kurze TTL:
        // der zweite Abruf direkt danach kostet keinen einzigen Cloud-Aufruf.
        verify(apiClient, times(7)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void einzelneFehlgeschlageneHaeppchenWerdenToleriert() {
        stubHappyAuth();
        // Heute liefert Daten, ein Vortag scheitert, der Rest ist leer.
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class)))
                .thenReturn(walkSegments())
                .thenThrow(new TractiveException("500 Internal Server Error"))
                .thenReturn(List.of());

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        verify(apiClient, times(7)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void wennAlleHaeppchenScheiternKommtDerFehlerDurch() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class)))
                .thenThrow(new TractiveException("kaputt"));

        assertThrows(TractiveException.class, () -> service.getWalks("dev-9", 7));
    }

    @Test
    void rateLimitBrichtWeitereCloudAufrufeAbUndLiefertTeilergebnis() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class)))
                .thenReturn(walkSegments())
                .thenThrow(new TractiveRateLimitException("429"));

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        // Nach dem ersten 429 wird kein weiteres Haeppchen mehr versucht.
        verify(apiClient, times(2)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void nachRateLimitKeineSofortigenNeuversuche() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class)))
                .thenReturn(walkSegments())
                .thenThrow(new TractiveRateLimitException("429"));

        service.getWalks("dev-9", 7);
        var walks = service.getWalks("dev-9", 7);

        // Zweiter Klick waehrend der Abkuehlpause: Cache liefert, Cloud bleibt in Ruhe.
        assertEquals(1, walks.size());
        verify(apiClient, times(2)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void rateLimitOhneJeglicheDatenLiefertVerstaendlichenFehler() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class)))
                .thenThrow(new TractiveRateLimitException("429"));

        TractiveException ex = assertThrows(TractiveException.class,
                () -> service.getWalks("dev-9", 7));

        assertTrue(ex.getMessage().contains("Rate-Limit"));
        // Schon der erste 429 beendet den Durchlauf.
        verify(apiClient, times(1)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void tageWerdenAufDasMaximumGeklemmt() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 999);

        var fromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(apiClient, times(TractiveWalkService.MAX_DAYS)).getPositionHistory(
                anyString(), anyString(), anyString(), fromCaptor.capture(), any(Instant.class));
        Instant oldest = fromCaptor.getAllValues().stream().min(Instant::compareTo).orElseThrow();
        long ageDays = Duration.between(oldest, Instant.now()).toDays();
        assertTrue(ageDays <= TractiveWalkService.MAX_DAYS);
    }

    @Test
    void dreissigTageWerdenNichtMehrGeklemmt() {
        stubHappyAuth();
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 30);

        // Ein Cloud-Haeppchen je Tag: 30 angefragte Tage muessen 30 Abrufe ergeben.
        // Vor der Anhebung von MAX_DAYS waren es 14 - ohne Fehler und ohne Hinweis.
        verify(apiClient, times(30)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }
}
