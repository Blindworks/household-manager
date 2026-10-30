package com.household.manager.dirigera;

import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.DirigeraSensorReading;
import com.household.manager.repository.DirigeraSensorReadingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirigeraReadingRecorderTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 30, 9, 0);

    @Mock private DirigeraSensorReadingRepository repository;
    @InjectMocks private DirigeraReadingRecorder recorder;

    private DirigeraSensor sensor(String temp, String hum) {
        return DirigeraSensor.builder().id(7L).relationId("rel")
                .lastTemperature(new BigDecimal(temp)).lastHumidity(new BigDecimal(hum)).build();
    }

    private DirigeraSensorReading last(String temp, String hum, LocalDateTime at) {
        return DirigeraSensorReading.builder().sensorId(7L).temperature(new BigDecimal(temp))
                .humidity(new BigDecimal(hum)).measuredAt(at).build();
    }

    @Test
    void ersteMessungWirdGespeichert() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.empty());

        recorder.record(sensor("18.79", "45"), T0);

        ArgumentCaptor<DirigeraSensorReading> captor = ArgumentCaptor.forClass(DirigeraSensorReading.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSensorId()).isEqualTo(7L);
        assertThat(captor.getValue().getMeasuredAt()).isEqualTo(T0);
        assertThat(captor.getValue().getTemperature()).isEqualByComparingTo("18.79");
    }

    @Test
    void gleicherWertInnerhalbFuenfMinutenNicht() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("18.79", "45", T0)));

        recorder.record(sensor("18.790", "45.00"), T0.plusMinutes(4));

        verify(repository, never()).save(any());
    }

    @Test
    void heartbeatNachFuenfMinuten() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("18.79", "45", T0)));

        recorder.record(sensor("18.79", "45"), T0.plusMinutes(5));

        verify(repository).save(any());
    }

    @Test
    void aenderungSofort() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("18.79", "45", T0)));

        recorder.record(sensor("18.80", "45"), T0.plusMinutes(1));

        verify(repository).save(any());
    }

    @Test
    void repositoryFehlerWirdGeschluckt() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenThrow(new RuntimeException("db weg"));

        recorder.record(sensor("18.79", "45"), T0); // darf nicht werfen
    }
}
