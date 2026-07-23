package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerHistoryAggregationJobTest {

    @Mock
    private EntityPowerHistoryRepository repository;

    private PowerHistoryAggregationJob job;

    @BeforeEach
    void setUp() {
        job = new PowerHistoryAggregationJob(repository);
    }

    private EntityPowerHistory point(long id, LocalDateTime at, Double watts) {
        return EntityPowerHistory.builder()
                .id(id).entityId("sensor.meross_wm_power").measuredAt(at).powerWatts(watts).build();
    }

    private List<EntityPowerHistory> captureAllSaved() {
        ArgumentCaptor<EntityPowerHistory> captor = ArgumentCaptor.forClass(EntityPowerHistory.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void verdichtet_mehrere_werte_derselben_minute_zum_mittelwert() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), 100.0),
                        point(2L, minute.withSecond(35), 200.0)))
                .thenReturn(List.of());

        job.aggregate();

        EntityPowerHistory saved = captureAllSaved().get(0);
        assertThat(saved.getMeasuredAt()).isEqualTo(minute);
        assertThat(saved.getPowerWatts()).isEqualTo(150.0);
        verify(repository).deleteAllByIdIn(List.of(1L, 2L));
    }

    @Test
    void laesst_einzelwerte_unangetastet() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(point(1L, minute.withSecond(5), 100.0)))
                .thenReturn(List.of());

        job.aggregate();

        verify(repository, never()).save(any());
        verify(repository, never()).deleteAllByIdIn(anyList());
    }

    @Test
    void ein_bucket_nur_aus_luecken_bleibt_eine_luecke() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), null),
                        point(2L, minute.withSecond(35), null)))
                .thenReturn(List.of());

        job.aggregate();

        assertThat(captureAllSaved().get(0).getPowerWatts()).isNull();
    }

    @Test
    void luecken_verwaessern_den_mittelwert_nicht() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), 100.0),
                        point(2L, minute.withSecond(35), null)))
                .thenReturn(List.of());

        job.aggregate();

        assertThat(captureAllSaved().get(0).getPowerWatts()).isEqualTo(100.0);
    }

    @Test
    void trennt_geraete_voneinander() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        EntityPowerHistory other = EntityPowerHistory.builder()
                .id(3L).entityId("sensor.meross_tv_power").measuredAt(minute.withSecond(5))
                .powerWatts(50.0).build();
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), 100.0),
                        point(2L, minute.withSecond(35), 200.0),
                        other))
                .thenReturn(List.of());

        job.aggregate();

        // Nur die zwei Punkte der Waschmaschine bilden einen Bucket; das TV-Geraet bleibt allein.
        assertThat(captureAllSaved()).hasSize(1);
        verify(repository).deleteAllByIdIn(List.of(1L, 2L));
    }

    @Test
    void loescht_zeilen_aelter_als_die_aufbewahrungsfrist() {
        when(repository.findByMeasuredAtBetween(any(), any())).thenReturn(List.of());

        job.aggregate();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteByMeasuredAtBefore(captor.capture());
        assertThat(captor.getValue()).isBefore(LocalDateTime.now().minusDays(29));
    }
}
