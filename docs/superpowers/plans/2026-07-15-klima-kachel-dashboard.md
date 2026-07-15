# Klima-Kachel im Tablet-Dashboard – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Energie-Kachel im Tablet-Dashboard zeigt statt Energiewerten die aktuellen Innentemperaturen je Raum im Vergleich zur Außentemperatur, jeweils mit Komfort-Bewertung.

**Architecture:** Ein neuer schlanker Backend-Endpoint `GET /api/v1/temperatures/current` liefert je Sensor nur den jüngsten Wert. Das Frontend lädt diese Werte periodisch, teilt sie in „Außen" (Referenz) und Innensensoren auf, bewertet jede Innentemperatur über eine reine Util-Funktion und rendert sie in der umgebauten Kachel. Veraltete Werte (Messung zu alt) werden markiert.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JPA (Backend), Angular 19 standalone / TypeScript / SCSS / Jasmine (Frontend).

---

## File Structure

**Backend (neu):**
- `backend/src/main/java/com/household/manager/dto/CurrentTemperatureReading.java` – DTO für einen aktuellen Sensorwert.

**Backend (ändern):**
- `backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java` – „jüngster Wert je Gerät"- und „alle Temperaturgeräte"-Query.
- `backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java` – „jüngste Messung mit Temperatur"-Query.
- `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java` – Methode `getCurrent()`, generisches `safe(...)`.
- `backend/src/main/java/com/household/manager/controller/TemperatureController.java` – Endpoint `/current`.
- `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java` – Tests für `getCurrent()`.

**Frontend (neu):**
- `frontend/src/app/shared/temperature-comfort.util.ts` – Komfort-Bewertung + Aufbau des Kachel-View-Models.
- `frontend/src/app/shared/temperature-comfort.util.spec.ts` – Tests dazu.

**Frontend (ändern):**
- `frontend/src/app/models/temperature.model.ts` – Interface `CurrentTemperatureReading`.
- `frontend/src/app/services/temperature.service.ts` – Methode `getCurrent()`.
- `frontend/src/app/services/temperature.service.spec.ts` – Test für `getCurrent()`.
- `frontend/src/app/pages/dashboard/dashboard.component.ts` – Laden + View-Model.
- `frontend/src/app/pages/dashboard/dashboard.component.html` – Kachelinhalt.
- `frontend/src/app/pages/dashboard/dashboard.component.scss` – `lumina__climate-*`-Klassen.

**Hinweis Build/Test:** Backend-Tests brauchen JDK 21 (`JAVA_HOME` auf jdk-21 setzen, siehe Projekt-Memory). Die vorhandenen `TemperatureSeriesServiceTest` sind reine Mockito-Unit-Tests ohne DB und laufen damit lokal durch.

---

## Task 1: DTO `CurrentTemperatureReading` (Backend)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/CurrentTemperatureReading.java`

- [ ] **Step 1: DTO anlegen**

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Aktueller (jüngster) Messwert eines Temperatursensors. */
@Getter
@Builder
public class CurrentTemperatureReading {
    /** Stabile, quellenpräfixierte ID, z. B. "zigbee:12". */
    private final String sensorId;
    /** Anzeigename des Sensors bzw. "Außen". */
    private final String name;
    /** Quelle: ZIGBEE | WEATHER | ALEXA. */
    private final String source;
    /** Jüngste Temperatur. */
    private final BigDecimal temperature;
    /** Jüngste Feuchte (null, wenn nicht vorhanden). */
    private final BigDecimal humidity;
    /** Zeitpunkt der Messung. */
    private final LocalDateTime measuredAt;
}
```

- [ ] **Step 2: Kompiliert prüfen**

Run: `cd backend && mvn -q -o compile`
Expected: BUILD SUCCESS (bzw. keine Fehler zu dieser Datei).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/CurrentTemperatureReading.java
git commit -m "feat(temperatures): DTO fuer aktuellen Sensorwert"
```

---

## Task 2: Repository-Queries für jüngste Werte (Backend)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java`
- Modify: `backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java`

- [ ] **Step 1: Zigbee-Queries ergänzen**

In `ZigbeeMeasurementRepository` den Import ergänzen und zwei Methoden hinzufügen. Neuer Import direkt nach den bestehenden imports:

```java
import java.util.Optional;
```

Methoden innerhalb des Interface (vor der schließenden Klammer) hinzufügen:

```java
    /** Alle Geräte, die jemals einen Messwert des Typs geliefert haben. */
    @Query("select distinct m.device from ZigbeeMeasurement m where m.measurementType = :type")
    List<ZigbeeDevice> findDistinctDevicesByMeasurementType(@Param("type") MeasurementType type);

    /** Jüngster Messwert eines Geräts für einen Messtyp. */
    Optional<ZigbeeMeasurement> findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
            Long deviceId, MeasurementType measurementType);
```

- [ ] **Step 2: Weather-Query ergänzen**

In `WeatherReadingRepository` den Import ergänzen:

```java
import java.util.Optional;
```

Methode innerhalb des Interface hinzufügen:

```java
    /** Jüngste Wettermessung, die eine Temperatur gesetzt hat. */
    Optional<WeatherReading> findTopByTemperatureIsNotNullOrderByReadingTimeDesc();
```

- [ ] **Step 3: Kompiliert prüfen**

Run: `cd backend && mvn -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java
git commit -m "feat(temperatures): Repository-Queries fuer juengste Werte"
```

---

## Task 3: Service-Methode `getCurrent()` (Backend, TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`
- Test: `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

Am Ende von `TemperatureSeriesServiceTest` (vor der schließenden Klasse) ergänzen. Zusätzlicher Import oben in der Datei:

```java
import com.household.manager.dto.CurrentTemperatureReading;
import java.util.Optional;
```

Testmethoden:

```java
    @Test
    void currentReturnsLatestPerZigbeeDevice() {
        LocalDateTime now = LocalDateTime.now();
        ZigbeeDevice wohnzimmer = device(1L, "Wohnzimmer");
        when(zigbeeRepository.findDistinctDevicesByMeasurementType(MeasurementType.TEMPERATURE))
                .thenReturn(List.of(wohnzimmer));
        when(zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                1L, MeasurementType.TEMPERATURE))
                .thenReturn(Optional.of(measurement(MeasurementType.TEMPERATURE, "21.5", now)));
        when(zigbeeRepository.findTopByDeviceIdAndMeasurementTypeOrderByMeasuredAtDesc(
                1L, MeasurementType.HUMIDITY))
                .thenReturn(Optional.of(measurement(MeasurementType.HUMIDITY, "48", now)));
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        when(alexaRepository.findDistinctApplianceIds()).thenReturn(List.of());

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
        when(zigbeeRepository.findDistinctDevicesByMeasurementType(any())).thenReturn(List.of());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.of(reading));
        when(alexaRepository.findDistinctApplianceIds()).thenReturn(List.of());

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
        when(zigbeeRepository.findDistinctDevicesByMeasurementType(any())).thenReturn(List.of());
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        when(alexaRepository.findDistinctApplianceIds()).thenReturn(List.of("APP-A"));
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
        when(zigbeeRepository.findDistinctDevicesByMeasurementType(any()))
                .thenThrow(new RuntimeException("db down"));
        when(weatherRepository.findTopByTemperatureIsNotNullOrderByReadingTimeDesc())
                .thenReturn(Optional.empty());
        lenient().when(alexaRepository.findDistinctApplianceIds()).thenReturn(List.of());

        List<CurrentTemperatureReading> result = service.getCurrent();

        assertThat(result).isEmpty();
    }
```

- [ ] **Step 2: Tests laufen lassen – müssen fehlschlagen**

Run: `cd backend && mvn -q -o test -Dtest=TemperatureSeriesServiceTest`
Expected: Kompilierfehler bzw. FAIL, weil `service.getCurrent()` noch nicht existiert.

- [ ] **Step 3: `getCurrent()` implementieren**

In `TemperatureSeriesService`: zunächst die bestehende `safe`-Methode generisch machen (Signatur ändern von `List<TemperatureSensorSeries>` auf `<T>`):

```java
    private <T> List<T> safe(String source, Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        } catch (Exception ex) {
            log.warn("Temperatur-Quelle '{}' fehlgeschlagen: {}", source, ex.getMessage(), ex);
            return List.of();
        }
    }
```

Zusätzlichen Import ergänzen:

```java
import com.household.manager.dto.CurrentTemperatureReading;
import java.util.Optional;
```

Danach die neue öffentliche Methode plus drei private Helfer hinzufügen (z. B. direkt nach `getSeries(...)`):

```java
    public List<CurrentTemperatureReading> getCurrent() {
        List<CurrentTemperatureReading> result = new ArrayList<>();
        result.addAll(safe("zigbee", this::zigbeeCurrent));
        result.addAll(safe("weather", this::weatherCurrent));
        result.addAll(safe("alexa", this::alexaCurrent));
        return result;
    }

    private List<CurrentTemperatureReading> zigbeeCurrent() {
        List<ZigbeeDevice> devices = zigbeeRepository
                .findDistinctDevicesByMeasurementType(MeasurementType.TEMPERATURE)
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
                        .name(device.getFriendlyName())
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
                        .name("Außen")
                        .source("WEATHER")
                        .temperature(r.getTemperature())
                        .humidity(r.getHumidity() != null ? BigDecimal.valueOf(r.getHumidity()) : null)
                        .measuredAt(r.getReadingTime())
                        .build())
                .map(List::of)
                .orElseGet(List::of);
    }

    private List<CurrentTemperatureReading> alexaCurrent() {
        List<CurrentTemperatureReading> result = new ArrayList<>();
        for (String applianceId : alexaRepository.findDistinctApplianceIds()) {
            alexaRepository.findTopByApplianceIdOrderByReadingTimeDesc(applianceId)
                    .filter(r -> r.getTemperature() != null)
                    .ifPresent(r -> result.add(CurrentTemperatureReading.builder()
                            .sensorId("alexa:" + applianceId)
                            .name(r.getDeviceName() != null ? r.getDeviceName() : applianceId)
                            .source("ALEXA")
                            .temperature(r.getTemperature())
                            .humidity(r.getHumidity())
                            .measuredAt(r.getReadingTime())
                            .build()));
        }
        return result;
    }
```

- [ ] **Step 4: Tests laufen lassen – müssen bestehen**

Run: `cd backend && mvn -q -o test -Dtest=TemperatureSeriesServiceTest`
Expected: PASS (alle bisherigen + 4 neuen Tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java
git commit -m "feat(temperatures): getCurrent liefert juengste Werte je Sensor"
```

---

## Task 4: Endpoint `/current` (Backend)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/TemperatureController.java`

- [ ] **Step 1: Endpoint hinzufügen**

Import ergänzen:

```java
import com.household.manager.dto.CurrentTemperatureReading;
```

Methode innerhalb der Klasse (nach `getTemperatures`) hinzufügen:

```java
    @GetMapping("/current")
    public List<CurrentTemperatureReading> getCurrentTemperatures() {
        return temperatureSeriesService.getCurrent();
    }
```

- [ ] **Step 2: Kompiliert prüfen**

Run: `cd backend && mvn -q -o compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/TemperatureController.java
git commit -m "feat(temperatures): Endpoint GET /api/v1/temperatures/current"
```

---

## Task 5: Frontend-Model & Service (TDD)

**Files:**
- Modify: `frontend/src/app/models/temperature.model.ts`
- Modify: `frontend/src/app/services/temperature.service.ts`
- Test: `frontend/src/app/services/temperature.service.spec.ts`

- [ ] **Step 1: Model-Interface ergänzen**

Am Ende von `temperature.model.ts` hinzufügen:

```typescript
/** Aktueller (jüngster) Wert eines Temperatursensors. */
export interface CurrentTemperatureReading {
  sensorId: string;
  name: string;
  source: TemperatureSource;
  temperature: number;
  humidity?: number;
  /** ISO-Zeitstempel der Messung. */
  measuredAt: string;
}
```

- [ ] **Step 2: Failing Test schreiben**

In `temperature.service.spec.ts` den Import erweitern:

```typescript
import { TemperatureSensorSeries, CurrentTemperatureReading } from '../models/temperature.model';
```

Test innerhalb des `describe`-Blocks (vor der schließenden Klammer) hinzufügen:

```typescript
  it('requests current readings', () => {
    const current: CurrentTemperatureReading[] = [];
    service.getCurrent().subscribe(result => expect(result).toEqual(current));

    const req = httpMock.expectOne('/api/v1/temperatures/current');
    expect(req.request.method).toBe('GET');
    req.flush(current);
  });
```

- [ ] **Step 3: Test laufen lassen – muss fehlschlagen**

Run: `cd frontend && npx ng test --watch=false --include='**/temperature.service.spec.ts'`
Expected: FAIL, weil `getCurrent` nicht existiert (Kompilierfehler).

- [ ] **Step 4: `getCurrent()` implementieren**

In `temperature.service.ts` den Import erweitern:

```typescript
import { TemperatureSensorSeries, TimeRange, CurrentTemperatureReading } from '../models/temperature.model';
```

Methode innerhalb der Klasse (nach `getSeries`) hinzufügen:

```typescript
  getCurrent(): Observable<CurrentTemperatureReading[]> {
    return this.http.get<CurrentTemperatureReading[]>(`${this.baseUrl}/current`).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 5: Test laufen lassen – muss bestehen**

Run: `cd frontend && npx ng test --watch=false --include='**/temperature.service.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/temperature.model.ts frontend/src/app/services/temperature.service.ts frontend/src/app/services/temperature.service.spec.ts
git commit -m "feat(temperatures): Frontend-Service getCurrent"
```

---

## Task 6: Komfort-Util + View-Model (Frontend, TDD)

**Files:**
- Create: `frontend/src/app/shared/temperature-comfort.util.ts`
- Test: `frontend/src/app/shared/temperature-comfort.util.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

`temperature-comfort.util.spec.ts` anlegen:

```typescript
import { comfortRating, buildClimateView } from './temperature-comfort.util';
import { CurrentTemperatureReading } from '../models/temperature.model';

describe('comfortRating', () => {
  it('bewertet unter 19 Grad als frisch', () => {
    expect(comfortRating(18.9)).toEqual({ label: 'frisch', tone: 'cool' });
  });

  it('bewertet 19 bis unter 23 Grad als angenehm', () => {
    expect(comfortRating(19).tone).toBe('comfortable');
    expect(comfortRating(22.9).tone).toBe('comfortable');
  });

  it('bewertet 23 bis 25 Grad als warm', () => {
    expect(comfortRating(23).tone).toBe('warm');
    expect(comfortRating(25).tone).toBe('warm');
  });

  it('bewertet ueber 25 Grad als heiss', () => {
    expect(comfortRating(25.1)).toEqual({ label: 'heiß', tone: 'hot' });
  });
});

describe('buildClimateView', () => {
  const now = new Date('2026-07-15T12:00:00Z').getTime();

  function reading(partial: Partial<CurrentTemperatureReading>): CurrentTemperatureReading {
    return {
      sensorId: 's', name: 'Raum', source: 'ZIGBEE',
      temperature: 21, measuredAt: '2026-07-15T11:59:00Z', ...partial
    };
  }

  it('trennt Aussen von Innensensoren', () => {
    const view = buildClimateView([
      reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 12.4 }),
      reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer', temperature: 21.4 })
    ], now);

    expect(view.outsideLabel).toBe('12°');
    expect(view.rows.length).toBe(1);
    expect(view.rows[0].name).toBe('Wohnzimmer');
    expect(view.rows[0].valueLabel).toBe('21,4°');
    expect(view.rows[0].statusLabel).toBe('angenehm');
    expect(view.rows[0].stale).toBe(false);
  });

  it('zeigt -- wenn keine Aussenquelle vorliegt', () => {
    const view = buildClimateView([reading({ sensorId: 'zigbee:1' })], now);
    expect(view.outsideLabel).toBe('--');
  });

  it('markiert veraltete Messungen', () => {
    const view = buildClimateView([
      reading({ sensorId: 'zigbee:1', name: 'Bad', measuredAt: '2026-07-15T09:00:00Z' })
    ], now);

    expect(view.rows[0].stale).toBe(true);
    expect(view.rows[0].statusLabel).toBe('veraltet');
    expect(view.rows[0].tone).toBe('stale');
  });
});
```

- [ ] **Step 2: Test laufen lassen – muss fehlschlagen**

Run: `cd frontend && npx ng test --watch=false --include='**/temperature-comfort.util.spec.ts'`
Expected: FAIL (Modul existiert nicht).

- [ ] **Step 3: Util implementieren**

`temperature-comfort.util.ts` anlegen:

```typescript
import { CurrentTemperatureReading } from '../models/temperature.model';

/** Komfort-Ton einer Innentemperatur (steuert Farbe + Label). */
export type ComfortTone = 'cool' | 'comfortable' | 'warm' | 'hot';

/** Ton einer Kachelzeile inkl. Sonderfall "veraltet". */
export type ClimateTone = ComfortTone | 'stale';

export interface ComfortRating {
  label: string;
  tone: ComfortTone;
}

/** Eine Innensensor-Zeile der Kachel. */
export interface TemperatureRow {
  name: string;
  /** Temperatur, z. B. "21,4°". */
  valueLabel: string;
  /** Komfort-Wort oder "veraltet". */
  statusLabel: string;
  tone: ClimateTone;
  stale: boolean;
}

/** Aufbereitetes View-Model der Klima-Kachel. */
export interface ClimateView {
  /** Außentemperatur als Referenz, z. B. "12°" oder "--". */
  outsideLabel: string;
  rows: TemperatureRow[];
}

/** Messung gilt als veraltet, wenn älter als diese Schwelle. */
const STALE_THRESHOLD_MS = 60 * 60 * 1000;

/** Bildet eine Innentemperatur auf ein Komfortband ab. */
export function comfortRating(celsius: number): ComfortRating {
  if (celsius < 19) {
    return { label: 'frisch', tone: 'cool' };
  }
  if (celsius < 23) {
    return { label: 'angenehm', tone: 'comfortable' };
  }
  if (celsius <= 25) {
    return { label: 'warm', tone: 'warm' };
  }
  return { label: 'heiß', tone: 'hot' };
}

/** Trennt Außen von Innensensoren und baut die Kachelzeilen. */
export function buildClimateView(
  readings: CurrentTemperatureReading[],
  nowMs: number
): ClimateView {
  const outside = readings.find(r => r.source === 'WEATHER');
  const outsideLabel = outside ? formatCelsius(Math.round(outside.temperature), 0) : '--';

  const rows = readings
    .filter(r => r.source !== 'WEATHER')
    .map(r => toRow(r, nowMs));

  return { outsideLabel, rows };
}

function toRow(reading: CurrentTemperatureReading, nowMs: number): TemperatureRow {
  const stale = nowMs - new Date(reading.measuredAt).getTime() > STALE_THRESHOLD_MS;
  const comfort = comfortRating(reading.temperature);
  return {
    name: reading.name,
    valueLabel: formatCelsius(reading.temperature, 1),
    statusLabel: stale ? 'veraltet' : comfort.label,
    tone: stale ? 'stale' : comfort.tone,
    stale
  };
}

function formatCelsius(value: number, fractionDigits: number): string {
  return `${value.toLocaleString('de-DE', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits
  })}°`;
}
```

- [ ] **Step 4: Test laufen lassen – muss bestehen**

Run: `cd frontend && npx ng test --watch=false --include='**/temperature-comfort.util.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/temperature-comfort.util.ts frontend/src/app/shared/temperature-comfort.util.spec.ts
git commit -m "feat(dashboard): Komfort-Util und Klima-View-Model"
```

---

## Task 7: Dashboard-Komponente – Laden & View-Model (Frontend)

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`

- [ ] **Step 1: Imports & Service ergänzen**

Oben in `dashboard.component.ts` die rxjs-Operatoren erweitern und neue Imports ergänzen:

```typescript
import { Subscription, interval, startWith, switchMap } from 'rxjs';
```

Nach den bestehenden Model-/Service-Imports:

```typescript
import { TemperatureService } from '../../services/temperature.service';
import { CurrentTemperatureReading } from '../../models/temperature.model';
import { ClimateView, buildClimateView } from '../../shared/temperature-comfort.util';
```

- [ ] **Step 2: Feld, Konstante & Injection ergänzen**

Bei den `inject(...)`-Zeilen ergänzen:

```typescript
  private readonly temperatureService = inject(TemperatureService);
```

Bei den Subscription-Feldern ergänzen:

```typescript
  private temperatureSubscription?: Subscription;
```

Bei den statischen Konstanten (nach `GRID_MAX_WATT`) ergänzen:

```typescript
  /** Aktualisierungsintervall der Klima-Kachel (60 s). */
  private static readonly CLIMATE_REFRESH_MS = 60000;
```

Zustandsfeld (z. B. nach `liveStatus`) ergänzen:

```typescript
  climate: ClimateView = { outsideLabel: '--', rows: [] };
```

- [ ] **Step 3: Laden in Lebenszyklus einhängen**

In `ngOnInit()` ergänzen:

```typescript
    this.startClimateRefresh();
```

In `ngOnDestroy()` ergänzen:

```typescript
    this.temperatureSubscription?.unsubscribe();
```

Neue private Methode (z. B. nach `startLiveStream()`):

```typescript
  private startClimateRefresh(): void {
    this.temperatureSubscription = interval(DashboardComponent.CLIMATE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.temperatureService.getCurrent())
      )
      .subscribe({
        next: (readings: CurrentTemperatureReading[]) =>
          (this.climate = buildClimateView(readings, Date.now())),
        error: () => (this.climate = { outsideLabel: '--', rows: [] })
      });
  }
```

- [ ] **Step 4: Kompiliert prüfen**

Run: `cd frontend && npx ng build --configuration development`
Expected: Build erfolgreich (die Kachel im Template wird erst in Task 8 umgebaut; `climate` ist bereits gültig).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts
git commit -m "feat(dashboard): aktuelle Temperaturen laden und aufbereiten"
```

---

## Task 8: Dashboard-Kachel – Template & Styles (Frontend)

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html:41-80`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

- [ ] **Step 1: Kachel-Markup ersetzen**

In `dashboard.component.html` den kompletten `<a ... class="... lumina__energy-tile ...">…</a>`-Block (aktuell Zeilen 41–80) durch folgenden ersetzen:

```html
        <!-- Klima-Kachel: Innentemperaturen vs. Außen -->
        <a
          routerLink="/temperatures"
          class="lumina-card lumina__room lumina__climate-tile lumina__fade"
          style="--delay: 0.1s"
        >
          <div class="lumina__room-top">
            <div class="lumina__room-icon">
              <span class="material-symbols-outlined">device_thermostat</span>
            </div>
            <span class="lumina__climate-outside">
              <span class="material-symbols-outlined lumina__climate-outside-icon">cloud</span>
              <span class="lumina__climate-outside-label">Außen</span>
              <span class="lumina__climate-outside-value">{{ climate.outsideLabel }}</span>
            </span>
          </div>
          <div class="lumina__room-body">
            <h3 class="lumina__room-name">Temperaturen</h3>
            <div class="lumina__climate-rows">
              <p *ngIf="climate.rows.length === 0" class="lumina__climate-empty">
                Keine Innensensoren verfügbar
              </p>
              <div
                *ngFor="let row of climate.rows"
                class="lumina__climate-row"
                [class.lumina__climate-row--stale]="row.stale"
              >
                <span class="lumina__climate-dot" [ngClass]="'lumina__climate-dot--' + row.tone"></span>
                <span class="lumina__climate-name">{{ row.name }}</span>
                <span class="lumina__climate-status">{{ row.statusLabel }}</span>
                <span class="lumina__climate-value">{{ row.valueLabel }}</span>
              </div>
            </div>
          </div>
        </a>
```

- [ ] **Step 2: Alte Energie-Kachel-Styles durch Klima-Styles ersetzen**

In `dashboard.component.scss` den Block von `.lumina__energy-tile { … }` bis zum Ende von `.lumina__energy-metric-value { … }` (aktuell Zeilen 249–299, Kommentar `// ---- Energie-Kachel (Live-Werte)`) durch folgenden ersetzen:

```scss
// ---- Klima-Kachel (Innentemperaturen vs. Aussen) -------------------------
.lumina__climate-tile {
  .lumina__room-icon {
    background: rgba(83, 225, 111, 0.1);
    color: var(--secondary);
  }

  &:hover .lumina__room-icon {
    background: var(--secondary);
    color: var(--on-secondary);
  }
}

.lumina__climate-outside {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.05);
  color: rgba(192, 198, 214, 0.7);
  font-size: 13px;
}

.lumina__climate-outside-icon {
  font-size: 16px;
}

.lumina__climate-outside-value {
  font-family: 'Space Grotesk', 'Geist', sans-serif;
  font-weight: 600;
  color: var(--on-surface);
}

.lumina__climate-rows {
  display: flex;
  flex-direction: column;
  margin-top: 14px;
}

.lumina__climate-empty {
  margin: 6px 0 0;
  font-size: 14px;
  color: rgba(192, 198, 214, 0.5);
}

.lumina__climate-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 11px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.06);

  &--stale {
    opacity: 0.45;
  }
}

.lumina__climate-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
  align-self: center;

  &--cool { background: #60a5fa; }
  &--comfortable { background: var(--secondary); }
  &--warm { background: #fbbf24; }
  &--hot { background: var(--error); }
  &--stale { background: rgba(192, 198, 214, 0.4); }
}

.lumina__climate-name {
  font-size: 15px;
  color: rgba(192, 198, 214, 0.85);
}

.lumina__climate-status {
  margin-left: auto;
  font-size: 12px;
  color: rgba(192, 198, 214, 0.5);
}

.lumina__climate-value {
  font-family: 'Space Grotesk', 'Geist', sans-serif;
  font-size: 18px;
  font-weight: 600;
  color: var(--on-surface);
  min-width: 60px;
  text-align: right;
  white-space: nowrap;
}
```

- [ ] **Step 3: Build prüfen**

Run: `cd frontend && npx ng build --configuration development`
Expected: Build erfolgreich, keine Referenzen mehr auf entfernte `lumina__energy-metric*`-Klassen im Dashboard-Template.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.scss
git commit -m "feat(dashboard): Klima-Kachel ersetzt Energie-Kachel"
```

---

## Task 9: Gesamtverifikation

- [ ] **Step 1: Backend-Tests grün**

Run: `cd backend && mvn -q -o test -Dtest=TemperatureSeriesServiceTest`
Expected: PASS.

- [ ] **Step 2: Frontend-Tests grün**

Run: `cd frontend && npx ng test --watch=false --include='**/temperature*.spec.ts'`
Expected: PASS (Service- und Util-Specs).

- [ ] **Step 3: Manuelle Sichtprüfung**

Backend (`mvn spring-boot:run`) und Frontend (`npm start`) starten, Dashboard in Tablet-Ansicht öffnen. Erwartung: Die Kachel „Temperaturen" zeigt oben rechts den Außenwert-Chip, darunter je Innensensor eine Zeile mit farbigem Komfort-Punkt, Name, Komfort-Wort und Temperatur; ein Klick öffnet `/temperatures`. Ohne Innensensoren erscheint „Keine Innensensoren verfügbar". (Verifikations-Skill `verify` optional nutzen.)

---

## Notizen

- Komfort-Schwellen (19 / 23 / 25 °C) und die Veraltet-Grenze (1 h) sind bewusst als Konstanten (`comfortRating`, `STALE_THRESHOLD_MS` in `temperature-comfort.util.ts`) gehalten und leicht anpassbar.
- Der Live-Energie-Stream und die Energiefluss-Gauge-Karte im Seitenbereich bleiben unverändert erhalten.
