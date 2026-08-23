# Eigene Tractive-Positionshistorie — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Positionspunkte, die der Tractive-Poller ohnehin jede Minute abruft und bisher wegwirft, dauerhaft speichern und die Spaziergänge daraus ableiten — statt sie bei jedem Abruf aus der Cloud zu holen, die beim Basic-Abo nur ~24 Stunden zurückreicht.

**Architecture:** Ein `TractivePositionRecorder` schreibt im bestehenden Poll-Zyklus je Tier höchstens eine Zeile nach `tractive_position` — **nur wenn `positionTime` neu ist**, weil die API bei ausgeschaltetem Tracker weiter die letzte bekannte Position liefert. `TractiveWalkService` liest künftig aus der Datenbank statt aus der Cloud und gibt die Punkte unverändert an den bestehenden `TractiveWalkDetector`. Dadurch entfallen Tages-Cache, Rate-Limit-Behandlung und Häppchen-Zerlegung ersatzlos.

**Tech Stack:** Spring Boot 3.4 / Java 21, JPA + Liquibase, MariaDB; JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-08-23-tractive-positionshistorie-design.md`

---

## Testkommandos auf dieser Maschine

Der Standard-`JAVA_HOME` zeigt auf JDK 17, das Projekt braucht 21. Aus `backend/`:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractiveWalkServiceTest
```

Ganze Testsuite (aus `backend/`):

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```

**Vorbestehende, umgebungsbedingte Fehlschläge:** `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern mit „Access denied for user 'root'@'localhost'" — die Test-Datenbank ist auf dieser Maschine nicht erreichbar. Ignorieren; sie sind kein Urteil über diese Arbeit.

**Wichtige Folge daraus:** Es gibt hier **keine** lauffähigen datenbankgestützten Tests. Repository und Liquibase-Changeset lassen sich lokal nicht gegen eine echte Datenbank prüfen — sie werden über Unit-Tests mit gemocktem Repository und über die Kompilierung abgesichert. Das ist eine bewusste Lücke, keine Nachlässigkeit; sie ist in Task 1 offengelegt.

---

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `backend/.../model/entity/TractivePosition.java` | JPA-Entität eines gespeicherten Positionspunkts |
| `backend/.../repository/TractivePositionRepository.java` | Bereichsabfrage und Dublettenprüfung |
| `backend/.../resources/db/changelog/changes/20260823-0048-create-tractive-position-table.xml` | Tabelle samt Eindeutigkeitsschlüssel |
| `backend/.../tractive/TractivePositionRecorder.java` | Schreibt Positionen aus dem Poll-Zyklus, wirft nie |
| `backend/src/test/.../tractive/TractivePositionRecorderTest.java` | dessen Tests |

**Geändert:**

| Datei | Änderung |
|---|---|
| `backend/.../resources/db/changelog/db.changelog-master.xml` | Changeset einbinden |
| `backend/.../tractive/TractivePollingService.java` | Recorder aufrufen |
| `backend/src/test/.../tractive/TractivePollingServiceTest.java` | Recorder-Mock, Aufruf festhalten |
| `backend/.../tractive/TractiveWalkService.java` | Lesepfad auf die Datenbank umstellen, Cloud-Sonderbehandlung entfernen |
| `backend/src/test/.../tractive/TractiveWalkServiceTest.java` | vollständig ersetzt |
| `backend/.../tractive/TractiveApiClient.java` | `getPositionHistory` entfernen |
| `backend/src/test/.../tractive/TractiveApiClientTest.java` | deren zwei Testfälle entfernen |
| `CLAUDE.md` | Abschnitt nachziehen |

Paketpräfix überall: `com.household.manager`.

---

## Task 1: Tabelle, Entität und Repository

**Der Eindeutigkeitsschlüssel ist die eigentliche Absicherung** gegen den Fallstrick dieser Aufgabe: Wenn der Tracker aus ist, liefert die API weiter die letzte bekannte Position mit unverändertem Zeitstempel. Ohne den Schlüssel könnten ein paralleler Poll und ein gleichzeitig ausgelöstes „Jetzt aktualisieren" trotz Prüfung im Code eine Dublette erzeugen — und Dubletten mit gleichem Zeitstempel würden die Lückenerkennung des Detektors verfälschen.

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260823-0048-create-tractive-position-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/model/entity/TractivePosition.java`
- Create: `backend/src/main/java/com/household/manager/repository/TractivePositionRepository.java`

- [ ] **Step 1: Das Liquibase-Changeset anlegen**

Datei `backend/src/main/resources/db/changelog/changes/20260823-0048-create-tractive-position-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260823-0048-create-tractive-position" author="claude">
        <comment>
            Eigene Positionshistorie der Tractive-Tracker. Gefuellt vom TractivePositionRecorder
            aus dem minuetlichen Poll-Zyklus; Grundlage der Spaziergangserkennung, weil die
            Tractive-Cloud beim Basic-Abo nur rund 24 Stunden Historie liefert.
        </comment>
        <createTable tableName="tractive_position">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="tracker_id" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <!--
                Zeitpunkt des BERICHTS, nicht des Polls. Bewusst DATETIME (fachlicher
                Zeitstempel, wie last_used_at in push_subscription).
            -->
            <column name="position_time" type="DATETIME">
                <constraints nullable="false"/>
            </column>
            <column name="latitude" type="DOUBLE">
                <constraints nullable="false"/>
            </column>
            <column name="longitude" type="DOUBLE">
                <constraints nullable="false"/>
            </column>
            <column name="accuracy" type="DOUBLE"/>
            <column name="sensor_used" type="VARCHAR(50)"/>
        </createTable>
        <!--
            Traegt doppelt: verhindert Dubletten auch bei parallelem Poll UND bedient
            die Bereichsabfrage (tracker_id, position_time >= X) als Index.
        -->
        <addUniqueConstraint tableName="tractive_position"
                             columnNames="tracker_id, position_time"
                             constraintName="uk_tractive_position_tracker_time"/>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Das Changeset einbinden**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` vor dem schließenden `</databaseChangeLog>` ergänzen:

```xml
    <!-- Eigene Positionshistorie der Tractive-Tracker -->
    <include file="db/changelog/changes/20260823-0048-create-tractive-position-table.xml"/>
```

- [ ] **Step 3: Die Entität anlegen**

Datei `backend/src/main/java/com/household/manager/model/entity/TractivePosition.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Ein gespeicherter Positionspunkt eines Tractive-Trackers.
 * <p>
 * Gefuellt vom TractivePositionRecorder aus dem minuetlichen Poll-Zyklus. Diese
 * Tabelle ist die Grundlage der Spaziergangserkennung: die Tractive-Cloud liefert
 * beim Basic-Abo nur rund 24 Stunden Historie, laengere Zeitraeume entstehen
 * ausschliesslich dadurch, dass wir selbst mitschreiben.
 * <p>
 * {@code positionTime} ist der Zeitpunkt des Berichts, nicht des Polls — nur so
 * bleiben die Funkpausen erhalten, an denen der Detektor die Runden trennt.
 */
@Entity
@Table(name = "tractive_position",
        uniqueConstraints = @UniqueConstraint(name = "uk_tractive_position_tracker_time",
                columnNames = {"tracker_id", "position_time"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TractivePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hardware-Id des Trackers, z. B. "dev-9". */
    @Column(name = "tracker_id", nullable = false, length = 100)
    private String trackerId;

    @Column(name = "position_time", nullable = false)
    private Instant positionTime;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "accuracy")
    private Double accuracy;

    /** Wie die Position bestimmt wurde, z. B. "GPS" oder "KNOWN_WIFI". */
    @Column(name = "sensor_used", length = 50)
    private String sensorUsed;
}
```

- [ ] **Step 4: Das Repository anlegen**

**Der Paketort ist zwingend:** `JpaConfig` schränkt das Repository-Scanning auf `com.household.manager.repository` ein. Ein Repository in einem anderen Paket wird nicht gefunden, und der Fehler zeigt sich erst beim Anwendungsstart.

Datei `backend/src/main/java/com/household/manager/repository/TractivePositionRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.TractivePosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Zugriff auf die selbst mitgeschriebene Positionshistorie der Tractive-Tracker. */
public interface TractivePositionRepository extends JpaRepository<TractivePosition, Long> {

    /** Alle Punkte eines Trackers ab einem Zeitpunkt, aelteste zuerst. */
    List<TractivePosition> findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
            String trackerId, Instant from);

    /**
     * Ist dieser Bericht schon gespeichert? Bei ausgeschaltetem Tracker liefert die
     * API denselben Zeitstempel immer wieder — ohne diese Pruefung entstuende ein
     * kuenstlich lueckenloser Positionsstrom, und der Detektor saehe einen einzigen,
     * nie endenden Spaziergang.
     */
    boolean existsByTrackerIdAndPositionTime(String trackerId, Instant positionTime);
}
```

- [ ] **Step 5: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q compile
```

Erwartet: erfolgreicher Build ohne Ausgabe.

**Warum es hier keinen Test gibt:** Entität, Repository-Interface und Changeset enthalten keine eigene Logik, und auf dieser Maschine ist keine Test-Datenbank erreichbar — ein `@DataJpaTest` würde am fehlenden Zugang scheitern, nicht am Code. Die Ableitung der Query-Methodennamen prüft Spring Data beim Anwendungsstart; ein Tippfehler dort fällt beim ersten echten Start auf, nicht im Test. Die eigentliche Logik dieser Aufgabe steckt in Task 2 und ist dort vollständig getestet.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/TractivePosition.java backend/src/main/java/com/household/manager/repository/TractivePositionRepository.java
git commit -m "feat(tractive): Tabelle und Repository fuer die eigene Positionshistorie"
```

Commit-Message um eine Leerzeile und `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ergänzen.

---

## Task 2: Der Recorder

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/TractivePositionRecorder.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractivePositionRecorderTest.java`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Datei `backend/src/test/java/com/household/manager/tractive/TractivePositionRecorderTest.java`:

```java
package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractivePositionRecorderTest {

    private static final long REPORT_EPOCH = 1_800_000_000L;

    @Mock
    private TractivePositionRepository repository;

    private TractivePositionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new TractivePositionRecorder(repository);
    }

    private TractivePetSnapshot snapshot(TractivePositionDto position) {
        TractiveTrackableDto trackable = new TractiveTrackableDto("obj-1", "dev-9", "Toni");
        return new TractivePetSnapshot(trackable, position, null, List.of());
    }

    private TractivePositionDto position(long epochSeconds) {
        return new TractivePositionDto(List.of(48.2182, 16.3738), 12.0, "GPS", epochSeconds);
    }

    @Test
    void speichertEinenNeuenPositionsbericht() {
        when(repository.existsByTrackerIdAndPositionTime(anyString(), any(Instant.class)))
                .thenReturn(false);

        recorder.record(List.of(snapshot(position(REPORT_EPOCH))));

        ArgumentCaptor<TractivePosition> captor = ArgumentCaptor.forClass(TractivePosition.class);
        verify(repository).save(captor.capture());
        TractivePosition saved = captor.getValue();
        assertEquals("dev-9", saved.getTrackerId());
        assertEquals(Instant.ofEpochSecond(REPORT_EPOCH), saved.getPositionTime());
        assertEquals(48.2182, saved.getLatitude());
        assertEquals(16.3738, saved.getLongitude());
        assertEquals(12.0, saved.getAccuracy());
        assertEquals("GPS", saved.getSensorUsed());
    }

    @Test
    void schreibtKeineZweiteZeileFuerDenselbenBericht() {
        // Bei ausgeschaltetem Tracker liefert die API denselben Zeitstempel immer
        // wieder. Wuerde er jedes Mal gespeichert, entstuende ein kuenstlich
        // lueckenloser Strom und der Detektor saehe einen nie endenden Spaziergang.
        when(repository.existsByTrackerIdAndPositionTime("dev-9", Instant.ofEpochSecond(REPORT_EPOCH)))
                .thenReturn(true);

        recorder.record(List.of(snapshot(position(REPORT_EPOCH))));

        verify(repository, never()).save(any());
    }

    @Test
    void ueberspringtSnapshotOhnePosition() {
        recorder.record(List.of(snapshot(null)));

        verifyNoInteractions(repository);
    }

    @Test
    void ueberspringtPositionOhneKoordinaten() {
        recorder.record(List.of(snapshot(
                new TractivePositionDto(null, null, "GPS", REPORT_EPOCH))));

        verifyNoInteractions(repository);
    }

    @Test
    void ueberspringtPositionOhneZeitstempel() {
        // Ein geratener Zeitstempel wuerde die Luecken verfaelschen, an denen der
        // Detektor die Runden trennt.
        recorder.record(List.of(snapshot(
                new TractivePositionDto(List.of(48.2182, 16.3738), null, "GPS", null))));

        verifyNoInteractions(repository);
    }

    @Test
    void einRepositoryFehlerBrichtDenPollNichtAb() {
        when(repository.existsByTrackerIdAndPositionTime(anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> recorder.record(List.of(snapshot(position(REPORT_EPOCH)))));
    }

    @Test
    void einKaputtesTierStopptDieAnderenNicht() {
        TractiveTrackableDto zweiter = new TractiveTrackableDto("obj-2", "dev-8", "Rex");
        when(repository.existsByTrackerIdAndPositionTime(eq("dev-9"), any(Instant.class)))
                .thenThrow(new RuntimeException("DB weg"));
        when(repository.existsByTrackerIdAndPositionTime(eq("dev-8"), any(Instant.class)))
                .thenReturn(false);

        recorder.record(List.of(
                snapshot(position(REPORT_EPOCH)),
                new TractivePetSnapshot(zweiter, position(REPORT_EPOCH), null, List.of())));

        verify(repository, times(1)).save(any());
    }
}
```

**Hinweis zum Konstruktor von `TractiveTrackableDto`:** Prüfe vor dem Schreiben die tatsächliche Feldreihenfolge in `backend/src/main/java/com/household/manager/tractive/dto/TractiveTrackableDto.java` und passe die Aufrufe an, falls sie abweicht. Die Tests brauchen nur, dass `deviceId()` `"dev-9"` bzw. `"dev-8"` liefert.

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractivePositionRecorderTest
```

Erwartet: Kompilierfehler — `TractivePositionRecorder` existiert nicht.

- [ ] **Step 3: Den Recorder schreiben**

Datei `backend/src/main/java/com/household/manager/tractive/TractivePositionRecorder.java`:

```java
package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Schreibt die Positionen mit, die der Poll-Zyklus ohnehin abruft.
 * <p>
 * Die Tractive-Cloud liefert beim Basic-Abo nur rund 24 Stunden Historie —
 * laengere Zeitraeume entstehen ausschliesslich dadurch, dass wir selbst
 * mitschreiben. Das kostet keinen einzigen zusaetzlichen Cloud-Aufruf: der
 * Poller hat die Position bereits in der Hand.
 * <p>
 * <b>Gespeichert wird nur ein NEUER Bericht.</b> Bei ausgeschaltetem Tracker
 * liefert die API weiter die letzte bekannte Position mit unveraendertem
 * Zeitstempel. Wuerde die jede Minute erneut gespeichert, entstuende ein
 * kuenstlich lueckenloser Strom — und der TractiveWalkDetector erkennt
 * Spaziergaenge gerade an den Funkpausen ueber 30 Minuten. Das Ergebnis waere
 * ein einziger, nie endender Spaziergang.
 * <p>
 * <b>Wirft nie.</b> Derselbe Poll versorgt die Entitaeten, die Dashboard-Kachel
 * und den Zu-Hause-Sensor; ein Historie-Fehler darf das nicht mitreissen
 * (Muster von PowerHistoryRecorder und AuditService).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TractivePositionRecorder {

    private final TractivePositionRepository repository;

    public void record(List<TractivePetSnapshot> snapshots) {
        for (TractivePetSnapshot snapshot : snapshots) {
            try {
                recordOne(snapshot);
            } catch (Exception ex) {
                // Bewusst je Tier gefangen: ein kaputter Tracker darf die anderen
                // nicht um ihren Eintrag bringen.
                log.warn("Position von {} nicht speicherbar: {}",
                        snapshot.trackerId(), ex.getMessage());
            }
        }
    }

    private void recordOne(TractivePetSnapshot snapshot) {
        TractivePositionDto position = snapshot.position();
        if (position == null || !position.hasCoordinates()) {
            return;
        }
        Instant reportedAt = position.reportedAt();
        if (reportedAt == null) {
            // Ein geratener Zeitstempel wuerde die Luecken verfaelschen, an denen
            // der Detektor die Runden trennt.
            return;
        }
        String trackerId = snapshot.trackerId();
        if (repository.existsByTrackerIdAndPositionTime(trackerId, reportedAt)) {
            return;
        }
        repository.save(TractivePosition.builder()
                .trackerId(trackerId)
                .positionTime(reportedAt)
                .latitude(position.latitude())
                .longitude(position.longitude())
                .accuracy(position.accuracy())
                .sensorUsed(position.sensorUsed())
                .build());
    }
}
```

**Zur Dublettenprüfung:** Die Abfrage vor dem Schreiben hält den Normalfall sauber; gegen ein echtes Wettrennen schützt der Eindeutigkeitsschlüssel der Tabelle. Verliert ein paralleler Schreibvorgang, wirft die Datenbank — und genau das fängt der `catch`-Block je Tier ab.

- [ ] **Step 4: Tests laufen lassen und grün sehen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractivePositionRecorderTest
```

Erwartet: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractivePositionRecorder.java backend/src/test/java/com/household/manager/tractive/TractivePositionRecorderTest.java
git commit -m "feat(tractive): Positionen aus dem Poll-Zyklus mitschreiben"
```

Commit-Message um eine Leerzeile und `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ergänzen.

---

## Task 3: Den Recorder in den Poll-Zyklus hängen

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractivePollingService.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractivePollingServiceTest.java`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `TractivePollingServiceTest.java` den Mock ergänzen (zu den übrigen `@Mock`-Feldern):

```java
    @Mock
    private TractivePositionRecorder positionRecorder;
```

Die Konstruktion in `setUp()` (Zeile 67) erweitern:

```java
        service = new TractivePollingService(properties, apiClient, authService, mapper,
                entityStateService, positionRecorder);
```

Und diesen Testfall ans Ende der Klasse hängen:

```java
    @Test
    void schreibtDiePositionenJedesPollZyklusMit() {
        // Ohne diesen Aufruf entstuende gar keine Historie - die Cloud liefert beim
        // Basic-Abo nur rund 24 Stunden, laengere Zeitraeume gibt es nur, weil wir
        // selbst mitschreiben.
        service.poll();

        ArgumentCaptor<List<TractivePetSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(positionRecorder).record(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("dev-9", captor.getValue().get(0).trackerId());
    }
```

**Hinweis:** Falls `setUp()` die übrigen Mocks so verdrahtet, dass `poll()` ohne weitere Stubs keinen erfolgreichen Zyklus durchläuft, orientiere dich an einem bestehenden erfolgreichen Test derselben Datei und übernimm dessen Stub-Aufbau. Ändere die bestehenden Tests dabei **nicht**.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractivePollingServiceTest
```

Erwartet: Kompilierfehler — der Konstruktor hat noch fünf Parameter.

- [ ] **Step 3: Den Aufruf einbauen**

In `TractivePollingService.java` das Feld ergänzen — **unmittelbar nach `entityStateService`**, dem letzten der bestehenden `private final`-Felder. Die Position ist nicht beliebig: `@RequiredArgsConstructor` leitet die Parameterreihenfolge aus der Feldreihenfolge ab, und der Test aus Step 1 übergibt den Recorder als letztes Argument.

```java
    private final TractivePositionRecorder positionRecorder;
```

In `pollOnce()` den Aufruf **direkt nach der `for`-Schleife über `refs`** einfügen, also unmittelbar vor der Zeile `List<EntityStateUpdate> updates = new ArrayList<>();`:

```java
            // Bewusst VOR dem Mapping: die Historie soll auch dann entstehen, wenn
            // das Mapping der Entitaeten scheitert. Der Recorder wirft nie.
            positionRecorder.record(snapshots);
```

- [ ] **Step 4: Tests laufen lassen und grün sehen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractivePollingServiceTest
```

Erwartet: alle Tests der Klasse grün, inklusive des neuen.

- [ ] **Step 5: Mutationsprüfung**

Entferne die Zeile `positionRecorder.record(snapshots);` testweise wieder und lass die Klasse erneut laufen. **Der neue Test muss fehlschlagen** („Wanted but not invoked"). Halte die tatsächliche Ausgabe fest, setze die Zeile zurück und stelle sicher, dass `git status --porcelain` danach nur die beabsichtigten Änderungen zeigt.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractivePollingService.java backend/src/test/java/com/household/manager/tractive/TractivePollingServiceTest.java
git commit -m "feat(tractive): Poll-Zyklus schreibt die Positionshistorie mit"
```

Commit-Message um eine Leerzeile und `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ergänzen.

---

## Task 4: Lesepfad auf die Datenbank umstellen

Der größte Schritt: `TractiveWalkService` verliert seine gesamte Cloud-Sonderbehandlung.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveWalkService.java` (vollständig ersetzt)
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveWalkServiceTest.java` (vollständig ersetzt)

- [ ] **Step 1: Die neue Testdatei schreiben**

`backend/src/test/java/com/household/manager/tractive/TractiveWalkServiceTest.java` **vollständig** durch dieses Inhalt ersetzen. Die bisherigen Testfälle zu Häppchen, Cache, Rate-Limit und Auth entfallen, weil es die geprüften Codepfade nicht mehr gibt:

```java
package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractiveWalkServiceTest {

    private static final TractiveHomeSettings HOME = new TractiveHomeSettings(
            48.2082, 16.3738, 100, 500, 60, 15, "Zuhause");
    private static final TractiveHomeSettings NO_HOME = new TractiveHomeSettings(
            null, null, 100, 500, 60, 15, "Zuhause");

    @Mock
    private TractivePositionRepository repository;
    @Mock
    private TractiveHomeSettingsService homeSettingsService;

    private TractiveWalkService service;

    @BeforeEach
    void setUp() {
        service = new TractiveWalkService(repository, homeSettingsService);
    }

    /**
     * Vier Punkte im 10-Minuten-Abstand, rund einen Kilometer vom Zuhause entfernt –
     * zusammen ein 30-minuetiger Spaziergang. Ein Sprung von 30 Minuten wuerde die
     * Gap-Schwelle des Detektors verletzen und in zu kurze Segmente zerfallen.
     */
    private List<TractivePosition> walkPoints() {
        Instant t = Instant.now().minus(Duration.ofHours(1));
        return List.of(
                point(t),
                point(t.plus(Duration.ofMinutes(10))),
                point(t.plus(Duration.ofMinutes(20))),
                point(t.plus(Duration.ofMinutes(30))));
    }

    private TractivePosition point(Instant at) {
        return TractivePosition.builder()
                .trackerId("dev-9")
                .positionTime(at)
                .latitude(48.2182)
                .longitude(16.3738)
                .accuracy(12.0)
                .sensorUsed("GPS")
                .build();
    }

    @Test
    void ohneZuhauseKommtEineKlareFehlermeldung() {
        // Der Detektor braucht die Home-Zone, um eine Runde von einem Aufenthalt
        // auf der Ladeschale zu unterscheiden.
        when(homeSettingsService.getSettings()).thenReturn(NO_HOME);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getWalks("dev-9", 7));
        assertTrue(ex.getMessage().contains("Zuhause"));
        verifyNoInteractions(repository);
    }

    @Test
    void leitetSpaziergaengeAusGespeichertenPunktenAb() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                eq("dev-9"), any(Instant.class))).thenReturn(walkPoints());

        var walks = service.getWalks("dev-9", 7);

        assertEquals(1, walks.size());
        assertEquals(30, walks.get(0).durationMinutes());
    }

    @Test
    void ohneGespeichertePunkteGibtEsKeineSpaziergaenge() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                anyString(), any(Instant.class))).thenReturn(List.of());

        assertTrue(service.getWalks("dev-9", 7).isEmpty());
    }

    @Test
    void fragtDenGewaehltenZeitraumAbMitternachtAb() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                anyString(), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 7);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                eq("dev-9"), from.capture());
        // 7 Tage heisst: heute plus die sechs Vortage, ab deren Mitternacht.
        long ageDays = Duration.between(from.getValue(), Instant.now()).toDays();
        assertTrue(ageDays >= 6 && ageDays <= 7,
                "Abfragebeginn lag " + ageDays + " Tage zurueck");
    }

    @Test
    void klemmtDenZeitraumAufDasMaximum() {
        when(homeSettingsService.getSettings()).thenReturn(HOME);
        when(repository.findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                anyString(), any(Instant.class))).thenReturn(List.of());

        service.getWalks("dev-9", 99_999);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(
                eq("dev-9"), from.capture());
        long ageDays = Duration.between(from.getValue(), Instant.now()).toDays();
        assertTrue(ageDays <= TractiveWalkService.MAX_DAYS,
                "Abfragebeginn lag " + ageDays + " Tage zurueck");
        assertTrue(ageDays >= TractiveWalkService.MAX_DAYS - 1L,
                "Das Maximum wurde nicht ausgeschoepft: " + ageDays);
    }
}
```

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractiveWalkServiceTest
```

Erwartet: Kompilierfehler — der Konstruktor von `TractiveWalkService` nimmt noch `TractiveApiClient` und `TractiveAuthService`.

- [ ] **Step 3: Den Service ersetzen**

`backend/src/main/java/com/household/manager/tractive/TractiveWalkService.java` **vollständig** ersetzen:

```java
package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Leitet Spaziergaenge aus der selbst mitgeschriebenen Positionshistorie ab.
 * <p>
 * Frueher holte diese Klasse die Positionen bei jedem Abruf aus der Cloud — in
 * Tages-Haeppchen, mit Cache, Rate-Limit-Behandlung und Teilergebnissen. Das ist
 * entfallen: die Cloud liefert beim Basic-Abo ohnehin nur rund 24 Stunden, und
 * der TractivePositionRecorder schreibt seither jeden Poll mit. Der Lesepfad ist
 * damit eine Bereichsabfrage plus der unveraenderte TractiveWalkDetector.
 * <p>
 * <b>Es wird keine Tractive-Anmeldung mehr gebraucht.</b> Gespeicherte Runden
 * bleiben sichtbar, auch wenn das Token abgelaufen ist — Tractive gibt kein
 * Refresh-Token aus, das passiert also regelmaessig.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveWalkService {

    /**
     * Obergrenze des abfragbaren Zeitraums — reine Eingabevalidierung, kein
     * Cloud-Schutz mehr. Bewusst gross: die Historie wird nie aufgeraeumt, eine
     * kleinere Grenze wuerde sie an der API wieder abschneiden.
     */
    static final int MAX_DAYS = 365;

    /** Lokale Haushaltszeit — wie ueberall im Projekt (Kalender, Scheduler). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final TractivePositionRepository repository;
    private final TractiveHomeSettingsService homeSettingsService;

    public List<TractiveWalkDto> getWalks(String trackerId, int days) {
        int clampedDays = Math.clamp(days, 1, MAX_DAYS);

        // Einmal lesen und damit weiterrechnen, damit eine Bewertung einen
        // konsistenten Satz Einstellungen sieht.
        TractiveHomeSettings settings = homeSettingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            throw new IllegalStateException(
                    "Kein Zuhause konfiguriert. Bitte unter Admin → Hundetracker-Zuhause festlegen.");
        }

        Instant from = LocalDate.now(ZONE).minusDays(clampedDays - 1L)
                .atStartOfDay(ZONE).toInstant();
        List<TractivePositionDto> points = repository
                .findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(trackerId, from)
                .stream()
                .map(TractiveWalkService::toDto)
                .toList();

        GeoZone home = new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(), settings.homeRadiusMeters());
        return TractiveWalkDetector.detectWalks(points, home);
    }

    /**
     * Bildet eine gespeicherte Zeile auf das DTO ab, mit dem der Detektor arbeitet —
     * so bleibt die Erkennungslogik unveraendert und weiter unabhaengig testbar.
     */
    private static TractivePositionDto toDto(TractivePosition position) {
        return new TractivePositionDto(
                List.of(position.getLatitude(), position.getLongitude()),
                position.getAccuracy(),
                position.getSensorUsed(),
                position.getPositionTime().getEpochSecond());
    }
}
```

- [ ] **Step 4: Tests laufen lassen und grün sehen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=TractiveWalkServiceTest
```

Erwartet: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Prüfen, dass nichts anderes am alten Konstruktor hängt**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test-compile
```

Erwartet: erfolgreicher Build. Schlägt er fehl, weil eine andere Klasse `TractiveWalkService` mit den alten Parametern erzeugt, **halte an und melde es** — der Plan geht davon aus, dass nur Spring die Klasse verdrahtet.

- [ ] **Step 6: Mutationsprüfung des Zeitraums**

Setze `MAX_DAYS` testweise auf `30` und lass die Klasse laufen. **`klemmtDenZeitraumAufDasMaximum` muss grün bleiben** (er rechnet über die Konstante), aber halte fest, ob `fragtDenGewaehltenZeitraumAbMitternachtAb` weiterhin grün ist. Setze anschließend zurück.

Ändere danach `clampedDays - 1L` testweise zu `clampedDays`. **`fragtDenGewaehltenZeitraumAbMitternachtAb` muss fehlschlagen.** Halte die Ausgabe fest und setze zurück. Bestätige mit `git diff`, dass die Datei wieder dem Stand aus Step 3 entspricht.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveWalkService.java backend/src/test/java/com/household/manager/tractive/TractiveWalkServiceTest.java
git commit -m "feat(tractive): Spaziergaenge aus der eigenen Historie statt aus der Cloud"
```

Commit-Message um eine Leerzeile und `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ergänzen.

---

## Task 5: Den toten Cloud-Endpunkt entfernen

Nach Task 4 ruft nur noch der eigene Test `TractiveApiClient.getPositionHistory` auf. Produktionscode, den ausschließlich sein Test benutzt, suggeriert einen Pfad, den es nicht mehr gibt.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java`
- Modify: `backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java`

- [ ] **Step 1: Belegen, dass die Methode wirklich tot ist**

```bash
grep -rn "getPositionHistory" backend/src/main/java/ backend/src/test/java/
```

Erwartet: Treffer **ausschließlich** in `TractiveApiClient.java` (die Definition) und in `TractiveApiClientTest.java` (zwei Testfälle). Findet sich ein weiterer Aufrufer, **halte an und melde es**, statt zu löschen.

- [ ] **Step 2: Die Methode entfernen**

In `backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java` die Methode `getPositionHistory` samt ihrem Javadoc löschen (beginnt bei Zeile 93). Entferne anschließend Importe, die dadurch ungenutzt werden.

- [ ] **Step 3: Die beiden Testfälle entfernen**

In `backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java` die beiden Testmethoden löschen, die `getPositionHistory` aufrufen (um Zeile 139 und 156), samt ihrer Hilfsstubs, falls diese danach ungenutzt sind. Entferne ungenutzt gewordene Importe.

**Nicht entfernen:** `TractiveRateLimitException` und ihre Behandlung. `TractivePollingService` und die übrigen Methoden des `TractiveApiClient` brauchen sie weiter.

- [ ] **Step 4: Ganze Suite laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```

Erwartet: nur die zwei vorbestehenden, umgebungsbedingten Fehlschläge (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest`) wegen der nicht erreichbaren Test-Datenbank. Jeder weitere Fehlschlag ist eine Regression.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveApiClient.java backend/src/test/java/com/household/manager/tractive/TractiveApiClientTest.java
git commit -m "refactor(tractive): Toten Positions-Historien-Endpunkt entfernen"
```

Commit-Message um eine Leerzeile und `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ergänzen.

---

## Task 6: Dokumentation nachziehen

**Files:**
- Modify: `CLAUDE.md`, Abschnitt „Tractive-Hundetracker"

- [ ] **Step 1: Den bestehenden Spaziergänge-Punkt ersetzen**

In `CLAUDE.md` gibt es im Abschnitt „Tractive-Hundetracker" einen langen Aufzählungspunkt, der mit **„Spaziergänge-Dialog:"** beginnt und die Cloud-Häppchen, den Cache und das Rate-Limit beschreibt. Dieser Punkt beschreibt jetzt einen Zustand, den es nicht mehr gibt.

Ersetze ihn durch:

```markdown
- **Spaziergänge kommen aus der eigenen Positionshistorie, nicht mehr aus der Cloud** (Entscheidung 2026-08-23): `TractivePositionRecorder` schreibt im minütlichen Poll-Zyklus jede Position nach `tractive_position` — das kostet **keinen** zusätzlichen Cloud-Aufruf, der Poller hat sie ohnehin in der Hand. `GET /v1/tractive/pets/{trackerId}/walks?days` liest daraus und gibt die Punkte an den unveränderten `TractiveWalkDetector`
- **Anlass:** die Tractive-Cloud liefert beim Basic-Abo nur ~24 h Historie, und der frühere `dayCache` war ein reiner Abruf-Cache (nur im Speicher, weg beim Neustart, und er konnte nur Tage enthalten, die die Cloud schon einmal geliefert hatte). In der Tablet-Ansicht war deshalb praktisch nur die aktuelle Runde sichtbar, egal welcher Zeitraum gewählt war
- **Gespeichert wird nur ein NEUER Bericht** (`existsByTrackerIdAndPositionTime` plus Eindeutigkeitsschlüssel auf `(tracker_id, position_time)`): bei ausgeschaltetem Tracker liefert die API weiter die letzte bekannte Position mit unverändertem Zeitstempel. Würde die jede Minute gespeichert, entstünde ein künstlich lückenloser Strom — und der Detector erkennt Runden **gerade an den Funkpausen über 30 min**. Das Ergebnis wäre ein einziger, nie endender „Spaziergang"
- `TractivePositionRecorder` **wirft nie**: derselbe Poll versorgt Entitäten, Dashboard-Kachel und Zu-Hause-Sensor (Muster `PowerHistoryRecorder`). Er läuft **vor** dem Entity-Mapping, damit die Historie auch dann entsteht, wenn das Mapping scheitert
- **Damit entfallen:** Tages-Cache, Rate-Limit-Behandlung, 24-h-Häppchen und die Anmeldeprüfung im Lesepfad. Gespeicherte Runden bleiben sichtbar, auch wenn das Tractive-Token abgelaufen ist — was mangels Refresh-Token regelmäßig passiert. `MAX_DAYS` ist von 14 auf **365** gestiegen und nur noch Eingabevalidierung
- **Preis, bewusst akzeptiert:** ein Punkt pro Minute ist gröber als die Aufzeichnung des Trackers selbst, die **Distanz** fällt deshalb systematisch etwas zu niedrig aus (Kurven werden zu Geraden). Dauer und Zeitpunkt bleiben exakt; wer mit der Tractive-App vergleicht, sieht kleinere Kilometerwerte
- **Die Historie beginnt beim Deploy** — was vorher war, gibt die Cloud nicht mehr her. Es gibt **keinen** Aufräumjob für `tractive_position`; bei einem Punkt pro Minute während der Runden bleibt die Tabelle auch nach Jahren klein
- `TractiveApiClient.getPositionHistory` wurde dabei entfernt (nur noch von seinem eigenen Test aufgerufen). Die daran erarbeiteten Erkenntnisse — Tages-Häppchen wegen Code 7500 HISTORY, Rate-Limit 4006 — bleiben in dieser Datei und in der Git-Historie erhalten
```

**Der Detector selbst ist unverändert** — der bestehende Punkt zu `TractiveWalkDetector` (Einschalt-Indikator, 30-min-Lücke, 5-min-Minimum, Home-Radius) bleibt stehen und gilt weiter.

- [ ] **Step 2: Den Tablet-Abschnitt korrigieren**

Im Abschnitt „Tablet-Ansichten" steht ein Punkt, der `MAX_DAYS` 14 → 30 und das Rate-Limit-Risiko beim ersten 30-Tage-Klick beschreibt. Ersetze ihn durch:

```markdown
- Der Zeitraum der Spaziergänge ist 7 / 14 / 30 Tage. Seit dem Umbau auf die eigene Positionshistorie (2026-08-23) kostet ein Zeitraumwechsel **keinen** Cloud-Aufruf mehr und kann nicht am Rate-Limit scheitern; die frühere `MAX_DAYS`-Grenze ist nur noch Eingabevalidierung (365). **Die Kachel füllt sich erst ab dem Deploy** — ältere Runden gibt die Cloud beim Basic-Abo nicht mehr her
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): Eigene Positionshistorie der Spaziergaenge festhalten"
```

Commit-Message um eine Leerzeile und `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` ergänzen.

---

## Abschluss

Nach Task 6 ist die Arbeit vollständig. Für die Integration nach `main` die Skill `superpowers:finishing-a-development-branch` verwenden.

**Was bewusst offen bleibt:**

- **Vor dem ersten Deploy ist die Kachel leer.** Das ist erwartet und kein Fehler. Nach einem Tag steht ein Tag, nach einer Woche eine Woche.
- **Auf dieser Maschine läuft kein datenbankgestützter Test.** Ob Liquibase-Changeset und Entität wirklich zusammenpassen, zeigt sich erst beim ersten Start gegen eine echte MariaDB. Der erste Start nach dem Deploy ist deshalb zu beobachten — ein Fehler dort wäre ein Liquibase- oder Mapping-Fehler, kein Logikfehler.
- Die Genauigkeit der Distanz (ein Punkt pro Minute) wurde nie gegen die Tractive-App gegengemessen.
