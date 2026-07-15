package com.household.manager.service;

import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import com.household.manager.repository.WeatherReadingRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Aggregiert Temperatur-/Feuchte-Zeitreihen aus Zigbee, Wetter und Alexa
 * in ein einheitliches Serienformat. Jede Quelle ist gekapselt: fällt sie aus,
 * wird sie geloggt und übersprungen, ohne die Gesamtantwort zu gefährden.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemperatureSeriesService {

    /** Zeitfenster für die Sensor-Discovery in {@link #getCurrent()}: begrenzt den Scan der
     * append-only Historientabellen, statt bei jedem Poll die komplette Lebenszeit zu scannen. */
    private static final int CURRENT_LOOKBACK_DAYS = 7;

    private final ZigbeeMeasurementRepository zigbeeRepository;
    private final WeatherReadingRepository weatherRepository;
    private final AlexaAirQualityReadingRepository alexaRepository;
    private final EntityStateService entityStateService;

    public List<TemperatureSensorSeries> getSeries(TemperatureRange range) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(range.getDays());

        List<TemperatureSensorSeries> series = new ArrayList<>();
        series.addAll(safe("zigbee", () -> zigbeeSeries(from, to)));
        series.addAll(safe("weather", () -> weatherSeries(from, to)));
        series.addAll(safe("alexa", () -> alexaSeries(from, to)));
        return series;
    }

    public List<CurrentTemperatureReading> getCurrent() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(CURRENT_LOOKBACK_DAYS);

        List<CurrentTemperatureReading> result = new ArrayList<>();
        result.addAll(safe("zigbee", () -> zigbeeCurrent(since, now)));
        result.addAll(safe("weather", this::weatherCurrent));
        result.addAll(safe("alexa", () -> alexaCurrent(since)));
        return result;
    }

    private List<CurrentTemperatureReading> zigbeeCurrent(LocalDateTime since, LocalDateTime now) {
        List<ZigbeeDevice> devices = zigbeeRepository
                .findDistinctDevicesByMeasurementTypeInRange(MeasurementType.TEMPERATURE, since, now)
                .stream()
                .sorted(Comparator.comparing(ZigbeeDevice::getFriendlyName))
                .toList();

        List<CurrentTemperatureReading> result = new ArrayList<>();
        for (ZigbeeDevice device : devices) {
            zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                    device.getId(), MeasurementType.TEMPERATURE).ifPresent(temp -> {
                BigDecimal humidity = zigbeeRepository
                        .findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                                device.getId(), MeasurementType.HUMIDITY)
                        .map(ZigbeeMeasurement::getValue).orElse(null);
                result.add(CurrentTemperatureReading.builder()
                        .sensorId("zigbee:" + device.getId())
                        .name(temperatureName(EntitySource.ZIGBEE, device.getFriendlyName(), device.getFriendlyName()))
                        .source("ZIGBEE")
                        .temperature(temp.getValue())
                        .humidity(humidity)
                        .measuredAt(temp.getMeasuredAt())
                        .build());
            });
        }
        return result;
    }

    private List<CurrentTemperatureReading> weatherCurrent() {
        return weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc()
                .map(r -> CurrentTemperatureReading.builder()
                        .sensorId("weather:outdoor")
                        .name(temperatureName(EntitySource.WEATHER, "dwd", "Außen"))
                        .source("WEATHER")
                        .temperature(r.getTemperature())
                        .humidity(r.getHumidity() != null ? BigDecimal.valueOf(r.getHumidity()) : null)
                        .measuredAt(r.getReadingTime())
                        .build())
                .map(List::of)
                .orElseGet(List::of);
    }

    private List<CurrentTemperatureReading> alexaCurrent(LocalDateTime since) {
        List<CurrentTemperatureReading> result = new ArrayList<>();
        for (String applianceId : alexaRepository.findDistinctApplianceIdsSince(since)) {
            alexaRepository.findTopByApplianceIdOrderByReadingTimeDesc(applianceId)
                    .filter(r -> r.getTemperature() != null)
                    .ifPresent(r -> result.add(CurrentTemperatureReading.builder()
                            .sensorId("alexa:" + applianceId)
                            .name(temperatureName(EntitySource.ALEXA, applianceId,
                                    r.getDeviceName() != null ? r.getDeviceName() : applianceId))
                            .source("ALEXA")
                            .temperature(r.getTemperature())
                            .humidity(r.getHumidity())
                            .measuredAt(r.getReadingTime())
                            .build()));
        }
        return result;
    }

    private <T> List<T> safe(String source, Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            log.warn("Temperatur-Quelle '{}' fehlgeschlagen: {}", source, ex.getMessage(), ex);
            return List.of();
        }
    }

    /**
     * Anzeigename eines Temperatursensors: der benutzerdefinierte Kurzname der zugehörigen
     * Temperatur-Entity, falls gesetzt, sonst der Rohname der Quelle. Die Entity-ID wird
     * deterministisch aus Quelle und stabiler Referenz gebildet – identisch zum jeweiligen
     * Entity-Mapper, sodass sie ohne zusätzliche Verknüpfungstabelle zueinander finden.
     */
    private String temperatureName(EntitySource source, String sourceRef, String rawName) {
        String entityId = EntityIds.build(EntityDomain.SENSOR, source, sourceRef, "temperature");
        return entityStateService.getByEntityId(entityId)
                .map(EntityState::getCustomName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(rawName);
    }

    private List<TemperatureSensorSeries> zigbeeSeries(LocalDateTime from, LocalDateTime to) {
        List<ZigbeeDevice> devices = zigbeeRepository
                .findDistinctDevicesByMeasurementTypeInRange(MeasurementType.TEMPERATURE, from, to)
                .stream()
                .sorted(Comparator.comparing(ZigbeeDevice::getFriendlyName))
                .toList();

        List<TemperatureSensorSeries> result = new ArrayList<>();
        for (ZigbeeDevice device : devices) {
            List<TimeValue> temperature = zigbeeRepository
                    .findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                            device.getId(), MeasurementType.TEMPERATURE, from, to)
                    .stream().map(this::toTimeValue).toList();
            List<TimeValue> humidity = zigbeeRepository
                    .findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                            device.getId(), MeasurementType.HUMIDITY, from, to)
                    .stream().map(this::toTimeValue).toList();

            result.add(TemperatureSensorSeries.builder()
                    .sensorId("zigbee:" + device.getId())
                    .name(temperatureName(EntitySource.ZIGBEE, device.getFriendlyName(), device.getFriendlyName()))
                    .source("ZIGBEE")
                    .temperature(temperature)
                    .humidity(humidity)
                    .build());
        }
        return result;
    }

    private List<TemperatureSensorSeries> weatherSeries(LocalDateTime from, LocalDateTime to) {
        List<WeatherReading> readings =
                weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(from, to);

        List<TimeValue> temperature = readings.stream()
                .filter(r -> r.getTemperature() != null)
                .map(r -> point(r.getReadingTime(), r.getTemperature()))
                .toList();
        if (temperature.isEmpty()) {
            return List.of();
        }
        List<TimeValue> humidity = readings.stream()
                .filter(r -> r.getHumidity() != null)
                .map(r -> point(r.getReadingTime(), BigDecimal.valueOf(r.getHumidity())))
                .toList();

        return List.of(TemperatureSensorSeries.builder()
                .sensorId("weather:outdoor")
                .name(temperatureName(EntitySource.WEATHER, "dwd", "Außen"))
                .source("WEATHER")
                .temperature(temperature)
                .humidity(humidity)
                .build());
    }

    private List<TemperatureSensorSeries> alexaSeries(LocalDateTime from, LocalDateTime to) {
        List<AlexaAirQualityReading> readings =
                alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(from, to);

        Map<String, List<AlexaAirQualityReading>> byAppliance = readings.stream()
                .collect(Collectors.groupingBy(
                        AlexaAirQualityReading::getApplianceId, LinkedHashMap::new, Collectors.toList()));

        List<TemperatureSensorSeries> result = new ArrayList<>();
        byAppliance.forEach((applianceId, group) -> {
            List<TimeValue> temperature = group.stream()
                    .filter(r -> r.getTemperature() != null)
                    .map(r -> point(r.getReadingTime(), r.getTemperature()))
                    .toList();
            if (temperature.isEmpty()) {
                return;
            }
            List<TimeValue> humidity = group.stream()
                    .filter(r -> r.getHumidity() != null)
                    .map(r -> point(r.getReadingTime(), r.getHumidity()))
                    .toList();
            String name = group.get(group.size() - 1).getDeviceName();

            result.add(TemperatureSensorSeries.builder()
                    .sensorId("alexa:" + applianceId)
                    .name(temperatureName(EntitySource.ALEXA, applianceId, name != null ? name : applianceId))
                    .source("ALEXA")
                    .temperature(temperature)
                    .humidity(humidity)
                    .build());
        });
        return result;
    }

    private TimeValue toTimeValue(ZigbeeMeasurement measurement) {
        return point(measurement.getMeasuredAt(), measurement.getValue());
    }

    private TimeValue point(LocalDateTime time, BigDecimal value) {
        return TimeValue.builder().time(time).value(value).build();
    }
}
