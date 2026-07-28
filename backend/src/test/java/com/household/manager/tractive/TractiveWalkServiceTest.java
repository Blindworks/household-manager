package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
        when(apiClient.getPositionHistory(eq("tok-1"), eq("u-1"), eq("dev-9"),
                any(Instant.class), any(Instant.class))).thenReturn(walkSegments());

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void zweiterAbrufInnerhalbDerTtlKommtAusDemCache() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(walkSegments());

        service.getWalks("dev-9", 7);
        service.getWalks("dev-9", 7);

        verify(apiClient, times(1)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void tageWerdenAufDasMaximumGeklemmt() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 999);

        var fromCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(apiClient).getPositionHistory(anyString(), anyString(), anyString(),
                fromCaptor.capture(), any(Instant.class));
        long ageDays = java.time.Duration.between(fromCaptor.getValue(), Instant.now()).toDays();
        assertTrue(ageDays <= TractiveWalkService.MAX_DAYS);
    }
}
