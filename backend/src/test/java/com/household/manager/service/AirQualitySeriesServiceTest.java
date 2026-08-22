package com.household.manager.service;

import com.household.manager.dto.AirQualitySensorSeries;
import com.household.manager.model.entity.AirrohrReading;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AirrohrReadingRepository;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AirQualitySeriesServiceTest {

    @Mock private AirrohrReadingRepository airrohrRepository;
    @Mock private AlexaAirQualityReadingRepository alexaRepository;

    private AirQualitySeriesService service;

    @BeforeEach
    void setUp() {
        service = new AirQualitySeriesService(airrohrRepository, alexaRepository, new SeriesDownsampler());
    }

    private AirrohrReading airrohr(LocalDateTime time, String pm10, String pm25) {
        return AirrohrReading.builder()
                .readingTime(time)
                .sdsP1(pm10 == null ? null : new BigDecimal(pm10))
                .sdsP2(pm25 == null ? null : new BigDecimal(pm25))
                .build();
    }

    private AlexaAirQualityReading alexa(LocalDateTime time, Integer iaq, String pm25, String voc, String co) {
        return AlexaAirQualityReading.builder()
                .applianceId("appliance-1")
                .deviceName("Wohnzimmer")
                .readingTime(time)
                .iaq(iaq)
                .pm25(pm25 == null ? null : new BigDecimal(pm25))
                .voc(voc == null ? null : new BigDecimal(voc))
                .co(co == null ? null : new BigDecimal(co))
                .build();
    }

    private AirQualitySensorSeries seriesWithId(List<AirQualitySensorSeries> all, String sensorId) {
        return all.stream().filter(s -> s.getSensorId().equals(sensorId)).findFirst().orElseThrow();
    }

    @Test
    void liefertFeinstaubDesAirrohrSensors() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 22, 10, 0);
        when(airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(airrohr(time, "12.00", "8.00")));
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        AirQualitySensorSeries series = seriesWithId(service.getSeries(SeriesRange.DAY), "airrohr:local");

        assertThat(series.getName()).isEqualTo("Draußen");
        assertThat(series.getSource()).isEqualTo("AIRROHR");
        assertThat(series.getMetrics()).containsOnlyKeys("pm25", "pm10");
        assertThat(series.getMetrics().get("pm25").get(0).getValue()).isEqualByComparingTo("8.00");
        assertThat(series.getMetrics().get("pm10").get(0).getValue()).isEqualByComparingTo("12.00");
    }

    @Test
    void liefertJeAmazonGeraetEineReiheMitNamenUndAllenGroessen() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 22, 10, 0);
        when(airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(alexa(time, 72, "3.00", "150.00", "0.400")));

        AirQualitySensorSeries series =
                seriesWithId(service.getSeries(SeriesRange.DAY), "alexa:appliance-1");

        assertThat(series.getName()).isEqualTo("Wohnzimmer");
        assertThat(series.getSource()).isEqualTo("ALEXA");
        assertThat(series.getMetrics()).containsOnlyKeys("iaq", "pm25", "voc", "co");
        assertThat(series.getMetrics().get("iaq").get(0).getValue()).isEqualByComparingTo("72");
    }

    @Test
    void laesstMessgroessenOhneWerteAusDerMapWeg() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 22, 10, 0);
        when(airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(alexa(time, 72, null, null, null)));

        AirQualitySensorSeries series =
                seriesWithId(service.getSeries(SeriesRange.DAY), "alexa:appliance-1");

        assertThat(series.getMetrics()).containsOnlyKeys("iaq");
    }

    @Test
    void laesstSensorenOhneJedenMesswertGanzWeg() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 22, 10, 0);
        when(airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(airrohr(time, null, null)));
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        assertThat(service.getSeries(SeriesRange.DAY)).isEmpty();
    }

    @Test
    void mitteltMehrereRohpunkteEinesBucketsZuEinemWert() {
        // DAY hat 5-Minuten-Buckets: beide Punkte fallen in denselben.
        LocalDateTime time = LocalDateTime.of(2026, 8, 22, 10, 0);
        when(airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(
                        airrohr(time, "10.00", "6.00"),
                        airrohr(time.plusMinutes(1), "20.00", "10.00")));
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        AirQualitySensorSeries series = seriesWithId(service.getSeries(SeriesRange.DAY), "airrohr:local");

        assertThat(series.getMetrics().get("pm10")).hasSize(1);
        assertThat(series.getMetrics().get("pm10").get(0).getValue()).isEqualByComparingTo("15.00");
    }

    @Test
    void eineAusfallendeQuelleKipptDieGesamtantwortNicht() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 22, 10, 0);
        when(airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenThrow(new RuntimeException("DB weg"));
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(alexa(time, 72, "3.00", "150.00", "0.400")));

        List<AirQualitySensorSeries> result = service.getSeries(SeriesRange.DAY);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSensorId()).isEqualTo("alexa:appliance-1");
    }
}
