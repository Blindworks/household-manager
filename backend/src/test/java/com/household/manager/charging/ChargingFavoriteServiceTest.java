package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingFavoriteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @Mock
    private ChargingFavoriteRepository repository;
    @Mock
    private ChargingOccupancyTracker tracker;
    @Mock
    private AuditService auditService;

    private ChargingSnapshot snapshot;
    private ChargingFavoriteService service;

    @BeforeEach
    void setUp() {
        snapshot = new ChargingSnapshot();
        service = new ChargingFavoriteService(repository, tracker, snapshot, auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void favorisierenUebernimmtNameBetreiberUndKoordinatenAusDemSnapshot() {
        snapshot.updateArea(List.of(new ChargingStation("S1", "Lidl Hauptstr.", "Lidl", "Hauptstr. 1",
                50.0, 8.0, 150.0, 2, 1)), NOW);
        when(repository.existsByStationId("S1")).thenReturn(false);

        service.add("S1");

        ArgumentCaptor<ChargingFavorite> captor = ArgumentCaptor.forClass(ChargingFavorite.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("Lidl Hauptstr.");
        assertThat(captor.getValue().getOperator()).isEqualTo("Lidl");
        assertThat(captor.getValue().getLat()).isEqualTo(50.0);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(auditService).record(eq("charging.favorite.add"), any());
    }

    @Test
    void unbekannteStationLaesstSichNichtFavorisieren() {
        assertThatThrownBy(() -> service.add("fremd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht in der aktuellen Umkreisliste");
        verify(repository, never()).save(any());
    }

    @Test
    void doppeltesFavorisierenIstIdempotent() {
        snapshot.updateArea(List.of(new ChargingStation("S1", "X", null, null, 50.0, 8.0, null, 1, 1)), NOW);
        when(repository.existsByStationId("S1")).thenReturn(true);

        service.add("S1");

        verify(repository, never()).save(any());
    }

    @Test
    void entfernenLoeschtFavoritUndBelegungszeilen() {
        ChargingFavorite favorite = ChargingFavorite.builder().id(1L).stationId("S1").displayName("X")
                .lat(50).lon(8).createdAt(NOW).build();
        when(repository.findByStationId("S1")).thenReturn(Optional.of(favorite));

        service.remove("S1");

        verify(repository).delete(favorite);
        verify(tracker).forgetStation("S1");
        verify(auditService).record(eq("charging.favorite.remove"), any());
    }

    @Test
    void entfernenEinesUnbekanntenFavoritenIstStill() {
        when(repository.findByStationId("S1")).thenReturn(Optional.empty());

        service.remove("S1");

        verify(repository, never()).delete(any());
        verify(tracker, never()).forgetStation(any());
    }
}
