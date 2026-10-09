package com.household.manager.thermostat;

import com.household.manager.model.entity.Thermostat;
import com.household.manager.model.entity.ThermostatHistory;
import com.household.manager.repository.ThermostatHistoryRepository;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThermostatHistoryRecorderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 18, 30);

    @Mock
    private ThermostatHistoryRepository repository;

    private ThermostatHistoryRecorder recorder;
    private final Thermostat thermostat = Thermostat.builder().id(3L).childDeviceId("KE-3")
            .hubDeviceId("HUB").name("Wohnzimmer").build();

    @BeforeEach
    void setUp() {
        recorder = new ThermostatHistoryRecorder(repository);
    }

    private ThermostatReading reading(boolean online, Double current, Double target, boolean heating) {
        return new ThermostatReading("KE-3", "x", online, current, target, ThermostatMode.MANUAL, heating, false);
    }

    private ThermostatHistory stored(LocalDateTime at, double current, double target, boolean heating) {
        return ThermostatHistory.builder().thermostatId(3L).recordedAt(at)
                .currentTemp(current).targetTemp(target).heating(heating).build();
    }

    @Test
    @DisplayName("Erster Punkt wird gespeichert")
    void firstPointIsStored() {
        when(repository.findTopByThermostatIdOrderByRecordedAtDesc(3L)).thenReturn(Optional.empty());

        recorder.record(thermostat, reading(true, 20.5, 21.0, false), NOW);

        ArgumentCaptor<ThermostatHistory> captor = ArgumentCaptor.forClass(ThermostatHistory.class);
        verify(repository).save(captor.capture());
        assertEquals(3L, captor.getValue().getThermostatId());
        assertEquals(20.5, captor.getValue().getCurrentTemp());
        assertEquals(NOW, captor.getValue().getRecordedAt());
    }

    @Test
    @DisplayName("Unveraenderter Wert innerhalb von 5 Minuten erzeugt keinen neuen Punkt")
    void unchangedWithinFiveMinutesIsSkipped() {
        when(repository.findTopByThermostatIdOrderByRecordedAtDesc(3L))
                .thenReturn(Optional.of(stored(NOW.minusMinutes(4), 20.5, 21.0, false)));

        recorder.record(thermostat, reading(true, 20.5, 21.0, false), NOW);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Unveraenderter Wert nach 5 Minuten erzeugt einen Punkt")
    void unchangedAfterFiveMinutesIsStored() {
        when(repository.findTopByThermostatIdOrderByRecordedAtDesc(3L))
                .thenReturn(Optional.of(stored(NOW.minusMinutes(5), 20.5, 21.0, false)));

        recorder.record(thermostat, reading(true, 20.5, 21.0, false), NOW);

        verify(repository).save(any(ThermostatHistory.class));
    }

    @Test
    @DisplayName("Aenderung von Ist, Soll oder Heizen erzeugt sofort einen Punkt")
    void changeIsStoredImmediately() {
        when(repository.findTopByThermostatIdOrderByRecordedAtDesc(3L))
                .thenReturn(Optional.of(stored(NOW.minusMinutes(1), 20.5, 21.0, false)));

        recorder.record(thermostat, reading(true, 20.5, 21.0, true), NOW);

        verify(repository).save(any(ThermostatHistory.class));
    }

    @Test
    @DisplayName("Offline-Geraete erzeugen keinen Punkt")
    void offlineIsNotRecorded() {
        recorder.record(thermostat, reading(false, null, null, false), NOW);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Repository-Fehler wirft keine Exception")
    void repositoryFailureIsSwallowed() {
        when(repository.findTopByThermostatIdOrderByRecordedAtDesc(3L)).thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> recorder.record(thermostat, reading(true, 20.5, 21.0, false), NOW));
        assertTrue(true);
    }
}
