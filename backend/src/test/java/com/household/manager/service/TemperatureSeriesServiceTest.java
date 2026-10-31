package com.household.manager.service;

import com.household.manager.blesensor.BleSensorEntityMapper;
import com.household.manager.dirigera.DirigeraEntityMapper;
import com.household.manager.dirigera.DirigeraSensorKind;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.BleSensorReading;
import com.household.manager.model.entity.DirigeraSensor;
import com.household.manager.model.entity.DirigeraSensorReading;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import com.household.manager.repository.BleSensorReadingRepository;
import com.household.manager.repository.BleSensorRepository;
import com.household.manager.repository.DirigeraSensorReadingRepository;
import com.household.manager.repository.DirigeraSensorRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemperatureSeriesServiceTest {

    @Mock private ZigbeeMeasurementRepository zigbeeRepository;
    @Mock private ZigbeeDeviceRepository zigbeeDeviceRepository;
    @Mock private SeriesDownsampler downsampler;
    @Mock private WeatherReadingRepository weatherRepository;
    @Mock private AlexaAirQualityReadingRepository alexaRepository;
    @Mock private EntityStateService entityStateService;
    @Mock private BleSensorRepository bleSensorRepository;
    @Mock private BleSensorReadingRepository bleReadingRepository;
    @Mock private DirigeraSensorRepository dirigeraSensorRepository;
    @Mock private DirigeraSensorReadingRepository dirigeraReadingRepository;

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
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        List<TemperatureSensorSeries> result = service.getSeries(SeriesRange.WEEK);

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
    void temperaturansichtenZeigenDieDwdVorhersageNichtAlsFuehler() {
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenReturn(List.of());
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        List<TemperatureSensorSeries> result = service.getSeries(SeriesRange.DAY);

        assertThat(result).isEmpty();
        verifyNoInteractions(weatherRepository);
    }

    @Test
    void dwdVorhersageBleibtAlsEinzelserieAbrufbar() {
        LocalDateTime now = LocalDateTime.now();
        WeatherReading reading = WeatherReading.builder()
                .readingTime(now).temperature(new BigDecimal("12.30")).humidity(80).build();
        when(weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(reading));
        when(downsampler.downsample(anyList(), eq(SeriesRange.DAY))).thenAnswer(inv -> inv.getArgument(0));

        TemperatureSensorSeries series = service.getSensorSeries("weather:outdoor", SeriesRange.DAY);

        assertThat(series.getSource()).isEqualTo("WEATHER");
        assertThat(series.getTemperature().get(0).getValue()).isEqualByComparingTo("12.30");
        assertThat(series.getHumidity().get(0).getValue()).isEqualByComparingTo("80");
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
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of(a1, a2));

        List<TemperatureSensorSeries> result = service.getSeries(SeriesRange.MONTH);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSensorId()).isEqualTo("alexa:APP-A");
        assertThat(result.get(0).getName()).isEqualTo("Sensor Bad");
        assertThat(result.get(0).getTemperature()).hasSize(2);
    }

    @Test
    void failingSourceIsSkippedNotFatal() {
        when(zigbeeRepository.findDistinctDevicesByMeasurementTypeInRange(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));
        lenient().when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());

        List<TemperatureSensorSeries> result = service.getSeries(SeriesRange.WEEK);

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
        assertThat(reading.getEntityId()).isEqualTo("sensor.zigbee_wohnzimmer_temperature");
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
        when(alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(any(), any()))
                .thenReturn(List.of());
        stubCustomName(EntitySource.ZIGBEE, "Wohnzimmer", "Couch-Sensor");

        List<TemperatureSensorSeries> result = service.getSeries(SeriesRange.WEEK);

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
        when(downsampler.downsample(anyList(), eq(SeriesRange.DAY)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TemperatureSensorSeries series = service.getSensorSeries("zigbee:12", SeriesRange.DAY);

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

        TemperatureSensorSeries series = service.getSensorSeries("zigbee:12", SeriesRange.DAY);

        assertThat(series.getTemperature()).isEmpty();
        assertThat(series.getHumidity()).isEmpty();
    }

    @Test
    void meldetUnbekannteSensorIdsAlsNichtGefunden() {
        when(zigbeeDeviceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSensorSeries("zigbee:99", SeriesRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("zigbee:abc", SeriesRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("quatsch:1", SeriesRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("", SeriesRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries(null, SeriesRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private BleSensor bleSensor(long id, String name, LocalDateTime lastSeen) {
        return BleSensor.builder().id(id).mac("aa:bb:cc:dd:ee:ff").name(name).active(true)
                .lastSeenAt(lastSeen).lastTemperature(new BigDecimal("21.5")).lastHumidity(new BigDecimal("50"))
                .build();
    }

    @Test
    void bleSeriesLiefertTemperaturUndFeuchteAktiverSensoren() {
        LocalDateTime now = LocalDateTime.now();
        when(bleSensorRepository.findByActiveTrue()).thenReturn(List.of(bleSensor(7L, "Küche", now)));
        when(bleReadingRepository.findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(eq(7L), any(), any()))
                .thenReturn(List.of(BleSensorReading.builder().sensorId(7L).measuredAt(now)
                        .temperature(new BigDecimal("21.5")).humidity(new BigDecimal("50")).build()));
        lenient().when(downsampler.downsample(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<TemperatureSensorSeries> series = service.getSeries(SeriesRange.DAY);

        TemperatureSensorSeries ble = series.stream().filter(s -> s.getSource().equals("BLE")).findFirst().orElseThrow();
        assertThat(ble.getSensorId()).isEqualTo("ble:7");
        assertThat(ble.getName()).isEqualTo("Küche");
        assertThat(ble.getTemperature()).hasSize(1);
        assertThat(ble.getHumidity()).hasSize(1);
    }

    @Test
    void bleCurrentNutztDieLetztwerteUndDieMapperEntityId() {
        LocalDateTime now = LocalDateTime.now();
        when(bleSensorRepository.findByActiveTrue()).thenReturn(List.of(bleSensor(7L, "Küche", now.minusMinutes(2))));

        List<CurrentTemperatureReading> current = service.getCurrent();

        CurrentTemperatureReading ble = current.stream().filter(c -> c.getSource().equals("BLE")).findFirst().orElseThrow();
        assertThat(ble.getSensorId()).isEqualTo("ble:7");
        assertThat(ble.getEntityId())
                .isEqualTo(BleSensorEntityMapper.entityId("aa:bb:cc:dd:ee:ff", BleSensorEntityMapper.Measurement.TEMPERATURE));
        assertThat(ble.getTemperature()).isEqualByComparingTo("21.5");
        assertThat(ble.getMeasuredAt()).isEqualTo(now.minusMinutes(2));
    }

    @Test
    void bleCurrentLaesstSensorenOhneWertInDenLetztenSiebenTagenWeg() {
        when(bleSensorRepository.findByActiveTrue()).thenReturn(List.of(bleSensor(7L, "Küche", LocalDateTime.now().minusDays(8))));

        assertThat(service.getCurrent()).noneMatch(c -> c.getSource().equals("BLE"));
    }

    @Test
    void bleSensorSeriesUnbekannteOderInaktiveIdIst404() {
        when(bleSensorRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSensorSeries("ble:9", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);

        BleSensor inactive = bleSensor(8L, "Alt", LocalDateTime.now());
        inactive.setActive(false);
        when(bleSensorRepository.findById(8L)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.getSensorSeries("ble:8", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("ble:x", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static final String DIRIGERA_RELATION_ID = "AB-12-cd";

    private DirigeraSensor dirigeraSensor(long id, String name, LocalDateTime lastSeen, boolean reachable) {
        return DirigeraSensor.builder().id(id).relationId(DIRIGERA_RELATION_ID).name(name)
                .lastSeenAt(lastSeen).lastTemperature(new BigDecimal("21.5")).lastHumidity(new BigDecimal("50"))
                .reachable(reachable).knownToHub(true).build();
    }

    @Test
    void dirigeraSerieErscheintInGetSeries() {
        LocalDateTime now = LocalDateTime.now();
        when(dirigeraSensorRepository.findByKnownToHubTrue())
                .thenReturn(List.of(dirigeraSensor(4L, "Bad", now, true)));
        when(dirigeraReadingRepository.findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(eq(4L), any(), any()))
                .thenReturn(List.of(DirigeraSensorReading.builder().sensorId(4L).measuredAt(now)
                        .temperature(new BigDecimal("21.5")).humidity(new BigDecimal("50")).build()));

        List<TemperatureSensorSeries> series = service.getSeries(SeriesRange.DAY);

        TemperatureSensorSeries dirigera = series.stream().filter(s -> s.getSource().equals("DIRIGERA")).findFirst().orElseThrow();
        assertThat(dirigera.getSensorId()).isEqualTo("dirigera:4");
        assertThat(dirigera.getName()).isEqualTo("Bad");
        assertThat(dirigera.getTemperature()).hasSize(1);
        assertThat(dirigera.getHumidity()).hasSize(1);
    }

    @Test
    void dirigeraCurrentTraegtEntityIdDesMappers() {
        LocalDateTime now = LocalDateTime.now();
        when(dirigeraSensorRepository.findByKnownToHubTrue())
                .thenReturn(List.of(dirigeraSensor(4L, "Bad", now.minusMinutes(2), true)));

        List<CurrentTemperatureReading> current = service.getCurrent();

        CurrentTemperatureReading dirigera = current.stream().filter(c -> c.getSource().equals("DIRIGERA")).findFirst().orElseThrow();
        assertThat(dirigera.getSensorId()).isEqualTo("dirigera:4");
        assertThat(dirigera.getEntityId())
                .isEqualTo(DirigeraEntityMapper.entityId(DIRIGERA_RELATION_ID, DirigeraEntityMapper.Measurement.TEMPERATURE));
        assertThat(dirigera.getTemperature()).isEqualByComparingTo("21.5");
        assertThat(dirigera.getMeasuredAt()).isEqualTo(now.minusMinutes(2));
    }

    @Test
    void unerreichbarerDirigeraSensorFehltInCurrent() {
        when(dirigeraSensorRepository.findByKnownToHubTrue())
                .thenReturn(List.of(dirigeraSensor(4L, "Bad", LocalDateTime.now(), false)));

        assertThat(service.getCurrent()).noneMatch(c -> c.getSource().equals("DIRIGERA"));
    }

    @Test
    void dirigeraEinzelserieUnbekannteIdWirft404() {
        when(dirigeraSensorRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSensorSeries("dirigera:9", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);

        DirigeraSensor gone = dirigeraSensor(8L, "Alt", LocalDateTime.now(), true);
        gone.setKnownToHub(false);
        when(dirigeraSensorRepository.findById(8L)).thenReturn(Optional.of(gone));
        assertThatThrownBy(() -> service.getSensorSeries("dirigera:8", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("dirigera:x", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void dirigeraKontaktErscheintNichtInDerTemperaturansicht() {
        DirigeraSensor contact = DirigeraSensor.builder().id(5L).relationId("c").name("Badfenster")
                .kind(DirigeraSensorKind.CONTACT).lastOpen(true).lastSeenAt(LocalDateTime.now())
                .reachable(true).knownToHub(true).build();
        when(dirigeraSensorRepository.findByKnownToHubTrue()).thenReturn(List.of(contact));
        when(dirigeraSensorRepository.findById(5L)).thenReturn(Optional.of(contact));

        assertThat(service.getSeries(SeriesRange.DAY)).noneMatch(s -> s.getSource().equals("DIRIGERA"));
        assertThat(service.getCurrent()).noneMatch(c -> c.getSource().equals("DIRIGERA"));
        assertThatThrownBy(() -> service.getSensorSeries("dirigera:5", SeriesRange.DAY))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void dirigeraQuellenfehlerKipptGetSeriesNicht() {
        when(dirigeraSensorRepository.findByKnownToHubTrue()).thenThrow(new IllegalStateException("db weg"));

        assertThat(service.getSeries(SeriesRange.DAY)).noneMatch(s -> s.getSource().equals("DIRIGERA"));
    }
}
