# Tablet-Ansicht "Luftqualität" — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine zweite Wandtablet-Unteransicht unter `/tablet/air-quality`, die die Luftqualitätswerte des Airrohr-Sensors (draußen) und der Amazon Smart Air Quality Monitore (drinnen) als Kachelraster ohne Scrollen zeigt.

**Architecture:** Neuer Serien-Endpunkt `GET /v1/air-quality/series?range=` liefert je Sensor eine Map aus Metrik-Schlüssel auf Zeitreihe, serverseitig auf Buckets gemittelt. Das Frontend baut daraus ein Kachelraster in der bestehenden `app-tablet-shell`; ein Umschalter wählt genau eine Messgrößen-Gruppe, damit jede Kachel immer genau eine Y-Achse hat. Zwei bereits quellen-agnostische Backend-Bausteine (`TemperatureRange`, `TemperatureSeriesDownsampler`) werden vorab umbenannt statt kopiert.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Lombok / JUnit 5 + Mockito + AssertJ; Angular 19 standalone / SCSS / ngx-echarts / Karma + Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-22-tablet-luftqualitaet-design.md`

**Build-Kommandos dieser Maschine:**

* Backend (aus `backend/`): erst `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`, dann `mvn ...`. Es gibt keinen `mvnw`-Wrapper.
  Die Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern auf dieser Maschine immer an "Access denied for user 'root'@'localhost'" (keine Test-DB). Das ist die Baseline, kein Fehler dieser Arbeit.
* Frontend (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless`.
  Baseline: genau 3 vorbestehende Fails (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Gelegentlich flaked `SmartDeviceListComponent` in `afterAll` — bei Verdacht erneut laufen lassen. Nur *zusätzliche* Fails sind Regressionen.

---

## Dateiübersicht

**Backend — neu**

| Datei | Verantwortung |
|---|---|
| `backend/src/main/java/com/household/manager/dto/AirQualitySensorSeries.java` | Antwort-DTO: eine Sensorzeitreihe mit Metrik-Map |
| `backend/src/main/java/com/household/manager/service/AirQualitySeriesService.java` | Sammelt Airrohr- und Alexa-Reihen, mittelt, isoliert Quellenfehler |
| `backend/src/test/java/com/household/manager/service/AirQualitySeriesServiceTest.java` | Unit-Test des Service gegen Mock-Repositories |

**Backend — geändert**

| Datei | Änderung |
|---|---|
| `service/TemperatureRange.java` → `service/SeriesRange.java` | Umbenennung (Inhalt unverändert) |
| `service/TemperatureSeriesDownsampler.java` → `service/SeriesDownsampler.java` | Umbenennung (Inhalt unverändert) |
| `service/TemperatureSeriesService.java` | zieht die beiden neuen Namen nach |
| `controller/TemperatureController.java` | zieht `SeriesRange` nach |
| `controller/AirQualityController.java` | neue Methode `getSeries` |
| `repository/AirrohrReadingRepository.java` | neue Zeitfenster-Abfrage |
| `src/test/.../service/TemperatureSeriesDownsamplerTest.java` → `SeriesDownsamplerTest.java` | Umbenennung |
| `src/test/.../service/TemperatureSeriesServiceTest.java` | zieht die neuen Namen nach |
| `src/test/.../security/SecurityRulesTest.java` | neuer Test: KIOSK darf den Serien-Endpunkt lesen |

**Frontend — neu**

| Datei | Verantwortung |
|---|---|
| `frontend/src/app/models/air-quality-series.model.ts` | Typen der Serienantwort + Gruppendefinition |
| `frontend/src/app/services/air-quality-series.service.ts` | HTTP-Zugriff auf den Serien-Endpunkt |
| `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.ts` | Kachelraster, Gruppen-/Zeitraumwahl, Selbst-Refresh |
| `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.html` | Template |
| `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.scss` | Styles inkl. Flex-Höhenkette |
| `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.spec.ts` | Komponententest |

**Frontend — geändert**

| Datei | Änderung |
|---|---|
| `frontend/src/app/shared/tablet-views.ts` | zweiter Eintrag in `TABLET_VIEWS` |
| `frontend/src/app/app.routes.ts` | Route `tablet/air-quality` |

**Dokumentation**

| Datei | Änderung |
|---|---|
| `CLAUDE.md` | Abschnitt "Tablet-Ansichten" um die Luftqualitätsansicht ergänzen |

---

## Task 1: Quellen-agnostische Bausteine umbenennen

Reine Umbenennung ohne Verhaltensänderung. Sie steht am Anfang, damit der neue Service die vorhandenen Bausteine benutzen kann, statt eine zweite Kopie mit eigenem Namen anzulegen.

**Files:**
- Rename: `backend/src/main/java/com/household/manager/service/TemperatureRange.java` → `SeriesRange.java`
- Rename: `backend/src/main/java/com/household/manager/service/TemperatureSeriesDownsampler.java` → `SeriesDownsampler.java`
- Rename: `backend/src/test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java` → `SeriesDownsamplerTest.java`
- Modify: `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`
- Modify: `backend/src/main/java/com/household/manager/controller/TemperatureController.java`
- Modify: `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`

- [ ] **Step 1: Bestehende Tests als grüne Ausgangslage laufen lassen**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q test -Dtest='TemperatureSeriesServiceTest,TemperatureSeriesDownsamplerTest'
```

Erwartet: BUILD SUCCESS, beide Testklassen grün.

- [ ] **Step 2: Dateien umbenennen**

```bash
cd backend/src
git mv main/java/com/household/manager/service/TemperatureRange.java main/java/com/household/manager/service/SeriesRange.java
git mv main/java/com/household/manager/service/TemperatureSeriesDownsampler.java main/java/com/household/manager/service/SeriesDownsampler.java
git mv test/java/com/household/manager/service/TemperatureSeriesDownsamplerTest.java test/java/com/household/manager/service/SeriesDownsamplerTest.java
```

- [ ] **Step 3: Bezeichner in allen sechs Dateien nachziehen**

```bash
cd backend/src
grep -rl "TemperatureSeriesDownsampler\|TemperatureRange" main test | xargs sed -i 's/TemperatureSeriesDownsamplerTest/SeriesDownsamplerTest/g; s/TemperatureSeriesDownsampler/SeriesDownsampler/g; s/TemperatureRange/SeriesRange/g'
```

Danach in `SeriesRange.java` den Klassenkommentar anpassen — er nennt noch ausdrücklich die Temperaturgraphen:

```java
/** Auswählbarer Zeitraum einer Messreihe inkl. der dazu passenden Mittelungs-Bucketlänge. */
```

und in `SeriesDownsampler.java` den Satz über die Quellen-Agnostik ergänzen:

```java
 * <p>Bewusst quellen-agnostisch: die Klasse kennt weder Zigbee noch Wetter noch Alexa
 * noch Airrohr und ist dadurch ohne Datenbank testbar. Sie bedient sowohl die
 * Temperatur- als auch die Luftqualitätsreihen.
```

Prüfen, dass kein alter Name übrig ist:

```bash
grep -rn "TemperatureRange\|TemperatureSeriesDownsampler" backend/src
```

Erwartet: keine Ausgabe.

- [ ] **Step 4: Tests erneut laufen lassen**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q test -Dtest='TemperatureSeriesServiceTest,SeriesDownsamplerTest'
```

Erwartet: BUILD SUCCESS — dieselben Tests wie in Step 1, nur unter neuen Namen.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "refactor(backend): Serien-Bausteine quellenneutral benennen"
```

---

## Task 2: Zeitfenster-Abfrage für Airrohr-Messwerte

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/AirrohrReadingRepository.java`

- [ ] **Step 1: Methode ergänzen**

Die Datei deklariert bisher nur `extends JpaRepository<AirrohrReading, Long>`. Innerhalb des Interfaces ergänzen (Imports `java.time.LocalDateTime` und `java.util.List` prüfen bzw. hinzufügen):

```java
    /**
     * Messwerte eines Zeitfensters, aufsteigend. Der Serien-Endpunkt fragt bewusst ein
     * Fenster ab statt die komplette Historie: die Tabelle waechst unbegrenzt, und das
     * Wandtablet ruft alle fuenf Minuten neu ab.
     */
    List<AirrohrReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);
```

- [ ] **Step 2: Kompilieren**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q -DskipTests compile
```

Erwartet: BUILD SUCCESS. (Spring Data prüft den Methodennamen erst beim Kontextstart; der Name folgt exakt dem bereits vorhandenen Vorbild in `AlexaAirQualityReadingRepository`.)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/repository/AirrohrReadingRepository.java
git commit -m "feat(backend): Zeitfenster-Abfrage fuer Airrohr-Messwerte"
```

---

## Task 3: Antwort-DTO `AirQualitySensorSeries`

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/AirQualitySensorSeries.java`

- [ ] **Step 1: DTO anlegen**

```java
package com.household.manager.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Luftqualitaets-Zeitreihen genau eines Sensors.
 *
 * <p>Die Messgroessen stehen in einer Map statt in festen Feldern, weil die Quellen
 * disjunkte Mengen liefern: der Airrohr-Sensor kennt kein IAQ, die Amazon-Monitore
 * kein PM10. Feste Felder waeren fuer die Mehrzahl der Kombinationen dauerhaft leer,
 * und jede weitere Messgroesse erzwaenge eine Vertragsaenderung.
 *
 * <p>Eine Groesse ohne Werte fehlt in der Map, statt als leere Liste zu erscheinen.
 */
@Getter
@Builder
public class AirQualitySensorSeries {

    /** Stabile, quellenpraefixierte ID: "airrohr:local" oder "alexa:&lt;applianceId&gt;". */
    private final String sensorId;

    /** Anzeigename des Sensors. */
    private final String name;

    /** Quelle: AIRROHR | ALEXA. */
    private final String source;

    /** Messgroessen-Schluessel ("pm25", "pm10", "iaq", "voc", "co") auf Zeitreihe. */
    private final Map<String, List<TimeValue>> metrics;
}
```

- [ ] **Step 2: Kompilieren**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q -DskipTests compile
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/AirQualitySensorSeries.java
git commit -m "feat(backend): DTO fuer Luftqualitaets-Zeitreihen"
```

---

## Task 4: `AirQualitySeriesService` (TDD)

**Files:**
- Create: `backend/src/test/java/com/household/manager/service/AirQualitySeriesServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/service/AirQualitySeriesService.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

Der Downsampler wird **nicht** gemockt, sondern echt eingesetzt (`new SeriesDownsampler()`): er ist reine Arithmetik ohne Abhängigkeiten, und der Test soll die Mittelung tatsächlich prüfen.

```java
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
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q test -Dtest='AirQualitySeriesServiceTest'
```

Erwartet: Compilerfehler "cannot find symbol: class AirQualitySeriesService".

- [ ] **Step 3: Service implementieren**

```java
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
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q test -Dtest='AirQualitySeriesServiceTest'
```

Erwartet: BUILD SUCCESS, 6 Tests grün.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/AirQualitySeriesService.java backend/src/test/java/com/household/manager/service/AirQualitySeriesServiceTest.java
git commit -m "feat(backend): Luftqualitaets-Zeitreihen aus Airrohr und Amazon-Monitoren"
```

---

## Task 5: Endpunkt und Sicherheitsregel

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/AirQualityController.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Sicherheitstest schreiben**

`AirQualityController` steht **nicht** im `@WebMvcTest`-Slice dieser Testklasse. Für Pfade ohne Controller im Slice liefert eine erlaubte Rolle 404 statt 403 — genau dieses Muster nutzen die bestehenden Tests der Klasse, um zu belegen, dass die Autorisierungsregel durchlässt. Am Ende der Klasse ergänzen:

```java
    /**
     * Der Luftqualitaets-Serienendpunkt braucht bewusst keine eigene Regel: das GET faellt
     * auf die generische Regel GET /v1/** -> KIOSK. Ohne sie waere die Wandtablet-Ansicht
     * "Luftqualitaet" leer, und zwar ohne sichtbaren Fehler.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieLuftqualitaetsReihenLesen() throws Exception {
        mockMvc.perform(get("/v1/air-quality/series?range=WEEK")).andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Test laufen lassen**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn -q test -Dtest='SecurityRulesTest#kioskDarfDieLuftqualitaetsReihenLesen'
```

Erwartet: BUILD SUCCESS. Der Test ist von Anfang an grün — er sichert die bestehende Regel gegen eine spätere Umsortierung der Matcher ab, genau wie `kioskDarfDieNutzerlisteLesen`. Schlägt er mit 403 statt 404 fehl, ist die Matcher-Reihenfolge kaputt.

- [ ] **Step 3: Endpunkt ergänzen**

In `AirQualityController` die Imports um `AirQualitySensorSeries`, `AirQualitySeriesService`, `SeriesRange`, `RequestParam` und `java.util.List` erweitern, das Feld ergänzen und die Methode anfügen:

```java
    private final AirQualitySeriesService airQualitySeriesService;

    /**
     * Zeitreihen der eigenen Luftsensorik (Airrohr draussen, Amazon-Monitore drinnen),
     * serverseitig auf Buckets gemittelt. Speist die Wandtablet-Ansicht "Luftqualitaet".
     */
    @GetMapping("/series")
    public List<AirQualitySensorSeries> getSeries(
            @RequestParam(required = false, defaultValue = "WEEK") SeriesRange range) {
        return airQualitySeriesService.getSeries(range);
    }
```

- [ ] **Step 4: Backend-Testlauf**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn test
```

Erwartet: nur die bekannten Umgebungsfehler (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest` — "Access denied for user 'root'@'localhost'"). Keine weiteren Fails.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/AirQualityController.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(backend): Endpunkt fuer Luftqualitaets-Zeitreihen"
```

---

## Task 6: Frontend-Modell und -Service

**Files:**
- Create: `frontend/src/app/models/air-quality-series.model.ts`
- Create: `frontend/src/app/services/air-quality-series.service.ts`

- [ ] **Step 1: Modell anlegen**

`TimeRange` und `TimeValue` werden aus `temperature.model.ts` wiederverwendet — es sind dieselben Typen, und eine zweite Definition liefe irgendwann auseinander.

```typescript
import { TimeValue } from './temperature.model';

/** Quelle einer Luftqualitaets-Zeitreihe. */
export type AirQualitySource = 'AIRROHR' | 'ALEXA';

/** Schluessel einer einzelnen Messgroesse, wie ihn das Backend in der Map fuehrt. */
export type AirQualityMetricKey = 'pm25' | 'pm10' | 'iaq' | 'voc' | 'co';

/** Luftqualitaets-Zeitreihen genau eines Sensors. Fehlt eine Groesse, fehlt ihr Schluessel. */
export interface AirQualitySensorSeries {
  sensorId: string;
  name: string;
  source: AirQualitySource;
  metrics: Partial<Record<AirQualityMetricKey, TimeValue[]>>;
}

/** Eine Linie innerhalb einer Gruppe. */
export interface AirQualityMetricLine {
  key: AirQualityMetricKey;
  label: string;
  color: string;
}

/**
 * Eine waehlbare Messgroessen-Gruppe der Wandansicht.
 *
 * Gewaehlt wird immer genau EINE Gruppe. Die vier Messgroessen haben vier
 * verschiedene Einheiten; frei kombinierbar wie bei den Temperaturen ergaebe das
 * bis zu vier Y-Achsen in einer Wandkachel - unlesbar. Innerhalb einer Gruppe
 * teilen sich alle Linien eine Einheit und damit eine Achse.
 */
export interface AirQualityMetricGroup {
  key: string;
  label: string;
  /** Einheit der gemeinsamen Y-Achse; leer beim einheitenlosen IAQ-Score. */
  unit: string;
  lines: AirQualityMetricLine[];
}

export const AIR_QUALITY_GROUPS: readonly AirQualityMetricGroup[] = [
  {
    key: 'dust',
    label: 'Feinstaub',
    unit: 'µg/m³',
    lines: [
      { key: 'pm25', label: 'PM2.5', color: '#f59e0b' },
      { key: 'pm10', label: 'PM10', color: '#a855f7' }
    ]
  },
  { key: 'iaq', label: 'IAQ', unit: '', lines: [{ key: 'iaq', label: 'Luftqualität', color: '#22c55e' }] },
  { key: 'voc', label: 'VOC', unit: 'ppb', lines: [{ key: 'voc', label: 'VOC', color: '#38bdf8' }] },
  { key: 'co', label: 'CO', unit: 'ppm', lines: [{ key: 'co', label: 'CO', color: '#fb7185' }] }
];
```

- [ ] **Step 2: Service anlegen**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AirQualitySensorSeries } from '../models/air-quality-series.model';
import { TimeRange } from '../models/temperature.model';

/** REST-Service fuer die aggregierten Luftqualitaets-Zeitreihen. */
@Injectable({ providedIn: 'root' })
export class AirQualitySeriesService {
  private readonly http = inject(HttpClient);
  private readonly url = '/api/v1/air-quality/series';

  getSeries(range: TimeRange): Observable<AirQualitySensorSeries[]> {
    const params = new HttpParams().set('range', range);
    return this.http.get<AirQualitySensorSeries[]>(this.url, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Luftqualitaets-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Luftqualitätsdaten.'));
  }
}
```

- [ ] **Step 3: Kompilieren**

```bash
cd frontend
npx tsc --noEmit -p tsconfig.app.json
```

Erwartet: keine Ausgabe (fehlerfrei).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/air-quality-series.model.ts frontend/src/app/services/air-quality-series.service.ts
git commit -m "feat(frontend): Modell und Service fuer Luftqualitaets-Zeitreihen"
```

---

## Task 7: Tablet-Komponente (Test zuerst)

**Files:**
- Create: `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.spec.ts`
- Create: `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.ts`
- Create: `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.html`
- Create: `frontend/src/app/pages/tablet-air-quality/tablet-air-quality.component.scss`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletAirQualityComponent } from './tablet-air-quality.component';
import { AirQualitySeriesService } from '../../services/air-quality-series.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { AirQualitySensorSeries } from '../../models/air-quality-series.model';

describe('TabletAirQualityComponent', () => {
  let fixture: ComponentFixture<TabletAirQualityComponent>;
  let component: TabletAirQualityComponent;
  let serviceSpy: jasmine.SpyObj<AirQualitySeriesService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const outdoor: AirQualitySensorSeries = {
    sensorId: 'airrohr:local', name: 'Draußen', source: 'AIRROHR',
    metrics: {
      pm25: [{ time: '2026-08-22T10:00:00', value: 8 }],
      pm10: [{ time: '2026-08-22T10:00:00', value: 12 }]
    }
  };
  const indoor: AirQualitySensorSeries = {
    sensorId: 'alexa:appliance-1', name: 'Wohnzimmer', source: 'ALEXA',
    metrics: {
      pm25: [{ time: '2026-08-22T10:00:00', value: 3 }],
      iaq: [{ time: '2026-08-22T10:00:00', value: 72 }],
      voc: [{ time: '2026-08-22T10:00:00', value: 150 }],
      co: [{ time: '2026-08-22T10:00:00', value: 0.4 }]
    }
  };

  function sensors(count: number): AirQualitySensorSeries[] {
    return Array.from({ length: count }, (_, i) => ({
      ...indoor,
      sensorId: `alexa:${i}`,
      name: `Monitor ${i}`
    }));
  }

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('AirQualitySeriesService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([outdoor, indoor]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletAirQualityComponent],
      providers: [
        // Der Rahmen (app-tablet-shell) nutzt routerLink fuer die Ansichtsleiste
        // und zieht das Wetter fuer die Kopfzeile.
        provideRouter([]),
        { provide: AirQualitySeriesService, useValue: serviceSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletAirQualityComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('lädt beim Start den Standardzeitraum WEEK und baut eine Kachel je Sensor', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledWith('WEEK');
    expect(component.charts.length).toBe(2);
  });

  it('zeigt ab Werk die Feinstaubgruppe mit genau einer Achse', () => {
    expect(component.activeGroup.key).toBe('dust');

    const options = component.chartOptionsFor(outdoor) as {
      series: { name: string }[];
      yAxis: unknown[];
    };
    expect(options.series.map(serie => serie.name)).toEqual(['PM2.5', 'PM10']);
    expect(options.yAxis.length).toBe(1);
  });

  it('zeichnet in einer Gruppe nur die Linien, die der Sensor liefert', () => {
    // Der Amazon-Monitor kennt kein PM10.
    const options = component.chartOptionsFor(indoor) as { series: { name: string }[] };
    expect(options.series.map(serie => serie.name)).toEqual(['PM2.5']);
  });

  it('baut die Kacheln beim Gruppenwechsel ohne neuen Abruf neu', () => {
    serviceSpy.getSeries.calls.reset();
    const before = component.charts[0].options;

    component.setGroup('iaq');

    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
    expect(component.charts[0].options).not.toBe(before);
  });

  it('meldet eine Kachel als leer, wenn der Sensor zur Gruppe nichts liefert', () => {
    component.setGroup('iaq');

    // Der Airrohr-Sensor misst keinen IAQ - statt eines leeren Diagramms
    // zeigt die Kachel einen Hinweis.
    expect(component.charts[0].empty).toBeTrue();
    expect(component.charts[1].empty).toBeFalse();
  });

  it('stellt den juengsten Wert der Gruppe neben den Kachelnamen', () => {
    expect(component.charts[0].currentLabel).toBe('8 µg/m³');

    component.setGroup('co');
    expect(component.charts[1].currentLabel).toBe('0,4 ppm');
  });

  it('faerbt nur den IAQ-Wert nach seiner Stufe', () => {
    expect(component.charts[1].currentLevel).toBeNull();

    component.setGroup('iaq');
    expect(component.charts[1].currentLevel).toBe('good');
  });

  it('lädt bei einem Zeitraumwechsel genau einmal nach', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('DAY');
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('DAY');
    expect(component.activeRange).toBe('DAY');
  });

  it('lädt nicht nach, wenn der aktive Zeitraum erneut gewählt wird', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEK');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  it('behält bei einem fehlgeschlagenen Refresh die bisherigen Daten', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    component.reload();
    expect(component.charts.length).toBe(2);
    expect(component.errorMessage).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf scheitert', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    const freshFixture = TestBed.createComponent(TabletAirQualityComponent);
    freshFixture.detectChanges();
    expect(freshFixture.componentInstance.errorMessage).not.toBeNull();
    freshFixture.destroy();
  });

  it('nutzt zwei Spalten bis fünf Sensoren und drei ab sechs', () => {
    serviceSpy.getSeries.and.returnValue(of(sensors(5)));
    component.setRange('DAY');
    expect(component.columns).toBe(2);

    serviceSpy.getSeries.and.returnValue(of(sensors(6)));
    component.setRange('MONTH');
    expect(component.columns).toBe(3);
  });

  it('gibt den Graphen die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Der Elternknoten spielt die Flex-Spalte der App-Shell nach: nur wenn die
    // Kette vom Host bis zum Chart durchgehend ist, waechst der Graph mit dem
    // Bildschirm - sonst bleibt er auf Inhaltshoehe stehen.
    const host = fixture.nativeElement as HTMLElement;
    const parent = host.parentElement as HTMLElement;
    parent.style.display = 'flex';
    parent.style.flexDirection = 'column';

    const chartHeights = (): number[] =>
      Array.from(host.querySelectorAll('.tablet-air__chart'))
        .map(chart => chart.getBoundingClientRect().height);

    parent.style.height = '600px';
    fixture.detectChanges();
    const small = chartHeights();

    parent.style.height = '900px';
    fixture.detectChanges();
    const large = chartHeights();

    expect(small.length).toBe(2);
    small.forEach(height => expect(height).toBeGreaterThan(80));
    // 300 px mehr Bildschirm landen in der einen Rasterzeile.
    large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 250));

    parent.removeAttribute('style');
  });
});
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-air-quality.component.spec.ts'
```

Erwartet: Kompilierfehler "Cannot find module './tablet-air-quality.component'".

- [ ] **Step 3: Komponente implementieren**

`tablet-air-quality.component.ts`:

```typescript
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { AirQualitySeriesService } from '../../services/air-quality-series.service';
import {
  AIR_QUALITY_GROUPS,
  AirQualityMetricGroup,
  AirQualityMetricLine,
  AirQualitySensorSeries
} from '../../models/air-quality-series.model';
import { TimeRange } from '../../models/temperature.model';
import { IaqLevel, iaqLevel } from '../../models/alexa-air-quality.model';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

/** Eine Sensorkachel des Rasters. */
interface ChartTile {
  sensorId: string;
  name: string;
  /** Rohserie, um die Optionen beim Gruppenwechsel ohne neuen Abruf zu bauen. */
  series: AirQualitySensorSeries;
  options: Record<string, unknown>;
  /** True, wenn der Sensor zur gewaehlten Gruppe keine Werte hat. */
  empty: boolean;
  /** Juengster Wert der Gruppe inkl. Einheit, z. B. "8 µg/m³"; null, wenn keiner da ist. */
  currentLabel: string | null;
  /** Nur beim IAQ gesetzt - nur dort gibt es eine allgemein gueltige Bewertung. */
  currentLevel: IaqLevel | null;
}

const AXIS_COLOR = '#94a3b8';

/**
 * Luftqualitaetsuebersicht fuer das Wandtablet: alle Sensoren gleichzeitig, ohne
 * Scrollen. Welche Messgroesse zu sehen ist, waehlt die Kopfzeile - immer genau
 * eine Gruppe, damit jede Kachel genau eine Y-Achse hat.
 */
@Component({
  selector: 'app-tablet-air-quality',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-air-quality.component.html',
  styleUrl: './tablet-air-quality.component.scss'
})
export class TabletAirQualityComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly seriesService = inject(AirQualitySeriesService);
  private refreshTimer: number | null = null;

  readonly ranges: RangeOption[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];

  readonly groups = AIR_QUALITY_GROUPS;

  activeRange: TimeRange = 'WEEK';
  /** Feinstaub ist die einzige Gruppe, die beide Quellen liefern. */
  activeGroup: AirQualityMetricGroup = AIR_QUALITY_GROUPS[0];
  charts: ChartTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load(this.activeRange);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletAirQualityComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /** Spaltenzahl des Rasters: bei vielen Sensoren lieber schmaler als scrollen. */
  get columns(): number {
    return this.charts.length >= 6 ? 3 : 2;
  }

  setGroup(key: string): void {
    const group = this.groups.find(candidate => candidate.key === key);
    if (!group || group.key === this.activeGroup.key) {
      return;
    }
    this.activeGroup = group;
    this.rebuildCharts();
  }

  setRange(range: TimeRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.load(range);
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(this.activeRange, true);
  }

  private load(range: TimeRange, silent = false): void {
    if (!silent) {
      this.isLoading = true;
      this.errorMessage = null;
    }
    this.seriesService.getSeries(range).subscribe({
      next: series => {
        this.charts = series.map(s => this.toTile(s));
        this.isEmpty = this.charts.length === 0;
        this.errorMessage = null;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Luftqualitätsdaten:', error);
        this.isLoading = false;
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte
        // nicht durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer
        // Wandanzeige mehr wert als gar keine.
        if (!silent) {
          this.errorMessage = 'Luftqualitätsdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  private rebuildCharts(): void {
    this.charts = this.charts.map(tile => this.toTile(tile.series));
  }

  private toTile(series: AirQualitySensorSeries): ChartTile {
    const current = this.currentValue(series);
    return {
      sensorId: series.sensorId,
      name: series.name,
      series,
      options: this.chartOptionsFor(series),
      empty: this.activeLines(series).length === 0,
      currentLabel: current === null ? null : this.formatValue(current),
      currentLevel:
        this.activeGroup.key === 'iaq' && current !== null ? iaqLevel(current) : null
    };
  }

  /** Die Linien der aktiven Gruppe, zu denen dieser Sensor ueberhaupt Werte hat. */
  private activeLines(series: AirQualitySensorSeries): AirQualityMetricLine[] {
    return this.activeGroup.lines.filter(line => (series.metrics[line.key] ?? []).length > 0);
  }

  /**
   * Juengster Wert der Kachel: der der ersten vorhandenen Linie der Gruppe. Bei
   * Feinstaub ist das PM2.5 - die Groesse, auf die es gesundheitlich ankommt.
   */
  private currentValue(series: AirQualitySensorSeries): number | null {
    const lines = this.activeLines(series);
    if (lines.length === 0) {
      return null;
    }
    const points = series.metrics[lines[0].key] ?? [];
    return points[points.length - 1].value;
  }

  /** Deutsche Zahlformatierung, hoechstens eine Nachkommastelle, plus Einheit. */
  private formatValue(value: number): string {
    const formatted = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(value);
    return this.activeGroup.unit ? `${formatted} ${this.activeGroup.unit}` : formatted;
  }

  /**
   * Baut die Chart-Optionen einer Kachel: eine Y-Achse fuer die ganze Gruppe, je
   * vorhandener Messgroesse eine Linie darauf.
   */
  chartOptionsFor(series: AirQualitySensorSeries): Record<string, unknown> {
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };
    const lines = this.activeLines(series);

    return {
      grid: { left: 56, right: 16, top: 12, bottom: 28, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'time', axisLabel },
      yAxis: [{
        type: 'value',
        scale: true,
        axisLabel: {
          ...axisLabel,
          formatter: this.activeGroup.unit ? `{value} ${this.activeGroup.unit}` : '{value}'
        },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      }],
      series: lines.map(line => ({
        name: line.label,
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: (series.metrics[line.key] ?? []).map(point => [point.time, point.value]),
        lineStyle: { width: 3, color: line.color },
        itemStyle: { color: line.color }
      }))
    };
  }
}
```

`tablet-air-quality.component.html`:

```html
<app-tablet-shell heading="Luftqualität">
  <div shellActions class="tablet-air__controls">
    <div class="tablet-air__ranges" role="group" aria-label="Zeitraum">
      @for (range of ranges; track range.value) {
        <button
          type="button"
          class="tablet-air__range"
          [class.tablet-air__range--active]="range.value === activeRange"
          (click)="setRange(range.value)">
          {{ range.label }}
        </button>
      }
    </div>
    <div class="tablet-air__ranges" role="group" aria-label="Messgröße">
      @for (group of groups; track group.key) {
        <button
          type="button"
          class="tablet-air__range"
          [class.tablet-air__range--active]="group.key === activeGroup.key"
          [attr.aria-pressed]="group.key === activeGroup.key"
          (click)="setGroup(group.key)">
          {{ group.label }}
        </button>
      }
    </div>
  </div>

  <section class="tablet-air">
    @if (isLoading && charts.length === 0) {
      <p class="tablet-air__status">Lade Luftqualitätsdaten…</p>
    } @else if (errorMessage && charts.length === 0) {
      <p class="tablet-air__status tablet-air__status--error">{{ errorMessage }}</p>
    } @else if (isEmpty) {
      <p class="tablet-air__status">Keine Luftqualitätssensoren gefunden.</p>
    } @else {
      <div class="tablet-air__grid" [style.grid-template-columns]="'repeat(' + columns + ', 1fr)'">
        @for (chart of charts; track chart.sensorId) {
          <article class="tablet-air__card">
            <h3 class="tablet-air__card-title">
              <span>{{ chart.name }}</span>
              @if (chart.currentLabel) {
                <span
                  class="tablet-air__current"
                  [class.tablet-air__current--good]="chart.currentLevel === 'good'"
                  [class.tablet-air__current--moderate]="chart.currentLevel === 'moderate'"
                  [class.tablet-air__current--bad]="chart.currentLevel === 'bad'">
                  {{ chart.currentLabel }}
                </span>
              }
            </h3>
            @if (chart.empty) {
              <p class="tablet-air__card-empty">Keine Werte für diese Auswahl</p>
            } @else {
              <div
                echarts
                class="tablet-air__chart"
                [options]="chart.options"
                [autoResize]="true"></div>
            }
          </article>
        }
      </div>
    }
  </section>
</app-tablet-shell>
```

`tablet-air-quality.component.scss`:

```scss
// Inhalt der Tablet-Luftqualitaetsansicht. Kopfzeile und Ansichtsleiste liefert
// app-tablet-shell; hier bleibt nur das Kachelraster, das sich die Resthoehe
// teilt, damit alle Sensoren ohne Wischen sichtbar sind.

// Die Hoehe kommt ueber eine durchgehende Flex-Kette von .app-layout (100vh)
// bis zum Chart. Fehlt sie an EINER Stelle, faellt alles darunter auf
// Inhaltshoehe zurueck und die Graphen werden winzig - deshalb traegt schon
// das Host-Element der Seite flex: 1 (dasselbe Muster wie im Dashboard).
:host {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.tablet-air {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 0.75rem;
  color: #e4e2e4;

  /* Zeitraum und Messgroesse stehen als zwei Gruppen in der Kopfzeile. */
  &__controls {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__ranges {
    display: inline-flex;
    align-self: center;
    gap: 0.25rem;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 12px;
    padding: 0.3rem;
    flex: 0 0 auto;
  }

  &__range {
    border: none;
    background: transparent;
    padding: 0.6rem 1.3rem;
    border-radius: 9px;
    cursor: pointer;
    font: inherit;
    font-size: 0.95rem;
    color: rgba(228, 226, 228, 0.75);

    &--active {
      background: rgba(170, 199, 255, 0.16);
      color: #aac7ff;
    }
  }

  &__status {
    flex: 1 1 auto;
    display: grid;
    place-items: center;
    margin: 0;
    color: #94a3b8;
    font-size: 1.25rem;

    &--error {
      color: #ffb4ab;
    }
  }

  &__grid {
    flex: 1 1 auto;
    display: grid;
    grid-auto-rows: 1fr;
    gap: 1rem;
    min-height: 0;
  }

  &__card {
    display: flex;
    flex-direction: column;
    min-height: 0;
    padding: 0.75rem 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 1rem;
    background: rgba(255, 255, 255, 0.03);
  }

  &__card-title {
    margin: 0 0 0.35rem;
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.5rem;
    font-size: 1.15rem;
    font-weight: 600;
    flex: 0 0 auto;
    // Die globale Regel h1..h6 { color: var(--color-dark) } aus styles.scss
    // schlaegt die vererbte helle Farbe - auf schwarzem Grund waere der
    // Sensorname sonst kaum lesbar.
    color: #f4f3f5;
    letter-spacing: -0.01em;
  }

  /* Der Jetzt-Wert ist auf einer Wandanzeige die eigentliche Information. */
  &__current {
    font-size: 1.35rem;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    color: #f4f3f5;

    /* Nur der IAQ-Score hat eine allgemein gueltige Bewertung. */
    &--good {
      color: #4ade80;
    }

    &--moderate {
      color: #fbbf24;
    }

    &--bad {
      color: #ffb4ab;
    }
  }

  &__card-empty {
    flex: 1 1 auto;
    display: grid;
    place-items: center;
    margin: 0;
    color: rgba(148, 163, 184, 0.8);
    font-size: 0.95rem;
  }

  &__chart {
    flex: 1 1 auto;
    width: 100%;
    min-height: 0;
  }
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-air-quality.component.spec.ts'
```

Erwartet: alle 13 Tests grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/tablet-air-quality
git commit -m "feat(tablet): Ansicht Luftqualitaet mit Kachel je Sensor"
```

---

## Task 8: Route und Eintrag in der Ansichtsleiste

Erst hier wird die Ansicht erreichbar — vorher gäbe es einen Menüeintrag ohne Ziel.

**Files:**
- Modify: `frontend/src/app/shared/tablet-views.ts`
- Modify: `frontend/src/app/app.routes.ts:70-75`

- [ ] **Step 1: Eintrag in `TABLET_VIEWS` ergänzen**

```typescript
export const TABLET_VIEWS: readonly TabletView[] = [
  { route: '/tablet/temperatures', icon: 'thermostat', label: 'Temperaturen' },
  { route: '/tablet/air-quality', icon: 'air', label: 'Luftqualität' }
];
```

- [ ] **Step 2: Route ergänzen**

Direkt hinter dem Block `path: 'tablet/temperatures'` in `app.routes.ts` einfügen (das Muster inkl. `authGuard` von dort übernehmen):

```typescript
  {
    path: 'tablet/air-quality',
    loadComponent: () => import('./pages/tablet-air-quality/tablet-air-quality.component').then(m => m.TabletAirQualityComponent),
    canActivate: [authGuard],
    title: 'Luftqualitaet Tablet - Household Manager'
  },
```

- [ ] **Step 3: Vollen Frontend-Testlauf**

```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: genau die 3 Baseline-Fails (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`), sonst grün. Ein vierter Fail in `SmartDeviceListComponent` ist die bekannte Flake — Lauf wiederholen.

- [ ] **Step 4: Produktionsbuild**

```bash
cd frontend
ng build --configuration production
```

Erwartet: erfolgreicher Build. Bekannt ist die Budget-Fehlermeldung zu `dashboard.component.scss`; sie betrifft eine unveränderte Datei. Bricht der Build **nur** daran ab, ist das kein Ergebnis dieser Arbeit — im Abschlussbericht erwähnen.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/tablet-views.ts frontend/src/app/app.routes.ts
git commit -m "feat(tablet): Luftqualitaet in Route und Ansichtsleiste aufnehmen"
```

---

## Task 9: Dokumentation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt "Tablet-Ansichten (Unterseiten des Wandtablets)")

- [ ] **Step 1: Abschnitt ergänzen**

Am Ende des Abschnitts "Tablet-Ansichten (Unterseiten des Wandtablets)", nach dem Punkt über `GET /v1/temperatures`, anfügen:

```markdown
- Zweite Ansicht: `/tablet/air-quality` (`pages/tablet-air-quality/`). Zeigt die eigene Luftsensorik — Airrohr draußen (PM2.5, PM10) und die Amazon-Monitore drinnen (IAQ, PM2.5, VOC, CO). Temperatur und Luftfeuchte der Amazon-Monitore bleiben bewusst der Temperaturansicht vorbehalten
- **Der Messgrößen-Umschalter ist hier eine Einfachauswahl, keine Mehrfachauswahl wie bei den Temperaturen:** die vier Größen haben vier Einheiten (µg/m³, Score 0–100, ppb, ppm), frei kombinierbar ergäbe das bis zu vier Y-Achsen in einer Wandkachel. Gewählt wird deshalb genau eine Gruppe — „Feinstaub" (PM2.5 + PM10, gemeinsame Einheit, Default), IAQ, VOC, CO —, und jede Kachel hat immer genau eine Achse. Definition der Gruppen: `AIR_QUALITY_GROUPS` in `models/air-quality-series.model.ts`
- Neben dem Kachelnamen steht der jüngste Wert der Gruppe; **nur beim IAQ** wird er nach `iaqLevel` eingefärbt — für Feinstaub, VOC und CO gibt es hier bewusst keine Bewertung, das wäre eine eigene Entscheidung über den anzuwendenden Grenzwert
- `GET /v1/air-quality/series?range=DAY|WEEK|MONTH` (`AirQualitySeriesService`) fasst beide Quellen zusammen und mittelt serverseitig. Die Messgrößen stehen in einer **Map** statt in festen Feldern, weil die Quellen disjunkte Mengen liefern (Airrohr kein IAQ, Amazon kein PM10); eine Größe ohne Werte fehlt in der Map, ein Sensor ohne jeden Wert fehlt in der Antwort. Ein Quellenfehler ist isoliert — fällt Airrohr aus, kommen die Innenraumkacheln trotzdem
- **Die Website-Seite `/air-quality` lädt weiterhin die kompletten Historien** (`/v1/airrohr-readings`, `/v1/alexa/air-quality/readings`, beide ohne Zeitraumparameter) und filtert im Browser. Der neue Serien-Endpunkt ersetzt sie nicht; für das Tablet, das alle 5 Minuten nachlädt, wäre dieser Weg zu schwer. Beide Tabellen wachsen unbegrenzt, eine Retention gibt es für keine von beiden
- `SeriesRange` und `SeriesDownsampler` (früher `TemperatureRange`/`TemperatureSeriesDownsampler`) bedienen jetzt beide Serien-Services. Wer die Bucketlängen anfasst, ändert damit Temperatur- **und** Luftqualitätsgraphen
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): Tablet-Ansicht Luftqualitaet festhalten"
```

---

## Abschluss

- [ ] **Backend-Gesamtlauf**

```bash
cd backend
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn test
```

Erwartet: nur die zwei bekannten DB-Umgebungsfehler.

- [ ] **Frontend-Gesamtlauf**

```bash
cd frontend
npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: nur die 3 Baseline-Fails.

- [ ] **Bericht** — was gebaut wurde, welche Tests liefen, welche Fails Baseline sind. Offen bleibt in jedem Fall die Verifikation am echten Wandtablet: ob das Raster bei der tatsächlichen Sensorzahl ohne Scrollen passt, lässt sich nur dort sehen.
