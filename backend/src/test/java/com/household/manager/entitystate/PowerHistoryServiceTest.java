package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.PowerHistoryResponse;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityPowerHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerHistoryServiceTest {

    @Mock
    private EntityPowerHistoryRepository historyRepository;
    @Mock
    private PowerConsumerQueryService consumerQueryService;

    private PowerHistoryService service;

    @BeforeEach
    void setUp() {
        service = new PowerHistoryService(historyRepository, consumerQueryService,
                new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState consumer() {
        return EntityState.builder()
                .entityId("sensor.meross_wm_power")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.MEROSS)
                .sourceRef("wm")
                .friendlyName("Waschmaschine Leistung")
                .state("1200")
                .attributes("{\"deviceClass\":\"power\"}")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityPowerHistory point(LocalDateTime at, Double watts) {
        return EntityPowerHistory.builder()
                .entityId("sensor.meross_wm_power").measuredAt(at).powerWatts(watts).build();
    }

    @Test
    void liefert_die_punkte_des_verbrauchers() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 23, 10, 0);
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq("sensor.meross_wm_power"), any(), any()))
                .thenReturn(List.of(point(at, 1200.0)));

        PowerHistoryResponse response = service.getHistory("sensor.meross_wm_power", PowerRange.DAY)
                .orElseThrow();

        assertThat(response.entityId()).isEqualTo("sensor.meross_wm_power");
        assertThat(response.displayName()).isEqualTo("Waschmaschine Leistung");
        assertThat(response.points()).hasSize(1);
        TimeValue first = response.points().get(0);
        assertThat(first.getTime()).isEqualTo(at);
        assertThat(first.getValue()).isEqualByComparingTo("1200.0");
    }

    @Test
    void reicht_messluecken_als_null_weiter() {
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(point(LocalDateTime.of(2026, 7, 23, 10, 0), null)));

        PowerHistoryResponse response = service.getHistory("sensor.meross_wm_power", PowerRange.DAY)
                .orElseThrow();

        assertThat(response.points().get(0).getValue()).isNull();
    }

    @Test
    void fragt_den_zeitraum_passend_zum_range_ab() {
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                any(), any(), any())).thenReturn(List.of());

        service.getHistory("sensor.meross_wm_power", PowerRange.MONTH);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(historyRepository)
                .findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isBefore(LocalDateTime.now().minusDays(29));
        assertThat(to.getValue()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void liefert_leer_wenn_die_entitaet_kein_verbraucher_ist() {
        when(consumerQueryService.findConsumer("sensor.zigbee_wz_temperature"))
                .thenReturn(Optional.empty());

        assertThat(service.getHistory("sensor.zigbee_wz_temperature", PowerRange.DAY)).isEmpty();
    }

    @Test
    void liefert_eine_leere_punktliste_wenn_noch_nichts_aufgezeichnet_wurde() {
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                any(), any(), any())).thenReturn(List.of());

        assertThat(service.getHistory("sensor.meross_wm_power", PowerRange.DAY)
                .orElseThrow().points()).isEmpty();
    }
}
