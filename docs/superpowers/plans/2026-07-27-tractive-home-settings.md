# Tractive-Home-Einstellungen in DB und Admin-Bereich — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Definition von „zu Hause" für den Hundetracker wandert aus `application.properties`/Umgebungsvariablen in die Datenbank und wird über eine Admin-Seite mit Karte gepflegt.

**Architecture:** Neue Kategorie `TRACTIVE_HOME` in der bestehenden Tabelle `application_settings`. `TractiveHomeSettingsService` ist eine typisierte, defensiv parsende Fassade darüber (Muster: `WasteCollectionSettingsService`). `TractiveHomeResolver` und `TractiveZoneResolver` lesen künftig daraus statt aus `TractiveProperties`; der Home-Resolver holt die Einstellungen genau einmal pro `resolve()`-Aufruf, damit eine Bewertung einen konsistenten Satz Werte sieht. Ein neuer ADMIN-geschützter Controller und eine Leaflet-Admin-Seite machen die Werte pflegbar.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Lombok, JUnit 5, Mockito, AssertJ, MockMvc; Angular 19 standalone, Leaflet, SCSS.

**Spec:** `docs/superpowers/specs/2026-07-27-tractive-home-settings-design.md`

---

## Vorbedingungen (einmal pro Session)

Backend-Kommandos laufen aus `backend/`, und **`JAVA_HOME` muss vorher auf JDK 21 zeigen** — der Maschinen-Default ist JDK 17 und `mvn` bricht sonst sofort ab:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Es gibt kein `mvnw`. Bekannte, **vorbestehende** Fehlschläge, die nichts mit dieser Arbeit zu tun haben:

- Backend: `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` (2 Methoden) — zusammen **3 Errors**, Ursache ist ausschließlich die nicht erreichbare lokale Test-DB (`Access denied for user 'root'@'localhost'`). Deshalb werden unten gezielte `-Dtest=…`-Läufe verwendet.
- Frontend: **3 dauerhaft rote Tests** (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Baseline ist „3 FAILED"; nur *zusätzliche* Fehlschläge sind Regressionen. Gelegentliche Karma-Flake in `SmartDeviceListComponent` (`Cannot read properties of undefined (reading 'subscribe')`) → einfach erneut laufen lassen.

**Sprachkonvention:** Javadoc und Kommentare sind Deutsch, in Java-Quelltext **ohne Umlaute** (`fuer`, `Entitaet`, `zurueck`). Die Code-Blöcke unten exakt so übernehmen; die fehlenden Umlaute sind Absicht.

## Dateiübersicht

**Neu (Backend):**
- `tractive/TractiveHomeSettings.java` — Wertetyp der sieben Einstellungen
- `tractive/TractiveHomeSettingsService.java` — Fassade über `ApplicationSettingsService`, defensives Parsen
- `tractive/TractiveHomeSettingsController.java` — `GET`/`PUT /v1/tractive/home-settings`
- Tests: `TractiveHomeSettingsServiceTest`, `TractiveHomeSettingsControllerTest`

**Geändert (Backend):**
- `tractive/TractiveHomeResolver.java`, `tractive/TractiveZoneResolver.java` — lesen aus dem Settings-Service
- `tractive/TractiveProperties.java`, `src/main/resources/application.properties`, `docker-compose.yml` — Home-Werte raus
- `security/SecurityConfig.java` — ADMIN-Regel für den neuen Pfad
- Tests: `TractiveHomeResolverTest`, `TractiveZoneResolverTest`, `TractiveEntityMapperTest`, `TractivePetServiceTest`

**Neu (Frontend):**
- `models/tractive-home-settings.model.ts`
- `pages/admin-tractive/admin-tractive.component.{ts,html,scss}`

**Geändert (Frontend):**
- `services/tractive.service.ts`, `app.routes.ts`, `components/header/header.component.ts`

**Geändert (Doku):** `CLAUDE.md`

---

## Task 1: `TractiveHomeSettings` und `TractiveHomeSettingsService`

Der Kern. Alles Weitere baut darauf auf.

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveHomeSettings.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveHomeSettingsService.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveHomeSettingsServiceTest.java`

- [ ] **Step 1: Wertetyp anlegen**

Reine Daten plus zwei abgeleitete Fragen — kein eigener Test, wird über die Service- und Resolver-Tests vollständig abgedeckt.

`TractiveHomeSettings.java`:

```java
package com.household.manager.tractive;

/**
 * Die Definition von "zu Hause", wie sie in der Datenbank steht.
 *
 * <p>{@code homeLatitude}/{@code homeLongitude} sind {@code null}, solange nichts
 * konfiguriert ist – dann entsteht keine Home-Entitaet. Alle uebrigen Werte sind
 * immer belegt, notfalls mit dem Default.
 */
public record TractiveHomeSettings(
        Double homeLatitude,
        Double homeLongitude,
        double homeRadiusMeters,
        double homeArrivalRadiusMeters,
        long poweredOffAfterMinutes,
        int poweredOffMinBatteryPercent,
        String homeZoneName
) {

    /** Nur ein vollstaendiges Koordinatenpaar zaehlt als konfiguriert. */
    public boolean hasHomeCoordinates() {
        return homeLatitude != null && homeLongitude != null;
    }

    /** Ein kleiner konfigurierter Ankunftsradius darf Regel 4 nicht unwirksam machen. */
    public double effectiveArrivalRadiusMeters() {
        return Math.max(homeArrivalRadiusMeters, homeRadiusMeters);
    }
}
```

- [ ] **Step 2: Den fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/tractive/TractiveHomeSettingsServiceTest.java`:

```java
package com.household.manager.tractive;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TractiveHomeSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;

    private TractiveHomeSettings settingsFrom(Map<String, String> stored) {
        when(applicationSettings.getSettingsByCategory("TRACTIVE_HOME")).thenReturn(stored);
        return new TractiveHomeSettingsService(applicationSettings, auditService).getSettings();
    }

    @Test
    void withoutStoredValuesTheDefaultsApply() {
        TractiveHomeSettings settings = settingsFrom(Map.of());

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeRadiusMeters()).isEqualTo(100);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(500);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(60);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(15);
        assertThat(settings.homeZoneName()).isEqualTo("Zuhause");
    }

    @Test
    void storedValuesAreRead() {
        TractiveHomeSettings settings = settingsFrom(Map.of(
                "home_latitude", "48.2082",
                "home_longitude", "16.3738",
                "home_radius_meters", "120.0",
                "home_arrival_radius_meters", "400.0",
                "powered_off_after_minutes", "45",
                "powered_off_min_battery_percent", "20",
                "home_zone_name", "Daheim"));

        assertThat(settings.homeLatitude()).isEqualTo(48.2082);
        assertThat(settings.homeLongitude()).isEqualTo(16.3738);
        assertThat(settings.homeRadiusMeters()).isEqualTo(120.0);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(400.0);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(45);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(20);
        assertThat(settings.homeZoneName()).isEqualTo("Daheim");
    }

    /**
     * Der Poller laeuft jede Minute – ein Tippfehler in der Datenbank darf ihn nicht
     * mit einer NumberFormatException lahmlegen.
     */
    @Test
    void unreadableValuesFallBackToDefaultsInsteadOfThrowing() {
        TractiveHomeSettings settings = settingsFrom(Map.of(
                "home_latitude", "keine Zahl",
                "home_longitude", "auch nicht",
                "home_radius_meters", "abc",
                "home_arrival_radius_meters", "",
                "powered_off_after_minutes", "x",
                "powered_off_min_battery_percent", "y",
                "home_zone_name", ""));

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeRadiusMeters()).isEqualTo(100);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(500);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(60);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(15);
        assertThat(settings.homeZoneName()).isEqualTo("Zuhause");
    }

    /** Direkt in der DB geschriebene Ausreisser umgeht die Controller-Validierung. */
    @Test
    void implausibleStoredValuesFallBackToDefaults() {
        TractiveHomeSettings settings = settingsFrom(Map.of(
                "home_latitude", "480.0",
                "home_longitude", "16.3738",
                "home_radius_meters", "0",
                "home_arrival_radius_meters", "-5",
                "powered_off_after_minutes", "0",
                "powered_off_min_battery_percent", "150"));

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeRadiusMeters()).isEqualTo(100);
        assertThat(settings.homeArrivalRadiusMeters()).isEqualTo(500);
        assertThat(settings.poweredOffAfterMinutes()).isEqualTo(60);
        assertThat(settings.poweredOffMinBatteryPercent()).isEqualTo(15);
    }

    /**
     * Eine halbe Koordinate ist keine Position: sonst zeigte das Formular einen Wert,
     * waehrend der Resolver still auf "nicht konfiguriert" steht.
     */
    @Test
    void aSingleCoordinateCountsAsNotConfigured() {
        TractiveHomeSettings settings = settingsFrom(Map.of("home_latitude", "48.2082"));

        assertThat(settings.hasHomeCoordinates()).isFalse();
        assertThat(settings.homeLatitude()).isNull();
        assertThat(settings.homeLongitude()).isNull();
    }

    @Test
    void savingWritesAllKeysInOneCallAndAudits() {
        var service = new TractiveHomeSettingsService(applicationSettings, auditService);

        service.saveSettings(new TractiveHomeSettings(
                48.2082, 16.3738, 120, 400, 45, 20, "Daheim"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(applicationSettings).saveSettings(eq("TRACTIVE_HOME"), captor.capture());
        Map<String, String> written = captor.getValue();
        assertThat(written).containsKeys("home_latitude", "home_longitude", "home_radius_meters",
                "home_arrival_radius_meters", "powered_off_after_minutes",
                "powered_off_min_battery_percent", "home_zone_name");
        assertThat(written.get("home_latitude")).isEqualTo("48.2082");
        assertThat(written.get("home_zone_name")).isEqualTo("Daheim");
        verify(auditService).record(eq("tractive.home-settings.update"), org.mockito.ArgumentMatchers.anyString());
    }

    /** Leere Koordinaten muessen als leerer String landen – die Spalte ist NOT NULL. */
    @Test
    void clearingTheCoordinatesStoresEmptyStrings() {
        var service = new TractiveHomeSettingsService(applicationSettings, auditService);

        service.saveSettings(new TractiveHomeSettings(null, null, 100, 500, 60, 15, "Zuhause"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(applicationSettings).saveSettings(eq("TRACTIVE_HOME"), captor.capture());
        assertThat(captor.getValue().get("home_latitude")).isEmpty();
        assertThat(captor.getValue().get("home_longitude")).isEmpty();
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractiveHomeSettingsServiceTest test`
Expected: COMPILATION ERROR — `cannot find symbol: class TractiveHomeSettingsService`

- [ ] **Step 4: Den Service implementieren**

`TractiveHomeSettingsService.java`:

```java
package com.household.manager.tractive;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uebersetzt zwischen {@link TractiveHomeSettings} und den String-Werten in
 * {@code application_settings}.
 *
 * <p>Kein Lesevorgang wirft: der Poller laeuft jede Minute, und ein Tippfehler in der
 * Datenbank darf ihn nicht lahmlegen. Unlesbare oder unplausible Werte fallen auf den
 * Default zurueck und werden geloggt. Die Controller-Validierung verhindert solche Werte
 * beim Speichern – ein direkter DB-Zugriff umgeht sie aber.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveHomeSettingsService {

    static final String CATEGORY = "TRACTIVE_HOME";

    static final String KEY_LATITUDE = "home_latitude";
    static final String KEY_LONGITUDE = "home_longitude";
    static final String KEY_RADIUS = "home_radius_meters";
    static final String KEY_ARRIVAL_RADIUS = "home_arrival_radius_meters";
    static final String KEY_POWERED_OFF_AFTER = "powered_off_after_minutes";
    static final String KEY_MIN_BATTERY = "powered_off_min_battery_percent";
    static final String KEY_ZONE_NAME = "home_zone_name";

    static final double DEFAULT_RADIUS_METERS = 100;
    static final double DEFAULT_ARRIVAL_RADIUS_METERS = 500;
    static final long DEFAULT_POWERED_OFF_AFTER_MINUTES = 60;
    static final int DEFAULT_MIN_BATTERY_PERCENT = 15;
    static final String DEFAULT_ZONE_NAME = "Zuhause";

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    /**
     * Liest die ganze Kategorie in einer Abfrage. Aufrufer sollen das Ergebnis einmal holen
     * und damit weiterrechnen, damit eine Bewertung einen konsistenten Satz Werte sieht.
     */
    public TractiveHomeSettings getSettings() {
        Map<String, String> values = applicationSettings.getSettingsByCategory(CATEGORY);

        Double latitude = coordinate(values.get(KEY_LATITUDE), KEY_LATITUDE, 90);
        Double longitude = coordinate(values.get(KEY_LONGITUDE), KEY_LONGITUDE, 180);
        // Eine halbe Koordinate ist keine Position.
        if (latitude == null || longitude == null) {
            latitude = null;
            longitude = null;
        }

        return new TractiveHomeSettings(
                latitude,
                longitude,
                positiveDouble(values.get(KEY_RADIUS), KEY_RADIUS, DEFAULT_RADIUS_METERS),
                positiveDouble(values.get(KEY_ARRIVAL_RADIUS), KEY_ARRIVAL_RADIUS,
                        DEFAULT_ARRIVAL_RADIUS_METERS),
                positiveLong(values.get(KEY_POWERED_OFF_AFTER), KEY_POWERED_OFF_AFTER,
                        DEFAULT_POWERED_OFF_AFTER_MINUTES),
                percent(values.get(KEY_MIN_BATTERY), KEY_MIN_BATTERY, DEFAULT_MIN_BATTERY_PERCENT),
                zoneName(values.get(KEY_ZONE_NAME)));
    }

    /**
     * Ein einziger {@code saveSettings}-Aufruf, damit ein Fehler mitten drin keine halb
     * aktualisierte Konfiguration hinterlaesst.
     */
    public void saveSettings(TractiveHomeSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_LATITUDE, text(settings.homeLatitude()));
        values.put(KEY_LONGITUDE, text(settings.homeLongitude()));
        values.put(KEY_RADIUS, String.valueOf(settings.homeRadiusMeters()));
        values.put(KEY_ARRIVAL_RADIUS, String.valueOf(settings.homeArrivalRadiusMeters()));
        values.put(KEY_POWERED_OFF_AFTER, String.valueOf(settings.poweredOffAfterMinutes()));
        values.put(KEY_MIN_BATTERY, String.valueOf(settings.poweredOffMinBatteryPercent()));
        values.put(KEY_ZONE_NAME, settings.homeZoneName() == null || settings.homeZoneName().isBlank()
                ? DEFAULT_ZONE_NAME : settings.homeZoneName());

        applicationSettings.saveSettings(CATEGORY, values);
        // Wer verschiebt, was "zu Hause" heisst, aendert das Verhalten jedes darauf
        // gebauten Flows – das gehoert nachvollziehbar protokolliert.
        auditService.record("tractive.home-settings.update",
                settings.hasHomeCoordinates()
                        ? settings.homeLatitude() + ", " + settings.homeLongitude()
                        : "Koordinaten entfernt");
        log.info("Tractive-Home-Einstellungen gespeichert");
    }

    /** Leere Koordinate wird als leerer String abgelegt – die Spalte ist NOT NULL. */
    private String text(Double value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** {@code null} heisst "nicht konfiguriert". */
    private Double coordinate(String raw, String key, double limit) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || Math.abs(value) > limit) {
                log.warn("Unplausibler Wert '{}' fuer {}, wird ignoriert", raw, key);
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, wird ignoriert", raw, key);
            return null;
        }
    }

    private double positiveDouble(String raw, String key, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value <= 0) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private long positiveLong(String raw, String key, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private int percent(String raw, String key, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || value > 100) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private String zoneName(String raw) {
        return raw == null || raw.isBlank() ? DEFAULT_ZONE_NAME : raw;
    }
}
```

- [ ] **Step 5: Test laufen lassen und grün bestätigen**

Run: `mvn -Dtest=TractiveHomeSettingsServiceTest test`
Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveHomeSettings.java backend/src/main/java/com/household/manager/tractive/TractiveHomeSettingsService.java backend/src/test/java/com/household/manager/tractive/TractiveHomeSettingsServiceTest.java
git commit -m "feat(tractive): Home-Einstellungen aus der Datenbank lesen und schreiben"
```

---

## Task 2: Beide Resolver auf die DB-Einstellungen umstellen

Bewusst **ein** Task für beide Resolver: `TractiveEntityMapperTest` konstruiert beide, und zwei getrennte Konstruktoränderungen würden dieselbe Testdatei zweimal aufbrechen.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveHomeResolver.java`
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveZoneResolver.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveHomeResolverTest.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveZoneResolverTest.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/TractiveEntityMapperTest.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractivePetServiceTest.java`

**Alle bestehenden Zusicherungen bleiben erhalten** — insbesondere die sicherheitskritischen: `unknown` statt geratenem `away`, Fail-safe bei fehlendem Akkustand, Regel 2 (Laden) vor Regel 6 (keine Position), und `max(...)` beim Ankunftsradius. Keine davon darf abgeschwächt werden, um den Umbau zum Laufen zu bringen.

- [ ] **Step 1: `TractiveHomeResolverTest` umstellen**

Die Klasse baut heute `TractiveProperties`. Ersetze die beiden Helfer `propertiesWithHome()` und `resolve(...)` sowie die Imports.

Imports ergänzen:

```java
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

`propertiesWithHome()` **löschen** und durch diese drei Helfer ersetzen:

```java
    private TractiveHomeSettings settings(double homeRadiusMeters, double arrivalRadiusMeters) {
        return new TractiveHomeSettings(HOME_LAT, HOME_LON, homeRadiusMeters, arrivalRadiusMeters,
                60, 15, "Zuhause");
    }

    private TractiveHomeSettings defaultSettings() {
        return settings(100, 500);
    }

    private TractiveHomeSettings notConfigured() {
        return new TractiveHomeSettings(null, null, 100, 500, 60, 15, "Zuhause");
    }
```

Den Helfer `resolve(TractiveProperties, TractivePetSnapshot)` ersetzen durch:

```java
    /**
     * Der Resolver wird bewusst mit einem Mock des Settings-Service gebaut: dieser Test
     * prueft die Regeln, nicht das Auslesen der Datenbank (das deckt
     * TractiveHomeSettingsServiceTest ab).
     */
    private Optional<HomeVerdict> resolve(TractiveHomeSettings settings, TractivePetSnapshot snapshot) {
        TractiveHomeSettingsService settingsService = mock(TractiveHomeSettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);
        return new TractiveHomeResolver(settingsService).resolve(snapshot, NOW);
    }
```

Dann in jedem Test die Aufrufe anpassen:
- `resolve(propertiesWithHome(), snapshot)` → `resolve(defaultSettings(), snapshot)`
- `resolve(new TractiveProperties(), snapshot)` (in `withoutHomeCoordinatesThereIsNoVerdict`) → `resolve(notConfigured(), snapshot)`

Zwei Tests mutieren heute die Properties und werden vollständig ersetzt:

```java
    /** Der Rand zaehlt als innerhalb – konsistent zu GeoZone.contains. */
    @Test
    void aPositionExactlyOnTheHomeRadiusCountsAsAtHome() {
        double distance = GeoZone.distanceMeters(HOME_LAT, HOME_LON, NEAR_LAT, HOME_LON);
        var position = positionAgedMinutes(NEAR_LAT, HOME_LON, 5);

        HomeVerdict verdict = resolve(settings(distance, 500), snapshot(position,
                new TractiveHardwareDto(87, "NOT_CHARGING"))).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
    }

    /** Ein zu klein konfigurierter Ankunftsradius darf Regel 4 nicht unwirksam machen. */
    @Test
    void theArrivalRadiusNeverFallsBelowTheHomeRadius() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(settings(400, 10), snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POWERED_OFF);
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractiveHomeResolverTest test`
Expected: COMPILATION ERROR — der Konstruktor `TractiveHomeResolver(TractiveHomeSettingsService)` existiert nicht

- [ ] **Step 3: `TractiveHomeResolver` umstellen**

`TractiveHomeResolver.java` vollständig ersetzen durch:

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Die einzige Definition von "zu Hause". Entity-Mapper und Haustier-API fragen
 * dieselbe Instanz, damit Dashboard-Kachel und Flow-Trigger nicht auseinanderlaufen.
 *
 * <p>Der Tracker wird zu Hause ausgeschaltet, und die Tractive-API kennt dafuer kein
 * Statusfeld – erkennbar ist es nur an einem ausbleibenden Positionsbericht. Weil
 * "Akku unterwegs leergelaufen" genauso aussieht, verlangt diese Deutung zwei
 * unabhaengige Belege: einen gesunden Akkustand und Heimnaehe im weiten Radius.
 *
 * <p>{@link Optional#empty()} bedeutet ueberall dasselbe: keine Aussage moeglich. Es wird
 * nie ein Zustand geraten – ein Alarm-Flow darf nicht bei jedem GPS-Aussetzer feuern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TractiveHomeResolver {

    private final TractiveHomeSettingsService settingsService;

    /** Die Warnung ueber fehlende Home-Koordinaten soll nicht jede Minute im Log stehen. */
    private final AtomicBoolean missingHomeWarned = new AtomicBoolean();

    /**
     * Die Warnung ueber einen Uhren-Versatz soll nicht mehrfach pro Minute und Tier im Log
     * stehen – seit die Haustier-API denselben Resolver bei jedem REST-Aufruf befragt, koennte
     * ein dauerhafter Versatz sonst mehrere WARN-Zeilen pro Minute pro Tier erzeugen.
     */
    private final AtomicBoolean clockSkewWarned = new AtomicBoolean();

    public Optional<HomeVerdict> resolve(TractivePetSnapshot snapshot, Instant now) {
        // Einmal lesen: innerhalb einer Bewertung darf sich die Definition nicht aendern.
        TractiveHomeSettings settings = settingsService.getSettings();

        if (!settings.hasHomeCoordinates()) {
            warnAboutMissingHomeOnce();
            return Optional.empty();
        }
        // Zuruecksetzen, damit ein spaeteres Entfernen der Koordinaten wieder auffaellt.
        missingHomeWarned.set(false);

        TractiveHardwareDto hardware = snapshot.hardware();
        if (hardware != null && hardware.isCharging()) {
            return Optional.of(HomeVerdict.charging());
        }

        TractivePositionDto position = snapshot.position();
        if (position == null || !position.hasCoordinates()) {
            return Optional.empty();
        }

        double distanceMeters = GeoZone.distanceMeters(
                settings.homeLatitude(), settings.homeLongitude(),
                position.latitude(), position.longitude());
        Long ageMinutes = positionAgeMinutes(position, now);
        boolean stale = ageMinutes != null && ageMinutes >= settings.poweredOffAfterMinutes();

        if (stale && looksPoweredOffAtHome(hardware, distanceMeters, settings)) {
            return Optional.of(HomeVerdict.poweredOff(distanceMeters, ageMinutes));
        }
        return Optional.of(HomeVerdict.fromPosition(
                distanceMeters <= settings.homeRadiusMeters(), stale, distanceMeters, ageMinutes));
    }

    private void warnAboutMissingHomeOnce() {
        if (missingHomeWarned.compareAndSet(false, true)) {
            log.warn("Kein Zuhause hinterlegt (Admin -> Hundetracker) – "
                    + "die Entitaet 'zu Hause' wird nicht gemeldet.");
        }
    }

    /** {@code null}, wenn der Bericht keinen Zeitstempel hat – dann gilt er als frisch. */
    private Long positionAgeMinutes(TractivePositionDto position, Instant now) {
        Instant reportedAt = position.reportedAt();
        if (reportedAt == null) {
            return null;
        }
        long minutes = Duration.between(reportedAt, now).toMinutes();
        if (minutes < 0) {
            // Uhren-Versatz zur Tractive-Cloud. Auf 0 geklemmt gilt der Bericht als frisch,
            // was fail-safe ist – aber ein dauerhafter Versatz wuerde Regel 4 unerreichbar
            // machen und einen zu Hause ausgeschalteten Tracker fuer immer als "unterwegs"
            // melden. Deshalb sichtbar machen statt still schlucken.
            if (clockSkewWarned.compareAndSet(false, true)) {
                log.warn("Tractive-Positionsbericht liegt {} Minuten in der Zukunft – Uhren-Versatz?",
                        -minutes);
            }
            return 0L;
        }
        return minutes;
    }

    /**
     * Fail-safe: ohne Akkustand wird nicht auf "ausgeschaltet" geschlossen. Sonst wuerde
     * ein unterwegs verlorener Tracker als "zu Hause" gemeldet.
     */
    private boolean looksPoweredOffAtHome(TractiveHardwareDto hardware, double distanceMeters,
                                          TractiveHomeSettings settings) {
        if (hardware == null || hardware.batteryLevel() == null) {
            return false;
        }
        return hardware.batteryLevel() >= settings.poweredOffMinBatteryPercent()
                && distanceMeters <= settings.effectiveArrivalRadiusMeters();
    }
}
```

- [ ] **Step 4: `TractiveZoneResolverTest` umstellen**

Imports ergänzen:

```java
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

Den Helfer `propertiesWithHome()` **löschen** und ersetzen durch:

```java
    private TractiveZoneResolver resolverWith(TractiveHomeSettings settings) {
        TractiveHomeSettingsService settingsService = mock(TractiveHomeSettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);
        return new TractiveZoneResolver(settingsService);
    }

    private TractiveZoneResolver resolverWithHome() {
        return resolverWith(new TractiveHomeSettings(48.2082, 16.3738, 100, 500, 60, 15, "Zuhause"));
    }

    private TractiveZoneResolver resolverWithoutHome() {
        return resolverWith(new TractiveHomeSettings(null, null, 100, 500, 60, 15, "Zuhause"));
    }
```

Die vier Tests werden zu:

```java
    @Test
    void positionInsideAZoneYieldsTheZoneName() {
        TractiveZoneResolver resolver = resolverWithoutHome();
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("Garten", resolver.resolve(48.2082, 16.3738, zones));
    }

    @Test
    void positionOutsideAllZonesYieldsAway() {
        TractiveZoneResolver resolver = resolverWithoutHome();
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("away", resolver.resolve(48.3000, 16.3738, zones));
    }

    @Test
    void homeZoneIsUsedWhenNoZonesAreKnown() {
        TractiveZoneResolver resolver = resolverWithHome();

        assertEquals("Zuhause", resolver.resolve(48.2082, 16.3738, List.of()));
        assertEquals("away", resolver.resolve(48.3000, 16.3738, List.of()));
    }

    @Test
    void withoutZonesAndWithoutHomeTheStateIsUnknown() {
        TractiveZoneResolver resolver = resolverWithoutHome();

        assertEquals("unknown", resolver.resolve(48.2082, 16.3738, List.of()));
    }
```

- [ ] **Step 5: `TractiveZoneResolver` umstellen**

`TractiveZoneResolver.java` vollständig ersetzen durch:

```java
package com.household.manager.tractive;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bestimmt aus einer Position den Zonennamen. Bekannte Zonen gewinnen; ist keine
 * bekannt, greift die konfigurierte Home-Zone. Ohne beides bleibt der Zustand
 * {@code unknown} – ein Zonenzustand wird nie geraten, damit Geofence-Flows
 * nicht auf erfundenen Werten feuern.
 */
@Component
@RequiredArgsConstructor
public class TractiveZoneResolver {

    /** Zustand ausserhalb aller bekannten Zonen. */
    public static final String AWAY = "away";
    /** Zustand, wenn keine Zonenaussage moeglich ist. */
    public static final String UNKNOWN = "unknown";

    private final TractiveHomeSettingsService settingsService;

    public String resolve(double latitude, double longitude, List<GeoZone> zones) {
        List<GeoZone> effectiveZones = zones.isEmpty() ? homeZone() : zones;
        if (effectiveZones.isEmpty()) {
            return UNKNOWN;
        }
        return effectiveZones.stream()
                .filter(zone -> zone.contains(latitude, longitude))
                .map(GeoZone::name)
                .findFirst()
                .orElse(AWAY);
    }

    /** Wird nur ohne bekannte Geofences gebraucht – kostet sonst keine Abfrage. */
    private List<GeoZone> homeZone() {
        TractiveHomeSettings settings = settingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            return List.of();
        }
        return List.of(new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(),
                settings.homeRadiusMeters()));
    }
}
```

- [ ] **Step 6: `TractiveEntityMapperTest` nachziehen**

Die `setUp()`-Methode baut heute beide Resolver aus `TractiveProperties`. Ersetzen durch:

```java
    private TractiveEntityMapper mapper;

    @BeforeEach
    void setUp() {
        // Home-Koordinaten sind zugleich die Fallback-Zone des TractiveZoneResolver.
        TractiveHomeSettings settings =
                new TractiveHomeSettings(48.2082, 16.3738, 100, 500, 60, 15, "Zuhause");
        TractiveHomeSettingsService settingsService = mock(TractiveHomeSettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);
        mapper = new TractiveEntityMapper(new TractiveZoneResolver(settingsService),
                new TractiveHomeResolver(settingsService));
    }
```

Imports ergänzen (und den nun ungenutzten `TractiveProperties`-Import entfernen):

```java
import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

- [ ] **Step 7: `TractivePetServiceTest` nachziehen**

Der Helfer `homeResolver()` baut heute `TractiveProperties`. Ersetzen durch:

```java
    /** Bewusst der echte Resolver: der Test soll die reale Home-Definition pruefen. */
    private TractiveHomeResolver homeResolver() {
        TractiveHomeSettings settings =
                new TractiveHomeSettings(48.2082, 16.3738, 100, 500, 60, 15, "Zuhause");
        TractiveHomeSettingsService settingsService = mock(TractiveHomeSettingsService.class);
        when(settingsService.getSettings()).thenReturn(settings);
        return new TractiveHomeResolver(settingsService);
    }
```

`mock` und `when` sind dort bereits statisch importiert (Mockito wird schon genutzt); `TractiveProperties` ist danach möglicherweise ungenutzt — Import entfernen, falls der Compiler das anmahnt.

- [ ] **Step 8: Alle betroffenen Tests laufen lassen**

Run: `mvn -Dtest='Tractive*Test,TractiveEntityMapperTest,GeoZoneTest' test`
Expected: BUILD SUCCESS, keine Failures. `TractiveHomeResolverTest` weiterhin 18 Tests, `TractiveZoneResolverTest` 4, `TractiveEntityMapperTest` 11, `TractivePetServiceTest` 7.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveHomeResolver.java backend/src/main/java/com/household/manager/tractive/TractiveZoneResolver.java backend/src/test/java/com/household/manager/tractive/TractiveHomeResolverTest.java backend/src/test/java/com/household/manager/tractive/TractiveZoneResolverTest.java backend/src/test/java/com/household/manager/entitystate/mapper/TractiveEntityMapperTest.java backend/src/test/java/com/household/manager/tractive/TractivePetServiceTest.java
git commit -m "refactor(tractive): Resolver lesen das Zuhause aus der Datenbank"
```

---

## Task 3: `TractiveProperties` und Konfiguration bereinigen

Erst jetzt, wo niemand mehr darauf zugreift.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveProperties.java`
- Modify: `backend/src/main/resources/application.properties`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Die sieben Felder entfernen**

`TractiveProperties.java` vollständig ersetzen durch:

```java
package com.household.manager.tractive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Verbindungs- und Poll-Einstellungen der Tractive-Integration.
 *
 * <p>Was "zu Hause" bedeutet, steht bewusst NICHT hier, sondern in der Datenbank
 * ({@link TractiveHomeSettingsService}) und wird im Admin-Bereich gepflegt.
 */
@Configuration
@ConfigurationProperties(prefix = "tractive")
@Data
public class TractiveProperties {

    private boolean enabled = true;
    private String baseUrl = "https://graph.tractive.com/4";
    /** Oeffentliche Client-ID der Tractive-App; kein Geheimnis. */
    private String clientId = "625e533dc3c3b41c28a669f0";
    private long pollIntervalMs = 60000;
    private long initialDelayMs = 20000;
    private int httpTimeoutMs = 10000;
}
```

- [ ] **Step 2: `application.properties` bereinigen**

Diese Zeilen ersatzlos löschen (sie stehen direkt unter den übrigen `tractive.*`-Zeilen):

```properties
tractive.home-latitude=${TRACTIVE_HOME_LAT:}
tractive.home-longitude=${TRACTIVE_HOME_LON:}
tractive.home-radius-meters=100
tractive.home-arrival-radius-meters=500
tractive.powered-off-after-minutes=60
tractive.powered-off-min-battery-percent=15
```

Falls eine leere Kommentarzeile oder Leerzeile allein zurückbleibt, mit entfernen.

- [ ] **Step 3: `docker-compose.yml` bereinigen**

Diesen Block im `environment` des Backend-Service ersatzlos löschen:

```yaml
      # Tractive-Hundetracker: definiert, was "zu Hause" heisst.
      # Ohne diese beiden Werte entsteht binary_sensor.tractive_<id>_home nicht
      # (sichtbar nur an einer Warnung im Log, sonst passiert stillschweigend nichts).
      TRACTIVE_HOME_LAT: ${TRACTIVE_HOME_LAT:-}
      TRACTIVE_HOME_LON: ${TRACTIVE_HOME_LON:-}
```

- [ ] **Step 4: Kompilieren und Tests laufen lassen**

Run: `mvn -Dtest='Tractive*Test,TractiveEntityMapperTest,GeoZoneTest' test`
Expected: BUILD SUCCESS. Bleibt irgendwo ein Zugriff auf ein entferntes Feld, scheitert bereits die Kompilierung — dann die Fundstelle nachziehen, nicht das Feld zurückholen.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveProperties.java backend/src/main/resources/application.properties docker-compose.yml
git commit -m "refactor(tractive): Home-Konfiguration aus Properties und Compose entfernen"
```

---

## Task 4: API und Sicherheitsregel

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveHomeSettingsController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java:146-147`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveHomeSettingsControllerTest.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (Rollenmatrix)

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/tractive/TractiveHomeSettingsControllerTest.java`:

```java
package com.household.manager.tractive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TractiveHomeSettingsControllerTest {

    @Mock
    private TractiveHomeSettingsService settingsService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveHomeSettingsController(settingsService))
                .build();
    }

    private String body(Double latitude, Double longitude, double radius, double arrivalRadius,
                        long minutes, int battery, String zoneName) throws Exception {
        return objectMapper.writeValueAsString(new TractiveHomeSettings(
                latitude, longitude, radius, arrivalRadius, minutes, battery, zoneName));
    }

    @Test
    void getReturnsTheStoredSettings() throws Exception {
        when(settingsService.getSettings()).thenReturn(new TractiveHomeSettings(
                48.2082, 16.3738, 100, 500, 60, 15, "Zuhause"));

        mockMvc.perform(get("/v1/tractive/home-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeLatitude").value(48.2082))
                .andExpect(jsonPath("$.homeZoneName").value("Zuhause"));
    }

    @Test
    void validSettingsAreSaved() throws Exception {
        when(settingsService.getSettings()).thenReturn(new TractiveHomeSettings(
                48.2082, 16.3738, 120, 400, 45, 20, "Daheim"));

        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 120, 400, 45, 20, "Daheim")))
                .andExpect(status().isOk());

        verify(settingsService).saveSettings(new TractiveHomeSettings(
                48.2082, 16.3738, 120, 400, 45, 20, "Daheim"));
    }

    /** Koordinaten duerfen geleert werden – das Feature wird damit bewusst abgeschaltet. */
    @Test
    void emptyCoordinatesAreAllowed() throws Exception {
        when(settingsService.getSettings()).thenReturn(new TractiveHomeSettings(
                null, null, 100, 500, 60, 15, "Zuhause"));

        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isOk());
    }

    @Test
    void anImpossibleLatitudeIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(480.0, 16.3738, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());

        verify(settingsService, never()).saveSettings(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anImpossibleLongitudeIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 200.0, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    /** Eine halbe Koordinate ist keine Position und wuerde still als "nicht konfiguriert" enden. */
    @Test
    void halfACoordinateIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, null, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroRadiusIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 0, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroSilenceThresholdIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 100, 500, 0, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBatteryPercentAbove100IsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 100, 500, 60, 150, "Zuhause")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractiveHomeSettingsControllerTest test`
Expected: COMPILATION ERROR — `cannot find symbol: class TractiveHomeSettingsController`

- [ ] **Step 3: Den Controller implementieren**

`TractiveHomeSettingsController.java`:

```java
package com.household.manager.tractive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pflege der Home-Definition im Admin-Bereich.
 *
 * <p>Der Zugriff ist ueber die Matcher-Reihenfolge in
 * {@code SecurityConfig} auf ADMIN beschraenkt – die generische Regel
 * {@code GET /v1/**} wuerde sonst auch dem Kiosk-Tablet das Lesen erlauben.
 */
@RestController
@RequestMapping("/v1/tractive/home-settings")
@RequiredArgsConstructor
public class TractiveHomeSettingsController {

    private final TractiveHomeSettingsService settingsService;

    @GetMapping
    public ResponseEntity<TractiveHomeSettings> get() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<TractiveHomeSettings> update(@RequestBody TractiveHomeSettings settings) {
        validate(settings);
        settingsService.saveSettings(settings);
        return ResponseEntity.ok(settingsService.getSettings());
    }

    /**
     * Serverseitig, nicht nur im Formular: ein vertippter Breitengrad darf nicht in die
     * Datenbank, weil er dort still zu "nicht konfiguriert" wird und die Entitaet
     * verschwinden laesst.
     */
    private void validate(TractiveHomeSettings settings) {
        boolean hasLatitude = settings.homeLatitude() != null;
        boolean hasLongitude = settings.homeLongitude() != null;
        if (hasLatitude != hasLongitude) {
            throw badRequest("Breiten- und Laengengrad muessen gemeinsam gesetzt oder gemeinsam leer sein.");
        }
        if (hasLatitude && Math.abs(settings.homeLatitude()) > 90) {
            throw badRequest("Der Breitengrad muss zwischen -90 und 90 liegen.");
        }
        if (hasLongitude && Math.abs(settings.homeLongitude()) > 180) {
            throw badRequest("Der Laengengrad muss zwischen -180 und 180 liegen.");
        }
        if (settings.homeRadiusMeters() <= 0) {
            throw badRequest("Der Home-Radius muss groesser als 0 sein.");
        }
        if (settings.homeArrivalRadiusMeters() <= 0) {
            throw badRequest("Der Ankunftsradius muss groesser als 0 sein.");
        }
        if (settings.poweredOffAfterMinutes() <= 0) {
            throw badRequest("Die Stille-Schwelle muss groesser als 0 Minuten sein.");
        }
        if (settings.poweredOffMinBatteryPercent() < 0 || settings.poweredOffMinBatteryPercent() > 100) {
            throw badRequest("Der Mindest-Akkustand muss zwischen 0 und 100 Prozent liegen.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
```

- [ ] **Step 4: Test laufen lassen und grün bestätigen**

Run: `mvn -Dtest=TractiveHomeSettingsControllerTest test`
Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

- [ ] **Step 5: Sicherheitsregel ergänzen**

In `SecurityConfig.java` die bestehende ADMIN-Zeile (aktuell Zeile 146–147)

```java
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**", "/v1/tractive/login", "/v1/tractive/logout").hasRole("ADMIN")
```

ersetzen durch:

```java
                        // /v1/tractive/home-settings MUSS vor der generischen GET-Regel weiter
                        // unten stehen, sonst duerfte das Kiosk-Tablet die Home-Definition lesen.
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**", "/v1/tractive/login", "/v1/tractive/logout",
                                "/v1/tractive/home-settings").hasRole("ADMIN")
```

- [ ] **Step 6: Sicherheitstest ergänzen**

Ohne diesen Test ist die Matcher-Reihenfolge nur eine Behauptung.

Die Rollenmatrix wird in `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` gegen echte HTTP-Requests geprüft. Dort gilt die dokumentierte Konvention: URLs ohne Controller im WebMvc-Slice liefern bei *erlaubter* Rolle **404** statt 403 — das genügt als Beleg, dass die Regel durchlässt. `TractiveHomeSettingsController` ist nicht im Slice, also gilt genau das hier.

Ans Ende der Klasse anfügen:

```java
    /**
     * Die generische Regel GET /v1/** laesst KIOSK lesen. Nur weil der ADMIN-Block in
     * SecurityConfig davor steht, bleibt die Home-Definition dem Wandtablet verborgen –
     * dieser Test haelt genau diese Reihenfolge fest.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieHomeEinstellungenNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/tractive/home-settings")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDarfDieHomeEinstellungenLesen() throws Exception {
        // Kein TractiveHomeSettingsController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst.
        mockMvc.perform(get("/v1/tractive/home-settings")).andExpect(status().isNotFound());
    }
```

Alle benötigten Imports (`get`, `status`, `WithMockUser`, `Test`) sind in der Datei bereits vorhanden.

- [ ] **Step 7: Tests laufen lassen**

Run: `mvn -Dtest='Tractive*Test,SecurityRulesTest' test`
Expected: BUILD SUCCESS, keine Failures

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveHomeSettingsController.java backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/tractive/TractiveHomeSettingsControllerTest.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(tractive): ADMIN-API fuer die Home-Einstellungen"
```

---

## Task 5: Frontend-Modell und Service

**Files:**
- Create: `frontend/src/app/models/tractive-home-settings.model.ts`
- Modify: `frontend/src/app/services/tractive.service.ts`

- [ ] **Step 1: Modell anlegen**

`frontend/src/app/models/tractive-home-settings.model.ts`:

```typescript
/**
 * Die Definition von „zu Hause" fuer den Hundetracker.
 * Ohne Koordinaten (beide null) existiert die Zu-Hause-Entitaet nicht.
 */
export interface TractiveHomeSettings {
  homeLatitude: number | null;
  homeLongitude: number | null;
  homeRadiusMeters: number;
  homeArrivalRadiusMeters: number;
  poweredOffAfterMinutes: number;
  poweredOffMinBatteryPercent: number;
  homeZoneName: string;
}
```

- [ ] **Step 2: Service-Methoden ergänzen**

In `tractive.service.ts` den Import ergänzen:

```typescript
import { TractiveHomeSettings } from '../models/tractive-home-settings.model';
```

Und vor `private handleError` diese beiden Methoden einfügen:

```typescript
  getHomeSettings(): Observable<TractiveHomeSettings> {
    return this.http.get<TractiveHomeSettings>(`${this.baseUrl}/home-settings`).pipe(
      catchError(this.handleError)
    );
  }

  /** Fehler werden bewusst NICHT geschluckt: die Seite zeigt die 400-Meldung des Servers an. */
  saveHomeSettings(settings: TractiveHomeSettings): Observable<TractiveHomeSettings> {
    return this.http.put<TractiveHomeSettings>(`${this.baseUrl}/home-settings`, settings);
  }
```

- [ ] **Step 3: Build prüfen**

Run (aus `frontend/`): `npm run build`
Expected: „Application bundle generation complete", keine Fehler

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/tractive-home-settings.model.ts frontend/src/app/services/tractive.service.ts
git commit -m "feat(pets): Frontend-Anbindung der Home-Einstellungen"
```

---

## Task 6: Admin-Seite mit Karte

**Files:**
- Create: `frontend/src/app/pages/admin-tractive/admin-tractive.component.ts`
- Create: `frontend/src/app/pages/admin-tractive/admin-tractive.component.html`
- Create: `frontend/src/app/pages/admin-tractive/admin-tractive.component.scss`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts:72`

**Hinweis zu CSRF:** Diese Route hat `adminGuard` und feuert in `ngOnInit` ein GET, bevor gespeichert werden kann — das `XSRF-TOKEN`-Cookie ist also gesetzt, wenn der PUT abgeht. Ein `primeCsrfToken()` wie auf der Login-Seite ist hier **nicht** nötig.

- [ ] **Step 1: Komponente anlegen**

`admin-tractive.component.ts`:

```typescript
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import { TractiveService } from '../../services/tractive.service';
import { TractiveHomeSettings } from '../../models/tractive-home-settings.model';

/**
 * Leaflet ermittelt die Standard-Marker-Icons ueber eine relative URL zum aktuell
 * ausgefuehrten Skript; unter dem Angular-Bundler schlaegt das schweigend fehl. Die
 * Icons kommen deshalb lokal aus den Angular-Assets, nie per CDN – die Anzeige muss
 * auch ohne Internetzugang funktionieren.
 */
function fixLeafletDefaultIcon(): void {
  const iconPrototype = L.Icon.Default.prototype as L.Icon.Default & { _getIconUrl?: unknown };
  delete iconPrototype._getIconUrl;
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: 'assets/leaflet/marker-icon-2x.png',
    iconUrl: 'assets/leaflet/marker-icon.png',
    shadowUrl: 'assets/leaflet/marker-shadow.png'
  });
}
fixLeafletDefaultIcon();

/** Mitte Deutschlands – nur der Startausschnitt, solange nichts konfiguriert ist. */
const FALLBACK_CENTER: L.LatLngExpression = [51.1657, 10.4515];
const FALLBACK_ZOOM = 6;
const CONFIGURED_ZOOM = 17;

/** Admin-Seite „Hundetracker": Definition von „zu Hause" pflegen. */
@Component({
  selector: 'app-admin-tractive',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-tractive.component.html',
  styleUrl: './admin-tractive.component.scss'
})
export class AdminTractiveComponent implements OnInit, OnDestroy {
  private readonly tractiveService = inject(TractiveService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);

  settings: TractiveHomeSettings = {
    homeLatitude: null,
    homeLongitude: null,
    homeRadiusMeters: 100,
    homeArrivalRadiusMeters: 500,
    poweredOffAfterMinutes: 60,
    poweredOffMinBatteryPercent: 15,
    homeZoneName: 'Zuhause'
  };

  private map?: L.Map;
  private marker?: L.Marker;
  private homeCircle?: L.Circle;
  private arrivalCircle?: L.Circle;

  ngOnInit(): void {
    this.tractiveService.getHomeSettings().subscribe({
      next: settings => {
        this.settings = settings;
        this.loading.set(false);
        // Erst nach dem Rendern des Containers: die Karte braucht ein Element im DOM.
        setTimeout(() => this.initMap());
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Einstellungen konnten nicht geladen werden.');
      }
    });
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.map = undefined;
  }

  get hasCoordinates(): boolean {
    return this.settings.homeLatitude != null && this.settings.homeLongitude != null;
  }

  /** Nach jeder Zahleneingabe: Marker und Kreise der Karte nachziehen. */
  onValueChange(): void {
    this.successMessage.set(null);
    this.renderHome();
  }

  save(): void {
    this.saving.set(true);
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.tractiveService.saveHomeSettings(this.settings).subscribe({
      next: saved => {
        this.settings = saved;
        this.saving.set(false);
        this.successMessage.set('Gespeichert. Die Entitaet folgt beim naechsten Abruf (bis zu 1 Minute).');
        this.renderHome();
      },
      // Die 400-Meldung des Servers ist die praezisere – sie nennt das konkrete Feld.
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(err.error?.message ?? 'Speichern fehlgeschlagen.');
      }
    });
  }

  clearCoordinates(): void {
    this.settings.homeLatitude = null;
    this.settings.homeLongitude = null;
    this.renderHome();
  }

  private initMap(): void {
    const container = document.getElementById('home-map');
    if (!container || this.map) {
      return;
    }
    const center: L.LatLngExpression = this.hasCoordinates
      ? [this.settings.homeLatitude!, this.settings.homeLongitude!]
      : FALLBACK_CENTER;
    this.map = L.map(container).setView(center, this.hasCoordinates ? CONFIGURED_ZOOM : FALLBACK_ZOOM);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap',
      maxZoom: 19
    }).addTo(this.map);
    this.map.on('click', (event: L.LeafletMouseEvent) => {
      this.settings.homeLatitude = Number(event.latlng.lat.toFixed(6));
      this.settings.homeLongitude = Number(event.latlng.lng.toFixed(6));
      this.successMessage.set(null);
      this.renderHome();
    });
    this.renderHome();
  }

  /** Marker und beide Radien neu zeichnen; ohne Koordinaten werden sie entfernt. */
  private renderHome(): void {
    if (!this.map) {
      return;
    }
    if (!this.hasCoordinates) {
      this.marker?.remove();
      this.homeCircle?.remove();
      this.arrivalCircle?.remove();
      this.marker = undefined;
      this.homeCircle = undefined;
      this.arrivalCircle = undefined;
      return;
    }
    const position: L.LatLngExpression = [this.settings.homeLatitude!, this.settings.homeLongitude!];

    this.marker ? this.marker.setLatLng(position)
      : (this.marker = L.marker(position).addTo(this.map));

    // Der Ankunftsradius wird nie kleiner als der Home-Radius gezeichnet – genau so
    // rechnet auch das Backend (effectiveArrivalRadiusMeters).
    const arrivalRadius = Math.max(this.settings.homeArrivalRadiusMeters, this.settings.homeRadiusMeters);

    if (this.arrivalCircle) {
      this.arrivalCircle.setLatLng(position).setRadius(arrivalRadius);
    } else {
      this.arrivalCircle = L.circle(position, {
        radius: arrivalRadius, color: '#f59e0b', weight: 1, fillOpacity: 0.05
      }).addTo(this.map);
    }
    if (this.homeCircle) {
      this.homeCircle.setLatLng(position).setRadius(this.settings.homeRadiusMeters);
    } else {
      this.homeCircle = L.circle(position, {
        radius: this.settings.homeRadiusMeters, color: '#16a34a', weight: 2, fillOpacity: 0.12
      }).addTo(this.map);
    }
  }
}
```

- [ ] **Step 2: Template anlegen**

`admin-tractive.component.html`:

```html
<div class="admin-tractive">
  <h1>Hundetracker – Zuhause festlegen</h1>

  <p *ngIf="loading()">Wird geladen …</p>

  <ng-container *ngIf="!loading()">
    <p class="warning" *ngIf="!hasCoordinates">
      Es ist kein Zuhause hinterlegt. Ohne Koordinaten gibt es keine „Hund zu Hause"-Entitaet,
      kein Badge auf der Hundetracker-Seite und keine Dashboard-Kachel.
      Klicke auf der Karte auf dein Haus.
    </p>

    <div id="home-map" class="map"></div>

    <div class="fields">
      <label>
        Breitengrad
        <input type="number" step="0.000001" name="latitude"
               [(ngModel)]="settings.homeLatitude" (ngModelChange)="onValueChange()">
      </label>
      <label>
        Laengengrad
        <input type="number" step="0.000001" name="longitude"
               [(ngModel)]="settings.homeLongitude" (ngModelChange)="onValueChange()">
      </label>
      <label>
        Home-Radius (m)
        <input type="number" min="1" name="radius"
               [(ngModel)]="settings.homeRadiusMeters" (ngModelChange)="onValueChange()">
        <span class="hint">Innerhalb dieses Kreises gilt der Hund als zu Hause.</span>
      </label>
      <label>
        Ankunftsradius (m)
        <input type="number" min="1" name="arrivalRadius"
               [(ngModel)]="settings.homeArrivalRadiusMeters" (ngModelChange)="onValueChange()">
        <span class="hint">
          Weiter Radius fuer den Fall, dass der Tracker zu Hause ausgeschaltet wird und der
          letzte Bericht noch von unterwegs stammt.
        </span>
      </label>
      <label>
        Stille-Schwelle (Minuten)
        <input type="number" min="1" name="poweredOffAfter"
               [(ngModel)]="settings.poweredOffAfterMinutes" (ngModelChange)="onValueChange()">
        <span class="hint">
          Ab wann ein ausbleibender Positionsbericht als „Tracker ausgeschaltet" gilt.
        </span>
      </label>
      <label>
        Mindest-Akkustand (%)
        <input type="number" min="0" max="100" name="minBattery"
               [(ngModel)]="settings.poweredOffMinBatteryPercent" (ngModelChange)="onValueChange()">
        <span class="hint">
          Darunter gilt Stille als „Akku leer" statt als „ausgeschaltet".
        </span>
      </label>
      <label>
        Name der Zone
        <input type="text" name="zoneName"
               [(ngModel)]="settings.homeZoneName" (ngModelChange)="onValueChange()">
        <span class="hint">Erscheint als Zustand des Standort-Sensors.</span>
      </label>
    </div>

    <div class="actions">
      <button type="button" (click)="save()" [disabled]="saving()">
        {{ saving() ? 'Speichert …' : 'Speichern' }}
      </button>
      <button type="button" class="secondary" (click)="clearCoordinates()" *ngIf="hasCoordinates">
        Koordinaten entfernen
      </button>
    </div>

    <p class="error" *ngIf="errorMessage()">{{ errorMessage() }}</p>
    <p class="success" *ngIf="successMessage()">{{ successMessage() }}</p>
  </ng-container>
</div>
```

- [ ] **Step 3: Styles anlegen**

`admin-tractive.component.scss`:

```scss
.admin-tractive {
  padding: 1.5rem;
  max-width: 1000px;
  margin: 0 auto;
}

.map {
  height: 420px;
  width: 100%;
  border-radius: 12px;
  margin-bottom: 1.5rem;
}

.warning {
  padding: 0.75rem 1rem;
  border-radius: 8px;
  background: #fef3c7;
  color: #7c2d12;
  font-weight: 600;
}

.fields {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 1rem;

  label {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    font-weight: 600;
  }

  input {
    padding: 0.5rem;
    border-radius: 6px;
    border: 1px solid #ccc;
  }
}

.hint {
  font-weight: 400;
  font-size: 0.85rem;
  opacity: 0.75;
}

.actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
}

.secondary {
  opacity: 0.8;
}

.error {
  color: #c0392b;
}

.success {
  color: #14532d;
}
```

- [ ] **Step 4: Route registrieren**

In `app.routes.ts` direkt vor dem `admin/audit-log`-Eintrag einfügen:

```typescript
  {
    path: 'admin/tractive',
    loadComponent: () => import('./pages/admin-tractive/admin-tractive.component').then(m => m.AdminTractiveComponent),
    canActivate: [adminGuard],
    title: 'Hundetracker - Household Manager'
  },
```

- [ ] **Step 5: Menüpunkt ergänzen**

In `header.component.ts` die Zeile

```typescript
        { path: '/admin/audit-log', label: 'Audit-Log', minRole: 'ADMIN' }
```

ersetzen durch:

```typescript
        { path: '/admin/audit-log', label: 'Audit-Log', minRole: 'ADMIN' },
        { path: '/admin/tractive', label: 'Hundetracker', minRole: 'ADMIN' }
```

- [ ] **Step 6: Build prüfen**

Run (aus `frontend/`): `npm run build`
Expected: „Application bundle generation complete", keine Fehler. Die Warnung zum SCSS-Budget von `dashboard.component.scss` und die Leaflet-CommonJS-Warnung sind vorbestehend.

- [ ] **Step 7: Frontend-Tests gegen die Baseline prüfen**

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: `TOTAL: 3 FAILED` — mehr wäre eine Regression.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/pages/admin-tractive/ frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(admin): Seite zum Pflegen der Home-Definition mit Karte"
```

---

## Task 7: Dokumentation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Tractive-Hundetracker")

- [ ] **Step 1: Den Konfigurationssatz ersetzen**

Im Abschnitt „### Tractive-Hundetracker" den Aufzählungspunkt, der mit
„`tractive.home-latitude`/`-longitude` haben eine **Doppelrolle**" beginnt, vollständig
ersetzen durch:

```markdown
- **Was „zu Hause" heißt, steht in der Datenbank, nicht in der Konfiguration** (`application_settings`, Kategorie `TRACTIVE_HOME`; Fassade `TractiveHomeSettingsService`, gepflegt unter Admin → Hundetracker, Route `admin/tractive`). Pflegbar sind Koordinaten, Home-Radius, Ankunftsradius, Stille-Schwelle, Mindest-Akkustand und der Zonenname. Die Werte haben eine **Doppelrolle**: Fallback-Zone für den Location-Sensor *und* verbindliche Home-Definition. Ohne Koordinaten fehlen Entität, Badge und Dashboard-Kachel — sichtbar nur an einer einmaligen Warnung im Log. `TRACTIVE_HOME_LAT`/`TRACTIVE_HOME_LON` und die zugehörigen `tractive.*`-Properties gibt es **nicht mehr**
- **Lesen wirft nie.** `TractiveHomeSettingsService` parst defensiv: unlesbare oder unplausible Werte fallen auf den Default zurück und werden geloggt. Der Poller läuft jede Minute; ein Tippfehler in der DB darf ihn nicht lahmlegen. Die Controller-Validierung verhindert solche Werte beim Speichern, ein direkter DB-Zugriff umgeht sie aber
- **Sicherheit hängt an der Matcher-Reihenfolge:** `/v1/tractive/home-settings` steht im ADMIN-Block von `SecurityConfig`, der **vor** der generischen Regel `GET /v1/**` → KIOSK steht. Ohne diese Reihenfolge könnte das Wandtablet die Home-Definition lesen. Änderungen landen im Audit-Log (`tractive.home-settings.update`)
- Neue Einstellungen wirken **beim nächsten Poll** (≤ 60 s). Kachel und Badge rechnen bei jedem Abruf frisch gegen die zwischengespeicherten Positionsdaten und übernehmen Schwellenänderungen praktisch sofort; nur die Entität wartet auf den Poll
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(tractive): Home-Definition liegt in der Datenbank"
```

---

## Task 8: Abschlussprüfung

- [ ] **Step 1: Vollständiger Backend-Testlauf**

Run (aus `backend/`): `mvn test`
Expected: BUILD FAILURE mit **genau drei** Errors — `HouseholdManagerApplicationTests.contextLoads` und die zwei Methoden von `HealthControllerTest`, alle mit `Access denied for user 'root'@'localhost'`. Jeder weitere Fehlschlag ist eine echte Regression.

- [ ] **Step 2: Frontend-Build und -Tests**

Run (aus `frontend/`): `npm run build`
Expected: „Application bundle generation complete"

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: `TOTAL: 3 FAILED`

- [ ] **Step 3: Nachweisen, dass keine alte Konfiguration übrig ist**

Run: `grep -rn "TRACTIVE_HOME_LAT\|TRACTIVE_HOME_LON\|tractive.home-latitude\|tractive.home-longitude\|home-radius-meters\|powered-off-after-minutes\|powered-off-min-battery-percent" --include=*.java --include=*.properties --include=*.yml .`
Expected: **keine Treffer** außerhalb von `docs/`. Treffer in `docs/superpowers/specs/` und `docs/superpowers/plans/` sind historische Dokumente und bleiben.

- [ ] **Step 4: Offene Punkte an den Nutzer melden**

Nicht automatisch verifizierbar und deshalb ausdrücklich zu berichten:

1. Nach dem Deployment muss die Seite Admin → Hundetracker einmal ausgefüllt werden — vorher gibt es keine Zu-Hause-Entität.
2. Die Stille-Schwelle (60 Min) bleibt geraten; nach ein paar Tagen anhand des Entity-Attributs `positionAgeMinutes` nachziehen — das geht jetzt ohne Redeploy.
3. Ob `device_hw_report` bei ausgeschaltetem Tracker noch einen Akkustand liefert, ist weiterhin offen; ohne ihn greift die Ausschalt-Erkennung nie.
