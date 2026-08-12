# Lüftungsempfehlung im Intelligence Hub — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine Hub-Karte plus Flow-Entität, die meldet, wenn ein Raum ≥ 24 °C liegt und es draußen ≥ 2 °C kühler ist — Lüften kühlt dann den Raum.

**Architecture:** `VentilationRecommendationService` (Backend) ist die einzige Definition der Empfehlung (Muster `TractiveHomeResolver`); ein `@Scheduled`-Reporter spiegelt sie als `binary_sensor.insight_ventilation` in den Entity-State-Layer, ein Controller liefert sie als `GET /api/v1/insights/ventilation`. Das Frontend pollt den Endpunkt im Klima-Takt und baut daraus eine `HubInsight`-Sammelkarte.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Lombok / Mockito (Backend), Angular 19 / Karma-Jasmine (Frontend). Keine DB-Migration.

**Spec:** `docs/superpowers/specs/2026-08-12-lueftungs-insight-design.md`

**Build-Umgebung (diese Maschine):**
- Maven braucht JDK 21: vor jedem `mvn` in Git-Bash `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Default ist JDK 17 und `mvn` schlägt sonst fehl). Aus `backend/` laufen lassen, es gibt kein `mvnw`.
- Vorbestehende, umgebungsbedingte Backend-Test-Fails (DB nicht erreichbar): `HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest` — ignorieren.
- Frontend-Tests: `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`. Baseline: 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent`); nur zusätzliche Fails sind Regressionen.

---

### Task 1: Backend — Konfiguration (`VentilationProperties`)

Reine Datenklasse ohne Logik — kein eigener Test, Muster `NukiProperties`.

**Files:**
- Create: `backend/src/main/java/com/household/manager/config/VentilationProperties.java`
- Modify: `backend/src/main/resources/application.properties` (neuer Block ans Ende der Integrations-Blöcke, z. B. nach dem `tractive.`-Block)

- [ ] **Step 1: Properties-Klasse anlegen**

```java
package com.household.manager.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Schwellen der Lüftungsempfehlung. Bewusst in application.properties statt in der DB
 * (wie beim Zigbee-Watchdog): kein Grund, das im laufenden Betrieb zu verstellen.
 */
@Configuration
@ConfigurationProperties(prefix = "ventilation")
@Data
public class VentilationProperties {

    /** Ab dieser Raumtemperatur gilt ein Raum als "zu warm". */
    private BigDecimal roomThresholdCelsius = new BigDecimal("24");
    /** Draußen muss es mindestens so viel kühler sein, damit die Empfehlung entsteht. */
    private BigDecimal minDifferenceCelsius = new BigDecimal("2");
    /** Eine bestehende Empfehlung erlischt erst unter dieser Differenz (Hysterese). */
    private BigDecimal offDifferenceCelsius = new BigDecimal("1");
    /** Messwerte, die älter sind, werden ignoriert (eingefrorener Sensor). */
    private int staleAfterMinutes = 30;
    /** Takt des Entity-Reporters. */
    private long reportIntervalMs = 300_000;
    private long initialDelayMs = 60_000;
}
```

- [ ] **Step 2: Properties-Defaults dokumentieren**

In `backend/src/main/resources/application.properties` ergänzen:

```properties
# Lüftungsempfehlung (Intelligence Hub + binary_sensor.insight_ventilation)
ventilation.room-threshold-celsius=24
ventilation.min-difference-celsius=2
ventilation.off-difference-celsius=1
ventilation.stale-after-minutes=30
ventilation.report-interval-ms=300000
ventilation.initial-delay-ms=60000
```

- [ ] **Step 3: Kompilieren**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/config/VentilationProperties.java backend/src/main/resources/application.properties
git commit -m "feat(backend): Konfiguration der Lueftungsempfehlung"
```

---

### Task 2: Backend — Kernlogik (`VentilationRecommendationService`) per TDD

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/VentilationRoom.java`
- Create: `backend/src/main/java/com/household/manager/dto/VentilationAssessment.java`
- Create: `backend/src/main/java/com/household/manager/service/VentilationRecommendationService.java`
- Test: `backend/src/test/java/com/household/manager/service/VentilationRecommendationServiceTest.java`

- [ ] **Step 1: DTOs anlegen** (die Tests brauchen sie zum Kompilieren)

`VentilationRoom.java`:

```java
package com.household.manager.dto;

import java.math.BigDecimal;

/** Ein betroffener Raum der Lüftungsempfehlung. */
public record VentilationRoom(String name, BigDecimal temperature) {
}
```

`VentilationAssessment.java`:

```java
package com.household.manager.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ergebnis der Lüftungsbewertung.
 *
 * <p>{@code recommended == null} heißt "keine Aussage möglich" (kein frischer
 * Außenwert) — bewusst verschieden von {@code false} ("kein Lüften nötig"),
 * damit das Frontend bei fehlender Datenlage keine Karte zeigt statt eine
 * falsche Entwarnung.
 */
public record VentilationAssessment(
        Boolean recommended,
        BigDecimal outdoorTemperature,
        List<VentilationRoom> rooms,
        LocalDateTime evaluatedAt
) {
}
```

- [ ] **Step 2: Failing Tests schreiben**

`VentilationRecommendationServiceTest.java` — reiner Mockito-Unit-Test, kein Spring-Kontext (die lokale Test-DB ist nicht erreichbar). Frische bzw. veraltete Messwerte werden relativ zu `LocalDateTime.now()` gebaut:

```java
package com.household.manager.service;

import com.household.manager.config.VentilationProperties;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.VentilationAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class VentilationRecommendationServiceTest {

    private TemperatureSeriesService temperatureSeriesService;
    private VentilationRecommendationService service;

    @BeforeEach
    void setUp() {
        temperatureSeriesService = Mockito.mock(TemperatureSeriesService.class);
        service = new VentilationRecommendationService(
                temperatureSeriesService, new VentilationProperties());
    }

    private CurrentTemperatureReading reading(
            String source, String name, String temp, int ageMinutes) {
        return CurrentTemperatureReading.builder()
                .sensorId(source.toLowerCase() + ":" + name)
                .name(name)
                .source(source)
                .temperature(new BigDecimal(temp))
                .measuredAt(LocalDateTime.now().minusMinutes(ageMinutes))
                .build();
    }

    @Test
    void empfiehltLueftenWennRaumWarmUndDraussenKuehler() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.recommended()).isTrue();
        assertThat(result.outdoorTemperature()).isEqualByComparingTo("21.0");
        assertThat(result.rooms()).hasSize(1);
        assertThat(result.rooms().get(0).name()).isEqualTo("Schlafzimmer");
    }

    @Test
    void keineEmpfehlungUnterRaumschwelle() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "18.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "23.9", 5)));

        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void keineEmpfehlungBeiZuKleinerDifferenz() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void keineAussageOhneFrischenAussenwert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 45),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));

        VentilationAssessment result = service.assess();

        assertThat(result.recommended()).isNull();
        assertThat(result.rooms()).isEmpty();
    }

    @Test
    void veralteteRaumwerteWerdenIgnoriert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 45)));

        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void raeumeSindAbsteigendNachTemperaturSortiert() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "20.0", 5),
                reading("ZIGBEE", "Wohnzimmer", "25.0", 5),
                reading("ALEXA", "Schlafzimmer", "26.0", 5)));

        List<String> names = service.assess().rooms().stream()
                .map(r -> r.name()).toList();

        assertThat(names).containsExactly("Schlafzimmer", "Wohnzimmer");
    }

    @Test
    void hystereseHaeltBestehendeEmpfehlungBeiKleinererDifferenz() {
        // Erst aktivieren: Differenz 5 °C.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isTrue();

        // Differenz nur noch 1.5 °C: unter der Einschalt- (2), über der Ausschaltschwelle (1).
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isTrue();

        // Differenz 0.5 °C: unter der Ausschaltschwelle — Empfehlung erlischt.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "25.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isFalse();

        // Und bleibt aus: 1.5 °C Differenz reicht ohne bestehende Empfehlung nicht.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isFalse();
    }

    @Test
    void fehlenderAussenwertSetztHystereseZurueck() {
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "21.0", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isTrue();

        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isNull();

        // Nach der Rückkehr gilt wieder die Einschaltschwelle (2 °C), nicht die Hysterese.
        when(temperatureSeriesService.getCurrent()).thenReturn(List.of(
                reading("WEATHER", "Außen", "24.5", 5),
                reading("ZIGBEE", "Schlafzimmer", "26.0", 5)));
        assertThat(service.assess().recommended()).isFalse();
    }
}
```

- [ ] **Step 3: Tests laufen lassen — sie müssen fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VentilationRecommendationServiceTest`
Expected: Kompilierfehler ("cannot find symbol: VentilationRecommendationService")

- [ ] **Step 4: Service implementieren**

`VentilationRecommendationService.java`:

```java
package com.household.manager.service;

import com.household.manager.config.VentilationProperties;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Einzige Definition von "Lüften lohnt sich" (Muster TractiveHomeResolver):
 * REST-Endpunkt und Entity-Reporter fragen dieselbe Klasse, damit Hub-Karte
 * und Flow-Trigger nie auseinanderlaufen.
 *
 * <p>Hysterese: eine bestehende Empfehlung erlischt erst, wenn kein Raum mehr
 * über der Raumschwelle liegt oder die Differenz unter die Ausschaltschwelle
 * fällt — sonst schaltete die Entität an der Schwelle im Minutentakt und ein
 * darauf gebauter Telegram-Flow spammte bei jeder on-Flanke.
 */
@Service
@RequiredArgsConstructor
public class VentilationRecommendationService {

    private static final String OUTDOOR_SOURCE = "WEATHER";

    private final TemperatureSeriesService temperatureSeriesService;
    private final VentilationProperties properties;

    /** Letztes Urteil; Basis der Hysterese. Nur unter dem synchronized von assess() angefasst. */
    private boolean lastRecommended = false;

    public synchronized VentilationAssessment assess() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleLimit = now.minusMinutes(properties.getStaleAfterMinutes());
        List<CurrentTemperatureReading> readings = temperatureSeriesService.getCurrent();

        Optional<BigDecimal> outdoor = readings.stream()
                .filter(r -> OUTDOOR_SOURCE.equals(r.getSource()))
                .filter(r -> isFresh(r, staleLimit))
                .map(CurrentTemperatureReading::getTemperature)
                .filter(Objects::nonNull)
                .findFirst();
        if (outdoor.isEmpty()) {
            // Ohne frischen Außenwert gibt es keine Aussage — und keine Hysterese:
            // nach der Rückkehr soll wieder die volle Einschaltschwelle gelten.
            lastRecommended = false;
            return new VentilationAssessment(null, null, List.of(), now);
        }

        BigDecimal outdoorTemp = outdoor.get();
        BigDecimal requiredDifference = lastRecommended
                ? properties.getOffDifferenceCelsius()
                : properties.getMinDifferenceCelsius();

        List<VentilationRoom> rooms = readings.stream()
                .filter(r -> !OUTDOOR_SOURCE.equals(r.getSource()))
                .filter(r -> isFresh(r, staleLimit))
                .filter(r -> r.getTemperature() != null)
                .filter(r -> r.getTemperature().compareTo(properties.getRoomThresholdCelsius()) >= 0)
                .filter(r -> r.getTemperature().subtract(outdoorTemp).compareTo(requiredDifference) >= 0)
                .sorted(Comparator.comparing(CurrentTemperatureReading::getTemperature).reversed())
                .map(r -> new VentilationRoom(r.getName(), r.getTemperature()))
                .toList();

        lastRecommended = !rooms.isEmpty();
        return new VentilationAssessment(lastRecommended, outdoorTemp, rooms, now);
    }

    private boolean isFresh(CurrentTemperatureReading reading, LocalDateTime staleLimit) {
        return reading.getMeasuredAt() != null && !reading.getMeasuredAt().isBefore(staleLimit);
    }
}
```

- [ ] **Step 5: Tests laufen lassen — sie müssen bestehen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VentilationRecommendationServiceTest`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/VentilationRoom.java backend/src/main/java/com/household/manager/dto/VentilationAssessment.java backend/src/main/java/com/household/manager/service/VentilationRecommendationService.java backend/src/test/java/com/household/manager/service/VentilationRecommendationServiceTest.java
git commit -m "feat(backend): Kernlogik der Lueftungsempfehlung mit Hysterese"
```

---

### Task 3: Backend — Entität `binary_sensor.insight_ventilation`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` (neuer Enum-Wert)
- Create: `backend/src/main/java/com/household/manager/service/VentilationEntityReporter.java`
- Test: `backend/src/test/java/com/household/manager/service/VentilationEntityReporterTest.java`

- [ ] **Step 1: `EntitySource.INSIGHT` ergänzen**

In `EntitySource.java` vor `MANUAL` einfügen:

```java
    /** Serverseitig berechnete Hinweise (z. B. Lüftungsempfehlung). */
    INSIGHT,
```

- [ ] **Step 2: Failing Tests schreiben**

`VentilationEntityReporterTest.java`:

```java
package com.household.manager.service;

import com.household.manager.config.VentilationProperties;
import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VentilationEntityReporterTest {

    private VentilationRecommendationService recommendationService;
    private EntityStateService entityStateService;
    private VentilationEntityReporter reporter;

    @BeforeEach
    void setUp() {
        recommendationService = Mockito.mock(VentilationRecommendationService.class);
        entityStateService = Mockito.mock(EntityStateService.class);
        reporter = new VentilationEntityReporter(recommendationService, entityStateService);
    }

    private VentilationAssessment assessment(Boolean recommended, List<VentilationRoom> rooms) {
        BigDecimal outdoor = recommended == null ? null : new BigDecimal("21.0");
        return new VentilationAssessment(recommended, outdoor, rooms, LocalDateTime.now());
    }

    @Test
    void meldetOnMitRaumAttributen() {
        when(recommendationService.assess()).thenReturn(assessment(true,
                List.of(new VentilationRoom("Schlafzimmer", new BigDecimal("26.0")))));

        reporter.report();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.entityId()).isEqualTo("binary_sensor.insight_ventilation");
        assertThat(update.state()).isEqualTo("on");
        assertThat(update.attributes()).containsEntry("outdoorTemperature", new BigDecimal("21.0"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) update.attributes().get("rooms");
        assertThat(rooms).containsExactly(
                Map.of("name", "Schlafzimmer", "temperature", new BigDecimal("26.0")));
    }

    @Test
    void meldetOffOhneEmpfehlung() {
        when(recommendationService.assess()).thenReturn(assessment(false, List.of()));

        reporter.report();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("off");
    }

    @Test
    void meldetUnavailableOhneAussage() {
        when(recommendationService.assess()).thenReturn(assessment(null, List.of()));

        reporter.report();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void wirftNieBeiFehlerDerBewertung() {
        when(recommendationService.assess()).thenThrow(new IllegalStateException("kaputt"));

        reporter.report();
    }
}
```

- [ ] **Step 3: Tests laufen lassen — sie müssen fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VentilationEntityReporterTest`
Expected: Kompilierfehler ("cannot find symbol: VentilationEntityReporter")

- [ ] **Step 4: Reporter implementieren**

`VentilationEntityReporter.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spiegelt die Lüftungsempfehlung als {@code binary_sensor.insight_ventilation}
 * in den Entity-State-Layer, damit Flows auf die on-Flanke triggern können.
 * Ohne frischen Außenwert wird {@code unavailable} gemeldet; die Flow-Engine
 * unterdrückt den Übergang NACH unavailable engine-weit, es entsteht also kein
 * Fehltrigger (die !=-Falle aus CLAUDE.md gilt hier wie überall).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VentilationEntityReporter {

    private final VentilationRecommendationService recommendationService;
    private final EntityStateService entityStateService;

    @Scheduled(fixedDelayString = "${ventilation.report-interval-ms:300000}",
            initialDelayString = "${ventilation.initial-delay-ms:60000}")
    public void report() {
        try {
            VentilationAssessment assessment = recommendationService.assess();
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(
                            EntityDomain.BINARY_SENSOR, EntitySource.INSIGHT, "ventilation", null))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.INSIGHT)
                    .sourceRef("ventilation")
                    .friendlyName("Lüftungsempfehlung")
                    .state(stateOf(assessment))
                    .attributes(attributesOf(assessment))
                    .build());
        } catch (Exception ex) {
            log.warn("Lüftungsbewertung fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private String stateOf(VentilationAssessment assessment) {
        if (assessment.recommended() == null) {
            return "unavailable";
        }
        return assessment.recommended() ? "on" : "off";
    }

    private Map<String, Object> attributesOf(VentilationAssessment assessment) {
        Map<String, Object> attributes = new HashMap<>();
        if (assessment.outdoorTemperature() != null) {
            attributes.put("outdoorTemperature", assessment.outdoorTemperature());
        }
        List<Map<String, Object>> rooms = assessment.rooms().stream()
                .map(this::roomAttributes)
                .toList();
        attributes.put("rooms", rooms);
        return attributes;
    }

    private Map<String, Object> roomAttributes(VentilationRoom room) {
        return Map.of("name", room.name(), "temperature", room.temperature());
    }
}
```

- [ ] **Step 5: Tests laufen lassen — sie müssen bestehen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=VentilationEntityReporterTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java backend/src/main/java/com/household/manager/service/VentilationEntityReporter.java backend/src/test/java/com/household/manager/service/VentilationEntityReporterTest.java
git commit -m "feat(backend): binary_sensor.insight_ventilation fuer Flow-Trigger"
```

---

### Task 4: Backend — REST-Endpunkt `GET /api/v1/insights/ventilation`

Der Controller ist ein reiner Delegat; ein schlanker Unit-Test genügt. Die Leseerlaubnis
kommt aus der bestehenden generischen `GET /v1/**`-Regel (KIOSK) — **keine** Änderung an
`SecurityConfig`, kein neuer `SecurityRulesTest`-Eintrag nötig.

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/InsightController.java`
- Test: `backend/src/test/java/com/household/manager/controller/InsightControllerTest.java`

- [ ] **Step 1: Failing Test schreiben**

`InsightControllerTest.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.service.VentilationRecommendationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class InsightControllerTest {

    @Test
    void liefertDieBewertungDesServices() {
        VentilationRecommendationService service =
                Mockito.mock(VentilationRecommendationService.class);
        VentilationAssessment assessment =
                new VentilationAssessment(null, null, List.of(), LocalDateTime.now());
        when(service.assess()).thenReturn(assessment);

        InsightController controller = new InsightController(service);

        assertThat(controller.getVentilation()).isSameAs(assessment);
    }
}
```

- [ ] **Step 2: Test laufen lassen — er muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=InsightControllerTest`
Expected: Kompilierfehler ("cannot find symbol: InsightController")

- [ ] **Step 3: Controller implementieren**

`InsightController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.VentilationAssessment;
import com.household.manager.service.VentilationRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serverseitig berechnete Hinweise für den Intelligence Hub.
 * Basis-URL: /api/v1/insights — lesbar für alle Rollen (generische GET-Regel,
 * auch das KIOSK-Wandtablet).
 */
@RestController
@RequestMapping("/v1/insights")
@RequiredArgsConstructor
public class InsightController {

    private final VentilationRecommendationService ventilationRecommendationService;

    @GetMapping("/ventilation")
    public VentilationAssessment getVentilation() {
        return ventilationRecommendationService.assess();
    }
}
```

- [ ] **Step 4: Test laufen lassen — er muss bestehen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=InsightControllerTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Gesamten Backend-Testlauf prüfen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test`
Expected: BUILD FAILURE ausschließlich wegen der beiden vorbestehenden DB-Fails (`HouseholdManagerApplicationTests`, `HealthControllerTest`); keine neuen Fails.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/InsightController.java backend/src/test/java/com/household/manager/controller/InsightControllerTest.java
git commit -m "feat(backend): GET /v1/insights/ventilation"
```

---

### Task 5: Frontend — Modell und `InsightService`

Reiner HTTP-Delegat nach dem Muster `WasteCollectionService`; kein eigener Service-Test
(das Muster hat dort einen, aber der Mehrwert ist bei einem Ein-Methoden-GET gering —
die Logik steckt im Util aus Task 6, und das wird getestet).

**Files:**
- Create: `frontend/src/app/models/ventilation.model.ts`
- Create: `frontend/src/app/services/insight.service.ts`

- [ ] **Step 1: Modell anlegen**

`ventilation.model.ts`:

```typescript
/** Ein betroffener Raum der Lüftungsempfehlung. */
export interface VentilationRoom {
  name: string;
  temperature: number;
}

/**
 * Bewertung des Backends. `recommended === null` heißt "keine Aussage möglich"
 * (kein frischer Außenwert) — dann zeigt der Hub keine Karte, statt eine
 * falsche Entwarnung zu geben.
 */
export interface VentilationAssessment {
  recommended: boolean | null;
  outdoorTemperature: number | null;
  rooms: VentilationRoom[];
  /** ISO-Zeitstempel der Bewertung. */
  evaluatedAt: string;
}
```

- [ ] **Step 2: Service anlegen**

`insight.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { VentilationAssessment } from '../models/ventilation.model';

/** Service fuer serverseitig berechnete Hub-Hinweise. */
@Injectable({ providedIn: 'root' })
export class InsightService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/insights';

  getVentilation(): Observable<VentilationAssessment> {
    return this.http.get<VentilationAssessment>(`${this.baseUrl}/ventilation`);
  }
}
```

- [ ] **Step 3: Kompilieren**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -5`
Expected: Build erfolgreich (der bekannte `anyComponentStyle`-Budget-ERROR zu `dashboard.component.scss` kann auftreten, sobald dessen SCSS wächst — in diesem Task ändert sich kein SCSS, also kein neuer Fehler erwartet).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/ventilation.model.ts frontend/src/app/services/insight.service.ts
git commit -m "feat(frontend): InsightService fuer die Lueftungsempfehlung"
```

---

### Task 6: Frontend — `ventilation-insight.util.ts` per TDD

**Files:**
- Create: `frontend/src/app/shared/ventilation-insight.util.ts`
- Test: `frontend/src/app/shared/ventilation-insight.util.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

`ventilation-insight.util.spec.ts`:

```typescript
import { buildVentilationInsight } from './ventilation-insight.util';
import { VentilationAssessment } from '../models/ventilation.model';

function assessment(overrides: Partial<VentilationAssessment> = {}): VentilationAssessment {
  return {
    recommended: true,
    outdoorTemperature: 21.2,
    rooms: [
      { name: 'Schlafzimmer', temperature: 26.4 },
      { name: 'Wohnzimmer', temperature: 24.6 }
    ],
    evaluatedAt: '2026-08-12T18:40:00',
    ...overrides
  };
}

describe('buildVentilationInsight', () => {

  it('liefert keine Karte ohne Bewertung', () => {
    expect(buildVentilationInsight(null)).toBeNull();
  });

  it('liefert keine Karte, wenn keine Aussage moeglich ist', () => {
    expect(buildVentilationInsight(assessment({ recommended: null }))).toBeNull();
  });

  it('liefert keine Karte, wenn Lueften nichts bringt', () => {
    expect(buildVentilationInsight(assessment({ recommended: false, rooms: [] }))).toBeNull();
  });

  it('baut die Sammelkarte mit gerundeten Temperaturen', () => {
    const insight = buildVentilationInsight(assessment());

    expect(insight?.title).toBe('Lüften lohnt sich');
    expect(insight?.icon).toBe('air');
    expect(insight?.tone).toBe('secondary');
    expect(insight?.text)
      .toBe('Draußen 21° — kühler als Schlafzimmer (26°), Wohnzimmer (25°)');
  });

  it('liefert keine Karte bei fehlendem Aussenwert trotz recommended', () => {
    // Defensive: eine widerspruechliche Antwort (recommended, aber kein Aussenwert)
    // darf keine "Draußen null°"-Karte erzeugen.
    expect(buildVentilationInsight(assessment({ outdoorTemperature: null }))).toBeNull();
  });
});
```

- [ ] **Step 2: Tests laufen lassen — sie müssen fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -15`
Expected: Kompilierfehler ("Cannot find module './ventilation-insight.util'")

- [ ] **Step 3: Util implementieren**

`ventilation-insight.util.ts`:

```typescript
import { VentilationAssessment } from '../models/ventilation.model';
import { HubInsight } from './hub-insight.model';

/**
 * Baut aus der Backend-Bewertung die Hub-Sammelkarte, z. B.
 * "Draußen 21° — kühler als Schlafzimmer (26°), Wohnzimmer (25°)".
 *
 * @returns `null`, wenn keine Empfehlung besteht oder keine Aussage moeglich ist
 * (`recommended` null) — dann erscheint im Hub keine Karte. Auch eine
 * widerspruechliche Antwort ohne Aussenwert oder ohne Raeume liefert `null`,
 * statt eine kaputte Karte zu rendern.
 */
export function buildVentilationInsight(
  assessment: VentilationAssessment | null): HubInsight | null {
  if (!assessment?.recommended
      || assessment.outdoorTemperature === null
      || assessment.rooms.length === 0) {
    return null;
  }
  const rooms = assessment.rooms
    .map(room => `${room.name} (${Math.round(room.temperature)}°)`)
    .join(', ');
  return {
    icon: 'air',
    tone: 'secondary',
    title: 'Lüften lohnt sich',
    text: `Draußen ${Math.round(assessment.outdoorTemperature)}° — kühler als ${rooms}`
  };
}
```

- [ ] **Step 4: Tests laufen lassen — sie müssen bestehen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -15`
Expected: alle `buildVentilationInsight`-Specs grün; insgesamt nur die 3 Baseline-Fails (`AppComponent` ×2, `HeroComponent`).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/ventilation-insight.util.ts frontend/src/app/shared/ventilation-insight.util.spec.ts
git commit -m "feat(frontend): Hub-Karte der Lueftungsempfehlung"
```

---

### Task 7: Frontend — Dashboard-Einbindung

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`

- [ ] **Step 1: Imports ergänzen**

Bei den bestehenden Imports (nahe `buildCalendarInsights`, Zeile ~35):

```typescript
import { InsightService } from '../../services/insight.service';
import { buildVentilationInsight } from '../../shared/ventilation-insight.util';
import { VentilationAssessment } from '../../models/ventilation.model';
```

- [ ] **Step 2: Service, Feld und Subscription anlegen**

Bei den anderen `inject(...)`-Feldern (nahe `temperatureService`, Zeile ~76):

```typescript
  private readonly insightService = inject(InsightService);
```

Bei den Insight-Feldern (nach `calendarInsights`, Zeile ~246):

```typescript
  /** Zuletzt gebaute Lueftungs-Karte; null = keine Empfehlung. */
  private ventilationInsight: HubInsight | null = null;
```

Bei den Subscription-Feldern (nahe `temperatureSubscription`, Zeile ~113):

```typescript
  private ventilationSubscription?: Subscription;
```

- [ ] **Step 3: Refresh starten und aufräumen**

In `ngOnInit()` nach `this.startCalendarRefresh();`:

```typescript
    this.startVentilationRefresh();
```

In `ngOnDestroy()` bei den anderen `unsubscribe()`-Aufrufen:

```typescript
    this.ventilationSubscription?.unsubscribe();
```

Neue Methode direkt nach `startClimateRefresh()` (Zeile ~1007):

```typescript
  /** Haelt die Lueftungs-Karte im Hub aktuell (gleicher Takt wie die Klima-Kacheln). */
  private startVentilationRefresh(): void {
    this.ventilationSubscription = interval(DashboardComponent.CLIMATE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() =>
          this.insightService.getVentilation().pipe(
            catchError(() => of<VentilationAssessment | null>(null))
          )
        )
      )
      .subscribe(assessment => {
        this.ventilationInsight = buildVentilationInsight(assessment);
        this.rebuildInsights();
      });
  }
```

- [ ] **Step 4: In `rebuildInsights()` einsortieren** (Zeile ~1105)

Müll und Termine sind terminlich fix und bleiben vorn; die Lüftungs-Karte ist ein
Hinweis und kommt dahinter, vor den Platzhaltern:

```typescript
  /** Komponiert den Hub: Muell voran, dann Termine, dann Lueften, dahinter die Platzhalter. */
  private rebuildInsights(): void {
    this.insights = [
      ...(this.wasteInsight ? [this.wasteInsight] : []),
      ...this.calendarInsights,
      ...(this.ventilationInsight ? [this.ventilationInsight] : []),
      ...DashboardComponent.PLACEHOLDER_INSIGHTS
    ];
  }
```

- [ ] **Step 5: Tests und Build**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -15`
Expected: nur die 3 Baseline-Fails.

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -5`
Expected: Build erfolgreich (kein SCSS geändert, Budget unverändert).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts
git commit -m "feat(frontend): Lueftungs-Karte im Intelligence Hub"
```

---

### Task 8: Verifikation gesamt

- [ ] **Step 1: Backend-Tests komplett**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test`
Expected: nur die 2 vorbestehenden DB-Fails.

- [ ] **Step 2: Frontend-Tests komplett**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -15`
Expected: nur die 3 Baseline-Fails.

- [ ] **Step 3: Manuelle Sichtprüfung (optional, wenn Backend lokal läuft)**

`GET http://localhost:8080/api/v1/insights/ventilation` liefert JSON mit
`recommended`/`outdoorTemperature`/`rooms`/`evaluatedAt`; im August mit warmen Räumen
und kühlem Abend sollte die Karte im Dashboard erscheinen.

**Hinweis fürs Deployment (nicht Teil dieses Plans):** Die Entität
`binary_sensor.insight_ventilation` entsteht erst nach dem ersten Reporter-Lauf
(~1 Minute nach Start). Ein Flow darauf (z. B. Telegram-Meldung) ist bewusst nicht
Teil dieses Plans.
