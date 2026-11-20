package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.forecast.ChargingPatternService;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import com.household.manager.repository.ChargingObservationRepository;
import com.household.manager.repository.ChargingOccupancyHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingStationMigrationServiceTest {

    private static final ChargingStation NEU = new ChargingStation("3511835", "Am Südbahnhof 3", "Mainova AG",
            null, 50.178389, 8.734765, 150.0, 2, 1);

    @Mock
    private ChargingFavoriteRepository favorites;
    @Mock
    private ChargingObservationRepository observations;
    @Mock
    private ChargingOccupancyHistoryRepository history;
    @Mock
    private ChargingOccupancyTracker tracker;
    @Mock
    private ChargingForecastService forecastService;
    @Mock
    private ChargingPatternService patternService;
    @Mock
    private AuditService auditService;

    private ChargingStationMigrationService service;
    private ChargingFavorite favorite;

    @BeforeEach
    void setUp() {
        service = new ChargingStationMigrationService(favorites, observations, history, tracker,
                forecastService, patternService, auditService);
        favorite = ChargingFavorite.builder().id(7L).stationId("ALT").displayName("Am Südbahnhof 3")
                .operator("Mainova AG").lat(50.1784).lon(8.7348).createdAt(Instant.EPOCH)
                .directPricePerKwh(new BigDecimal("0.590")).directPriceNote("Schell-App").build();
    }

    @Test
    void ziehtFavoritHistorieUndBeobachtungszeitUm() {
        when(favorites.findByStationId("ALT")).thenReturn(Optional.of(favorite));

        service.migrate("ALT", NEU);

        assertThat(favorite.getStationId()).isEqualTo("3511835");
        assertThat(favorite.getLat()).isEqualTo(50.178389);
        assertThat(favorite.getDisplayName()).isEqualTo("Am Südbahnhof 3");
        assertThat(favorite.getDirectPricePerKwh()).isEqualByComparingTo("0.59");
        assertThat(favorite.getDirectPriceNote()).isEqualTo("Schell-App");
        verify(favorites).save(favorite);
        verify(observations).reassignStation("ALT", "3511835");
        verify(history).reassignStation("ALT", "3511835");
        verify(tracker).forgetStation("ALT");
        verify(forecastService).forget("ALT");
        verify(patternService).evict("ALT");
        verify(patternService).evict("3511835");
        verify(auditService).record(eq("charging.favorite.migrate"), anyString());
    }

    @Test
    void zielSchonFavoritWirdAbgelehnt() {
        when(favorites.findByStationId("ALT")).thenReturn(Optional.of(favorite));
        when(favorites.existsByStationId("3511835")).thenReturn(true);

        assertThatThrownBy(() -> service.migrate("ALT", NEU)).isInstanceOf(IllegalStateException.class);
        verify(favorites, never()).save(any());
        verify(observations, never()).reassignStation(any(), any());
    }

    @Test
    void zielMitEigenerBeobachtungszeitWirdAbgelehnt() {
        when(favorites.findByStationId("ALT")).thenReturn(Optional.of(favorite));
        when(observations.existsByStationId("3511835")).thenReturn(true);

        assertThatThrownBy(() -> service.migrate("ALT", NEU)).isInstanceOf(IllegalStateException.class);
        verify(favorites, never()).save(any());
        verify(history, never()).reassignStation(any(), any());
    }
}
