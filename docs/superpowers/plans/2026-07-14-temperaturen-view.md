# Temperaturen-View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine neue View „Temperaturen" unter „Umwelt", die je Temperatursensor (Zigbee, Wetter-Außen, Alexa-Innen) eine Kachel mit einem Graph über Temperatur und Luftfeuchtigkeit zeigt.

**Architecture:** Serverseitige Aggregation über drei Fachtabellen in einem neuen Endpoint `GET /api/v1/temperatures?range=DAY|WEEK|MONTH`. Das Frontend lädt die normalisierte Serienliste mit einem Call und rendert je Serie eine ECharts-Kachel (Dual-Y: Temperatur links, Luftfeuchtigkeit gestrichelt rechts).

**Tech Stack:** Backend Spring Boot 3.4 / Java 21 / Lombok / Mockito+AssertJ. Frontend Angular 19 standalone / ngx-echarts / Karma-Jasmine.

**Referenz-Spec:** `docs/superpowers/specs/2026-07-14-temperaturen-view-design.md`

**Hinweis Backend-Build:** Vor jedem `mvn`-Aufruf `JAVA_HOME` auf das JDK 21 setzen (Default ist JDK 17). Die hier definierten Tests sind reine Mockito-Unit-Tests ohne Datenbank und laufen ohne lokale DB.

---

## File Structure

**Backend (neu):**
- `backend/src/main/java/com/household/manager/dto/TimeValue.java` — ein Zeit/Wert-Punkt.
- `backend/src/main/java/com/household/manager/dto/TemperatureSensorSeries.java` — eine Sensor-Serie (Temperatur + optional Feuchte).
- `backend/src/main/java/com/household/manager/service/TemperatureRange.java` — Enum DAY/WEEK/MONTH → Tage.
- `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java` — Aggregation + Normalisierung, resilient je Quelle.
- `backend/src/main/java/com/household/manager/controller/TemperatureController.java` — dünner REST-Endpoint.
- `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java` — Unit-Test (Mock-Repos).

**Backend (modifiziert):**
- `backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java` — Query für Geräte mit Temperatur im Zeitfenster.
- `backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java` — Range-Query.
- `backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java` — Range-Query.

**Frontend (neu):**
- `frontend/src/app/models/temperature.model.ts` — Typen.
- `frontend/src/app/services/temperature.service.ts` — HTTP-Service.
- `frontend/src/app/services/temperature.service.spec.ts` — Service-Test.
- `frontend/src/app/pages/temperatures/temperatures.component.ts` — Seite.
- `frontend/src/app/pages/temperatures/temperatures.component.html` — Template.
- `frontend/src/app/pages/temperatures/temperatures.component.scss` — Styling.
- `frontend/src/app/pages/temperatures/temperatures.component.spec.ts` — Component-Test.

**Frontend (modifiziert):**
- `frontend/src/app/app.routes.ts` — Route `temperatures`.
- `frontend/src/app/components/header/header.component.ts` — Nav-Eintrag unter „Umwelt".

---

## Task 1: Backend DTOs

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/TimeValue.java`
- Create: `backend/src/main/java/com/household/manager/dto/TemperatureSensorSeries.java`

- [ ] **Step 1: TimeValue schreiben**

`backend/src/main/java/com/household/manager/dto/TimeValue.java`:
```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Ein einzelner Zeit/Wert-Punkt einer Messreihe. */
@Getter
@Builder
public class TimeValue {
    private final LocalDateTime time;
    private final BigDecimal value;
}
```

- [ ] **Step 2: TemperatureSensorSeries schreiben**

`backend/src/main/java/com/household/manager/dto/TemperatureSensorSeries.java`:
```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Zeitreihe eines Temperatursensors inkl. optionaler Luftfeuchtigkeit. */
@Getter
@Builder
public class TemperatureSensorSeries {
    /** Stabile, quellenpräfixierte ID, z. B. "zigbee:12". */
    private final String sensorId;
    /** Anzeigename des Sensors. */
    private final String name;
    /** Quelle: ZIGBEE | WEATHER | ALEXA. */
    private final String source;
    /** Temperaturpunkte (immer vorhanden). */
    private final List<TimeValue> temperature;
    /** Feuchtepunkte (leer, wenn der Sensor keine Feuchte liefert). */
    private final List<TimeValue> humidity;
}
```

- [ ] **Step 3: Kompilieren prüfen**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS (keine Fehler in den neuen DTOs).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/TimeValue.java backend/src/main/java/com/household/manager/dto/TemperatureSensorSeries.java
git commit -m "feat(temperatures): DTOs für Temperatur-Zeitreihen"
```

---

## Task 2: Repository-Query-Methoden

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java`
- Modify: `backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java`
- Modify: `backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java`

Diese Methoden sind Spring-Data-Ableitungen bzw. eine einfache JPQL-Query; sie werden über den Service-Test (Task 4) mitabgedeckt und brauchen keinen eigenen DB-Test.

- [ ] **Step 1: Zigbee-Query für Geräte mit Temperatur im Zeitfenster ergänzen**

In `ZigbeeMeasurementRepository.java` die Imports und eine Methode ergänzen. Datei danach vollständig:
```java
package com.household.manager.repository;

import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ZigbeeMeasurementRepository extends JpaRepository<ZigbeeMeasurement, Long> {

    List<ZigbeeMeasurement> findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long deviceId, MeasurementType measurementType, LocalDateTime from, LocalDateTime to);

    List<ZigbeeMeasurement> findByDeviceIdOrderByMeasuredAtAsc(Long deviceId);

    /** Geräte, die im Zeitfenster mindestens einen Messwert des Typs geliefert haben. */
    @Query("select distinct m.device from ZigbeeMeasurement m "
            + "where m.measurementType = :type and m.measuredAt between :from and :to")
    List<ZigbeeDevice> findDistinctDevicesByMeasurementTypeInRange(
            @Param("type") MeasurementType type,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
```

- [ ] **Step 2: Weather-Range-Query ergänzen**

In `WeatherReadingRepository.java` eine Methode und den Import ergänzen. Datei danach vollständig:
```java
package com.household.manager.repository;

import com.household.manager.model.entity.WeatherReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Repository für {@link WeatherReading}. */
@Repository
public interface WeatherReadingRepository extends JpaRepository<WeatherReading, Long> {

    List<WeatherReading> findAllByOrderByReadingTimeAsc();

    List<WeatherReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);
}
```

- [ ] **Step 3: Alexa-Range-Query ergänzen**

In `AlexaAirQualityReadingRepository.java` eine Methode und den Import ergänzen. Datei danach vollständig:
```java
package com.household.manager.repository;

import com.household.manager.model.entity.AlexaAirQualityReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AlexaAirQualityReadingRepository extends JpaRepository<AlexaAirQualityReading, Long> {

    List<AlexaAirQualityReading> findAllByOrderByReadingTimeAsc();

    Optional<AlexaAirQualityReading> findTopByApplianceIdOrderByReadingTimeDesc(String applianceId);

    @Query("select distinct r.applianceId from AlexaAirQualityReading r")
    List<String> findDistinctApplianceIds();

    List<AlexaAirQualityReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);
}
```

- [ ] **Step 4: Kompilieren prüfen**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java
git commit -m "feat(temperatures): Range-Queries für Temperatur-Aggregation"
```

---

## Task 3: TemperatureRange-Enum

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/TemperatureRange.java`

- [ ] **Step 1: Enum schreiben**

`backend/src/main/java/com/household/manager/service/TemperatureRange.java`:
```java
package com.household.manager.service;

import lombok.Getter;

/** Auswählbarer Zeitraum der Temperaturgraphen. */
@Getter
public enum TemperatureRange {
    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int days;

    TemperatureRange(int days) {
        this.days = days;
    }
}
```

- [ ] **Step 2: Kompilieren prüfen**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/TemperatureRange.java
git commit -m "feat(temperatures): TemperatureRange-Enum"
```

---

## Task 4: TemperatureSeriesService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`
- Test: `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`

- [ ] **Step 1: Failing test schreiben**

`backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`:
```java
package com.household.manager.service;

import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import com.household.manager.repository.WeatherReadingRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemperatureSeriesServiceTest {

    @Mock private ZigbeeMeasurementRepository zigbeeRepository;
    @Mock private WeatherReadingRepository weatherRepository;
    @Mock private AlexaAirQualityReadingRepository alexaRepository;

    @InjectMocks private TemperatureSeriesService service;

    private ZigbeeDevice device(long id, String name) {
        return ZigbeeDevice.builder().id(id).friendlyName(name).build();
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
}
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `cd backend && mvn -q test -Dtest=TemperatureSeriesServiceTest`
Expected: Kompilierfehler / FAIL — `TemperatureSeriesService` existiert noch nicht.

- [ ] **Step 3: Service implementieren**

`backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`:
```java
package com.household.manager.service;

import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.dto.TimeValue;
import com.household.manager.model.entity.AlexaAirQualityReading;
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

    private final ZigbeeMeasurementRepository zigbeeRepository;
    private final WeatherReadingRepository weatherRepository;
    private final AlexaAirQualityReadingRepository alexaRepository;

    public List<TemperatureSensorSeries> getSeries(TemperatureRange range) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(range.getDays());

        List<TemperatureSensorSeries> series = new ArrayList<>();
        series.addAll(safe("zigbee", () -> zigbeeSeries(from, to)));
        series.addAll(safe("weather", () -> weatherSeries(from, to)));
        series.addAll(safe("alexa", () -> alexaSeries(from, to)));
        return series;
    }

    private List<TemperatureSensorSeries> safe(
            String source, Supplier<List<TemperatureSensorSeries>> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            log.warn("Temperatur-Quelle '{}' fehlgeschlagen: {}", source, ex.getMessage());
            return List.of();
        }
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
                    .name(device.getFriendlyName())
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
                .name("Außen")
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
                    .name(name != null ? name : applianceId)
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
```

- [ ] **Step 4: Test laufen lassen, muss bestehen**

Run: `cd backend && mvn -q test -Dtest=TemperatureSeriesServiceTest`
Expected: PASS (4 Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java
git commit -m "feat(temperatures): TemperatureSeriesService mit Quellen-Aggregation"
```

---

## Task 5: TemperatureController

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/TemperatureController.java`

Der Controller ist dünn (nur Delegation); die Logik ist bereits in Task 4 getestet.

- [ ] **Step 1: Controller schreiben**

`backend/src/main/java/com/household/manager/controller/TemperatureController.java`:
```java
package com.household.manager.controller;

import com.household.manager.dto.TemperatureSensorSeries;
import com.household.manager.service.TemperatureRange;
import com.household.manager.service.TemperatureSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API für aggregierte Temperatur-/Feuchte-Zeitreihen. Basis-URL: /api/v1/temperatures
 */
@RestController
@RequestMapping("/v1/temperatures")
@RequiredArgsConstructor
public class TemperatureController {

    private final TemperatureSeriesService temperatureSeriesService;

    @GetMapping
    public List<TemperatureSensorSeries> getTemperatures(
            @RequestParam(required = false, defaultValue = "WEEK") TemperatureRange range) {
        return temperatureSeriesService.getSeries(range);
    }
}
```

- [ ] **Step 2: Kompilieren + Gesamttest-Kompilat prüfen**

Run: `cd backend && mvn -q test -Dtest=TemperatureSeriesServiceTest`
Expected: BUILD SUCCESS, Test grün (bestätigt, dass alles zusammen kompiliert).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/TemperatureController.java
git commit -m "feat(temperatures): REST-Endpoint GET /api/v1/temperatures"
```

---

## Task 6: Frontend-Model

**Files:**
- Create: `frontend/src/app/models/temperature.model.ts`

- [ ] **Step 1: Model schreiben**

`frontend/src/app/models/temperature.model.ts`:
```typescript
/** Auswählbarer Zeitraum der Temperaturgraphen. */
export type TimeRange = 'DAY' | 'WEEK' | 'MONTH';

/** Ein Zeit/Wert-Punkt einer Messreihe (time als ISO-String). */
export interface TimeValue {
  time: string;
  value: number;
}

/** Quelle eines Temperatursensors. */
export type TemperatureSource = 'ZIGBEE' | 'WEATHER' | 'ALEXA';

/** Zeitreihe eines Temperatursensors inkl. optionaler Luftfeuchtigkeit. */
export interface TemperatureSensorSeries {
  sensorId: string;
  name: string;
  source: TemperatureSource;
  temperature: TimeValue[];
  humidity: TimeValue[];
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/app/models/temperature.model.ts
git commit -m "feat(temperatures): Frontend-Model für Temperatur-Serien"
```

---

## Task 7: TemperatureService (TDD)

**Files:**
- Create: `frontend/src/app/services/temperature.service.ts`
- Test: `frontend/src/app/services/temperature.service.spec.ts`

- [ ] **Step 1: Failing test schreiben**

`frontend/src/app/services/temperature.service.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TemperatureService } from './temperature.service';
import { TemperatureSensorSeries } from '../models/temperature.model';

describe('TemperatureService', () => {
  let service: TemperatureService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(TemperatureService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('requests series with range query param', () => {
    const series: TemperatureSensorSeries[] = [];
    service.getSeries('WEEK').subscribe(result => expect(result).toEqual(series));

    const req = httpMock.expectOne(r => r.url === '/api/v1/temperatures');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('range')).toBe('WEEK');
    req.flush(series);
  });

  it('passes the selected range through', () => {
    service.getSeries('DAY').subscribe();

    const req = httpMock.expectOne(r => r.url === '/api/v1/temperatures');
    expect(req.request.params.get('range')).toBe('DAY');
    req.flush([]);
  });
});
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `cd frontend && ng test --include='**/temperature.service.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `temperature.service` kann nicht importiert werden.

- [ ] **Step 3: Service implementieren**

`frontend/src/app/services/temperature.service.ts`:
```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TemperatureSensorSeries, TimeRange } from '../models/temperature.model';

/**
 * REST-Service für aggregierte Temperatur-/Feuchte-Zeitreihen.
 */
@Injectable({ providedIn: 'root' })
export class TemperatureService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/temperatures';

  getSeries(range: TimeRange): Observable<TemperatureSensorSeries[]> {
    const params = new HttpParams().set('range', range);
    return this.http.get<TemperatureSensorSeries[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Temperatur-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Temperaturdaten.'));
  }
}
```

- [ ] **Step 4: Test laufen lassen, muss bestehen**

Run: `cd frontend && ng test --include='**/temperature.service.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: PASS (2 Tests grün).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/temperature.service.ts frontend/src/app/services/temperature.service.spec.ts
git commit -m "feat(temperatures): TemperatureService (Frontend)"
```

---

## Task 8: TemperaturesComponent (TDD)

**Files:**
- Create: `frontend/src/app/pages/temperatures/temperatures.component.ts`
- Create: `frontend/src/app/pages/temperatures/temperatures.component.html`
- Create: `frontend/src/app/pages/temperatures/temperatures.component.scss`
- Test: `frontend/src/app/pages/temperatures/temperatures.component.spec.ts`

- [ ] **Step 1: Failing test schreiben**

`frontend/src/app/pages/temperatures/temperatures.component.spec.ts`:
```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { TemperaturesComponent } from './temperatures.component';
import { TemperatureService } from '../../services/temperature.service';
import { TemperatureSensorSeries } from '../../models/temperature.model';

describe('TemperaturesComponent', () => {
  let fixture: ComponentFixture<TemperaturesComponent>;
  let component: TemperaturesComponent;
  let serviceSpy: jasmine.SpyObj<TemperatureService>;

  const withHumidity: TemperatureSensorSeries = {
    sensorId: 'zigbee:1', name: 'Wohnzimmer', source: 'ZIGBEE',
    temperature: [{ time: '2026-07-14T10:00:00', value: 21.5 }],
    humidity: [{ time: '2026-07-14T10:00:00', value: 48 }]
  };
  const withoutHumidity: TemperatureSensorSeries = {
    sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER',
    temperature: [{ time: '2026-07-14T10:00:00', value: 12.3 }],
    humidity: []
  };

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('TemperatureService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([withHumidity, withoutHumidity]));

    await TestBed.configureTestingModule({
      imports: [TemperaturesComponent],
      providers: [{ provide: TemperatureService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(TemperaturesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads series for the default range WEEK on init', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('WEEK');
    expect(component.charts.length).toBe(2);
  });

  it('reloads when a different range is selected', () => {
    component.setRange('DAY');
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('DAY');
    expect(component.activeRange).toBe('DAY');
  });

  it('does not reload when the active range is selected again', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEK');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  it('builds a humidity series only when humidity data exists', () => {
    const withHum = component.chartOptionsFor(withHumidity) as { series: unknown[] };
    const withoutHum = component.chartOptionsFor(withoutHumidity) as { series: unknown[] };
    expect(withHum.series.length).toBe(2);
    expect(withoutHum.series.length).toBe(1);
  });

  it('shows the empty state when no sensors are returned', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('MONTH');
    expect(component.charts.length).toBe(0);
    expect(component.isEmpty).toBeTrue();
  });
});
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `cd frontend && ng test --include='**/temperatures.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: FAIL — Komponente existiert noch nicht.

- [ ] **Step 3: Component-TS implementieren**

`frontend/src/app/pages/temperatures/temperatures.component.ts`:
```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TemperatureService } from '../../services/temperature.service';
import { TemperatureSensorSeries, TimeRange } from '../../models/temperature.model';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

interface ChartTile {
  sensorId: string;
  name: string;
  source: string;
  options: Record<string, unknown>;
}

@Component({
  selector: 'app-temperatures',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './temperatures.component.html',
  styleUrl: './temperatures.component.scss'
})
export class TemperaturesComponent implements OnInit {
  private readonly temperatureService = inject(TemperatureService);

  readonly ranges: RangeOption[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];

  activeRange: TimeRange = 'WEEK';
  charts: ChartTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load(this.activeRange);
  }

  setRange(range: TimeRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.load(range);
  }

  private load(range: TimeRange): void {
    this.isLoading = true;
    this.isEmpty = false;
    this.errorMessage = null;
    this.temperatureService.getSeries(range).subscribe({
      next: series => {
        this.charts = series.map(s => ({
          sensorId: s.sensorId,
          name: s.name,
          source: s.source,
          options: this.chartOptionsFor(s)
        }));
        this.isEmpty = this.charts.length === 0;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Temperaturen:', error);
        this.errorMessage = 'Fehler beim Laden der Temperaturdaten. Bitte erneut versuchen.';
        this.isLoading = false;
      }
    });
  }

  chartOptionsFor(series: TemperatureSensorSeries): Record<string, unknown> {
    const hasHumidity = series.humidity.length > 0;
    const legend = ['Temperatur'];
    const yAxis: Record<string, unknown>[] = [
      {
        type: 'value',
        axisLabel: { color: '#94a3b8', formatter: '{value} °C' },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      }
    ];
    const chartSeries: Record<string, unknown>[] = [
      {
        name: 'Temperatur',
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        showSymbol: false,
        data: series.temperature.map(p => [p.time, p.value]),
        lineStyle: { width: 2.5, color: '#e6484d' },
        itemStyle: { color: '#e6484d' }
      }
    ];

    if (hasHumidity) {
      legend.push('Luftfeuchtigkeit');
      yAxis.push({
        type: 'value',
        position: 'right',
        axisLabel: { color: '#94a3b8', formatter: '{value} %' },
        splitLine: { show: false }
      });
      chartSeries.push({
        name: 'Luftfeuchtigkeit',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        showSymbol: false,
        data: series.humidity.map(p => [p.time, p.value]),
        lineStyle: { width: 2, color: '#3b82f6', type: 'dashed' },
        itemStyle: { color: '#3b82f6' }
      });
    }

    return {
      grid: { left: 48, right: hasHumidity ? 48 : 16, top: 32, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { data: legend, top: 0, textStyle: { color: '#94a3b8' } },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis,
      series: chartSeries
    };
  }
}
```

- [ ] **Step 4: Component-HTML implementieren**

`frontend/src/app/pages/temperatures/temperatures.component.html`:
```html
<section class="temperatures">
  <header class="temperatures__header">
    <h1 class="temperatures__title">Temperaturen</h1>
    <div class="temperatures__ranges" role="group" aria-label="Zeitraum">
      @for (range of ranges; track range.value) {
        <button
          type="button"
          class="temperatures__range"
          [class.temperatures__range--active]="range.value === activeRange"
          (click)="setRange(range.value)">
          {{ range.label }}
        </button>
      }
    </div>
  </header>

  @if (isLoading) {
    <p class="temperatures__status">Lade Temperaturdaten…</p>
  } @else if (errorMessage) {
    <p class="temperatures__status temperatures__status--error">{{ errorMessage }}</p>
  } @else if (isEmpty) {
    <p class="temperatures__status">Keine Temperatursensoren gefunden.</p>
  } @else {
    <div class="temperatures__grid">
      @for (chart of charts; track chart.sensorId) {
        <article class="temperatures__card">
          <h2 class="temperatures__card-title">{{ chart.name }}</h2>
          <div
            echarts
            class="temperatures__chart"
            [options]="chart.options"></div>
        </article>
      }
    </div>
  }
</section>
```

- [ ] **Step 5: Component-SCSS implementieren**

`frontend/src/app/pages/temperatures/temperatures.component.scss`:
```scss
.temperatures {
  padding: 1.5rem;

  &__header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    margin-bottom: 1.5rem;
  }

  &__title {
    margin: 0;
    font-size: 1.5rem;
  }

  &__ranges {
    display: inline-flex;
    gap: 0.25rem;
    background: rgba(148, 163, 184, 0.12);
    border-radius: 8px;
    padding: 0.25rem;
  }

  &__range {
    border: none;
    background: transparent;
    padding: 0.4rem 0.9rem;
    border-radius: 6px;
    cursor: pointer;
    font-size: 0.9rem;
    color: inherit;

    &--active {
      background: #e6484d;
      color: #fff;
    }
  }

  &__status {
    padding: 2rem 0;
    text-align: center;
    color: #94a3b8;

    &--error {
      color: #e6484d;
    }
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: 1rem;
  }

  &__card {
    border: 1px solid rgba(148, 163, 184, 0.25);
    border-radius: 10px;
    padding: 1rem;
  }

  &__card-title {
    margin: 0 0 0.5rem;
    font-size: 1rem;
  }

  &__chart {
    width: 100%;
    height: 240px;
  }
}
```

- [ ] **Step 6: Test laufen lassen, muss bestehen**

Run: `cd frontend && ng test --include='**/temperatures.component.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: PASS (5 Tests grün).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/temperatures/
git commit -m "feat(temperatures): TemperaturesComponent mit Kachel-Graphen"
```

---

## Task 9: Route + Navigation

**Files:**
- Modify: `frontend/src/app/app.routes.ts` (nach dem `weather`-Block, um Zeile 53)
- Modify: `frontend/src/app/components/header/header.component.ts:38-45`

- [ ] **Step 1: Route ergänzen**

In `frontend/src/app/app.routes.ts` direkt nach dem `weather`-Routenobjekt (dem Block, der mit `path: 'weather'` beginnt und mit `},` endet) einfügen:
```typescript
  {
    path: 'temperatures',
    loadComponent: () => import('./pages/temperatures/temperatures.component').then(m => m.TemperaturesComponent),
    title: 'Temperaturen - Household Manager'
  },
```

- [ ] **Step 2: Nav-Eintrag ergänzen**

In `frontend/src/app/components/header/header.component.ts` den „Umwelt"-Block so ändern, dass er den neuen Eintrag enthält:
```typescript
    {
      path: '/environment',
      label: 'Umwelt',
      children: [
        { path: '/air-quality', label: 'Luftqualitaet' },
        { path: '/weather', label: 'Wetter' },
        { path: '/temperatures', label: 'Temperaturen' }
      ]
    },
```

- [ ] **Step 3: Frontend baut**

Run: `cd frontend && ng build --configuration development`
Expected: BUILD erfolgreich, keine TypeScript-Fehler, Route lazy-chunk für `temperatures` erzeugt.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(temperatures): Route und Nav-Eintrag unter Umwelt"
```

---

## Task 10: Verifikation End-to-End

- [ ] **Step 1: Backend-Test-Suite (neue Tests) laufen lassen**

Run: `cd backend && mvn -q test -Dtest=TemperatureSeriesServiceTest`
Expected: PASS.

- [ ] **Step 2: Frontend-Tests der neuen Dateien laufen lassen**

Run: `cd frontend && ng test --include='**/temperature*.spec.ts' --watch=false --browsers=ChromeHeadless`
Expected: PASS (Service + Component).

- [ ] **Step 3: App manuell prüfen**

Backend starten (`mvn spring-boot:run`) und Frontend (`npm start`), dann `http://localhost:4200` öffnen → „Umwelt" → „Temperaturen".
Erwartung: Zeitraum-Umschalter (24h/7 Tage/30 Tage, Standard 7 Tage), je Sensor eine Kachel mit Temperatur- und (falls vorhanden) gestrichelter Feuchte-Linie; bei fehlenden Daten der Leer-Zustand.

- [ ] **Step 4: Abschluss-Commit (falls noch Uncommitted)**

```bash
git status
```
Expected: sauberer Arbeitsbaum (alle Tasks committet).

---

## Self-Review-Ergebnis

- **Spec-Abdeckung:** Alle drei Quellen (Zigbee/Weather/Alexa) → Task 2+4; Ein-Endpoint-Aggregation → Task 4+5; Layout C Kacheln + Dual-Y → Task 8; Zeitraum 24h/7d/30d → Task 3+8; Nav/Route → Task 9; Resilienz je Quelle → Task 4 (`safe`/Test `failingSourceIsSkippedNotFatal`); Leer-/Fehler-/Loading-Zustand → Task 8; Tests Backend+Frontend → Task 4/7/8. Keine Lücke.
- **Platzhalter:** keine.
- **Typkonsistenz:** DTO-Felder (`sensorId`, `name`, `source`, `temperature`, `humidity`, `TimeValue.time/value`) identisch in Backend (Task 1) und Frontend-Model (Task 6); Methodennamen `getSeries`/`chartOptionsFor`/`setRange`/`load` durchgängig; `findByReadingTimeBetweenOrderByReadingTimeAsc` und `findDistinctDevicesByMeasurementTypeInRange` in Repo (Task 2), Service (Task 4) und Test identisch.
