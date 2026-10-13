package com.household.manager.blesensor;

import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.BleSensorReading;
import com.household.manager.repository.BleSensorReadingRepository;
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
class BleSensorReadingRecorderTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Mock private BleSensorReadingRepository repository;
    @InjectMocks private BleSensorReadingRecorder recorder;

    private BleSensor sensor(String temp, String hum) {
        return BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").active(true)
                .lastTemperature(new BigDecimal(temp)).lastHumidity(new BigDecimal(hum)).lastBatteryMv(3030).build();
    }

    private BleSensorReading last(String temp, String hum, LocalDateTime at) {
        return BleSensorReading.builder().sensorId(7L).temperature(new BigDecimal(temp))
                .humidity(new BigDecimal(hum)).measuredAt(at).build();
    }

    @Test
    void ersterPunktWirdImmerGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.empty());

        recorder.record(sensor("21.5", "50"), T0);

        ArgumentCaptor<BleSensorReading> captor = ArgumentCaptor.forClass(BleSensorReading.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSensorId()).isEqualTo(7L);
        assertThat(captor.getValue().getMeasuredAt()).isEqualTo(T0);
        assertThat(captor.getValue().getBatteryMv()).isEqualTo(3030);
    }

    @Test
    void unveraenderterWertInnerhalbVonFuenfMinutenWirdNichtGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.50", "50.00"), T0.plusMinutes(4));

        verify(repository, never()).save(any());
    }

    @Test
    void geaenderteTemperaturWirdSofortGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.6", "50"), T0.plusMinutes(1));

        verify(repository).save(any());
    }

    @Test
    void geaenderteFeuchteWirdSofortGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.5", "51"), T0.plusMinutes(1));

        verify(repository).save(any());
    }

    @Test
    void nachFuenfMinutenKommtEinHeartbeatPunkt() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.5", "50"), T0.plusMinutes(5));

        verify(repository).save(any());
    }

    @Test
    void repositoryFehlerWirdVerschluckt() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenThrow(new RuntimeException("db weg"));

        recorder.record(sensor("21.5", "50"), T0); // darf nicht werfen
    }
}
