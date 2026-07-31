package com.household.manager.service;

import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import com.household.manager.repository.WeatherReadingRepository;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemperatureSeriesServiceTest {

    @Mock private ZigbeeMeasurementRepository zigbeeRepository;
    @Mock private ZigbeeDeviceRepository zigbeeDeviceRepository;
    @Mock private TemperatureSeriesDownsampler downsampler;
    @Mock private WeatherReadingRepository weatherRepository;
    @Mock private AlexaAirQualityReadingRepository alexaRepository;
    @Mock private EntityStateService entityStateService;

    @InjectMocks private TemperatureSeriesService service;

    private ZigbeeDevice device(long id, String name) {
        return ZigbeeDevice.builder().id(id).friendlyName(name).build();
    }

    /** Stubbt die Temperatur-Entity einer Quelle mit gesetztem Kurznamen. */
    private void stubCustomName(EntitySource source, String sourceRef, String customName) {
        String entityId = EntityIds.build(EntityDomain.SENSOR, source, sourceRef, "temperature");
        when(entityStateService.getByEntityId(entityId))
                .thenReturn(Optional.of(EntityState.builder().customName(customName).build()));
    }

    private ZigbeeMeasurement measurement(MeasurementType type, String value, LocalDateTime at) {
        return ZigbeeMeasurement.builder()
                .measurementType(type).value(new BigDecimal(value)).measuredAt(at).build();
    }

    @Test
    void zigbeeDevicePairsTemperatureAndHumidity() {
        LocalDateTime now = LocalDateTime.now();
        ZigbeeDevice wohnzimmer = device(1L, "Wohnzimmer");
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(
                eq(MeasurementType.TEMPERATURE), any(), any())).thenReturn(List.of(wohnzimmer));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(1L), eq(MeasurementType.TEMPERATURE), any(), any()))
                .thenReturn(List.of(measurement(MeasurementType.TEMPERATURE, "21.5", now)));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(1L), eq(MeasurementType.HUMIDITY), any(), any()))
                .thenReturn(List.of(measurement(MeasurementType.HUMIDITY, "48", now)));
        when(weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        List<TemperatureSensorSeries> result = service.getSeries(TemperatureRange.WEEK);

        assertThat(result).hasSize(1);
        TemperatureSensorSeries series = result.get(0);
        assertThat(series.getSensorId()).isEqualTo("zigbee:1");
        assertThat(series.getName()).isEqualTo("Wohnzimmer");
        assertThat(series.getSource()).isEqualTo("ZIGBEE");
        assertThat(series.getTemperature()).hasSize(1);
        assertThat(series.getTemperature().get(0).getValue()).isEqualByComparingTo("21.5");
        assertThat(series.getHumidity()).hasSize(1);
        assertThat(series.getHumidity().get(0).getValue()).isEqualByComparingTo("48");
    }

    @Test
    void weatherProducesSingleOutdoorSeries() {
        LocalDateTime now = LocalDateTime.now();
        WeatherReading reading = WeatherReading.builder()
                .readingTime(now).temperature(new BigDecimal("12.30")).humidity(80).build();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(reading));
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        List<TemperatureSensorSeries> result = service.getSeries(TemperatureRange.DAY);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSensorId()).isEqualTo("weather:outdoor");
        assertThat(result.get(0).getSource()).isEqualTo("WEATHER");
        assertThat(result.get(0).getTemperature().get(0).getValue()).isEqualByComparingTo("12.30");
        assertThat(result.get(0).getHumidity().get(0).getValue()).isEqualByComparingTo("80");
    }

    @Test
    void alexaGroupsByApplianceId() {
        LocalDateTime now = LocalDateTime.now();
        AlexaAirQualityReading a1 = AlexaAirQualityReading.builder()
                .applianceId("APP-A").deviceName("Sensor Bad").readingTime(now)
                .temperature(new BigDecimal("22.00")).humidity(new BigDecimal("55.00")).build();
        AlexaAirQualityReading a2 = AlexaAirQualityReading.builder()
                .applianceId("APP-A").deviceName("Sensor Bad").readingTime(now.plusMinutes(5))
                .temperature(new BigDecimal("22.50")).humidity(new BigDecimal("54.00")).build();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(a1, a2));

        List<TemperatureSensorSeries> result = service.getSeries(TemperatureRange.MONTH);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSensorId()).isEqualTo("alexa:APP-A");
        assertThat(result.get(0).getName()).isEqualTo("Sensor Bad");
        assertThat(result.get(0).getTemperature()).hasSize(2);
    }

    @Test
    void failingSourceIsSkippedNotFatal() {
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        lenient().when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        List<TemperatureSensorSeries> result = service.getSeries(TemperatureRange.WEEK);

        assertThat(result).isEmpty();
    }

    @Test
    void currentReturnsLatestPerZigbeeDevice() {
        LocalDateTime now = LocalDateTime.now();
        ZigbeeDevice wohnzimmer = device(1L, "Wohnzimmer");
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(
                eq(MeasurementType.TEMPERATURE), any(), any()))
                .thenReturn(List.of(wohnzimmer));
        when(zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                1L, MeasurementType.TEMPERATURE))
                .thenReturn(Optional.of(measurement(MeasurementType.TEMPERATURE, "21.5", now)));
        when(zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                1L, MeasurementType.HUMIDITY))
                .thenReturn(Optional.of(measurement(MeasurementType.HUMIDITY, "48", now)));
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of());

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).hasSize(1);
        CurrentTemperatureReading reading = result.get(0);
        assertThat(reading.getSensorId()).isEqualTo("zigbee:1");
        assertThat(reading.getName()).isEqualTo("Wohnzimmer");
        assertThat(reading.getSource()).isEqualTo("ZIGBEE");
        assertThat(reading.getTemperature()).isEqualByComparingTo("21.5");
        assertThat(reading.getHumidity()).isEqualByComparingTo("48");
        assertThat(reading.getMeasuredAt()).isEqualTo(now);
    }

    @Test
    void currentReturnsLatestWeatherAsOutside() {
        LocalDateTime now = LocalDateTime.now();
        WeatherReading reading = WeatherReading.builder()
                .readingTime(now).temperature(new BigDecimal("12.30")).humidity(80).build();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.of(reading));
        when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of());

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSensorId()).isEqualTo("weather:outdoor");
        assertThat(result.get(0).getName()).isEqualTo("Außen");
        assertThat(result.get(0).getSource()).isEqualTo("WEATHER");
        assertThat(result.get(0).getTemperature()).isEqualByComparingTo("12.30");
        assertThat(result.get(0).getHumidity()).isEqualByComparingTo("80");
    }

    @Test
    void currentReturnsLatestPerAlexaAppliance() {
        LocalDateTime now = LocalDateTime.now();
        AlexaAirQualityReading latest = AlexaAirQualityReading.builder()
                .applianceId("APP-A").deviceName("Sensor Bad").readingTime(now)
                .temperature(new BigDecimal("22.50")).humidity(new BigDecimal("54.00")).build();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of("APP-A"));
        when(alexaRepository.findTopByApplianceIdOrderByReadingTimeDesc("APP-A"))
                .thenReturn(Optional.of(latest));

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSensorId()).isEqualTo("alexa:APP-A");
        assertThat(result.get(0).getName()).isEqualTo("Sensor Bad");
        assertThat(result.get(0).getSource()).isEqualTo("ALEXA");
        assertThat(result.get(0).getTemperature()).isEqualByComparingTo("22.50");
    }

    @Test
    void currentSkipsFailingSource() {
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        lenient().when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of());

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).isEmpty();
    }

    @Test
    void currentUsesCustomNameForZigbeeWhenSet() {
        LocalDateTime now = LocalDateTime.now();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(
                eq(MeasurementType.TEMPERATURE), any(), any()))
                .thenReturn(List.of(device(1L, "Wohnzimmer")));
        when(zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                1L, MeasurementType.TEMPERATURE))
                .thenReturn(Optional.of(measurement(MeasurementType.TEMPERATURE, "21.5", now)));
        when(zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                1L, MeasurementType.HUMIDITY)).thenReturn(Optional.empty());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of());
        stubCustomName(EntitySource.ZIGBEE, "Wohnzimmer", "Couch-Sensor");

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Couch-Sensor");
    }

    @Test
    void currentUsesCustomNameForWeatherWhenSet() {
        LocalDateTime now = LocalDateTime.now();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.of(WeatherReading.builder()
                        .readingTime(now).temperature(new BigDecimal("12.30")).humidity(80).build()));
        when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of());
        stubCustomName(EntitySource.WEATHER, "dwd", "Garten");

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Garten");
    }

    @Test
    void currentUsesCustomNameForAlexaWhenSet() {
        LocalDateTime now = LocalDateTime.now();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        when(alexaRepository.findDistinctApplianceIdsSince(any())).thenReturn(List.of("APP-A"));
        when(alexaRepository.findTopByApplianceIdOrderByReadingTimeDesc("APP-A"))
                .thenReturn(Optional.of(AlexaAirQualityReading.builder()
                        .applianceId("APP-A").deviceName("Sensor Bad").readingTime(now)
                        .temperature(new BigDecimal("22.50")).build()));
        stubCustomName(EntitySource.ALEXA, "APP-A", "Badezimmer");

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Badezimmer");
    }

    @Test
    void seriesUsesCustomNameForZigbeeWhenSet() {
        LocalDateTime now = LocalDateTime.now();
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(
                eq(MeasurementType.TEMPERATURE), any(), any()))
                .thenReturn(List.of(device(1L, "Wohnzimmer")));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(1L), eq(MeasurementType.TEMPERATURE), any(), any()))
                .thenReturn(List.of(measurement(MeasurementType.TEMPERATURE, "21.5", now)));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(1L), eq(MeasurementType.HUMIDITY), any(), any())).thenReturn(List.of());
        when(weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        stubCustomName(EntitySource.ZIGBEE, "Wohnzimmer", "Couch-Sensor");

        List<TemperatureSensorSeries> result = service.getSeries(TemperatureRange.WEEK);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Couch-Sensor");
    }

    @Test
    void liefertDieZeitreiheEinesZigbeeSensors() {
        LocalDateTime at = LocalDateTime.now().minusHours(1);
        when(zigbeeDeviceRepository.findById(12L))
                .thenReturn(Optional.of(device(12L, "Wohnzimmer")));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(12L), eq(MeasurementType.TEMPERATURE), any(), any()))
                .thenReturn(List.of(measurement(MeasurementType.TEMPERATURE, "21.5", at)));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(12L), eq(MeasurementType.HUMIDITY), any(), any()))
                .thenReturn(List.of(measurement(MeasurementType.HUMIDITY, "48", at)));
        lenient().when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());
        when(downsampler.downsample(anyList(), eq(TemperatureRange.DAY)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TemperatureSensorSeries series = service.getSensorSeries("zigbee:12", TemperatureRange.DAY);

        assertThat(series.getSensorId()).isEqualTo("zigbee:12");
        assertThat(series.getSource()).isEqualTo("ZIGBEE");
        assertThat(series.getName()).isEqualTo("Wohnzimmer");
        assertThat(series.getTemperature()).hasSize(1);
        assertThat(series.getHumidity()).hasSize(1);
    }

    @Test
    void liefertLeereReihenWennDerSensorImZeitraumNichtsGemeldetHat() {
        when(zigbeeDeviceRepository.findById(12L))
                .thenReturn(Optional.of(device(12L, "Wohnzimmer")));
        when(zigbeeRepository.findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq(12L), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(entityStateService.getByEntityId(any())).thenReturn(Optional.empty());
        lenient().when(downsampler.downsample(anyList(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TemperatureSensorSeries series = service.getSensorSeries("zigbee:12", TemperatureRange.DAY);

        assertThat(series.getTemperature()).isEmpty();
        assertThat(series.getHumidity()).isEmpty();
    }

    @Test
    void meldetUnbekannteSensorIdsAlsNichtGefunden() {
        when(zigbeeDeviceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSensorSeries("zigbee:99", TemperatureRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("zigbee:abc", TemperatureRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("quatsch:1", TemperatureRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("", TemperatureRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries(null, TemperatureRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
