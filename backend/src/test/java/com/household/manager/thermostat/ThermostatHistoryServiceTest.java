package com.household.manager.thermostat;

import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ThermostatHistory;
import com.household.manager.repository.ThermostatHistoryRepository;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.service.SeriesDownsampler;
import com.household.manager.service.SeriesRange;
import com.household.manager.thermostat.dto.HistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatHistoryServiceTest {

    @Mock
    private ThermostatRepository thermostatRepository;
    @Mock
    private ThermostatHistoryRepository historyRepository;

    private ThermostatHistoryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T16:30:00Z"), ZoneId.of("Europe/Berlin"));
        service = new ThermostatHistoryService(thermostatRepository, historyRepository,
                new SeriesDownsampler(), clock);
    }

    private ThermostatHistory point(LocalDateTime at, double current, double target, boolean heating) {
        return ThermostatHistory.builder().thermostatId(1L).recordedAt(at)
                .currentTemp(current).targetTemp(target).heating(heating).build();
    }

    @Test
    @DisplayName("Punkte im selben 5-Minuten-Bucket werden gemittelt, Heizen gilt als wahr, wenn einer heizte")
    void downsamplesIntoBuckets() {
        when(thermostatRepository.existsById(1L)).thenReturn(true);
        when(historyRepository.findByThermostatIdAndRecordedAtBetweenOrderByRecordedAtAsc(eq(1L), any(), any()))
                .thenReturn(List.of(
                        point(LocalDateTime.of(2026, 10, 9, 18, 1), 20.0, 21.0, false),
                        point(LocalDateTime.of(2026, 10, 9, 18, 3), 21.0, 21.0, true),
                        point(LocalDateTime.of(2026, 10, 9, 18, 7), 22.0, 22.0, false)));

        HistoryResponse response = service.history(1L, SeriesRange.DAY);

        assertEquals(2, response.points().size());
        HistoryResponse.Point first = response.points().get(0);
        assertEquals(LocalDateTime.of(2026, 10, 9, 18, 0), first.time());
        assertEquals(20.5, first.currentTemp());
        assertEquals(21.0, first.targetTemp());
        assertTrue(first.heating());
        assertEquals(false, response.points().get(1).heating());
    }

    @Test
    @DisplayName("Ohne Daten ist die Punktliste leer")
    void emptyWhenNoData() {
        when(thermostatRepository.existsById(1L)).thenReturn(true);
        when(historyRepository.findByThermostatIdAndRecordedAtBetweenOrderByRecordedAtAsc(eq(1L), any(), any()))
                .thenReturn(List.of());

        assertTrue(service.history(1L, SeriesRange.WEEK).points().isEmpty());
    }

    @Test
    @DisplayName("Unbekannte Id ergibt ResourceNotFoundException")
    void unknownIdIsNotFound() {
        when(thermostatRepository.existsById(9L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.history(9L, SeriesRange.DAY));
    }
}
