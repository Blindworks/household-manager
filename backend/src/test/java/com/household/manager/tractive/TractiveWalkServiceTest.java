package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractiveWalkServiceTest {

    private static final TractiveHomeSettings HOME = new TractiveHomeSettings(
            48.2082, 16.3738, 100, 500, 60, 15, "Zuhause");
    private static final TractiveHomeSettings NO_HOME = new TractiveHomeSettings(
            null, null, 100, 500, 60, 15, "Zuhause");

    @Mock
    private TractivePositionRepository repository;
    @Mock
    private TractiveHomeSettingsService homeSettingsService;

    private TractiveWalkService service;

    @BeforeEach
    void setUp() {
        service = new TractiveWalkService(repository, homeSettingsService);
    }

    /**
     * Vier Punkte im 10-Minuten-Abstand, rund einen Kilometer vom Zuhause entfernt –
     * zusammen ein 30-minuetiger Spaziergang. Ein Sprung von 30 Minuten wuerde die
     * Gap-Schwelle des Detektors verletzen und in zu kurze Segmente zerfallen.
     */
    private List<TractivePosition> walkPoints() {
        Instant t = Instant.now().minus(Duration.ofHours(1));
        return List.of(
                point(t),
                point(t.plus(Duration.ofMinutes(10))),
                point(t.plus(Duration.ofMinutes(20))),
                point(t.plus(Duration.ofMinutes(30))));
    }

    private TractivePosition point(Instant at) {
        return TractivePosition.builder()
                .trackerId("dev-9")
                .positionTime(at)
                .latitude(48.2182)
                .longitude(16.3738)
                .accuracy(12.0)
                .sensorUsed("GPS")
                .build();
    }

    @Test
    void ohneZuhauseKommtEineKlareFehlermeldung() {
        // Der Detektor braucht die Home-Zone, um eine Runde von einem Aufenthalt
        // auf der Ladeschale zu unterscheiden.
        when(homeSettingsService.getSettings()).thenReturn(NO_HOME);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getWalks("dev-9", 7));
        assertTrue(ex.getMessage().contains("Zuhause"));
        verifyNoInteractions(repository);
    }

    @Test
    void leitetSpaziergaengeAusGespeichertenPunktenAb() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                eq("dev-9"), any(Instant.class))).thenReturn(walkPoints());

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void ohneGespeichertePunkteGibtEsKeineSpaziergaenge() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                anyString(), any(Instant.class))).thenReturn(List.of());

        assertTrue(service.getWalks("dev-9", 7).isEmpty());
    }

    @Test
    void fragtDenGewaehltenZeitraumAbMitternachtAb() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                anyString(), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 7);

        // Exakt statt einer Spanne: Duration.toDays() schneidet ab, eine Toleranz
        // von einem Tag wuerde einen Off-by-one im Offset gerade verdecken.
        assertEquals(midnightDaysAgo(6), capturedFrom());
    }

    @Test
    void klemmtDenZeitraumAufDasMaximum() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                anyString(), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 99_999);

        assertEquals(midnightDaysAgo(TractiveWalkService.MAX_DAYS - 1), capturedFrom());
    }

    /** Mitternacht vor N Tagen in lokaler Haushaltszeit — dieselbe Rechnung wie im Service. */
    private Instant midnightDaysAgo(int days) {
        return LocalDate.now(ZoneId.systemDefault()).minusDays(days)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private Instant capturedFrom() {
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                eq("dev-9"), from.capture());
        return from.getValue();
    }
}
