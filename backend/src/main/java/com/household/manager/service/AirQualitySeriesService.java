package com.household.manager.service;

import com.household.manager.dto.AirQualitySensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.model.entity.AirrohrReading;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AirrohrReadingRepository;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Aggregiert die Luftqualitaets-Zeitreihen von Airrohr (draussen) und den Amazon
 * Smart Air Quality Monitoren (drinnen) in ein einheitliches Serienformat.
 *
 * <p>Jede Quelle ist gekapselt: faellt sie aus, wird sie geloggt und uebersprungen,
 * ohne die Gesamtantwort zu gefaehrden - ein toter Sensor draussen darf die
 * Innenraumkacheln nicht mitnehmen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AirQualitySeriesService {

    /** Der Airrohr-Sensor ist genau ein Geraet ohne Geraetetabelle - feste ID und fester Name. */
    private static final String AIRROHR_SENSOR_ID = "airrohr:local";
    private static final String AIRROHR_NAME = "Draußen";

    private final AirrohrReadingRepository airrohrRepository;
    private final AlexaAirQualityReadingRepository alexaRepository;
    private final SeriesDownsampler downsampler;

    public List<AirQualitySensorSeries> getSeries(SeriesRange range) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(range.getDays());

        List<AirQualitySensorSeries> series = new ArrayList<>();
        series.addAll(safe("airrohr", () -> airrohrSeries(from, to, range)));
        series.addAll(safe("alexa", () -> alexaSeries(from, to, range)));
        return series;
    }

    private List<AirQualitySensorSeries> airrohrSeries(
            LocalDateTime from, LocalDateTime to, SeriesRange range) {
        List<AirrohrReading> readings =
                airrohrRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(from, to);

        Map<String, List<TimeValue>> metrics = new LinkedHashMap<>();
        putIfAny(metrics, "pm25", points(readings, AirrohrReading::getReadingTime, AirrohrReading::getSdsP2), range);
        putIfAny(metrics, "pm10", points(readings, AirrohrReading::getReadingTime, AirrohrReading::getSdsP1), range);

        if (metrics.isEmpty()) {
            return List.of();
        }
        return List.of(AirQualitySensorSeries.builder()
                .sensorId(AIRROHR_SENSOR_ID)
                .name(AIRROHR_NAME)
                .source("AIRROHR")
                .metrics(metrics)
                .build());
    }

    private List<AirQualitySensorSeries> alexaSeries(
            LocalDateTime from, LocalDateTime to, SeriesRange range) {
        List<AlexaAirQualityReading> readings =
                alexaRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(from, to);

        Map<String, List<AlexaAirQualityReading>> byAppliance = readings.stream()
                .collect(Collectors.groupingBy(
                        AlexaAirQualityReading::getApplianceId, LinkedHashMap::new, Collectors.toList()));

        List<AirQualitySensorSeries> result = new ArrayList<>();
        byAppliance.forEach((applianceId, group) -> {
            Map<String, List<TimeValue>> metrics = new LinkedHashMap<>();
            putIfAny(metrics, "iaq", points(group, AlexaAirQualityReading::getReadingTime,
                    r -> r.getIaq() == null ? null : BigDecimal.valueOf(r.getIaq())), range);
            putIfAny(metrics, "pm25", points(group, AlexaAirQualityReading::getReadingTime,
                    AlexaAirQualityReading::getPm25), range);
            putIfAny(metrics, "voc", points(group, AlexaAirQualityReading::getReadingTime,
                    AlexaAirQualityReading::getVoc), range);
            putIfAny(metrics, "co", points(group, AlexaAirQualityReading::getReadingTime,
                    AlexaAirQualityReading::getCo), range);

            if (metrics.isEmpty()) {
                return;
            }
            // Der Anzeigename kann sich in der Amazon-App aendern; der juengste gilt.
            String name = group.get(group.size() - 1).getDeviceName();
            result.add(AirQualitySensorSeries.builder()
                    .sensorId("alexa:" + applianceId)
                    .name(name)
                    .source("ALEXA")
                    .metrics(metrics)
                    .build());
        });
        return result;
    }

    /** Zieht Zeit/Wert-Paare einer Messgroesse aus den Rohzeilen; Zeilen ohne Wert entfallen. */
    private <T> List<TimeValue> points(
            List<T> readings, Function<T, LocalDateTime> time, Function<T, BigDecimal> value) {
        List<TimeValue> points = new ArrayList<>();
        for (T reading : readings) {
            BigDecimal raw = value.apply(reading);
            if (raw == null || time.apply(reading) == null) {
                continue;
            }
            points.add(TimeValue.builder().time(time.apply(reading)).value(raw).build());
        }
        return points;
    }

    /**
     * Nimmt eine Messgroesse gemittelt in die Map auf - aber nur, wenn sie Werte hat.
     * Eine leere Liste in der Antwort waere von "gemessen, aber alles null" nicht zu
     * unterscheiden und zwaenge das Frontend zu einer zweiten Leerpruefung.
     */
    private void putIfAny(
            Map<String, List<TimeValue>> metrics, String key, List<TimeValue> points, SeriesRange range) {
        if (points.isEmpty()) {
            return;
        }
        metrics.put(key, downsampler.downsample(points, range));
    }

    /** Kapselt eine Quelle: ein Fehler kostet ihre Kacheln, nicht die ganze Antwort. */
    private List<AirQualitySensorSeries> safe(
            String source, Supplier<List<AirQualitySensorSeries>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Luftqualitaets-Zeitreihen der Quelle {} konnten nicht geladen werden: {}",
                    source, e.getMessage());
            return List.of();
        }
    }
}
