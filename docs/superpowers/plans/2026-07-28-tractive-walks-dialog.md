# Spaziergänge-Dialog (Tractive) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Klick auf die Hund-Kachel im Dashboard öffnet einen Dialog mit den Spaziergängen der letzten 7 Tage (Start–Ende, Dauer, Distanz), abgeleitet aus der Tractive-Positionshistorie.

**Architecture:** Neuer API-Client-Aufruf `getPositionHistory` (Positions-Endpunkt, defensiv geparst) → reine Heuristik-Klasse `TractiveWalkDetector` (testbar ohne Mocks) → `TractiveWalkService` (Token, Home-Settings, 5-Min-Cache) → Endpunkt `GET /v1/tractive/pets/{trackerId}/walks`. Frontend: Kachel klickbar, Dialog inline im Dashboard nach dem Muster des Verlaufs-Dialogs.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JUnit + MockRestServiceServer + Mockito; Angular 19 standalone.

**Spec:** `docs/superpowers/specs/2026-07-28-tractive-walks-dialog-design.md`

**Build-Hinweis:** Vor `mvn` JAVA_HOME auf JDK 21 setzen (Maschinen-Default ist JDK 17):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
```

---

### Task 1: `TractiveApiClient.getPositionHistory`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java`

- [ ] **Step 1: Failing Test schreiben** (in `TractiveApiClientTest`, hinter `positionReportParsesLatLong`):

```java
@Test
void positionHistoryParsesSegments() {
    server.expect(requestTo("https://graph.tractive.com/4/tracker/dev-9/positions"
                    + "?time_from=1800000000&time_to=1800086400&format=json_segments"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer tok-1"))
            .andExpect(header("x-tractive-user", "u-1"))
            .andRespond(withSuccess("""
                    [[{"time": 1800000000, "latlong": [48.2082, 16.3738],
                       "sensor_used": "GPS", "alt": 200, "speed": 1.2},
                      {"time": 1800000060, "latlong": [48.2090, 16.3745]}],
                     [{"time": 1800040000, "latlong": [48.2100, 16.3800]}]]
                    """, MediaType.APPLICATION_JSON));

    var segments = client.getPositionHistory("tok-1", "u-1", "dev-9",
            java.time.Instant.ofEpochSecond(1800000000L),
            java.time.Instant.ofEpochSecond(1800086400L));

    assertEquals(2, segments.size());
    assertEquals(2, segments.get(0).size());
    assertEquals(48.2082, segments.get(0).get(0).latitude());
    server.verify();
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen** (Compile-Fehler: Methode fehlt)

Run: `cd backend; mvn test -Dtest=TractiveApiClientTest`
Expected: FAIL (cannot find symbol `getPositionHistory`)

- [ ] **Step 3: Implementierung** (in `TractiveApiClient`, hinter `getHardware`):

```java
/**
 * Positionshistorie des Trackers als Segmente (Liste von Punktlisten).
 * Antwortform nur gegen Fremdbibliotheken verifiziert; Aufrufer muessen
 * unplausible Punkte selbst verwerfen.
 */
public List<List<TractivePositionDto>> getPositionHistory(String token, String userId,
                                                          String trackerId,
                                                          java.time.Instant from,
                                                          java.time.Instant to) {
    String path = "/tracker/" + trackerId + "/positions"
            + "?time_from=" + from.getEpochSecond()
            + "&time_to=" + to.getEpochSecond()
            + "&format=json_segments";
    return getList(path, token, userId,
            new ParameterizedTypeReference<List<List<TractivePositionDto>>>() {
            });
}
```

(`java.time.Instant` oben in die Imports ziehen: `import java.time.Instant;` und im Code `Instant` verwenden.)

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `cd backend; mvn test -Dtest=TractiveApiClientTest`
Expected: PASS (alle Tests der Klasse)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java
git commit -m "feat(tractive): Positionshistorie im API-Client"
```

---

### Task 2: `TractiveWalkDto` + `TractiveWalkDetector` (Heuristik)

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/dto/TractiveWalkDto.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveWalkDetector.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveWalkDetectorTest.java`

- [ ] **Step 1: DTO anlegen** (kein Test nötig, reiner Record):

```java
package com.household.manager.tractive.dto;

import java.time.Instant;

/** Ein aus der Positionshistorie abgeleiteter Spaziergang. */
public record TractiveWalkDto(
        Instant start,
        Instant end,
        long durationMinutes,
        double distanceMeters
) {
}
```

- [ ] **Step 2: Failing Tests schreiben** (`TractiveWalkDetectorTest`):

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TractiveWalkDetectorTest {

    /** Zuhause in Wien, Radius 100 m. */
    private static final GeoZone HOME = new GeoZone("Zuhause", 48.2082, 16.3738, 100);
    private static final Instant T0 = Instant.ofEpochSecond(1_800_000_000L);

    /** Punkt ~50 m vom Zuhause (innerhalb). */
    private TractivePositionDto homePoint(long minutesAfterT0) {
        return point(48.2086, 16.3738, minutesAfterT0);
    }

    /** Punkt ~1,1 km vom Zuhause (außerhalb). */
    private TractivePositionDto awayPoint(long minutesAfterT0) {
        return point(48.2182, 16.3738, minutesAfterT0);
    }

    private TractivePositionDto point(double lat, double lon, long minutesAfterT0) {
        return new TractivePositionDto(List.of(lat, lon), null, "GPS",
                T0.plusSeconds(minutesAfterT0 * 60).getEpochSecond());
    }

    @Test
    void leereEingabeErgibtKeineSpaziergaenge() {
        assertTrue(TractiveWalkDetector.detectWalks(List.of(), HOME).isEmpty());
    }

    @Test
    void nurZuhausePunkteErgebenKeineSpaziergaenge() {
        var walks = TractiveWalkDetector.detectWalks(
                List.of(homePoint(0), homePoint(10), homePoint(20)), HOME);
        assertTrue(walks.isEmpty());
    }

    @Test
    void zusammenhaengendeUnterwegsPunkteWerdenEinSpaziergang() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), awayPoint(5), awayPoint(10), awayPoint(35),
                homePoint(40)), HOME);

        assertEquals(1, walks.size());
        TractiveWalkDto walk = walks.get(0);
        assertEquals(T0.plusSeconds(5 * 60), walk.start());
        assertEquals(T0.plusSeconds(35 * 60), walk.end());
        assertEquals(30, walk.durationMinutes());
        assertTrue(walk.distanceMeters() > 0);
    }

    @Test
    void kurzerAusreisserUnterFuenfMinutenWirdVerworfen() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                homePoint(0), awayPoint(5), awayPoint(7), homePoint(10)), HOME);
        assertTrue(walks.isEmpty());
    }

    @Test
    void lueckeUnterZehnMinutenWirdUeberbrueckt() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(9), awayPoint(18)), HOME);
        assertEquals(1, walks.size());
        assertEquals(18, walks.get(0).durationMinutes());
    }

    @Test
    void lueckeAbZehnMinutenTeiltInZweiSpaziergaenge() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6), awayPoint(20), awayPoint(28)), HOME);
        assertEquals(2, walks.size());
    }

    @Test
    void heimpunktBeendetDenSpaziergangAuchInnerhalbDerLuecke() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6), homePoint(8), awayPoint(10), awayPoint(16)), HOME);
        assertEquals(2, walks.size());
    }

    @Test
    void unplausiblePunkteWerdenIgnoriert() {
        var kaputt = List.of(
                new TractivePositionDto(null, null, null, T0.getEpochSecond()),
                new TractivePositionDto(List.of(48.2182), null, null, T0.getEpochSecond()),
                new TractivePositionDto(Arrays.asList(48.2182, (Double) null), null, null,
                        T0.getEpochSecond()),
                new TractivePositionDto(List.of(Double.NaN, 16.3738), null, null,
                        T0.getEpochSecond()),
                new TractivePositionDto(List.of(48.2182, 16.3738), null, null, null));
        assertTrue(TractiveWalkDetector.detectWalks(kaputt, HOME).isEmpty());
    }

    @Test
    void unsortiertePunkteWerdenVorherSortiert() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(35), awayPoint(5), awayPoint(20)), HOME);
        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void neuesteSpaziergaengeStehenVorn() {
        var walks = TractiveWalkDetector.detectWalks(List.of(
                awayPoint(0), awayPoint(6),
                awayPoint(60), awayPoint(70)), HOME);
        assertEquals(2, walks.size());
        assertTrue(walks.get(0).start().isAfter(walks.get(1).start()));
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `cd backend; mvn test -Dtest=TractiveWalkDetectorTest`
Expected: FAIL (Klasse `TractiveWalkDetector` fehlt)

- [ ] **Step 4: Implementierung** (`TractiveWalkDetector.java`):

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Leitet Spaziergaenge aus der rohen Positionshistorie ab: zusammenhaengende
 * Zeitraeume ausserhalb des Home-Radius. Bewusste Unschaerfe (siehe Spec):
 * jede Abwesenheit zaehlt, und im Stromsparmodus meldet der Tracker selten –
 * kurze Runden koennen verschluckt werden.
 */
public final class TractiveWalkDetector {

    /** Berichte kommen unregelmaessig; kuerzere Luecken gelten als derselbe Spaziergang. */
    static final Duration MAX_GAP = Duration.ofMinutes(10);
    /** GPS-Jitter am Radiusrand erzeugt Sekunden-"Spaziergaenge" – die fliegen raus. */
    static final Duration MIN_DURATION = Duration.ofMinutes(5);

    private TractiveWalkDetector() {
    }

    public static List<TractiveWalkDto> detectWalks(List<TractivePositionDto> points, GeoZone home) {
        List<TractivePositionDto> usable = points.stream()
                .filter(TractiveWalkDetector::isUsable)
                .sorted(Comparator.comparing(TractivePositionDto::time))
                .toList();

        List<TractiveWalkDto> walks = new ArrayList<>();
        List<TractivePositionDto> current = new ArrayList<>();
        for (TractivePositionDto point : usable) {
            boolean away = !home.contains(point.latitude(), point.longitude());
            if (!away) {
                closeWalk(current, walks);
                continue;
            }
            if (!current.isEmpty() && gapTooLarge(current.get(current.size() - 1), point)) {
                closeWalk(current, walks);
            }
            current.add(point);
        }
        closeWalk(current, walks);

        walks.sort(Comparator.comparing(TractiveWalkDto::start).reversed());
        return walks;
    }

    private static boolean isUsable(TractivePositionDto point) {
        // Double.isFinite ist an der API-Grenze tragend: Jackson macht aus dem
        // String "NaN" klaglos ein Double.NaN.
        return point.time() != null
                && point.hasCoordinates()
                && Double.isFinite(point.latitude())
                && Double.isFinite(point.longitude())
                && Math.abs(point.latitude()) <= 90
                && Math.abs(point.longitude()) <= 180;
    }

    private static boolean gapTooLarge(TractivePositionDto previous, TractivePositionDto next) {
        return Duration.between(previous.reportedAt(), next.reportedAt())
                .compareTo(MAX_GAP) >= 0;
    }

    private static void closeWalk(List<TractivePositionDto> current, List<TractiveWalkDto> walks) {
        if (current.isEmpty()) {
            return;
        }
        Instant start = current.get(0).reportedAt();
        Instant end = current.get(current.size() - 1).reportedAt();
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(MIN_DURATION) >= 0) {
            walks.add(new TractiveWalkDto(start, end, duration.toMinutes(), distance(current)));
        }
        current.clear();
    }

    private static double distance(List<TractivePositionDto> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += GeoZone.distanceMeters(
                    points.get(i - 1).latitude(), points.get(i - 1).longitude(),
                    points.get(i).latitude(), points.get(i).longitude());
        }
        return total;
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `cd backend; mvn test -Dtest=TractiveWalkDetectorTest`
Expected: PASS (10 Tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/dto/TractiveWalkDto.java backend/src/main/java/com/household/manager/tractive/TractiveWalkDetector.java backend/src/test/java/com/household/manager/tractive/TractiveWalkDetectorTest.java
git commit -m "feat(tractive): Spaziergang-Heuristik aus der Positionshistorie"
```

---

### Task 3: `TractiveWalkService` + Endpunkt

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveWalkService.java`
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveController.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveWalkServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben** (`TractiveWalkServiceTest`):

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TractiveWalkServiceTest {

    private static final TractiveHomeSettings HOME = new TractiveHomeSettings(
            48.2082, 16.3738, 100, 500, 60, 15, "Zuhause");
    private static final TractiveHomeSettings NO_HOME = new TractiveHomeSettings(
            null, null, 100, 500, 60, 15, "Zuhause");

    @Mock
    private TractiveApiClient apiClient;
    @Mock
    private TractiveAuthService authService;
    @Mock
    private TractiveHomeSettingsService homeSettingsService;

    private TractiveWalkService service;

    @BeforeEach
    void setUp() {
        service = new TractiveWalkService(apiClient, authService, homeSettingsService);
    }

    private TractiveAuth auth() {
        return TractiveAuth.builder().accessToken("tok-1").userId("u-1").build();
    }

    /** Zwei Unterwegs-Punkte, 30 Minuten auseinander – ein gueltiger Spaziergang. */
    private List<List<TractivePositionDto>> walkSegments() {
        long t = Instant.now().minusSeconds(3600).getEpochSecond();
        return List.of(List.of(
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", t),
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", t + 1800)));
    }

    @Test
    void ohneZuhauseKommtEineKlareFehlermeldung() {
        when(homeSettingsService.getSettings()).thenReturn(NO_HOME);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getWalks("dev-9", 7));
        assertTrue(ex.getMessage().contains("Zuhause"));
    }

    @Test
    void ohneTokenKommtEinAuthFehler() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.empty());

        assertThrows(TractiveAuthException.class, () -> service.getWalks("dev-9", 7));
    }

    @Test
    void liefertSpaziergaengeAusDerPositionshistorie() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
        when(apiClient.getPositionHistory(eq("tok-1"), eq("u-1"), eq("dev-9"),
                any(Instant.class), any(Instant.class))).thenReturn(walkSegments());

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void zweiterAbrufInnerhalbDerTtlKommtAusDemCache() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(walkSegments());

        service.getWalks("dev-9", 7);
        service.getWalks("dev-9", 7);

        verify(apiClient, times(1)).getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void tageWerdenAufDasMaximumGeklemmt() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(authService.getValidToken()).thenReturn(Optional.of(auth()));
        when(apiClient.getPositionHistory(anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 999);

        var fromCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(apiClient).getPositionHistory(anyString(), anyString(), anyString(),
                fromCaptor.capture(), any(Instant.class));
        long ageDays = java.time.Duration.between(fromCaptor.getValue(), Instant.now()).toDays();
        assertTrue(ageDays <= TractiveWalkService.MAX_DAYS);
    }
}
```

**Hinweis:** Die Konstruktor-Reihenfolge von `TractiveHomeSettings` gegen den Record in
`backend/src/main/java/com/household/manager/tractive/TractiveHomeSettings.java` prüfen
(erwartet: `homeLatitude, homeLongitude, homeRadiusMeters, homeArrivalRadiusMeters,
poweredOffAfterMinutes, poweredOffMinBatteryPercent, homeZoneName` — Reihenfolge aus
`TractiveHomeSettingsService.getSettings()`); bei Abweichung die Testdaten anpassen, nie den Record.

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd backend; mvn test -Dtest=TractiveWalkServiceTest`
Expected: FAIL (Klasse `TractiveWalkService` fehlt)

- [ ] **Step 3: Service implementieren** (`TractiveWalkService.java`):

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liefert Spaziergaenge on-the-fly aus der Tractive-Positionshistorie.
 * Ergebnis wird kurz gecacht, damit wiederholtes Oeffnen des Dialogs
 * nicht die Cloud haemmert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveWalkService {

    static final int DEFAULT_DAYS = 7;
    static final int MAX_DAYS = 14;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final TractiveApiClient apiClient;
    private final TractiveAuthService authService;
    private final TractiveHomeSettingsService homeSettingsService;

    private final Map<String, CachedWalks> cache = new ConcurrentHashMap<>();

    public List<TractiveWalkDto> getWalks(String trackerId, int days) {
        int clampedDays = Math.clamp(days, 1, MAX_DAYS);

        // Einmal lesen und damit weiterrechnen, damit eine Bewertung einen
        // konsistenten Satz Einstellungen sieht.
        TractiveHomeSettings settings = homeSettingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            throw new IllegalStateException(
                    "Kein Zuhause konfiguriert. Bitte unter Admin → Hundetracker-Zuhause festlegen.");
        }

        String cacheKey = trackerId + ":" + clampedDays;
        CachedWalks cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().isAfter(Instant.now().minus(CACHE_TTL))) {
            return cached.walks();
        }

        TractiveAuth auth = authService.getValidToken()
                .orElseThrow(() -> new TractiveAuthException("Nicht bei Tractive angemeldet."));

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(clampedDays));
        List<List<TractivePositionDto>> segments = apiClient.getPositionHistory(
                auth.getAccessToken(), auth.getUserId(), trackerId, from, to);

        GeoZone home = new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(), settings.homeRadiusMeters());
        List<TractiveWalkDto> walks = TractiveWalkDetector.detectWalks(
                segments.stream().flatMap(List::stream).toList(), home);

        cache.put(cacheKey, new CachedWalks(Instant.now(), walks));
        return walks;
    }

    private record CachedWalks(Instant fetchedAt, List<TractiveWalkDto> walks) {
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `cd backend; mvn test -Dtest=TractiveWalkServiceTest`
Expected: PASS (5 Tests)

- [ ] **Step 5: Endpunkt ergänzen** (`TractiveController.java`, komplett neu):

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liefert den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveController {

    private final TractivePetService petService;
    private final TractiveWalkService walkService;

    @GetMapping("/pets")
    public List<TractivePetDto> pets() {
        return petService.listPets();
    }

    /** Spaziergaenge der letzten Tage, abgeleitet aus der Positionshistorie. */
    @GetMapping("/pets/{trackerId}/walks")
    public List<TractiveWalkDto> walks(@PathVariable String trackerId,
                                       @RequestParam(defaultValue = "7") int days) {
        return walkService.getWalks(trackerId, days);
    }
}
```

**Achtung:** `TractiveControllerTest` instanziiert den Controller vermutlich direkt —
falls er nun nicht mehr kompiliert, dort einen Mock für `TractiveWalkService` ergänzen.

- [ ] **Step 6: Gesamtes Tractive-Testpaket laufen lassen**

Run: `cd backend; mvn test -Dtest="com.household.manager.tractive.*Test"`
Expected: PASS (lokale DB-Tests schlagen designbedingt fehl — nur bei `mvn test` ohne Filter relevant)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/ backend/src/test/java/com/household/manager/tractive/
git commit -m "feat(tractive): Spaziergaenge-Endpunkt mit Cache"
```

---

### Task 4: Frontend-Modell + Service-Methode

**Files:**
- Modify: `frontend/src/app/models/tractive.model.ts`
- Modify: `frontend/src/app/services/tractive.service.ts`

- [ ] **Step 1: Modell ergänzen** (ans Ende von `tractive.model.ts`):

```typescript
/** Ein aus der Positionshistorie abgeleiteter Spaziergang. */
export interface TractiveWalk {
  start: string;
  end: string;
  durationMinutes: number;
  distanceMeters: number;
}
```

- [ ] **Step 2: Service-Methode ergänzen** (in `tractive.service.ts`, hinter `getPets`; Import `TractiveWalk` in die bestehende Import-Zeile aufnehmen):

```typescript
  /**
   * Fehler werden bewusst NICHT auf eine Einheitsmeldung reduziert: der Dialog
   * zeigt die Server-Meldung an (z. B. "Kein Zuhause konfiguriert").
   */
  getWalks(trackerId: string, days = 7): Observable<TractiveWalk[]> {
    return this.http.get<TractiveWalk[]>(
      `${this.baseUrl}/pets/${trackerId}/walks`, { params: { days } });
  }
```

- [ ] **Step 3: Compile-Check**

Run: `cd frontend; npx ng build --configuration production`
Expected: Build erfolgreich

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/tractive.model.ts frontend/src/app/services/tractive.service.ts
git commit -m "feat(tractive): Frontend-Service fuer Spaziergaenge"
```

---

### Task 5: Dashboard — Kachel klickbar + Spaziergänge-Dialog

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Kachel ~Zeile 298, Dialoge am Dateiende vor `</div>`)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (hinter `.lumina__pets`-Block, ~Zeile 956)

- [ ] **Step 1: TS-Logik** — in `dashboard.component.ts`:

Import ergänzen (bestehende Zeile mit `TractivePet` erweitern):

```typescript
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
```

State-Felder (hinter `pets: TractivePet[] = [];`):

```typescript
  /** True, solange der Spaziergänge-Dialog offen ist. */
  walksDialogOpen = false;
  /** Ein Abschnitt pro Hund im Spaziergänge-Dialog. */
  walkSections: PetWalkSection[] = [];
  /** Verwirft verspätete Antworten eines bereits geschlossenen Dialogs. */
  private walksRequestId = 0;
```

Interface (außerhalb der Komponentenklasse, zu den anderen lokalen Typen der Datei):

```typescript
/** Spaziergänge eines Hundes, gruppiert nach Tag. */
interface PetWalkSection {
  pet: TractivePet;
  dayGroups: { label: string; walks: TractiveWalk[] }[];
  loading: boolean;
  error: string | null;
  empty: boolean;
}
```

Methoden (hinter `petStatusIcon`):

```typescript
  /** Öffnet den Spaziergänge-Dialog und lädt die letzten 7 Tage pro Hund. */
  openWalksDialog(): void {
    if (this.petsWithVerdict.length === 0) {
      return;
    }
    this.walksDialogOpen = true;
    const requestId = ++this.walksRequestId;
    this.walkSections = this.petsWithVerdict.map(pet => ({
      pet, dayGroups: [], loading: true, error: null, empty: false
    }));
    for (const section of this.walkSections) {
      this.tractiveService.getWalks(section.pet.trackerId).subscribe({
        next: walks => {
          if (requestId !== this.walksRequestId) {
            return;
          }
          section.loading = false;
          section.empty = walks.length === 0;
          section.dayGroups = this.groupWalksByDay(walks);
        },
        error: err => {
          if (requestId !== this.walksRequestId) {
            return;
          }
          section.loading = false;
          section.error = err?.error?.message ?? 'Spaziergänge konnten nicht geladen werden.';
        }
      });
    }
  }

  closeWalksDialog(): void {
    this.walksDialogOpen = false;
    this.walksRequestId++;
    this.walkSections = [];
  }

  /** Gruppiert nach Kalendertag; die Reihenfolge (neueste zuerst) kommt vom Server. */
  private groupWalksByDay(walks: TractiveWalk[]): { label: string; walks: TractiveWalk[] }[] {
    const groups: { label: string; walks: TractiveWalk[] }[] = [];
    for (const walk of walks) {
      const label = new Date(walk.start).toLocaleDateString('de-DE', {
        weekday: 'long', day: 'numeric', month: 'long'
      });
      const last = groups[groups.length - 1];
      if (last && last.label === label) {
        last.walks.push(walk);
      } else {
        groups.push({ label, walks: [walk] });
      }
    }
    return groups;
  }

  walkTimeRange(walk: TractiveWalk): string {
    const format = (iso: string) =>
      new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
    return `${format(walk.start)}–${format(walk.end)} Uhr`;
  }

  walkDuration(walk: TractiveWalk): string {
    const hours = Math.floor(walk.durationMinutes / 60);
    const minutes = walk.durationMinutes % 60;
    return hours > 0 ? `${hours} h ${minutes} min` : `${minutes} min`;
  }

  walkDistance(walk: TractiveWalk): string {
    return walk.distanceMeters >= 1000
      ? `${(walk.distanceMeters / 1000).toFixed(1).replace('.', ',')} km`
      : `${Math.round(walk.distanceMeters)} m`;
  }
```

`onEscape()` erweitern — der Spaziergänge-Dialog schließt wie der Verlaufs-Dialog zuerst (an den Anfang der Methode):

```typescript
    if (this.walksDialogOpen) {
      this.closeWalksDialog();
      return;
    }
```

**Hinweis:** `tractiveService` ist in der Komponente bereits injiziert (wird von `startPetRefresh` genutzt) — exakten Feldnamen dort nachschlagen und verwenden.

- [ ] **Step 2: Kachel klickbar machen** — in `dashboard.component.html` den `lumina__pets`-Block (~Zeile 298) ersetzen:

```html
    <div
      class="lumina-card lumina__pets lumina__pets--clickable"
      *ngIf="petsWithVerdict.length > 0"
      role="button"
      tabindex="0"
      (click)="openWalksDialog()"
      (keydown.enter)="openWalksDialog()"
      (keydown.space)="$event.preventDefault(); openWalksDialog()"
      aria-label="Spaziergänge anzeigen"
    >
      <div class="lumina__secured-icon">
        <span class="material-symbols-outlined">pets</span>
      </div>
      <div class="lumina__pets-info">
        <h4 class="lumina__label lumina__label--secondary">
          {{ petsWithVerdict.length > 1 ? 'Haustiere' : 'Hund' }}
        </h4>
        <p class="lumina__secured-detail" *ngFor="let pet of petsWithVerdict"
           [class.lumina__pet--away]="!pet.atHome">
          <span class="material-symbols-outlined">{{ petStatusIcon(pet) }}</span>
          {{ pet.name }} • {{ petStatusLabel(pet) }}
        </p>
      </div>
    </div>
```

- [ ] **Step 3: Dialog-Markup** — in `dashboard.component.html` hinter dem Nuki-Bestätigungsdialog (vor dem schließenden `</div>` der Datei):

```html
  <!-- Spaziergaenge-Dialog (oeffnet sich beim Klick auf die Hund-Kachel) -->
  <div
    *ngIf="walksDialogOpen"
    class="lumina__dialog-backdrop"
    (click)="closeWalksDialog()"
  >
    <div
      class="lumina__dialog lumina__dialog--walks"
      role="dialog"
      aria-modal="true"
      aria-label="Spaziergänge"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">Spaziergänge</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closeWalksDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <section *ngFor="let section of walkSections" class="lumina__walk-section">
          <h3 *ngIf="walkSections.length > 1" class="lumina__walk-pet">{{ section.pet.name }}</h3>
          <p *ngIf="section.loading" class="lumina__history-message">Lädt…</p>
          <p *ngIf="section.error" class="lumina__history-message">{{ section.error }}</p>
          <p *ngIf="section.empty && !section.error" class="lumina__history-message">
            Keine Spaziergänge in den letzten 7 Tagen
          </p>
          <div *ngFor="let group of section.dayGroups" class="lumina__walk-day">
            <h4 class="lumina__walk-day-label">{{ group.label }}</h4>
            <div *ngFor="let walk of group.walks" class="lumina__walk-row">
              <span class="material-symbols-outlined lumina__walk-icon">directions_walk</span>
              <span class="lumina__walk-time">{{ walkTimeRange(walk) }}</span>
              <span class="lumina__walk-meta">{{ walkDuration(walk) }} • {{ walkDistance(walk) }}</span>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
```

- [ ] **Step 4: SCSS** — in `dashboard.component.scss` hinter dem bestehenden `.lumina__pets`-Block (Hover-Optik an `.lumina__energy--clickable` orientieren; dortige Werte übernehmen, falls abweichend):

```scss
.lumina__pets--clickable {
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;

  &:hover,
  &:focus-visible {
    transform: translateY(-2px);
    box-shadow: 0 12px 30px rgba(0, 0, 0, 0.35);
  }
}

.lumina__walk-section + .lumina__walk-section {
  margin-top: 1.5rem;
}

.lumina__walk-pet {
  margin: 0 0 0.5rem;
  font-size: 0.95rem;
  font-weight: 600;
}

.lumina__walk-day {
  margin-bottom: 1rem;
}

.lumina__walk-day-label {
  margin: 0 0 0.4rem;
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  opacity: 0.6;
}

.lumina__walk-row {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.45rem 0;

  .lumina__walk-icon {
    font-size: 1.1rem;
    opacity: 0.7;
  }

  .lumina__walk-time {
    flex: 1;
  }

  .lumina__walk-meta {
    opacity: 0.7;
    font-size: 0.85rem;
  }
}
```

- [ ] **Step 5: Build-Check**

Run: `cd frontend; npx ng build --configuration production`
Expected: Build erfolgreich

- [ ] **Step 6: Frontend-Tests (Baseline beachten)**

Run (headless, siehe Memory `frontend-test-baseline.md`): `cd frontend; npx ng test --watch=false --browsers=ChromeHeadless`
Expected: Keine NEUEN Fehlschläge gegenüber der Baseline (3 vorbestehende Fails App/Hero + SmartDeviceList-Flake)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/dashboard/
git commit -m "feat(dashboard): Spaziergaenge-Dialog auf der Hund-Kachel"
```

---

### Task 6: Doku nachziehen

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Tractive-Hundetracker")

- [ ] **Step 1: CLAUDE.md ergänzen** — im Tractive-Abschnitt einen Punkt hinter der Frontend-Beschreibung einfügen:

```markdown
- **Spaziergänge-Dialog:** Klick auf die Hund-Kachel im Dashboard-Footer öffnet einen Dialog mit den Spaziergängen der letzten 7 Tage. Es gibt keinen Walks-Endpunkt in der Tractive-API (das App-Feature ist nicht reverse-engineert) — `GET /v1/tractive/pets/{trackerId}/walks?days` leitet sie stattdessen on-the-fly aus der Positionshistorie ab (`GET /tracker/{id}/positions`, wie die Geofences unverifiziert → defensives Parsen): zusammenhängende Zeiträume außerhalb des Home-Radius, Lücken < 10 min überbrückt, Runden < 5 min verworfen (`TractiveWalkDetector`). Jede Abwesenheit zählt als „Spaziergang" (auch Autofahrten), und im Stromsparmodus können kurze Runden verschluckt werden — bewusste Eigenschaft der Datenlage. Ergebnis 5 min pro Tracker gecacht; ohne konfiguriertes Zuhause 400 mit klarer Meldung
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(tractive): Spaziergaenge-Dialog dokumentieren"
```
