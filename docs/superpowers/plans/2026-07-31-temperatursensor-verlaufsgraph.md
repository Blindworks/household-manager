# Verlaufsgraph im Temperatursensor-Dialog — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unter den aktuellen Messwerten im Temperatursensor-Dialog des Dashboards einen Verlaufsgraphen (Temperatur + Luftfeuchte, umschaltbar 24 h / 7 Tage / 30 Tage) anzeigen.

**Architecture:** Neuer Backend-Endpunkt `GET /v1/temperatures/series?sensorId=&range=` liefert die Zeitreihe **eines** Sensors, serverseitig auf Zeit-Buckets gemittelt. Das Frontend baut daraus im bestehenden Sensor-Dialog einen ECharts-Liniengraphen mit zwei Y-Achsen, exakt nach dem Muster des bereits vorhandenen Verbraucher-Verlaufs.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Lombok / JUnit 5 + Mockito + AssertJ; Angular 19 standalone / ngx-echarts / Karma + Jasmine.

**Spec:** `docs/superpowers/specs/2026-07-31-temperatursensor-verlaufsgraph-design.md`

---

## Voraussetzungen für den ausführenden Entwickler

**Backend-Build braucht JDK 21** (Standard auf dieser Maschine ist JDK 17). Vor jedem `mvn`-Aufruf:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Unter PowerShell entsprechend `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"`.

**Bekannte Vorbelastung:** Tests, die eine lokale Datenbank brauchen, schlagen hier fehl — das ist erwartet und kein Regressionssignal. Im Frontend gibt es drei vorbestehende Fehlschläge (App/Hero) sowie einen Karma-Flake in `SmartDeviceList`. Alle Testläufe in diesem Plan sind deshalb **gezielt auf die betroffene Klasse** eingegrenzt.

Frontend-Tests headless:

```bash
npx ng test --watch=false --browsers=ChromeHeadless
```

---

## Dateiübersicht

**Backend — neu**
- `backend/src/main/java/com/household/manager/service/TemperatureSeriesDownsampler.java` — reine Mittelungsfunktion auf `List<TimeValue>`, ohne Kenntnis von Quellen oder Datenbank.
- `backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java`

**Backend — geändert**
- `backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java` — eine Abfragemethode für einen einzelnen Appliance.
- `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java` — neue Methode `getSensorSeries`, zusätzliches `ZigbeeDeviceRepository`.
- `backend/src/main/java/com/household/manager/controller/TemperatureController.java` — neuer Endpunkt.
- `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java` — Tests der neuen Methode.

**Frontend — geändert**
- `frontend/src/app/services/temperature.service.ts` — `getSensorSeries`.
- `frontend/src/app/pages/dashboard/dashboard.component.ts` — Zustand, Laden, Chart-Optionen.
- `frontend/src/app/pages/dashboard/dashboard.component.html` — Verlaufsabschnitt im Sensor-Dialog.
- `frontend/src/app/pages/dashboard/dashboard.component.scss` — Dialogbreite (siehe Task 8).
- `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` — Spy-Erweiterung + neuer Test.

**Abweichung von der Spec:** Die Spec sagt „neues Styling entsteht nicht“. Das stimmt für die Verlaufsklassen, aber nicht ganz: `.lumina__dialog--sensor` ist heute `min(420px, 92vw)` breit — für einen Graphen mit zwei Achsen zu schmal. Task 8 verbreitert genau diese eine Regel. Sonst ändert sich am Styling nichts.

---

## Task 1: Mittelung — Bucket-Grenzen

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/TemperatureSeriesDownsampler.java`
- Test: `backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java`

- [ ] **Step 1: Failing Test schreiben**

Neue Datei `backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.TimeValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemperatureSeriesDownsamplerTest {

    private final TemperatureSeriesDownsampler downsampler = new TemperatureSeriesDownsampler();

    private TimeValue point(String time, String value) {
        return TimeValue.builder()
                .time(LocalDateTime.parse(time))
                .value(new BigDecimal(value))
                .build();
    }

    @Test
    void mitteltPunkteInnerhalbEinesBuckets() {
        List<TimeValue> input = List.of(
                point("2026-07-31T10:00:00", "20.0"),
                point("2026-07-31T10:02:00", "22.0"),
                point("2026-07-31T10:04:59", "24.0"));

        List<TimeValue> result = downsampler.downsample(input, TemperatureRange.DAY);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:00:00"));
        assertThat(result.get(0).getValue()).isEqualByComparingTo("22.00");
    }

    @Test
    void punktAufDerBucketGrenzeBeginntDenNaechstenBucket() {
        List<TimeValue> input = List.of(
                point("2026-07-31T10:04:59", "20.0"),
                point("2026-07-31T10:05:00", "30.0"));

        List<TimeValue> result = downsampler.downsample(input, TemperatureRange.DAY);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:00:00"));
        assertThat(result.get(1).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:05:00"));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TemperatureSeriesDownsamplerTest
```

Erwartet: Compile-Fehler — `TemperatureSeriesDownsampler` existiert nicht.

- [ ] **Step 3: Minimale Implementierung**

Neue Datei `backend/src/main/java/com/household/manager/service/TemperatureSeriesDownsampler.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.TimeValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mittelt eine Messreihe auf feste Zeit-Buckets herunter, damit der Verlaufsgraph
 * eines einzelnen Sensors auch über 30 Tage eine übertragbare und flüssig zeichenbare
 * Punktzahl hat.
 *
 * <p>Bewusst quellen-agnostisch: die Klasse kennt weder Zigbee noch Wetter noch Alexa
 * und ist dadurch ohne Datenbank testbar.
 *
 * <p>Leere Buckets werden ausgelassen statt mit Nullen gefüllt. Eine Funkpause ist bei
 * Temperatursensoren der Normalfall — sie melden nur bei Wertänderung — und darf nicht
 * wie ein Messausfall aussehen.
 */
@Component
public class TemperatureSeriesDownsampler {

    /** Nachkommastellen des gemittelten Werts; mehr täuscht eine Genauigkeit vor, die die Sensoren nicht haben. */
    private static final int SCALE = 2;

    public List<TimeValue> downsample(List<TimeValue> points, TemperatureRange range) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        long bucketSeconds = range.getBucketSeconds();

        Map<LocalDateTime, List<BigDecimal>> buckets = new LinkedHashMap<>();
        for (TimeValue point : points) {
            if (point.getTime() == null || point.getValue() == null) {
                continue;
            }
            buckets.computeIfAbsent(bucketStart(point.getTime(), bucketSeconds), key -> new ArrayList<>())
                    .add(point.getValue());
        }

        List<TimeValue> result = new ArrayList<>(buckets.size());
        buckets.forEach((start, values) -> result.add(TimeValue.builder()
                .time(start)
                .value(average(values))
                .build()));
        result.sort(java.util.Comparator.comparing(TimeValue::getTime));
        return result;
    }

    /**
     * Bucket-Anfang per Abrunden auf ein Vielfaches der Bucket-Länge. Ein Punkt exakt auf
     * der Grenze beginnt damit den folgenden Bucket. {@code floorDiv} statt {@code /},
     * damit Zeitpunkte vor der Epoche nicht in den falschen Bucket kippen.
     */
    private LocalDateTime bucketStart(LocalDateTime time, long bucketSeconds) {
        long seconds = time.toEpochSecond(ZoneOffset.UTC);
        long start = Math.floorDiv(seconds, bucketSeconds) * bucketSeconds;
        return LocalDateTime.ofEpochSecond(start, 0, ZoneOffset.UTC);
    }

    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }
}
```

Und `TemperatureRange` um die Bucket-Länge erweitern — `backend/src/main/java/com/household/manager/service/TemperatureRange.java` **vollständig** ersetzen durch:

```java
package com.household.manager.service;

import lombok.Getter;

/** Auswählbarer Zeitraum der Temperaturgraphen inkl. der dazu passenden Mittelungs-Bucketlänge. */
@Getter
public enum TemperatureRange {
    DAY(1, 5 * 60L),
    WEEK(7, 30 * 60L),
    MONTH(30, 2 * 60 * 60L);

    private final int days;
    /** Länge eines Mittelungs-Buckets in Sekunden; je länger der Zeitraum, desto gröber. */
    private final long bucketSeconds;

    TemperatureRange(int days, long bucketSeconds) {
        this.days = days;
        this.bucketSeconds = bucketSeconds;
    }
}
```

- [ ] **Step 4: Tests laufen lassen, grün erwarten**

```bash
cd backend && mvn -q test -Dtest=TemperatureSeriesDownsamplerTest
```

Erwartet: BUILD SUCCESS, 2 Tests grün.

- [ ] **Step 5: Committen**

```bash
git add backend/src/main/java/com/household/manager/service/TemperatureSeriesDownsampler.java backend/src/main/java/com/household/manager/service/TemperatureRange.java backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java
git commit -m "feat(temperatures): Mittelung von Messreihen auf Zeit-Buckets"
```

---

## Task 2: Mittelung — Randfälle

**Files:**
- Modify: `backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java`

- [ ] **Step 1: Weitere Tests ergänzen**

Diese drei Methoden in die bestehende Testklasse einfügen (vor die schließende Klammer):

```java
    @Test
    void laesstLeereBucketsAusStattSieMitNullenZuFuellen() {
        List<TimeValue> input = List.of(
                point("2026-07-31T10:00:00", "20.0"),
                point("2026-07-31T11:00:00", "21.0"));

        List<TimeValue> result = downsampler.downsample(input, TemperatureRange.DAY);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TimeValue::getTime)
                .containsExactly(
                        LocalDateTime.parse("2026-07-31T10:00:00"),
                        LocalDateTime.parse("2026-07-31T11:00:00"));
    }

    @Test
    void liefertBeiLeererEingabeEineLeereListe() {
        assertThat(downsampler.downsample(List.of(), TemperatureRange.WEEK)).isEmpty();
        assertThat(downsampler.downsample(null, TemperatureRange.WEEK)).isEmpty();
    }

    @Test
    void nutztFuerMonatZweiStundenBuckets() {
        List<TimeValue> input = List.of(
                point("2026-07-31T10:00:00", "20.0"),
                point("2026-07-31T11:59:00", "22.0"),
                point("2026-07-31T12:00:00", "30.0"));

        List<TimeValue> result = downsampler.downsample(input, TemperatureRange.MONTH);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T10:00:00"));
        assertThat(result.get(0).getValue()).isEqualByComparingTo("21.00");
        assertThat(result.get(1).getTime()).isEqualTo(LocalDateTime.parse("2026-07-31T12:00:00"));
    }
```

- [ ] **Step 2: Tests laufen lassen**

```bash
cd backend && mvn -q test -Dtest=TemperatureSeriesDownsamplerTest
```

Erwartet: BUILD SUCCESS, 5 Tests grün. Die Implementierung deckt diese Fälle bereits ab — die Tests halten sie fest, damit eine spätere Vereinfachung sie nicht still kaputtmacht.

- [ ] **Step 3: Committen**

```bash
git add backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java
git commit -m "test(temperatures): Randfaelle der Bucket-Mittelung"
```

---

## Task 3: Repository-Abfrage für einen einzelnen Alexa-Sensor

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java`

Die heutige Serien-Abfrage lädt **alle** Appliances und gruppiert in Java. Für einen einzelnen Sensor braucht es eine gezielte Abfrage. Zigbee und Wetter haben passende Methoden bereits.

- [ ] **Step 1: Methode ergänzen**

In `AlexaAirQualityReadingRepository` (innerhalb des Interfaces) einfügen:

```java
    /** Messungen genau eines Geräts im Zeitfenster, aufsteigend — Grundlage des Sensor-Verlaufs. */
    List<AlexaAirQualityReading> findByApplianceIdAndReadingTimeBetweenOrderByReadingTimeAsc(
            String applianceId, LocalDateTime from, LocalDateTime to);
```

Prüfen, dass `java.util.List` und `java.time.LocalDateTime` importiert sind; falls nicht, Importe ergänzen.

- [ ] **Step 2: Kompilieren**

```bash
cd backend && mvn -q -DskipTests compile
```

Erwartet: BUILD SUCCESS. Spring Data leitet die Abfrage aus dem Methodennamen ab — ein Tippfehler im Namen fällt erst beim Kontextstart auf, deshalb im nächsten Task ein Service-Test darüber.

- [ ] **Step 3: Committen**

```bash
git add backend/src/main/java/com/household/manager/repository/AlexaAirQualityReadingRepository.java
git commit -m "feat(temperatures): Repository-Abfrage fuer einen einzelnen Alexa-Sensor"
```

---

## Task 4: Service — Zeitreihe eines einzelnen Sensors

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`
- Test: `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

In `TemperatureSeriesServiceTest` zuerst die Mocks erweitern. Die Zeile

```java
    @Mock private ZigbeeMeasurementRepository zigbeeRepository;
```

wird zu:

```java
    @Mock private ZigbeeMeasurementRepository zigbeeRepository;
    @Mock private ZigbeeDeviceRepository zigbeeDeviceRepository;
    @Mock private TemperatureSeriesDownsampler downsampler;
```

Und diese Importe zum Kopf hinzufügen:

```java
import com.household.manager.dto.TimeValue;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.repository.ZigbeeDeviceRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
```

Dann diese Testmethoden ergänzen (vor die schließende Klammer der Klasse):

```java
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
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd backend && mvn -q test -Dtest=TemperatureSeriesServiceTest
```

Erwartet: Compile-Fehler — `getSensorSeries` existiert nicht.

- [ ] **Step 3: Service implementieren**

In `TemperatureSeriesService` zuerst die neuen Abhängigkeiten ergänzen. Der Feldblock

```java
    private final ZigbeeMeasurementRepository zigbeeRepository;
    private final WeatherReadingRepository weatherRepository;
    private final AlexaAirQualityReadingRepository alexaRepository;
    private final EntityStateService entityStateService;
```

wird zu:

```java
    private final ZigbeeMeasurementRepository zigbeeRepository;
    private final ZigbeeDeviceRepository zigbeeDeviceRepository;
    private final WeatherReadingRepository weatherRepository;
    private final AlexaAirQualityReadingRepository alexaRepository;
    private final EntityStateService entityStateService;
    private final TemperatureSeriesDownsampler downsampler;
```

Importe ergänzen:

```java
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.repository.ZigbeeDeviceRepository;
```

Dann diese Methoden einfügen (direkt nach `getCurrent()`):

```java
    /**
     * Zeitreihe genau eines Sensors, serverseitig auf Buckets gemittelt.
     *
     * <p>Bewusst ohne die {@code safe(...)}-Fehlerisolierung der Sammelabfrage: die ist dafür
     * da, dass eine kaputte Quelle die Gesamtantwort nicht kippt. Bei genau einer angefragten
     * Quelle verwandelte sie einen Fehler in einen stumm leeren Graphen — und der ist von
     * "dieser Sensor hat nichts gemeldet" nicht unterscheidbar.
     *
     * @throws ResourceNotFoundException wenn die sensorId keiner bekannten Quelle zuzuordnen ist
     */
    public TemperatureSensorSeries getSensorSeries(String sensorId, TemperatureRange range) {
        if (sensorId == null || sensorId.isBlank()) {
            throw new ResourceNotFoundException("Sensor", "sensorId", sensorId);
        }
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(range.getDays());

        if (sensorId.startsWith("zigbee:")) {
            return zigbeeSensorSeries(sensorId.substring("zigbee:".length()), from, to, range);
        }
        if (sensorId.equals("weather:outdoor")) {
            return weatherSensorSeries(from, to, range);
        }
        if (sensorId.startsWith("alexa:")) {
            return alexaSensorSeries(sensorId.substring("alexa:".length()), from, to, range);
        }
        throw new ResourceNotFoundException("Sensor", "sensorId", sensorId);
    }

    private TemperatureSensorSeries zigbeeSensorSeries(
            String rawDeviceId, LocalDateTime from, LocalDateTime to, TemperatureRange range) {
        long deviceId;
        try {
            deviceId = Long.parseLong(rawDeviceId);
        } catch (NumberFormatException ex) {
            throw new ResourceNotFoundException("Sensor", "sensorId", "zigbee:" + rawDeviceId);
        }
        ZigbeeDevice device = zigbeeDeviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Sensor", "sensorId", "zigbee:" + deviceId));

        List<TimeValue> temperature = zigbeeRepository
                .findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                        deviceId, MeasurementType.TEMPERATURE, from, to)
                .stream().map(this::toTimeValue).toList();
        List<TimeValue> humidity = zigbeeRepository
                .findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                        deviceId, MeasurementType.HUMIDITY, from, to)
                .stream().map(this::toTimeValue).toList();

        return TemperatureSensorSeries.builder()
                .sensorId("zigbee:" + deviceId)
                .name(temperatureName(EntitySource.ZIGBEE, device.getFriendlyName(), device.getFriendlyName()))
                .source("ZIGBEE")
                .temperature(downsampler.downsample(temperature, range))
                .humidity(downsampler.downsample(humidity, range))
                .build();
    }

    private TemperatureSensorSeries weatherSensorSeries(
            LocalDateTime from, LocalDateTime to, TemperatureRange range) {
        List<WeatherReading> readings =
                weatherRepository.findByReadingTimeBetweenOrderByReadingTimeAsc(from, to);

        List<TimeValue> temperature = readings.stream()
                .filter(r -> r.getTemperature() != null)
                .map(r -> point(r.getReadingTime(), r.getTemperature()))
                .toList();
        List<TimeValue> humidity = readings.stream()
                .filter(r -> r.getHumidity() != null)
                .map(r -> point(r.getReadingTime(), BigDecimal.valueOf(r.getHumidity())))
                .toList();

        return TemperatureSensorSeries.builder()
                .sensorId("weather:outdoor")
                .name(temperatureName(EntitySource.WEATHER, "dwd", "Außen"))
                .source("WEATHER")
                .temperature(downsampler.downsample(temperature, range))
                .humidity(downsampler.downsample(humidity, range))
                .build();
    }

    private TemperatureSensorSeries alexaSensorSeries(
            String applianceId, LocalDateTime from, LocalDateTime to, TemperatureRange range) {
        List<AlexaAirQualityReading> readings = alexaRepository
                .findByApplianceIdAndReadingTimeBetweenOrderByReadingTimeAsc(applianceId, from, to);

        String name = readings.isEmpty() || readings.get(readings.size() - 1).getDeviceName() == null
                ? applianceId
                : readings.get(readings.size() - 1).getDeviceName();

        List<TimeValue> temperature = readings.stream()
                .filter(r -> r.getTemperature() != null)
                .map(r -> point(r.getReadingTime(), r.getTemperature()))
                .toList();
        List<TimeValue> humidity = readings.stream()
                .filter(r -> r.getHumidity() != null)
                .map(r -> point(r.getReadingTime(), r.getHumidity()))
                .toList();

        return TemperatureSensorSeries.builder()
                .sensorId("alexa:" + applianceId)
                .name(temperatureName(EntitySource.ALEXA, applianceId, name))
                .source("ALEXA")
                .temperature(downsampler.downsample(temperature, range))
                .humidity(downsampler.downsample(humidity, range))
                .build();
    }
```

**Hinweis zur Alexa-Quelle:** Anders als bei Zigbee gibt es keine Gerätetabelle, gegen die sich eine unbekannte `applianceId` prüfen ließe. Eine unbekannte Alexa-ID liefert deshalb 200 mit leeren Reihen statt 404. Das ist hinnehmbar — die IDs kommen ausschließlich aus der eigenen `/current`-Antwort, ein Nutzer tippt sie nie von Hand.

- [ ] **Step 4: Tests laufen lassen, grün erwarten**

```bash
cd backend && mvn -q test -Dtest=TemperatureSeriesServiceTest
```

Erwartet: BUILD SUCCESS. Alle bestehenden Tests der Klasse bleiben grün — `@InjectMocks` versorgt die zwei neuen Abhängigkeiten automatisch.

- [ ] **Step 5: Committen**

```bash
git add backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java
git commit -m "feat(temperatures): Zeitreihe eines einzelnen Sensors im Service"
```

---

## Task 5: Endpunkt

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/TemperatureController.java`

- [ ] **Step 1: Endpunkt ergänzen**

In `TemperatureController` nach `getTemperatures` einfügen:

```java
    /**
     * Zeitreihe genau eines Sensors für den Verlaufsgraphen im Detaildialog.
     *
     * <p>sensorId ist bewusst ein Query-Parameter und keine Pfadvariable: die IDs tragen einen
     * Doppelpunkt ("zigbee:12", "alexa:&lt;applianceId&gt;"), und die Alexa-Appliance-ID kommt
     * unkontrolliert aus der Amazon-API. Enthielte sie ein "/" oder "=", zerlegte sie ein
     * Pfadsegment und der Endpunkt wäre für genau diese Sensoren still kaputt.
     */
    @GetMapping("/series")
    public TemperatureSensorSeries getSensorSeries(
            @RequestParam String sensorId,
            @RequestParam(required = false, defaultValue = "DAY") TemperatureRange range) {
        return temperatureSeriesService.getSensorSeries(sensorId, range);
    }
```

Der Endpunkt fällt unter die generische Regel `GET /v1/**` → KIOSK in `SecurityConfig`; **keine** eigene Matcher-Zeile hinzufügen (das Wandtablet muss ihn lesen können).

- [ ] **Step 2: Kompilieren**

```bash
cd backend && mvn -q -DskipTests compile
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 3: Committen**

```bash
git add backend/src/main/java/com/household/manager/controller/TemperatureController.java
git commit -m "feat(temperatures): Endpunkt fuer die Zeitreihe eines Sensors"
```

---

## Task 6: Frontend-Service

**Files:**
- Modify: `frontend/src/app/services/temperature.service.ts`

- [ ] **Step 1: Methode ergänzen**

In `TemperatureService` nach `getCurrent()` einfügen:

```typescript
  /** Zeitreihe genau eines Sensors für den Verlaufsgraphen im Detaildialog. */
  getSensorSeries(sensorId: string, range: TimeRange): Observable<TemperatureSensorSeries> {
    const params = new HttpParams().set('sensorId', sensorId).set('range', range);
    return this.http.get<TemperatureSensorSeries>(`${this.baseUrl}/series`, { params }).pipe(
      catchError(this.handleError)
    );
  }
```

Alle verwendeten Typen und Importe sind in der Datei bereits vorhanden.

- [ ] **Step 2: Kompilieren**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```

Erwartet: keine Ausgabe (Erfolg).

- [ ] **Step 3: Committen**

```bash
git add frontend/src/app/services/temperature.service.ts
git commit -m "feat(dashboard): Frontend-Service fuer die Sensor-Zeitreihe"
```

---

## Task 7: Dashboard-Komponente — Zustand, Laden, Chart

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`

- [ ] **Step 1: LegendComponent bei ECharts registrieren**

Die Zeile

```typescript
import { GridComponent, TooltipComponent } from 'echarts/components';
```

wird zu:

```typescript
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
```

und

```typescript
echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);
```

wird zu:

```typescript
// LegendComponent: ohne Legende ist bei zwei Linien nicht erkennbar, welche welche ist.
echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);
```

- [ ] **Step 2: Zustandsfelder ergänzen**

Direkt unter dem Feld `sensorDetail` einfügen:

```typescript
  /** Gewählter Zeitraum des Sensor-Verlaufs. */
  sensorHistoryRange: TimeRange = 'DAY';
  /** ECharts-Optionen des Sensor-Verlaufs. */
  sensorHistoryOptions: Record<string, unknown> | null = null;
  /** True, wenn im gewählten Zeitraum keine Messpunkte vorliegen. */
  sensorHistoryEmpty = false;
  sensorHistoryError: string | null = null;

  /** Auswählbare Zeiträume des Sensor-Verlaufs. */
  readonly sensorHistoryRanges: { value: TimeRange; label: string }[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];
```

Den Import der Temperatur-Modelle um `TimeRange` und `TemperatureSensorSeries` erweitern. Die bestehende Zeile sucht man mit `grep -n "temperature.model" frontend/src/app/pages/dashboard/dashboard.component.ts` und ergänzt die beiden Namen in der Import-Liste.

- [ ] **Step 3: Öffnen/Schließen erweitern und Laden implementieren**

`openSensorDialog` und `closeSensorDialog` **vollständig ersetzen** durch:

```typescript
  /** Öffnet den Detaildialog eines Temperatursensors (Temperatur + Luftfeuchte). */
  openSensorDialog(sensorId: string): void {
    this.sensorDetail = buildSensorDetail(this.currentTemperatures, sensorId, Date.now());
    this.sensorHistoryRange = 'DAY';
    this.sensorHistoryOptions = null;
    this.sensorHistoryEmpty = false;
    this.sensorHistoryError = null;
    if (this.sensorDetail) {
      this.loadSensorHistory();
    }
  }

  closeSensorDialog(): void {
    this.sensorDetail = null;
    this.sensorHistoryRange = 'DAY';
    this.sensorHistoryOptions = null;
    this.sensorHistoryEmpty = false;
    this.sensorHistoryError = null;
  }

  setSensorHistoryRange(range: TimeRange): void {
    if (range === this.sensorHistoryRange) {
      return;
    }
    this.sensorHistoryRange = range;
    this.sensorHistoryOptions = null;
    this.loadSensorHistory();
  }

  private loadSensorHistory(): void {
    const detail = this.sensorDetail;
    if (!detail) {
      return;
    }
    const requestedId = detail.sensorId;
    const requestedRange = this.sensorHistoryRange;
    this.sensorHistoryError = null;
    this.sensorHistoryEmpty = false;
    this.temperatureService.getSensorSeries(requestedId, requestedRange).subscribe({
      next: series => {
        // Verworfen, wenn der Dialog inzwischen geschlossen, auf einen anderen Sensor
        // gewechselt oder auf einen anderen Zeitraum gestellt wurde: sonst ueberschreibt
        // eine spaet eintreffende 30-Tage-Antwort die schon geladene 24-Stunden-Sicht.
        if (this.sensorDetail?.sensorId !== requestedId
            || this.sensorHistoryRange !== requestedRange) {
          return;
        }
        this.sensorHistoryEmpty = series.temperature.length === 0;
        this.sensorHistoryOptions = this.buildSensorHistoryOptions(series);
      },
      error: () => {
        // Derselbe Schutz: ein fehlgeschlagener alter Request darf nicht die Fehlermeldung
        // ueber einen inzwischen erfolgreich geladenen Verlauf legen.
        if (this.sensorDetail?.sensorId !== requestedId
            || this.sensorHistoryRange !== requestedRange) {
          return;
        }
        this.sensorHistoryOptions = null;
        this.sensorHistoryError = 'Verlauf konnte nicht geladen werden.';
      }
    });
  }

  /**
   * Liniendiagramm des Sensor-Verlaufs. Temperatur links, Luftfeuchte rechts auf eigener
   * Achse. Fehlen Feuchtewerte, entfallen Serie und rechte Achse: eine leere zweite Achse
   * suggeriert fehlende Daten, wo es nie welche gab.
   *
   * Kein connectNulls-Abriss wie beim Leistungsverlauf — Temperatursensoren melden nur bei
   * Wertaenderung, eine Funkpause ist dort der Normalfall und kein Messausfall.
   */
  private buildSensorHistoryOptions(series: TemperatureSensorSeries): Record<string, unknown> {
    const hasHumidity = series.humidity.length > 0;
    const yAxis: Record<string, unknown>[] = [
      {
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: '{value} °C' },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      }
    ];
    const chartSeries: Record<string, unknown>[] = [
      {
        name: 'Temperatur',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: series.temperature.map(point => [point.time, point.value]),
        lineStyle: { width: 2.5, color: '#ef4444' },
        itemStyle: { color: '#ef4444' },
        areaStyle: { color: 'rgba(239, 68, 68, 0.12)' }
      }
    ];

    if (hasHumidity) {
      yAxis.push({
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: '{value} %' },
        splitLine: { show: false }
      });
      chartSeries.push({
        name: 'Luftfeuchte',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        data: series.humidity.map(point => [point.time, point.value]),
        lineStyle: { width: 2, color: '#3b82f6' },
        itemStyle: { color: '#3b82f6' }
      });
    }

    return {
      grid: { left: 56, right: hasHumidity ? 56 : 16, top: 40, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { top: 0, textStyle: { color: '#94a3b8', fontSize: 11 } },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis,
      series: chartSeries
    };
  }
```

**`refreshSensorDetail` bleibt unverändert.** Der 30-Sekunden-Refresh zieht weiterhin nur die Zahlenwerte oben nach — ein regelmäßig neu aufgebauter Chart würde flackern, und bei 30-Tage-Sicht ändert sich die Kurve in dieser Auflösung ohnehin nicht sichtbar.

Prüfen, dass `temperatureService` bereits als Feld injiziert ist (es wird für `getCurrent` schon verwendet) — der exakte Feldname steht in der Datei, mit `grep -n "temperatureService" frontend/src/app/pages/dashboard/dashboard.component.ts` nachsehen.

- [ ] **Step 4: Kompilieren**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```

Erwartet: keine Ausgabe.

- [ ] **Step 5: Committen**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts
git commit -m "feat(dashboard): Verlaufsdaten und Chart-Optionen fuer Temperatursensoren"
```

---

## Task 8: Dialog-Markup und Dialogbreite

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

- [ ] **Step 1: Verlaufsabschnitt ins Dialog-Markup einfügen**

Im Sensor-Dialog steht am Ende des `lumina__dialog-body` heute:

```html
        <p class="lumina__sensor-meta">
          <span class="lumina__sensor-status">{{ sensorDetail.statusLabel }}</span>
          <span>Gemessen: {{ sensorDetail.measuredAt | date: 'dd.MM.yyyy, HH:mm' }}</span>
        </p>
```

**Direkt darunter** (noch innerhalb des `lumina__dialog-body`) einfügen:

```html
        <div class="lumina__history-ranges" role="group" aria-label="Zeitraum">
          <button
            *ngFor="let range of sensorHistoryRanges"
            type="button"
            class="lumina__history-range"
            [class.lumina__history-range--active]="range.value === sensorHistoryRange"
            (click)="setSensorHistoryRange(range.value)"
          >
            {{ range.label }}
          </button>
        </div>
        <p *ngIf="sensorHistoryError" class="lumina__history-message">{{ sensorHistoryError }}</p>
        <p *ngIf="sensorHistoryEmpty && !sensorHistoryError" class="lumina__history-message">
          Keine Messwerte in diesem Zeitraum
        </p>
        <div
          *ngIf="sensorHistoryOptions && !sensorHistoryEmpty && !sensorHistoryError"
          echarts
          class="lumina__history-chart"
          [options]="sensorHistoryOptions"
        ></div>
```

Das Markup bleibt **direkt in `dashboard.component.html`** — die `lumina`-Styles sind dort gekapselt und griffen in einer Kindkomponente lautlos nicht.

- [ ] **Step 2: Dialogbreite anpassen**

In `dashboard.component.scss` die Regel

```scss
.lumina__dialog--sensor {
  width: min(420px, 92vw);
}
```

ersetzen durch:

```scss
.lumina__dialog--sensor {
  // Breiter als die uebrigen Detaildialoge: der Verlaufsgraph traegt zwei Y-Achsen
  // und wird bei 420px unleserlich gestaucht.
  width: min(620px, 92vw);
}
```

Sonst ändert sich am Styling nichts — `lumina__history-ranges`, `lumina__history-range`, `lumina__history-chart` und `lumina__history-message` existieren bereits und werden unverändert wiederverwendet.

- [ ] **Step 3: Build prüfen**

```bash
cd frontend && npx ng build --configuration production
```

Erwartet: BUILD SUCCESS. **Achtung:** `dashboard.component.scss` reißt bereits heute das `anyComponentStyle`-Budget. Erscheint dazu ein Fehler, ist das die bekannte Größenpolizei und keine Regression — dann in `angular.json` das `anyComponentStyle`-Budget so weit anheben, dass der Build durchläuft, und die Anhebung im Commit erwähnen.

- [ ] **Step 4: Committen**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.scss
git commit -m "feat(dashboard): Verlaufsgraph im Temperatursensor-Dialog anzeigen"
```

---

## Task 9: Frontend-Test für das Verwerfen verspäteter Antworten

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: Alle TemperatureService-Spies erweitern**

Die Datei erzeugt an **acht** Stellen einen Spy mit nur `getCurrent`. Jede dieser Zeilen

```typescript
    jasmine.createSpyObj('TemperatureService', ['getCurrent']);
```

wird zu

```typescript
    jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
```

Stellen finden mit:

```bash
grep -n "TemperatureService', \[" frontend/src/app/pages/dashboard/dashboard.component.spec.ts
```

Ohne diese Erweiterung ist `getSensorSeries` beim Öffnen des Dialogs `undefined` und bestehende Tests brechen.

- [ ] **Step 2: Failing Test schreiben**

Im `describe`-Block, der den Sensor-Dialog abdeckt (dort, wo `temperatureServiceSpy` als Variable des Blocks existiert — Zeile ~1049), diesen Test ergänzen:

```typescript
  it('verwirft eine verspaetete Antwort nach Zeitraumwechsel', fakeAsync(() => {
    const series = (range: string) => ({
      sensorId: 'zigbee:1',
      name: 'Wohnzimmer',
      source: 'ZIGBEE' as const,
      temperature: [{ time: '2026-07-31T10:00:00', value: range === 'DAY' ? 20 : 30 }],
      humidity: []
    });
    temperatureServiceSpy.getSensorSeries.and.callFake((_id: string, range: string) =>
      // Die 24-Stunden-Antwort trifft absichtlich SPAETER ein als die 7-Tage-Antwort.
      range === 'DAY' ? of(series('DAY')).pipe(delay(100)) : of(series('WEEK'))
    );
    temperatureServiceSpy.getCurrent.and.returnValue(of([{
      sensorId: 'zigbee:1',
      name: 'Wohnzimmer',
      source: 'ZIGBEE',
      temperature: 21,
      measuredAt: new Date().toISOString()
    } as CurrentTemperatureReading]));

    fixture.detectChanges();
    component.openSensorDialog('zigbee:1');
    component.setSensorHistoryRange('WEEK');
    tick(200);

    expect(component.sensorHistoryRange).toBe('WEEK');
    const seriesOption = (component.sensorHistoryOptions as any).series[0];
    expect(seriesOption.data[0][1]).toBe(30);

    discardPeriodicTasks();
  }));
```

`fakeAsync`, `tick`, `discardPeriodicTasks`, `of`, `delay` und `CurrentTemperatureReading` sind in der Datei bereits importiert.

- [ ] **Step 3: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless
```

Erwartet: Der neue Test schlägt fehl, wenn der Verwerf-Schutz aus Task 7 fehlt oder falsch ist. Ist Task 7 bereits korrekt umgesetzt, ist er sofort grün — dann zur Absicherung testweise die beiden Guard-Blöcke in `loadSensorHistory` auskommentieren, den Fehlschlag beobachten und wieder einkommentieren. Ein Test, der nie rot war, beweist nichts.

- [ ] **Step 4: Vollen Frontend-Lauf prüfen**

```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless
```

Erwartet: alle Tests grün **bis auf** die drei bekannten Vorbelastungen (App/Hero) und ggf. den `SmartDeviceList`-Flake. Kommt ein *neuer* Fehlschlag dazu, ist es eine Regression und muss behoben werden.

- [ ] **Step 5: Committen**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "test(dashboard): verspaetete Verlaufsantwort wird verworfen"
```

---

## Task 10: Gesamtlauf und manuelle Prüfung

- [ ] **Step 1: Backend-Tests der betroffenen Klassen**

```bash
cd backend && mvn -q test -Dtest='TemperatureSeries*'
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 2: Backend starten und Endpunkt prüfen**

Backend starten (`mvn spring-boot:run`), dann in einem zweiten Terminal:

```bash
curl -s -u admin:<passwort> "http://localhost:8080/api/v1/temperatures/current"
```

Eine `sensorId` aus der Antwort nehmen und einsetzen:

```bash
curl -s -u admin:<passwort> "http://localhost:8080/api/v1/temperatures/series?sensorId=zigbee:1&range=DAY"
```

Erwartet: JSON mit `sensorId`, `name`, `source`, `temperature[]`, `humidity[]`. Gegenprobe:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -u admin:<passwort> "http://localhost:8080/api/v1/temperatures/series?sensorId=quatsch:1"
```

Erwartet: `404`.

- [ ] **Step 3: Dialog im Browser prüfen**

Frontend starten (`npm start`), Dashboard öffnen, auf einen Temperatursensor klicken. Prüfen:
- Die bestehenden Messwerte oben sind unverändert.
- Darunter erscheinen die drei Zeitraum-Buttons und der Graph.
- Umschalten auf „7 Tage“ und „30 Tage“ lädt neu und zeichnet gröber.
- Ein Sensor ohne Feuchtewerte zeigt nur die Temperaturlinie, ohne leere rechte Achse.

- [ ] **Step 4: Abschluss-Commit, falls noch etwas offen ist**

```bash
git status
```

Erwartet: sauberer Arbeitsbaum. Falls nicht, die Reste prüfen und committen.
