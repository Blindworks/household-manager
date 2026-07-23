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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerHistoryRecorderTest {

    private static final LocalDateTime MOMENT = LocalDateTime.of(2026, 7, 23, 10, 30);

    @Mock
    private EntityPowerHistoryRepository repository;

    private PowerHistoryRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new PowerHistoryRecorder(repository);
    }

    private EntityStateChangedEvent event(String state, Map<String, Object> attributes) {
        return new EntityStateChangedEvent(
                "sensor.meross_wm_power", "0", state, attributes, MOMENT);
    }

    private EntityPowerHistory captureSaved() {
        ArgumentCaptor<EntityPowerHistory> captor = ArgumentCaptor.forClass(EntityPowerHistory.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void schreibt_messpunkt_fuer_power_sensor() {
        recorder.onStateChanged(event("1250.5", Map.of("deviceClass", "power", "unit", "W")));

        EntityPowerHistory saved = captureSaved();
        assertThat(saved.getEntityId()).isEqualTo("sensor.meross_wm_power");
        assertThat(saved.getMeasuredAt()).isEqualTo(MOMENT);
        assertThat(saved.getPowerWatts()).isEqualTo(1250.5);
    }

    @Test
    void ignoriert_sensoren_ohne_device_class_power() {
        recorder.onStateChanged(event("21.5", Map.of("deviceClass", "temperature")));

        verify(repository, never()).save(any());
    }

    @Test
    void ignoriert_events_ohne_attribute() {
        recorder.onStateChanged(event("on", Map.of()));

        verify(repository, never()).save(any());
    }

    @Test
    void schreibt_luecke_wenn_der_sensor_nicht_erreichbar_ist() {
        recorder.onStateChanged(event("unavailable", Map.of("deviceClass", "power")));

        assertThat(captureSaved().getPowerWatts()).isNull();
    }

    @Test
    void ein_repository_fehler_schlaegt_nie_zur_integration_durch() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB weg"));

        assertThatCode(() -> recorder.onStateChanged(
                event("42", Map.of("deviceClass", "power")))).doesNotThrowAnyException();
    }
}
