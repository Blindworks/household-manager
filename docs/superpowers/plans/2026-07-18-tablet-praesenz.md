# Tablet-Präsenzerkennung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine Android-Kiosk-App (`tablet-app/`) zeigt das Dashboard im Vollbild, erkennt per Frontkamera Anwesenheit (Bewegung weckt, Gesicht hält wach), dunkelt das Display bei Abwesenheit ab (Soft-Off) und meldet Präsenz-Wechsel an das Backend, wo sie als Binärsensor im Entity-State-Layer für Flows verfügbar sind.

**Architecture:** Backend bekommt eine schlanke `POST /v1/tablet-presence/{tabletId}`-API, die über die bestehende `EntityStateService`-Facade einen `BINARY_SENSOR` (`EntitySource.TABLET`) spiegelt; ein `@Scheduled`-Check setzt Tablets ohne Heartbeat auf `unavailable`. Die Android-App ist ein eigenständiges Gradle-Projekt (Kotlin, minSdk 29): CameraX-`ImageAnalysis` speist einen puren `MotionDetector` (Luma-Frame-Differenz) und ML-Kit-Gesichtserkennung; eine Android-freie `PresenceStateMachine` entscheidet über hell/dunkel; `DisplayController` setzt Overlay + Helligkeit 0; `PresenceReporter` postet Wechsel + Heartbeat per OkHttp.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 (Backend); Kotlin 2.0, AGP 8.5, CameraX 1.3, ML Kit Face Detection, OkHttp, JUnit 4 (App).

**Spec:** `docs/superpowers/specs/2026-07-18-tablet-praesenz-design.md`

---

## Voraussetzungen (einmalig prüfen)

- **Backend-Builds:** `JAVA_HOME` muss auf JDK 21 zeigen (Standard der Maschine ist JDK 17):
  - PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'`
  - Maven liegt unter `C:\Users\bened\apache-maven-3.9.11\bin\mvn` (auf PATH), kein `mvnw`. Aus `backend/` ausführen.
  - Die Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal **immer** fehl (Test-DB nicht erreichbar) — das ist vorbestehend, nur gezielte Tests laufen lassen (`mvn test -Dtest=...`).
- **Android-Builds:** Android SDK erforderlich (`ANDROID_HOME` oder `tablet-app/local.properties` mit `sdk.dir=...`). Falls kein SDK installiert ist: Android Studio installieren oder Commandline-Tools + `sdkmanager "platforms;android-35" "build-tools;35.0.0"`. Die reinen JVM-Unit-Tests (Tasks 5–6) brauchen das SDK ebenfalls (AGP), daher SDK vor Task 4 einrichten.
- `tablet-app/local.properties` ist maschinenspezifisch → in `.gitignore` aufnehmen (Task 4).

---

## Teil 1: Backend

### Task 1: `EntitySource.TABLET` ergänzen

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`

- [ ] **Step 1: Enum-Konstante hinzufügen**

In `EntitySource.java` nach `WASTE,` einfügen:

```java
    /** Wandtablet-App (Präsenzerkennung per Frontkamera). */
    TABLET,
```

Das Enum endet danach weiterhin mit `MANUAL`.

- [ ] **Step 2: Kompilieren**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
cd backend
mvn -q compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```powershell
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java
git commit -m "feat(tablet): EntitySource.TABLET fuer Wandtablet-Praesenz"
```

### Task 2: `TabletPresenceService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/tablet/TabletPresenceService.java`
- Test: `backend/src/test/java/com/household/manager/tablet/TabletPresenceServiceTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/tablet/TabletPresenceServiceTest.java`:

```java
package com.household.manager.tablet;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class TabletPresenceServiceTest {

    /** Testuhr, die sich manuell vorstellen lässt. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-18T10:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Mock
    private EntityStateService entityStateService;

    private final MutableClock clock = new MutableClock();
    private TabletPresenceService service;

    @BeforeEach
    void setUp() {
        service = new TabletPresenceService(entityStateService, clock);
    }

    private EntityStateUpdate lastReportedUpdate(int expectedCalls) {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(expectedCalls)).reportState(captor.capture());
        return captor.getValue();
    }

    @Test
    void presenceOnIsMirroredAsBinarySensorOn() {
        service.reportPresence("wandtablet", true);

        EntityStateUpdate update = lastReportedUpdate(1);
        assertEquals("binary_sensor.tablet_wandtablet_presence", update.entityId());
        assertEquals("on", update.state());
        assertEquals("wandtablet", update.sourceRef());
    }

    @Test
    void presenceOffIsMirroredAsBinarySensorOff() {
        service.reportPresence("wandtablet", false);

        assertEquals("off", lastReportedUpdate(1).state());
    }

    @Test
    void staleTabletIsMarkedUnavailable() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(181);

        service.markStaleTabletsUnavailable();

        assertEquals("unavailable", lastReportedUpdate(2).state());
    }

    @Test
    void freshTabletStaysAvailable() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(60);

        service.markStaleTabletsUnavailable();

        verify(entityStateService, times(1)).reportState(org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(entityStateService);
    }

    @Test
    void unavailableIsOnlyReportedOnce() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(181);

        service.markStaleTabletsUnavailable();
        service.markStaleTabletsUnavailable();

        verify(entityStateService, times(2)).reportState(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newReportRevivesUnavailableTablet() {
        service.reportPresence("wandtablet", true);
        clock.advanceSeconds(181);
        service.markStaleTabletsUnavailable();

        service.reportPresence("wandtablet", true);

        assertEquals("on", lastReportedUpdate(3).state());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
cd backend
mvn -q test -Dtest=TabletPresenceServiceTest
```

Expected: Kompilierfehler `cannot find symbol: class TabletPresenceService`

- [ ] **Step 3: Service implementieren**

`backend/src/main/java/com/household/manager/tablet/TabletPresenceService.java`:

```java
package com.household.manager.tablet;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nimmt Präsenz-Meldungen der Wandtablet-App entgegen und spiegelt sie als
 * Binärsensor in den Entity-State-Layer. Tablets registrieren sich implizit
 * mit der ersten Meldung; bleibt der Heartbeat aus, geht die Entität auf
 * "unavailable".
 */
@Service
@Slf4j
public class TabletPresenceService {

    static final String STATE_ON = "on";
    static final String STATE_OFF = "off";
    static final String STATE_UNAVAILABLE = "unavailable";
    static final Duration OFFLINE_THRESHOLD = Duration.ofSeconds(180);

    private record TabletPresence(boolean present, Instant lastSeen, boolean unavailable) {
    }

    private final EntityStateService entityStateService;
    private final Clock clock;
    private final Map<String, TabletPresence> tablets = new ConcurrentHashMap<>();

    @Autowired
    public TabletPresenceService(EntityStateService entityStateService) {
        this(entityStateService, Clock.systemDefaultZone());
    }

    TabletPresenceService(EntityStateService entityStateService, Clock clock) {
        this.entityStateService = entityStateService;
        this.clock = clock;
    }

    public void reportPresence(String tabletId, boolean present) {
        tablets.put(tabletId, new TabletPresence(present, clock.instant(), false));
        reportEntityState(tabletId, present ? STATE_ON : STATE_OFF);
    }

    @Scheduled(fixedDelayString = "${tablet.presence.offline-check-ms:60000}")
    public void markStaleTabletsUnavailable() {
        Instant threshold = clock.instant().minus(OFFLINE_THRESHOLD);
        tablets.forEach((tabletId, presence) -> {
            if (!presence.unavailable() && presence.lastSeen().isBefore(threshold)) {
                tablets.put(tabletId, new TabletPresence(presence.present(), presence.lastSeen(), true));
                log.warn("Tablet '{}' sendet keinen Heartbeat mehr, Entität geht auf unavailable", tabletId);
                reportEntityState(tabletId, STATE_UNAVAILABLE);
            }
        });
    }

    private void reportEntityState(String tabletId, String state) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TABLET, tabletId, "presence"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.TABLET)
                .sourceRef(tabletId)
                .friendlyName(tabletId + " Präsenz")
                .state(state)
                .attributes(Map.of("deviceClass", "presence"))
                .build());
    }
}
```

Hinweis: `reportState` ist laut `EntityStateService`-Javadoc bereits absolut fehlertolerant — kein zusätzliches try/catch nötig.

- [ ] **Step 4: Test ausführen — muss bestehen**

```powershell
mvn -q test -Dtest=TabletPresenceServiceTest
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/household/manager/tablet/ backend/src/test/java/com/household/manager/tablet/
git commit -m "feat(tablet): TabletPresenceService mit Entity-Spiegelung und Offline-Erkennung"
```

### Task 3: REST-API `POST /v1/tablet-presence/{tabletId}`

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/TabletPresenceRequest.java`
- Create: `backend/src/main/java/com/household/manager/controller/TabletPresenceController.java`
- Test: `backend/src/test/java/com/household/manager/controller/TabletPresenceControllerTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/controller/TabletPresenceControllerTest.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.TabletPresenceRequest;
import com.household.manager.tablet.TabletPresenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TabletPresenceControllerTest {

    @Mock
    private TabletPresenceService tabletPresenceService;

    @Test
    void delegatesPresenceReportToService() {
        TabletPresenceController controller = new TabletPresenceController(tabletPresenceService);

        controller.reportPresence("wandtablet", new TabletPresenceRequest(true));

        verify(tabletPresenceService).reportPresence("wandtablet", true);
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```powershell
mvn -q test -Dtest=TabletPresenceControllerTest
```

Expected: Kompilierfehler (Controller und Request-DTO existieren nicht)

- [ ] **Step 3: DTO und Controller implementieren**

`backend/src/main/java/com/household/manager/dto/TabletPresenceRequest.java`:

```java
package com.household.manager.dto;

/** Präsenz-Meldung der Wandtablet-App. */
public record TabletPresenceRequest(boolean present) {
}
```

`backend/src/main/java/com/household/manager/controller/TabletPresenceController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.TabletPresenceRequest;
import com.household.manager.tablet.TabletPresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Präsenz-Meldungen der Wandtablet-App. Tablets registrieren sich implizit
 * mit der ersten Meldung (kein Verwaltungs-UI).
 */
@RestController
@RequestMapping("/v1/tablet-presence")
@RequiredArgsConstructor
public class TabletPresenceController {

    private final TabletPresenceService tabletPresenceService;

    @PostMapping("/{tabletId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportPresence(@PathVariable String tabletId, @RequestBody TabletPresenceRequest request) {
        tabletPresenceService.reportPresence(tabletId, request.present());
    }
}
```

- [ ] **Step 4: Tests ausführen — müssen bestehen**

```powershell
mvn -q test -Dtest="TabletPresenceControllerTest,TabletPresenceServiceTest"
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/household/manager/dto/TabletPresenceRequest.java backend/src/main/java/com/household/manager/controller/TabletPresenceController.java backend/src/test/java/com/household/manager/controller/TabletPresenceControllerTest.java
git commit -m "feat(tablet): REST-API fuer Tablet-Praesenz-Meldungen"
```

---

## Teil 2: Android-App

### Task 4: Gradle-Projekt-Scaffold `tablet-app/`

**Files:**
- Create: `tablet-app/settings.gradle.kts`
- Create: `tablet-app/build.gradle.kts`
- Create: `tablet-app/gradle.properties`
- Create: `tablet-app/app/build.gradle.kts`
- Create: `tablet-app/app/src/main/AndroidManifest.xml`
- Modify: `.gitignore`

- [ ] **Step 1: Projektdateien anlegen**

`tablet-app/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "tablet-app"
include(":app")
```

`tablet-app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
```

`tablet-app/gradle.properties`:

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2g
```

`tablet-app/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.household.manager.tabletapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.household.manager.tabletapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
```

`tablet-app/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />

    <!-- usesCleartextTraffic: Backend/Frontend laufen im LAN ohne HTTPS -->
    <application
        android:label="Household Tablet"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.AppCompat.NoActionBar">

        <activity
            android:name=".KioskActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".SettingsActivity" />
    </application>
</manifest>
```

In `.gitignore` (Repo-Wurzel) ergänzen:

```
tablet-app/local.properties
tablet-app/.gradle/
tablet-app/app/build/
tablet-app/build/
```

- [ ] **Step 2: Gradle-Wrapper erzeugen**

Falls Gradle lokal installiert ist (sonst Android Studio öffnen, das legt den Wrapper an):

```powershell
cd tablet-app
gradle wrapper --gradle-version 8.9
```

Expected: `gradlew.bat`, `gradlew`, `gradle/wrapper/` entstehen.

- [ ] **Step 3: SDK-Pfad hinterlegen und Sync prüfen**

`tablet-app/local.properties` (nicht committen):

```properties
sdk.dir=C\:\\Users\\bened\\AppData\\Local\\Android\\Sdk
```

```powershell
cd tablet-app
.\gradlew.bat :app:tasks --quiet
```

Expected: Task-Liste ohne Fehler (Projekt syncs). Bei fehlendem SDK: Pfad korrigieren bzw. SDK installieren (siehe Voraussetzungen).

- [ ] **Step 4: Commit**

```powershell
git add tablet-app/ .gitignore
git commit -m "feat(tablet-app): Gradle-Scaffold fuer die Android-Kiosk-App"
```

### Task 5: `PresenceStateMachine` (TDD, pure Kotlin)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/PresenceStateMachine.kt`
- Test: `tablet-app/app/src/test/java/com/household/manager/tabletapp/presence/PresenceStateMachineTest.kt`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

`tablet-app/app/src/test/java/com/household/manager/tabletapp/presence/PresenceStateMachineTest.kt`:

```kotlin
package com.household.manager.tabletapp.presence

import com.household.manager.tabletapp.presence.PresenceStateMachine.DisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceStateMachineTest {

    private val timeoutMs = 60_000L

    private fun machine(startMs: Long = 0L) = PresenceStateMachine(timeoutMs, startMs)

    @Test
    fun `starts with display on`() {
        assertEquals(DisplayState.ON, machine().displayState)
    }

    @Test
    fun `stays on before timeout`() {
        val m = machine()
        assertEquals(DisplayState.ON, m.tick(timeoutMs - 1))
    }

    @Test
    fun `turns off after timeout without presence`() {
        val m = machine()
        assertEquals(DisplayState.OFF, m.tick(timeoutMs))
    }

    @Test
    fun `motion wakes display from off`() {
        val m = machine()
        m.tick(timeoutMs)
        assertEquals(DisplayState.ON, m.onMotion(timeoutMs + 1))
    }

    @Test
    fun `motion resets the timeout`() {
        val m = machine()
        m.onMotion(50_000)
        assertEquals(DisplayState.ON, m.tick(50_000 + timeoutMs - 1))
        assertEquals(DisplayState.OFF, m.tick(50_000 + timeoutMs))
    }

    @Test
    fun `face keeps display awake while on`() {
        val m = machine()
        m.onFace(50_000)
        assertEquals(DisplayState.ON, m.tick(50_000 + timeoutMs - 1))
    }

    @Test
    fun `face does not wake display from off`() {
        val m = machine()
        m.tick(timeoutMs)
        assertEquals(DisplayState.OFF, m.onFace(timeoutMs + 1))
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```powershell
cd tablet-app
.\gradlew.bat :app:testDebugUnitTest --tests "com.household.manager.tabletapp.presence.PresenceStateMachineTest"
```

Expected: Kompilierfehler `Unresolved reference: PresenceStateMachine`

- [ ] **Step 3: State-Machine implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/PresenceStateMachine.kt`:

```kotlin
package com.household.manager.tabletapp.presence

/**
 * Hybrid-Logik der Anwesenheitserkennung, bewusst ohne Android-Abhängigkeiten:
 * Bewegung weckt das Display, ein erkanntes Gesicht hält es wach. Ohne beides
 * schaltet [tick] das Display nach Ablauf des Timeouts ab.
 *
 * Zeit wird als monotone Millisekunden übergeben (z. B. SystemClock.elapsedRealtime()).
 */
class PresenceStateMachine(private val timeoutMs: Long, startMs: Long) {

    enum class DisplayState { ON, OFF }

    var displayState: DisplayState = DisplayState.ON
        private set

    private var lastPresenceMs: Long = startMs

    fun onMotion(nowMs: Long): DisplayState {
        lastPresenceMs = nowMs
        displayState = DisplayState.ON
        return displayState
    }

    fun onFace(nowMs: Long): DisplayState {
        if (displayState == DisplayState.ON) {
            lastPresenceMs = nowMs
        }
        return displayState
    }

    fun tick(nowMs: Long): DisplayState {
        if (displayState == DisplayState.ON && nowMs - lastPresenceMs >= timeoutMs) {
            displayState = DisplayState.OFF
        }
        return displayState
    }
}
```

- [ ] **Step 4: Test ausführen — muss bestehen**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.household.manager.tabletapp.presence.PresenceStateMachineTest"
```

Expected: `BUILD SUCCESSFUL`, 7 Tests grün

- [ ] **Step 5: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): PresenceStateMachine fuer die Hybrid-Erkennung"
```

### Task 6: `MotionDetector` (TDD, pure Kotlin)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/MotionDetector.kt`
- Test: `tablet-app/app/src/test/java/com/household/manager/tabletapp/presence/MotionDetectorTest.kt`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

`tablet-app/app/src/test/java/com/household/manager/tabletapp/presence/MotionDetectorTest.kt`:

```kotlin
package com.household.manager.tabletapp.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val detector = MotionDetector(pixelThreshold = 25, motionFraction = 0.02)

    private fun frame(value: Byte, size: Int = 100) = ByteArray(size) { value }

    @Test
    fun `first frame never reports motion`() {
        assertFalse(detector.detect(frame(10)))
    }

    @Test
    fun `identical frames report no motion`() {
        detector.detect(frame(10))
        assertFalse(detector.detect(frame(10)))
    }

    @Test
    fun `large change reports motion`() {
        detector.detect(frame(10))
        assertTrue(detector.detect(frame(120)))
    }

    @Test
    fun `small noise below pixel threshold reports no motion`() {
        detector.detect(frame(10))
        assertFalse(detector.detect(frame(20))) // Differenz 10 < pixelThreshold 25
    }

    @Test
    fun `change in tiny area reports no motion`() {
        detector.detect(frame(10, size = 1000))
        val next = frame(10, size = 1000)
        next[0] = 120 // 1 von 1000 Pixeln = 0.1 % < 2 %
        assertFalse(detector.detect(next))
    }

    @Test
    fun `frame size change resets comparison`() {
        detector.detect(frame(10, size = 100))
        assertFalse(detector.detect(frame(120, size = 200)))
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.household.manager.tabletapp.presence.MotionDetectorTest"
```

Expected: Kompilierfehler `Unresolved reference: MotionDetector`

- [ ] **Step 3: Detektor implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/MotionDetector.kt`:

```kotlin
package com.household.manager.tabletapp.presence

import kotlin.math.abs

/**
 * Bewegungserkennung per Luma-Frame-Differenz: meldet Bewegung, wenn sich
 * mindestens [motionFraction] der Pixel um mehr als [pixelThreshold]
 * Helligkeitsstufen gegenüber dem letzten Frame geändert haben.
 */
class MotionDetector(
    private val pixelThreshold: Int = 25,
    private val motionFraction: Double = 0.02
) {

    private var previousFrame: ByteArray? = null

    fun detect(luma: ByteArray): Boolean {
        val previous = previousFrame
        previousFrame = luma.copyOf()
        if (previous == null || previous.size != luma.size) {
            return false
        }
        var changedPixels = 0
        for (i in luma.indices) {
            val diff = (luma[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)
            if (abs(diff) > pixelThreshold) {
                changedPixels++
            }
        }
        return changedPixels.toDouble() / luma.size >= motionFraction
    }
}
```

- [ ] **Step 4: Test ausführen — muss bestehen**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.household.manager.tabletapp.presence.MotionDetectorTest"
```

Expected: `BUILD SUCCESSFUL`, 6 Tests grün

- [ ] **Step 5: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): MotionDetector per Luma-Frame-Differenz"
```

### Task 7: `AppSettings` (SharedPreferences)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/AppSettings.kt`

- [ ] **Step 1: Settings-Klasse implementieren**

Reiner SharedPreferences-Wrapper ohne Logik — Unit-Test lohnt hier nicht (nur Android-API-Delegation).

`tablet-app/app/src/main/java/com/household/manager/tabletapp/AppSettings.kt`:

```kotlin
package com.household.manager.tabletapp

import android.content.Context

/**
 * Persistente App-Einstellungen (SharedPreferences). Server-URLs zeigen auf
 * den Household-Manager im LAN und sind über den Settings-Screen anpassbar.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("tablet_app", Context.MODE_PRIVATE)

    var dashboardUrl: String
        get() = prefs.getString(KEY_DASHBOARD_URL, "http://192.168.178.2:4200")!!
        set(value) = prefs.edit().putString(KEY_DASHBOARD_URL, value.trim()).apply()

    /** Basis-URL des Backends inklusive Context-Path, z. B. http://192.168.178.2:8080/api */
    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, "http://192.168.178.2:8080/api")!!
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value.trim().trimEnd('/')).apply()

    var tabletId: String
        get() = prefs.getString(KEY_TABLET_ID, "wandtablet")!!
        set(value) = prefs.edit().putString(KEY_TABLET_ID, value.trim()).apply()

    var displayTimeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_SECONDS, 60)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_SECONDS, value.coerceAtLeast(5)).apply()

    /** Schwellwert der Bewegungserkennung (Helligkeitsstufen); höher = unempfindlicher. */
    var motionPixelThreshold: Int
        get() = prefs.getInt(KEY_MOTION_THRESHOLD, 25)
        set(value) = prefs.edit().putInt(KEY_MOTION_THRESHOLD, value.coerceIn(5, 100)).apply()

    private companion object {
        const val KEY_DASHBOARD_URL = "dashboardUrl"
        const val KEY_BACKEND_URL = "backendBaseUrl"
        const val KEY_TABLET_ID = "tabletId"
        const val KEY_TIMEOUT_SECONDS = "displayTimeoutSeconds"
        const val KEY_MOTION_THRESHOLD = "motionPixelThreshold"
    }
}
```

- [ ] **Step 2: Kompilieren**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): AppSettings fuer URLs, Tablet-ID und Timeout"
```

### Task 8: `DisplayController` (Soft-Off)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/DisplayController.kt`

- [ ] **Step 1: Controller implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/DisplayController.kt`:

```kotlin
package com.household.manager.tabletapp

import android.app.Activity
import android.view.View
import android.view.WindowManager

/**
 * Soft-Off für das Wandtablet: schwarzes Overlay + Bildschirmhelligkeit 0.
 * Das Display bleibt technisch an (FLAG_KEEP_SCREEN_ON in der Activity),
 * damit Kamera und App durchgehend weiterlaufen.
 */
class DisplayController(private val activity: Activity, private val overlay: View) {

    fun turnOn() {
        overlay.visibility = View.GONE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    fun turnOff() {
        overlay.visibility = View.VISIBLE
        setBrightness(0f)
    }

    private fun setBrightness(value: Float) {
        val params = activity.window.attributes
        params.screenBrightness = value
        activity.window.attributes = params
    }
}
```

- [ ] **Step 2: Kompilieren**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): DisplayController fuer Soft-Off"
```

### Task 9: Kamera-Pipeline (`FacePresenceDetector`, `PresenceAnalyzer`, `PresenceCamera`)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/FacePresenceDetector.kt`
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/PresenceAnalyzer.kt`
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/PresenceCamera.kt`

- [ ] **Step 1: ML-Kit-Gesichtserkennung kapseln**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/FacePresenceDetector.kt`:

```kotlin
package com.household.manager.tabletapp.presence

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Kapselt ML Kit Face Detection (Fast-Modus, vollständig lokal).
 * Liefert nur die Information "mindestens ein Gesicht sichtbar".
 */
class FacePresenceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .build()
    )

    /** [onComplete] wird auf einem ML-Kit-Thread aufgerufen; der Aufrufer schließt das ImageProxy. */
    @OptIn(ExperimentalGetImage::class)
    fun process(imageProxy: ImageProxy, onComplete: (Boolean) -> Unit) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            onComplete(false)
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces -> onComplete(faces.isNotEmpty()) }
            .addOnFailureListener { onComplete(false) }
    }
}
```

- [ ] **Step 2: Analyzer implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/PresenceAnalyzer.kt`:

```kotlin
package com.household.manager.tabletapp.presence

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Callbacks der Kamera-Pipeline; werden auf Hintergrund-Threads aufgerufen. */
interface PresenceListener {
    fun onMotion()
    fun onFace()
}

/**
 * Verbindet CameraX-Frames mit der Erkennung: jedes analysierte Frame läuft
 * durch den [MotionDetector], jedes [faceEveryNthFrame]-te zusätzlich durch
 * die ML-Kit-Gesichtserkennung. [minIntervalMs] drosselt die Analyse,
 * damit CPU-Last und Wärmeentwicklung gering bleiben.
 */
class PresenceAnalyzer(
    private val motionDetector: MotionDetector,
    private val faceDetector: FacePresenceDetector,
    private val listener: PresenceListener,
    private val faceEveryNthFrame: Int = 5,
    private val minIntervalMs: Long = 300
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedMs = 0L
    private var frameCount = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedMs < minIntervalMs) {
            image.close()
            return
        }
        lastAnalyzedMs = now
        frameCount++

        if (motionDetector.detect(extractLuma(image))) {
            listener.onMotion()
        }

        if (frameCount % faceEveryNthFrame == 0L) {
            faceDetector.process(image) { hasFace ->
                if (hasFace) {
                    listener.onFace()
                }
                image.close()
            }
        } else {
            image.close()
        }
    }

    /** Tastet die Y-Ebene (Luma) grob ab — für Bewegungserkennung reicht ein Raster. */
    private fun extractLuma(image: ImageProxy, step: Int = 4): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val out = ByteArray((height / step) * (width / step))
        var i = 0
        for (y in 0 until height - (height % step) step step) {
            for (x in 0 until width - (width % step) step step) {
                out[i++] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
            }
        }
        return out
    }
}
```

- [ ] **Step 3: CameraX-Verkabelung implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/presence/PresenceCamera.kt`:

```kotlin
package com.household.manager.tabletapp.presence

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Startet die Frontkamera mit einem ImageAnalysis-Use-Case in niedriger
 * Auflösung. Fehler führen nie zum Absturz — bei Kameraproblemen bleibt das
 * Display dauerhaft an (Fail-safe, siehe Aufrufer).
 */
class PresenceCamera {

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun start(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        analyzer: ImageAnalysis.Analyzer,
        onError: (Exception) -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Log.i(TAG, "Frontkamera für Präsenzerkennung gestartet")
            } catch (ex: Exception) {
                onError(ex)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private companion object {
        const val TAG = "PresenceCamera"
    }
}
```

- [ ] **Step 4: Kompilieren + bestehende Tests**

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, alle Tests grün

- [ ] **Step 5: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): CameraX-Pipeline mit Bewegungs- und Gesichtserkennung"
```

### Task 10: `PresenceReporter` (Backend-Anbindung + Heartbeat)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/PresenceReporter.kt`

- [ ] **Step 1: Reporter implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/PresenceReporter.kt`:

```kotlin
package com.household.manager.tabletapp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Meldet Präsenz-Wechsel an das Household-Manager-Backend im LAN und sendet
 * zusätzlich einen periodischen Heartbeat mit dem letzten Zustand. Fehler
 * werden nur geloggt — die Display-Logik funktioniert vollständig offline.
 */
class PresenceReporter(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val heartbeatIntervalMs: Long = 60_000
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastPresent: Boolean? = null

    fun reportPresence(present: Boolean) {
        lastPresent = present
        scope.launch(Dispatchers.IO) { post(present) }
    }

    fun startHeartbeat() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(heartbeatIntervalMs)
                lastPresent?.let { post(it) }
            }
        }
    }

    private fun post(present: Boolean) {
        val url = "${settings.backendBaseUrl}/v1/tablet-presence/${settings.tabletId}"
        val body = """{"present":$present}""".toRequestBody("application/json".toMediaType())
        try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Backend antwortete mit ${response.code} auf $url")
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Präsenz-Meldung fehlgeschlagen: ${ex.message}")
        }
    }

    private companion object {
        const val TAG = "PresenceReporter"
    }
}
```

- [ ] **Step 2: Kompilieren**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): PresenceReporter mit Heartbeat ans Backend"
```

### Task 11: `KioskActivity`, Layouts und `SettingsActivity`

**Files:**
- Create: `tablet-app/app/src/main/res/layout/activity_kiosk.xml`
- Create: `tablet-app/app/src/main/res/layout/activity_settings.xml`
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/KioskActivity.kt`
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/SettingsActivity.kt`

- [ ] **Step 1: Kiosk-Layout anlegen**

`tablet-app/app/src/main/res/layout/activity_kiosk.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <WebView
        android:id="@+id/webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Versteckte Geste: langes Drücken oben links öffnet die Einstellungen -->
    <View
        android:id="@+id/settings_corner"
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:layout_gravity="top|start" />

    <!-- Soft-Off: liegt über allem; Tippen weckt das Display (Fail-safe) -->
    <View
        android:id="@+id/overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#FF000000"
        android:clickable="true"
        android:focusable="true"
        android:visibility="gone" />
</FrameLayout>
```

- [ ] **Step 2: Settings-Layout anlegen**

`tablet-app/app/src/main/res/layout/activity_settings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="24dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Dashboard-URL"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/input_dashboard_url"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textUri" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Backend-Basis-URL (inkl. /api)"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/input_backend_url"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textUri" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Tablet-ID"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/input_tablet_id"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="text" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Display-Timeout (Sekunden)"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/input_timeout"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="number" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Bewegungs-Schwellwert (5–100, höher = unempfindlicher)"
            android:textStyle="bold" />

        <EditText
            android:id="@+id/input_motion_threshold"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="number" />

        <Button
            android:id="@+id/button_save"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="Speichern" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 3: `SettingsActivity` implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/SettingsActivity.kt`:

```kotlin
package com.household.manager.tabletapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/** Einstellungs-Formular; erreichbar nur über die versteckte Geste im Kiosk. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val settings = AppSettings(this)

        val dashboardUrl = findViewById<EditText>(R.id.input_dashboard_url)
        val backendUrl = findViewById<EditText>(R.id.input_backend_url)
        val tabletId = findViewById<EditText>(R.id.input_tablet_id)
        val timeout = findViewById<EditText>(R.id.input_timeout)
        val motionThreshold = findViewById<EditText>(R.id.input_motion_threshold)

        dashboardUrl.setText(settings.dashboardUrl)
        backendUrl.setText(settings.backendBaseUrl)
        tabletId.setText(settings.tabletId)
        timeout.setText(settings.displayTimeoutSeconds.toString())
        motionThreshold.setText(settings.motionPixelThreshold.toString())

        findViewById<Button>(R.id.button_save).setOnClickListener {
            settings.dashboardUrl = dashboardUrl.text.toString()
            settings.backendBaseUrl = backendUrl.text.toString()
            settings.tabletId = tabletId.text.toString()
            settings.displayTimeoutSeconds = timeout.text.toString().toIntOrNull() ?: 60
            settings.motionPixelThreshold = motionThreshold.text.toString().toIntOrNull() ?: 25
            finish()
        }
    }
}
```

- [ ] **Step 4: `KioskActivity` implementieren**

`tablet-app/app/src/main/java/com/household/manager/tabletapp/KioskActivity.kt`:

```kotlin
package com.household.manager.tabletapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.household.manager.tabletapp.presence.FacePresenceDetector
import com.household.manager.tabletapp.presence.MotionDetector
import com.household.manager.tabletapp.presence.PresenceAnalyzer
import com.household.manager.tabletapp.presence.PresenceCamera
import com.household.manager.tabletapp.presence.PresenceListener
import com.household.manager.tabletapp.presence.PresenceStateMachine
import com.household.manager.tabletapp.presence.PresenceStateMachine.DisplayState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * Vollbild-Kiosk: zeigt das Dashboard im WebView und steuert das Display über
 * die Präsenzerkennung. Fail-safe: ohne Kamera(-Berechtigung) bleibt das
 * Display dauerhaft an.
 */
class KioskActivity : AppCompatActivity(), PresenceListener {

    private lateinit var settings: AppSettings
    private lateinit var webView: WebView
    private lateinit var displayController: DisplayController
    private lateinit var reporter: PresenceReporter
    private var stateMachine: PresenceStateMachine? = null

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var loadedDashboardUrl: String? = null
    private var lastAppliedState: DisplayState? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startPresenceDetection()
            } else {
                Log.w(TAG, "Kamera-Berechtigung verweigert — Display bleibt dauerhaft an (Fail-safe)")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kiosk)
        settings = AppSettings(this)
        webView = findViewById(R.id.webview)
        val overlay = findViewById<View>(R.id.overlay)
        displayController = DisplayController(this, overlay)
        reporter = PresenceReporter(settings, scope)

        // Android darf das Gerät nie selbst sperren — hell/dunkel entscheidet die App
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWebView()
        overlay.setOnClickListener { onMotion() }
        findViewById<View>(R.id.settings_corner).setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        reporter.startHeartbeat()
        cameraPermission.launch(Manifest.permission.CAMERA)
        handler.post(tickRunnable)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        // Einstellungen können sich im SettingsScreen geändert haben
        if (loadedDashboardUrl != settings.dashboardUrl) {
            loadDashboard()
        }
        stateMachine = PresenceStateMachine(
            settings.displayTimeoutSeconds * 1000L,
            SystemClock.elapsedRealtime()
        )
        applyDisplayState(DisplayState.ON)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        scope.cancel()
        super.onDestroy()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            stateMachine?.let { applyDisplayState(it.tick(SystemClock.elapsedRealtime())) }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onMotion() {
        runOnUiThread {
            stateMachine?.let { applyDisplayState(it.onMotion(SystemClock.elapsedRealtime())) }
        }
    }

    override fun onFace() {
        runOnUiThread {
            stateMachine?.let { applyDisplayState(it.onFace(SystemClock.elapsedRealtime())) }
        }
    }

    private fun applyDisplayState(state: DisplayState) {
        if (state == lastAppliedState) {
            return
        }
        lastAppliedState = state
        when (state) {
            DisplayState.ON -> {
                displayController.turnOn()
                reporter.reportPresence(true)
            }
            DisplayState.OFF -> {
                displayController.turnOff()
                reporter.reportPresence(false)
            }
        }
    }

    private fun startPresenceDetection() {
        val motionDetector = MotionDetector(pixelThreshold = settings.motionPixelThreshold)
        val analyzer = PresenceAnalyzer(motionDetector, FacePresenceDetector(), this)
        PresenceCamera().start(this, this, analyzer) { ex ->
            Log.w(TAG, "Kamera nicht verfügbar — Display bleibt dauerhaft an (Fail-safe): ${ex.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "Dashboard nicht erreichbar, neuer Versuch in 10 s")
                    view.loadData(ERROR_PAGE, "text/html", "utf-8")
                    handler.postDelayed({ loadDashboard() }, 10_000)
                }
            }
        }
        loadDashboard()
    }

    private fun loadDashboard() {
        loadedDashboardUrl = settings.dashboardUrl
        webView.loadUrl(settings.dashboardUrl)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, webView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val TAG = "KioskActivity"
        const val ERROR_PAGE = """
            <html><body style="background:#111;color:#ccc;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh">
            <div><h2>Dashboard nicht erreichbar</h2>
            <p>Neuer Verbindungsversuch in 10&nbsp;Sekunden &hellip;</p></div>
            </body></html>"""
    }
}
```

- [ ] **Step 5: Kompilieren + alle App-Tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; APK unter `tablet-app/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 6: Commit**

```powershell
git add tablet-app/app/src
git commit -m "feat(tablet-app): KioskActivity mit WebView, Soft-Off und Settings"
```

### Task 12: Doku (README + CLAUDE.md)

**Files:**
- Create: `tablet-app/README.md`
- Modify: `CLAUDE.md` (Abschnitt "Smart Device Integrations" bzw. Projektstruktur)

- [ ] **Step 1: README schreiben**

`tablet-app/README.md`:

```markdown
# Household Tablet (Wandtablet-App)

Android-Kiosk-App für das Wandtablet: zeigt das Household-Manager-Dashboard im
Vollbild-WebView und steuert das Display über Anwesenheitserkennung per
Frontkamera (Bewegung weckt, Gesicht hält wach; Soft-Off per schwarzem
Overlay + Helligkeit 0). Präsenz-Wechsel werden an
`POST <backend>/v1/tablet-presence/{tabletId}` gemeldet und stehen im
Entity-State-Layer als `binary_sensor.tablet_<id>_presence` für Flows bereit.

## Build

Voraussetzungen: JDK 17+, Android SDK (Pfad in `local.properties`:
`sdk.dir=...`).

```
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Installation (Sideload)

1. Auf dem Tablet "Unbekannte Quellen" für den Datei-Manager erlauben.
2. APK aufs Tablet kopieren (USB, Netzwerkfreigabe) und installieren —
   oder per ADB: `adb install app/build/outputs/apk/debug/app-debug.apk`
3. App starten, Kamera-Berechtigung erteilen.
4. Einstellungen öffnen: **langes Drücken oben links** — Dashboard-URL,
   Backend-URL (inkl. `/api`), Tablet-ID und Display-Timeout setzen.

## Verhalten

- Ohne Kamera-Berechtigung oder bei Kamerafehlern bleibt das Display
  dauerhaft an (Fail-safe).
- Tippen auf das dunkle Display weckt es immer.
- Backend nicht erreichbar → Display-Logik läuft lokal weiter, Meldungen
  werden beim nächsten Wechsel/Heartbeat erneut versucht.
- Bleibt der Heartbeat aus, setzt das Backend die Entität nach ~3 Minuten
  auf `unavailable`.

## Tests

```
.\gradlew.bat :app:testDebugUnitTest
```
```

- [ ] **Step 2: CLAUDE.md ergänzen**

In `CLAUDE.md` unter "Smart Device Integrations" einen Abschnitt anfügen:

```markdown
### Wandtablet (Präsenzerkennung)
- Eigene Android-Kiosk-App in `tablet-app/` (Kotlin, minSdk 29): Dashboard im Vollbild-WebView, Anwesenheitserkennung per Frontkamera (CameraX: Bewegung weckt, ML-Kit-Gesicht hält wach), Soft-Off via schwarzem Overlay + Helligkeit 0
- Präsenz-Meldung an `POST /v1/tablet-presence/{tabletId}`; Spiegelung als `binary_sensor.tablet_<id>_presence` (`EntitySource.TABLET`) im Entity-State-Layer, nutzbar als Flow-Trigger; ausbleibender Heartbeat → `unavailable`
- Backend-Implementierung in `backend/src/main/java/com/household/manager/tablet/`
```

Zusätzlich im Projektstruktur-Baum unter `scripts/` die Zeile ergänzen:

```
├── tablet-app/                    # Android-Kiosk-App für das Wandtablet
```

- [ ] **Step 3: Commit**

```powershell
git add tablet-app/README.md CLAUDE.md
git commit -m "docs(tablet-app): README und CLAUDE.md fuer die Wandtablet-App"
```

### Task 13: End-to-End-Verifikation

- [ ] **Step 1: Backend-Tests gesamt**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
cd backend
mvn -q test -Dtest="TabletPresenceServiceTest,TabletPresenceControllerTest"
```

Expected: 7 Tests grün.

- [ ] **Step 2: API manuell prüfen (Backend lokal starten)**

```powershell
mvn spring-boot:run
```

In zweitem Terminal:

```powershell
curl.exe -s -o NUL -w "%{http_code}" -X POST http://localhost:8080/api/v1/tablet-presence/wandtablet -H "Content-Type: application/json" -d '{\"present\":true}'
curl.exe -s http://localhost:8080/api/v1/entities | Select-String tablet
```

Expected: `204`; die Entities-Antwort enthält `binary_sensor.tablet_wandtablet_presence` mit `"state":"on"`.

- [ ] **Step 3: App-Tests + APK**

```powershell
cd ..\tablet-app
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, 13 Unit-Tests grün, APK vorhanden.

- [ ] **Step 4: Manuelle Verifikation auf dem Tablet (durch den Benutzer)**

APK sideloaden (siehe `tablet-app/README.md`), Einstellungen setzen und prüfen:
1. Dashboard lädt im Vollbild.
2. Nach Ablauf des Timeouts ohne Anwesenheit wird der Bildschirm schwarz.
3. Vor das Tablet treten → Display geht sofort an.
4. Vor dem Tablet stehen bleiben (mit Blick darauf) → Display bleibt an, auch ohne Bewegung.
5. Im Dashboard/Flows: Entität `binary_sensor.tablet_wandtablet_presence` wechselt zwischen `on`/`off`; nach App-Beenden geht sie binnen ~3 Minuten auf `unavailable`.

- [ ] **Step 5: Abschluss-Commit (falls Restdateien) und Branch-Abschluss**

```powershell
git status
```

Expected: sauberer Arbeitsbaum. Danach superpowers:finishing-a-development-branch verwenden.
