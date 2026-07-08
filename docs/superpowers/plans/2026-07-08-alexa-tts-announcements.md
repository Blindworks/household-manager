# Alexa-TTS-Integration („Ansagen") Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Text-to-Speech-Durchsagen auf Amazon-Echo-Geräten aus dem Household-Manager auslösen — manuell aus der UI, zeitgeplant und als interner Baustein für spätere automatische Benachrichtigungen.

**Architecture:** Neues Backend-Package `com.household.manager.alexa` mit direkter Anbindung an die inoffiziellen `alexa.amazon.de`-Endpunkte (wie alexa-remote-control / alexa_media_player). Erst-Login als App-Flow (E-Mail/Passwort + MFA), gespeichert wird nur das Refresh-Token. TTS läuft über `POST /api/behaviors/preview` mit `Alexa.Speak` (ein Gerät) bzw. `AlexaAnnouncement` (mehrere Geräte, mit Signalton). Neue Angular-Seite „Ansagen".

**Tech Stack:** Spring Boot 3.4.1, Java 21, `java.net.http.HttpClient`, Jackson, Liquibase, Lombok, JUnit 5 + AssertJ; Angular 19 standalone, RxJS, Karma/Jasmine.

**Wichtige Konventionen (aus dem Repo & Memory):**
- Build braucht JDK 21: `JAVA_HOME="C:\Program Files\Java\jdk-21.0.10"` vor `mvn` setzen (Default ist JDK 17). Lokale DB-Tests scheitern by design — das ist kein Fehler dieses Plans.
- **Alle** JPA-Repositories müssen in `com.household.manager.repository` liegen (JpaConfig beschränkt das Scanning). Entities in `com.household.manager.model.entity`.
- Geräte-Identität über **stabile `serialNumber`**, nie über IP/Listenreihenfolge (Lehre aus Kasa).
- Controller-Basispfad ohne `/api` (Servlet-Context-Path liefert das Präfix); vorhandene Controller nutzen z. B. `@RequestMapping("/devices")`, `@RequestMapping("/v1/weather")`. Wir nutzen `@RequestMapping("/v1/alexa")`.
- Deutschsprachige Log-/Fehlermeldungen ohne Umlaute (bestehender Stil, z. B. „Geraet").
- Liquibase: neue Changeset-Datei unter `db/changelog/changes/`, Include in `db.changelog-master.xml`.
- Amazon-Domain konfigurierbar über Property `alexa.domain` (Default `amazon.de`).

**Arbeitsbasis:** Direkt auf `main` (ausdrücklicher Wunsch des Nutzers). Task 0 stellt nur sicher, dass `main` ausgecheckt und aktuell ist.

---

## File Structure

**Backend — neu:**
- `backend/src/main/java/com/household/manager/alexa/AlexaProperties.java` — `@ConfigurationProperties(prefix="alexa")`: domain.
- `backend/src/main/java/com/household/manager/alexa/AlexaException.java` — RuntimeException der Integration.
- `backend/src/main/java/com/household/manager/alexa/AlexaTtsMode.java` — Enum `SPEAK`, `ANNOUNCE`.
- `backend/src/main/java/com/household/manager/alexa/AlexaSession.java` — In-Memory-Sitzung (Cookies, csrf, accessToken, customerId, Ablauf).
- `backend/src/main/java/com/household/manager/alexa/AlexaSequenceBuilder.java` — **reine Logik**: baut `sequenceJson`-Body für Speak/Announce (testbar).
- `backend/src/main/java/com/household/manager/alexa/AlexaAuthService.java` — Login-/MFA-/Captcha-Flow, Token-Refresh, Cookie-Austausch, Session-Cache.
- `backend/src/main/java/com/household/manager/alexa/AlexaApiClient.java` — HTTP gegen `alexa.<domain>`: Geräteliste, `users/me`, `behaviors/preview`.
- `backend/src/main/java/com/household/manager/alexa/AlexaRemoteDevice.java` — Record: serialNumber, accountName, deviceType, deviceFamily, capabilities.
- `backend/src/main/java/com/household/manager/service/AlexaDeviceService.java` — Persistenz/Rescan der Echos.
- `backend/src/main/java/com/household/manager/service/AlexaAnnouncementService.java` — Fachschnittstelle `announce(text, serials, mode)`.
- `backend/src/main/java/com/household/manager/service/AlexaScheduledAnnouncementService.java` — CRUD + `@Scheduled`-Fälligkeit.
- `backend/src/main/java/com/household/manager/controller/AlexaController.java` — REST.
- `backend/src/main/java/com/household/manager/model/entity/AlexaAccount.java`
- `backend/src/main/java/com/household/manager/model/entity/AlexaDevice.java`
- `backend/src/main/java/com/household/manager/model/entity/AlexaScheduledAnnouncement.java`
- `backend/src/main/java/com/household/manager/repository/AlexaAccountRepository.java`
- `backend/src/main/java/com/household/manager/repository/AlexaDeviceRepository.java`
- `backend/src/main/java/com/household/manager/repository/AlexaScheduledAnnouncementRepository.java`
- `backend/src/main/java/com/household/manager/dto/alexa/*` — DTOs (siehe Tasks).
- `backend/src/main/resources/db/changelog/changes/20260708-0028-create-alexa-tables.xml`

**Backend — geändert:**
- `backend/src/main/resources/db/changelog/db.changelog-master.xml` — Include neu.
- `backend/src/main/resources/application.properties` — `alexa.domain`, Scheduling-Intervall.

**Backend — Tests:**
- `backend/src/test/java/com/household/manager/alexa/AlexaSequenceBuilderTest.java`
- `backend/src/test/java/com/household/manager/service/AlexaScheduledAnnouncementServiceTest.java`
- `backend/src/test/java/com/household/manager/service/AlexaDeviceServiceTest.java`

**Frontend — neu:**
- `frontend/src/app/models/alexa.model.ts`
- `frontend/src/app/services/alexa.service.ts`
- `frontend/src/app/services/alexa.service.spec.ts`
- `frontend/src/app/pages/announcements/announcements.component.ts` / `.html` / `.scss` / `.spec.ts`

**Frontend — geändert:**
- `frontend/src/app/app.routes.ts` — Route `announcements`.
- `frontend/src/app/components/header/header.component.ts` — Nav-Eintrag „Ansagen".

---

## Task 0: Auf main-Branch wechseln

**Files:** keine.

- [ ] **Step 1: main auschecken und aktualisieren**

```bash
git checkout main
git pull --ff-only
```

- [ ] **Step 2: Verifizieren**

Run: `git status -sb`
Expected: `## main` (bzw. `## main...origin/main`). Es wird direkt auf `main` committet — kein Feature-Branch.

> **Hinweis:** Spec und Plan wurden auf dem Branch `fix/meross-power-state-mqtt` committet. Damit sie auf `main` verfügbar sind, muss dieser Branch nach `main` gemergt sein (oder die beiden Doc-Commits nach `main` übernommen werden), bevor die Implementierung startet. Das ist eine Vorbedingung dieses Tasks.

---

## Task 1: Konfiguration & Basistypen

**Files:**
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaProperties.java`
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaException.java`
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaTtsMode.java`
- Modify: `backend/src/main/resources/application.properties`
- Modify: `backend/src/main/java/com/household/manager/HouseholdManagerApplication.java` (nur falls `@ConfigurationPropertiesScan` fehlt — siehe Step 4)

- [ ] **Step 1: `AlexaTtsMode` anlegen**

```java
package com.household.manager.alexa;

/** Wiedergabemodus fuer Alexa-Durchsagen. */
public enum AlexaTtsMode {
    /** Sprachausgabe auf einem Geraet, ohne Signalton. */
    SPEAK,
    /** Durchsage mit vorangestelltem Signalton, ein oder mehrere Geraete. */
    ANNOUNCE
}
```

- [ ] **Step 2: `AlexaException` anlegen**

```java
package com.household.manager.alexa;

/** Fehler bei der Kommunikation mit den (inoffiziellen) Alexa-Endpunkten. */
public class AlexaException extends RuntimeException {

    public AlexaException(String message) {
        super(message);
    }

    public AlexaException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: `AlexaProperties` anlegen**

```java
package com.household.manager.alexa;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Konfiguration der Alexa-Integration. */
@Component
@ConfigurationProperties(prefix = "alexa")
@Getter
@Setter
public class AlexaProperties {

    /** Amazon-Domain des Kontos, z. B. amazon.de. */
    private String domain = "amazon.de";
}
```

- [ ] **Step 4: `@ConfigurationPropertiesScan` sicherstellen**

Run: `grep -n "ConfigurationPropertiesScan\|EnableConfigurationProperties" backend/src/main/java/com/household/manager/HouseholdManagerApplication.java backend/src/main/java/com/household/manager/tapo/TapoProperties.java`

- Wenn `TapoProperties` bereits per `@Component` registriert wird (wie hier durch `@Component`), ist nichts weiter nötig — `AlexaProperties` ist ebenfalls `@Component`.
- Falls das Projektmuster stattdessen `@EnableConfigurationProperties` nutzt, ergänze `AlexaProperties.class` dort und entferne `@Component`.

- [ ] **Step 5: `application.properties` ergänzen**

Am Ende der Datei einfügen:

```properties
# Alexa Text-to-Speech Integration
alexa.domain=${ALEXA_DOMAIN:amazon.de}
# Faelligkeitspruefung fuer geplante Ansagen (ms)
alexa.scheduled.check-interval-ms=60000
```

- [ ] **Step 6: Kompilieren**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/alexa backend/src/main/resources/application.properties
git commit -m "feat(alexa): add config properties, exception and TTS mode enum"
```

---

## Task 2: Entities & Repositories

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/AlexaAccount.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/AlexaDevice.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/AlexaScheduledAnnouncement.java`
- Create: `backend/src/main/java/com/household/manager/repository/AlexaAccountRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/AlexaDeviceRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/AlexaScheduledAnnouncementRepository.java`

- [ ] **Step 1: `AlexaAccount` anlegen**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Persistiertes Amazon-Konto der Alexa-Integration. Es existiert hoechstens eine Zeile. */
@Entity
@Table(name = "alexa_account")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Langlebiges Refresh-Token; einziger dauerhaft gespeicherter Zugangsschluessel. */
    @Column(name = "refresh_token", nullable = false, columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "amazon_domain", nullable = false, length = 64)
    private String amazonDomain;

    @Column(name = "account_name", length = 255)
    private String accountName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: `AlexaDevice` anlegen**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Persistiertes Echo-Geraet. Identitaet ueber die stabile serialNumber, nie ueber IP/Reihenfolge. */
@Entity
@Table(name = "alexa_device")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "serial_number", nullable = false, unique = true, length = 128)
    private String serialNumber;

    @Column(name = "device_type", length = 64)
    private String deviceType;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** true, wenn das Geraet Text-to-Speech/Announcement unterstuetzt. */
    @Column(name = "tts_capable", nullable = false)
    private boolean ttsCapable;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: `AlexaScheduledAnnouncement` anlegen**

`targetSerialNumbers` wird als CSV in einer `@ElementCollection`-Join-Tabelle abgelegt (entspricht der im Spec beschriebenen Join-Tabelle `alexa_scheduled_announcement_device`).

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

/** Zeitgeplante Ansage: Text zu einer Uhrzeit an ausgewaehlten Wochentagen. */
@Entity
@Table(name = "alexa_scheduled_announcement")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaScheduledAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "text", nullable = false, length = 1000)
    private String text;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    /** Wochentage als CSV der java.time.DayOfWeek-Namen, z. B. "MONDAY,TUESDAY". */
    @Column(name = "weekdays", nullable = false, length = 128)
    private String weekdays;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private com.household.manager.alexa.AlexaTtsMode mode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_run")
    private LocalDateTime lastRun;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "alexa_scheduled_announcement_device",
            joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "serial_number", nullable = false, length = 128)
    @Builder.Default
    private Set<String> targetSerialNumbers = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 4: Repositories anlegen**

`AlexaAccountRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.AlexaAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlexaAccountRepository extends JpaRepository<AlexaAccount, Long> {

    Optional<AlexaAccount> findFirstByOrderByIdAsc();
}
```

`AlexaDeviceRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.AlexaDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlexaDeviceRepository extends JpaRepository<AlexaDevice, Long> {

    Optional<AlexaDevice> findBySerialNumber(String serialNumber);
}
```

`AlexaScheduledAnnouncementRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlexaScheduledAnnouncementRepository extends JpaRepository<AlexaScheduledAnnouncement, Long> {

    List<AlexaScheduledAnnouncement> findByEnabledTrue();
}
```

- [ ] **Step 5: Kompilieren**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/Alexa*.java backend/src/main/java/com/household/manager/repository/Alexa*.java
git commit -m "feat(alexa): add entities and repositories for account, device and scheduled announcement"
```

---

## Task 3: Liquibase-Migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260708-0028-create-alexa-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Changeset anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260708-0028" author="household-manager">
        <comment>Create alexa_account, alexa_device and scheduled announcement tables</comment>

        <createTable tableName="alexa_account">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="refresh_token" type="TEXT">
                <constraints nullable="false"/>
            </column>
            <column name="amazon_domain" type="VARCHAR(64)">
                <constraints nullable="false"/>
            </column>
            <column name="account_name" type="VARCHAR(255)"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="alexa_device">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="serial_number" type="VARCHAR(128)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="device_type" type="VARCHAR(64)"/>
            <column name="name" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="tts_capable" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="alexa_scheduled_announcement">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="text" type="VARCHAR(1000)">
                <constraints nullable="false"/>
            </column>
            <column name="time_of_day" type="TIME">
                <constraints nullable="false"/>
            </column>
            <column name="weekdays" type="VARCHAR(128)">
                <constraints nullable="false"/>
            </column>
            <column name="mode" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="last_run" type="TIMESTAMP"/>
            <column name="last_error" type="VARCHAR(500)"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="alexa_scheduled_announcement_device">
            <column name="announcement_id" type="BIGINT">
                <constraints nullable="false"
                             foreignKeyName="fk_alexa_sched_ann_device"
                             references="alexa_scheduled_announcement(id)"/>
            </column>
            <column name="serial_number" type="VARCHAR(128)">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <rollback>
            <dropTable tableName="alexa_scheduled_announcement_device"/>
            <dropTable tableName="alexa_scheduled_announcement"/>
            <dropTable tableName="alexa_device"/>
            <dropTable tableName="alexa_account"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: In Master-Changelog einbinden**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` unmittelbar vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Alexa TTS Announcements Feature -->
    <include file="db/changelog/changes/20260708-0028-create-alexa-tables.xml"/>
```

- [ ] **Step 3: Changelog-XML validieren (Kompilierung genügt hier nicht)**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (XML wird als Ressource kopiert). Die tatsächliche Migration wird beim App-Start in Task 12 geprüft.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat(alexa): add liquibase migration for alexa tables"
```

---

## Task 4: `AlexaSequenceBuilder` (reine Logik, TDD)

Diese Klasse kapselt die exakten `behaviors/preview`-Payloads. Sie ist rein (kein HTTP) und damit voll unit-testbar — genau wie `TapoCloudService.isTapoDevice`/`decodeAlias` das Muster vorgibt.

**Files:**
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaSequenceBuilder.java`
- Test: `backend/src/test/java/com/household/manager/alexa/AlexaSequenceBuilderTest.java`

- [ ] **Step 1: Failing test schreiben**

```java
package com.household.manager.alexa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlexaSequenceBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AlexaSequenceBuilder builder = new AlexaSequenceBuilder(mapper);

    private AlexaRemoteDevice device(String serial, String type) {
        return new AlexaRemoteDevice(serial, "Kueche", type, "ROOK", List.of("AUDIO_PLAYER"));
    }

    @Test
    void speakPayloadContainsPreviewEnvelopeAndSpeakNode() throws Exception {
        String body = builder.buildSpeak(device("DSN1", "A1TYPE"), "cid-123", "de-DE", "Hallo Welt");

        JsonNode root = mapper.readTree(body);
        assertThat(root.get("behaviorId").asText()).isEqualTo("PREVIEW");
        assertThat(root.get("status").asText()).isEqualTo("ENABLED");

        JsonNode seq = mapper.readTree(root.get("sequenceJson").asText());
        JsonNode op = seq.get("startNode");
        assertThat(op.get("type").asText()).isEqualTo("Alexa.Speak");
        JsonNode payload = op.get("operationPayload");
        assertThat(payload.get("deviceSerialNumber").asText()).isEqualTo("DSN1");
        assertThat(payload.get("deviceType").asText()).isEqualTo("A1TYPE");
        assertThat(payload.get("customerId").asText()).isEqualTo("cid-123");
        assertThat(payload.get("locale").asText()).isEqualTo("de-DE");
        assertThat(payload.get("textToSpeak").asText()).isEqualTo("Hallo Welt");
    }

    @Test
    void announcePayloadTargetsAllDevicesWithAnnouncementNode() throws Exception {
        String body = builder.buildAnnouncement(
                List.of(device("DSN1", "A1TYPE"), device("DSN2", "A2TYPE")),
                "cid-123", "de-DE", "Abendessen ist fertig");

        JsonNode root = mapper.readTree(body);
        JsonNode seq = mapper.readTree(root.get("sequenceJson").asText());
        JsonNode op = seq.get("startNode");
        assertThat(op.get("type").asText()).isEqualTo("AlexaAnnouncement");
        JsonNode payload = op.get("operationPayload");
        assertThat(payload.get("skillId").asText()).isEqualTo("amzn1.ask.1p.routines.messaging");
        assertThat(payload.get("content").get(0).get("speak").get("value").asText())
                .isEqualTo("Abendessen ist fertig");
        JsonNode devices = payload.get("target").get("devices");
        assertThat(devices).hasSize(2);
        assertThat(devices.get(0).get("deviceSerialNumber").asText()).isEqualTo("DSN1");
        assertThat(devices.get(0).get("deviceTypeId").asText()).isEqualTo("A1TYPE");
        assertThat(devices.get(1).get("deviceSerialNumber").asText()).isEqualTo("DSN2");
    }

    @Test
    void announcementEscapesQuotesInText() throws Exception {
        String body = builder.buildAnnouncement(
                List.of(device("DSN1", "A1TYPE")), "cid", "de-DE", "Sag \"Hallo\"");
        // Muss wieder parsebar sein -> keine Escaping-Fehler
        JsonNode root = mapper.readTree(body);
        JsonNode seq = mapper.readTree(root.get("sequenceJson").asText());
        assertThat(seq.get("startNode").get("operationPayload")
                .get("content").get(0).get("speak").get("value").asText())
                .isEqualTo("Sag \"Hallo\"");
    }
}
```

- [ ] **Step 2: `AlexaRemoteDevice`-Record anlegen (wird vom Test benötigt)**

```java
package com.household.manager.alexa;

import java.util.List;

/**
 * Ein von der Alexa-Cloud gemeldetes Geraet.
 *
 * @param serialNumber  stabile Seriennummer (Identitaet)
 * @param accountName   Anzeigename
 * @param deviceType    Alexa-Geraetetyp-ID (z. B. A1TYPE)
 * @param deviceFamily  Familie (z. B. ROOK, ECHO)
 * @param capabilities  gemeldete Faehigkeiten
 */
public record AlexaRemoteDevice(
        String serialNumber,
        String accountName,
        String deviceType,
        String deviceFamily,
        List<String> capabilities) {

    /** true, wenn das Geraet Sprachausgabe unterstuetzt. */
    public boolean isTtsCapable() {
        if (capabilities == null) {
            return false;
        }
        return capabilities.contains("AUDIO_PLAYER") || capabilities.contains("TEXT_TO_SPEECH");
    }
}
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaSequenceBuilderTest`
Expected: FAIL — `AlexaSequenceBuilder` existiert noch nicht (Kompilierfehler).

- [ ] **Step 4: `AlexaSequenceBuilder` implementieren**

```java
package com.household.manager.alexa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Baut die exakten Request-Bodies fuer POST /api/behaviors/preview.
 * <p>
 * Die Struktur entspricht den bewaehrten Referenzimplementierungen
 * (alexa-remote-control, alexa_media_player): eine aeussere PREVIEW-Huelle mit
 * einem als String eingebetteten "sequenceJson".
 */
@Component
public class AlexaSequenceBuilder {

    private static final String SEQUENCE_TYPE = "com.amazon.alexa.behaviors.model.Sequence";
    private static final String OPERATION_NODE_TYPE =
            "com.amazon.alexa.behaviors.model.OpaquePayloadOperationNode";
    private static final String ANNOUNCEMENT_SKILL_ID = "amzn1.ask.1p.routines.messaging";

    private final ObjectMapper mapper;

    public AlexaSequenceBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Alexa.Speak: einfache Sprachausgabe auf genau einem Geraet, ohne Signalton. */
    public String buildSpeak(AlexaRemoteDevice device, String customerId, String locale, String text) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("deviceType", device.deviceType());
        payload.put("deviceSerialNumber", device.serialNumber());
        payload.put("customerId", customerId);
        payload.put("locale", locale);
        payload.put("textToSpeak", text);

        ObjectNode startNode = mapper.createObjectNode();
        startNode.put("@type", OPERATION_NODE_TYPE);
        startNode.put("type", "Alexa.Speak");
        startNode.set("operationPayload", payload);

        return wrapSequence(startNode);
    }

    /** AlexaAnnouncement: Durchsage mit Signalton an ein oder mehrere Geraete. */
    public String buildAnnouncement(List<AlexaRemoteDevice> devices, String customerId,
                                    String locale, String text) {
        ObjectNode display = mapper.createObjectNode();
        display.put("title", "Household Manager");
        display.put("body", text);

        ObjectNode speak = mapper.createObjectNode();
        speak.put("type", "text");
        speak.put("value", text);

        ObjectNode contentItem = mapper.createObjectNode();
        contentItem.put("locale", locale);
        contentItem.set("display", display);
        contentItem.set("speak", speak);

        ArrayNode content = mapper.createArrayNode();
        content.add(contentItem);

        ArrayNode targetDevices = mapper.createArrayNode();
        for (AlexaRemoteDevice device : devices) {
            ObjectNode d = mapper.createObjectNode();
            d.put("deviceSerialNumber", device.serialNumber());
            d.put("deviceTypeId", device.deviceType());
            targetDevices.add(d);
        }

        ObjectNode target = mapper.createObjectNode();
        target.put("customerId", customerId);
        target.set("devices", targetDevices);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("expandTextField", "None");
        payload.put("customerId", customerId);
        payload.put("locale", locale);
        payload.set("content", content);
        payload.set("target", target);
        payload.put("skillId", ANNOUNCEMENT_SKILL_ID);

        ObjectNode startNode = mapper.createObjectNode();
        startNode.put("@type", OPERATION_NODE_TYPE);
        startNode.put("type", "AlexaAnnouncement");
        startNode.set("operationPayload", payload);

        return wrapSequence(startNode);
    }

    private String wrapSequence(ObjectNode startNode) {
        try {
            ObjectNode sequence = mapper.createObjectNode();
            sequence.put("@type", SEQUENCE_TYPE);
            sequence.set("startNode", startNode);

            ObjectNode body = mapper.createObjectNode();
            body.put("behaviorId", "PREVIEW");
            body.put("sequenceJson", mapper.writeValueAsString(sequence));
            body.put("status", "ENABLED");
            return mapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Sequenz konnte nicht serialisiert werden.", ex);
        }
    }
}
```

- [ ] **Step 5: Test ausführen — muss bestehen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaSequenceBuilderTest`
Expected: PASS (3 Tests grün).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/alexa/AlexaRemoteDevice.java backend/src/main/java/com/household/manager/alexa/AlexaSequenceBuilder.java backend/src/test/java/com/household/manager/alexa/AlexaSequenceBuilderTest.java
git commit -m "feat(alexa): add tested sequence builder for speak and announcement payloads"
```

---

## Task 5: `AlexaSession` + `AlexaAuthService` (inoffizieller Login-Flow)

Dies ist der fragilste, Amazon-spezifische Teil. Er wird bewusst in `AlexaAuthService`/`AlexaApiClient` isoliert. Netzwerkaufrufe werden **nicht** unit-getestet (kein Live-Amazon in CI, gleiches Vorgehen wie bei `TapoCloudService`); die Verifikation erfolgt manuell in Task 12.

**Files:**
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaSession.java`
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaAuthService.java`

- [ ] **Step 1: `AlexaSession` anlegen**

```java
package com.household.manager.alexa;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/** Fluechtige, im Speicher gehaltene Alexa-Sitzung (nicht persistiert). */
@Getter
@Builder
public class AlexaSession {

    /** Cookie-Header-Wert fuer alexa.<domain> (Name=Wert; ...). */
    private final String cookie;

    /** CSRF-Token fuer schreibende Aufrufe. */
    private final String csrf;

    /** Aktuelles Access-Token. */
    private final String accessToken;

    /** Amazon-Kundennummer (fuer behaviors/preview). */
    private final String customerId;

    /** Zeitpunkt, ab dem die Sitzung als abgelaufen gilt. */
    private final Instant expiresAt;

    public boolean isValid() {
        return cookie != null && csrf != null && customerId != null
                && expiresAt != null && Instant.now().isBefore(expiresAt);
    }
}
```

- [ ] **Step 2: `AlexaAuthService` implementieren**

> Hinweis für den Umsetzer: Der vollständige Amazon-Geräteregistrierungs-Flow (PKCE `code_verifier`/`code_challenge`, `frc`/`map-md`, `/ap/signin` mit versteckten Formularfeldern, MFA, `/auth/register`) ist umfangreich. Die Referenz ist `Apollon77/alexa-cookie` (Node). Die folgende Klasse bildet exakt die Schritte aus dem Design ab. Sie hält den laufenden Flow-Zustand (session-scoped) im Speicher, weil zwischen Login und MFA ein zweiter Request nötig ist.

Endpunkt-Referenz (aus alexa-cookie / alexa-remote-control):
- Cookies holen: `GET https://alexa.<domain>/`
- Leere Login-Session: `POST https://www.<domain>/ap/signin` (versteckte Felder aus HTML via Regex)
- Credential-Login: `POST https://www.<domain>/ap/signin` (Body: `email`, `password`, hidden fields). Antwort enthält je nach Konto ein MFA-Formular (`auth-mfa-otpcode`) oder Captcha (`<img ... id="auth-captcha-image">`).
- MFA: erneuter `POST /ap/signin` mit `otpCode` + hidden fields.
- Registrierung: `POST https://api.<domain>/auth/register` (Header `x-amzn-identity-auth-domain: api.<domain>`, Body mit `requested_token_type:[bearer,mac_dms,website_cookies]`, `registration_data`, `auth_data`, `cookies.website_cookies`). Antwort: `response.success.tokens.bearer.refresh_token` + `access_token`, plus `...tokens.website_cookies`.
- Token-Refresh: `POST https://api.<domain>/auth/token` (Body `app_name`, `source_token_type:refresh_token`, `source_token`, `requested_token_type:access_token`). Antwort: `access_token`.
- Cookie-Austausch: `POST https://www.<domain>/ap/exchangetoken/cookies` (`source_token`=refresh_token, `requested_token_type:auth_cookies`, `domain:.<domain>`). Antwort liefert Website-Cookies.
- CSRF: `GET https://alexa.<domain>/api/language` (oder `/spa/index.html`), csrf aus `Set-Cookie`.
- customerId: `GET https://alexa.<domain>/api/users/me` → Feld `id`.

```java
package com.household.manager.alexa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.AlexaAccount;
import com.household.manager.repository.AlexaAccountRepository;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Kapselt den inoffiziellen Amazon-Login (Geraeteregistrierung) sowie die
 * Verwaltung von Refresh-/Access-Token und alexa.<domain>-Cookies.
 * <p>
 * Der gesamte Amazon-spezifische, bruechige Code lebt hier und in {@link AlexaApiClient}.
 * Netzwerkaufrufe werden nicht unit-getestet; die Verifikation erfolgt manuell.
 */
@Service
@Slf4j
public class AlexaAuthService {

    /** Ergebnisstatus eines Login-Schritts. */
    public enum LoginResult { OK, MFA_REQUIRED, CAPTCHA_REQUIRED, FAILED }

    /** Antwort eines Login-/MFA-Aufrufs an die UI. */
    @Getter
    public static class LoginStep {
        private final LoginResult result;
        private final String captchaImageUrl;
        private final String message;

        public LoginStep(LoginResult result, String captchaImageUrl, String message) {
            this.result = result;
            this.captchaImageUrl = captchaImageUrl;
            this.message = message;
        }
    }

    private static final Duration SESSION_TTL = Duration.ofHours(1);

    private final AlexaAccountRepository accountRepository;
    private final AlexaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    /** Laufender Login-Flow-Zustand zwischen /login und /mfa (nur ein Flow gleichzeitig). */
    private volatile PendingLogin pendingLogin;

    /** Gecachte, gueltige Sitzung. */
    private volatile AlexaSession session;

    /** true, wenn Refresh endgueltig fehlschlug und Neuanmeldung noetig ist. */
    @Getter
    private volatile boolean reauthRequired;

    public AlexaAuthService(AlexaAccountRepository accountRepository,
                            AlexaProperties properties,
                            ObjectMapper mapper) {
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Zwischenzustand des laufenden Login-Flows. */
    private static final class PendingLogin {
        String sessionCookies;
        String signInReferer;
        String codeVerifier;
        String deviceSerial;
        String frc;
        String mapMd;
        // weitere versteckte Felder je nach Amazon-Formular
    }

    // ==================== Public API ====================

    public synchronized LoginStep login(String email, String password, String captchaSolution) {
        reauthRequired = false;
        try {
            // 1) GET alexa.<domain>/  -> Basis-Cookies
            // 2) POST /ap/signin (leer) -> Formularfelder + session-id
            // 3) POST /ap/signin (email/password [+ captchaSolution]) -> HTML auswerten
            //    - enthaelt MFA-Formular  -> MFA_REQUIRED
            //    - enthaelt Captcha-Bild  -> CAPTCHA_REQUIRED (URL zurueckgeben)
            //    - erfolgreich            -> weiter zu completeRegistration()
            // Implementierung gemaess alexa-cookie-Referenz; pendingLogin fuellen.
            throw new UnsupportedOperationException("Login-Flow implementieren (siehe alexa-cookie)");
        } catch (UnsupportedOperationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Alexa-Login fehlgeschlagen: {}", ex.getMessage());
            return new LoginStep(LoginResult.FAILED, null, "Login fehlgeschlagen: " + ex.getMessage());
        }
    }

    public synchronized LoginStep submitMfa(String code) {
        if (pendingLogin == null) {
            return new LoginStep(LoginResult.FAILED, null, "Kein laufender Login-Vorgang.");
        }
        // POST /ap/signin mit otpCode + hidden fields -> bei Erfolg completeRegistration()
        throw new UnsupportedOperationException("MFA-Schritt implementieren (siehe alexa-cookie)");
    }

    /** Nach erfolgreichem Signin: /auth/register aufrufen, refresh_token speichern. */
    private void completeRegistration(String authorizationCode) {
        // POST https://api.<domain>/auth/register ...
        // refresh_token aus response.success.tokens.bearer.refresh_token
        // saveRefreshToken(refreshToken, accountName);
        // buildSessionFromRefreshToken();
        throw new UnsupportedOperationException("Registrierung implementieren (siehe alexa-cookie)");
    }

    public synchronized void logout() {
        accountRepository.findFirstByOrderByIdAsc().ifPresent(accountRepository::delete);
        session = null;
        pendingLogin = null;
        reauthRequired = false;
    }

    public boolean isLoggedIn() {
        return accountRepository.findFirstByOrderByIdAsc().isPresent();
    }

    public String getAccountName() {
        return accountRepository.findFirstByOrderByIdAsc()
                .map(AlexaAccount::getAccountName)
                .orElse(null);
    }

    /**
     * Liefert eine gueltige Sitzung; erneuert sie bei Ablauf per Refresh-Token.
     * Wirft {@link AlexaException}, wenn nicht angemeldet oder Refresh endgueltig scheitert.
     */
    public synchronized AlexaSession getValidSession() {
        if (session != null && session.isValid()) {
            return session;
        }
        AlexaAccount account = accountRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new AlexaException("Nicht bei Amazon angemeldet."));
        try {
            session = buildSessionFromRefreshToken(account.getRefreshToken());
            reauthRequired = false;
            return session;
        } catch (Exception ex) {
            reauthRequired = true;
            log.warn("Alexa-Sitzung konnte nicht erneuert werden: {}", ex.getMessage());
            throw new AlexaException("Alexa-Sitzung abgelaufen, Neuanmeldung erforderlich.", ex);
        }
    }

    private AlexaSession buildSessionFromRefreshToken(String refreshToken) {
        // 1) /auth/token -> access_token
        // 2) /ap/exchangetoken/cookies -> website cookies
        // 3) GET /api/language -> csrf
        // 4) GET /api/users/me -> customerId
        // return AlexaSession.builder()...expiresAt(Instant.now().plus(SESSION_TTL)).build();
        throw new UnsupportedOperationException("Refresh-Flow implementieren (siehe alexa-cookie)");
    }

    private void saveRefreshToken(String refreshToken, String accountName) {
        AlexaAccount account = accountRepository.findFirstByOrderByIdAsc()
                .orElseGet(AlexaAccount::new);
        account.setRefreshToken(refreshToken);
        account.setAmazonDomain(properties.getDomain());
        account.setAccountName(accountName);
        accountRepository.save(account);
    }
}
```

> **Wichtig für den Umsetzer:** Die drei `UnsupportedOperationException`-Stellen sind die Amazon-Netzwerkschritte. Sie sind bewusst als klar markierte Einschübe belassen, weil ihr Inhalt 1:1 aus der `alexa-cookie`-Referenz portiert wird und nicht sinnvoll ohne Live-Konto testbar ist. Portiere sie vollständig, bevor Task 12 (manuelle Verifikation) läuft. Lasse **keine** `UnsupportedOperationException` im finalen Code stehen.

- [ ] **Step 3: Kompilieren**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/alexa/AlexaSession.java backend/src/main/java/com/household/manager/alexa/AlexaAuthService.java
git commit -m "feat(alexa): add auth service skeleton with session, refresh and login flow states"
```

---

## Task 6: `AlexaApiClient`

**Files:**
- Create: `backend/src/main/java/com/household/manager/alexa/AlexaApiClient.java`

- [ ] **Step 1: `AlexaApiClient` implementieren**

```java
package com.household.manager.alexa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** HTTP-Aufrufe gegen alexa.<domain> mit einer gueltigen Sitzung. */
@Service
@Slf4j
public class AlexaApiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final AlexaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AlexaApiClient(AlexaProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    private String baseUrl() {
        return "https://alexa." + properties.getDomain();
    }

    /** GET /api/devices-v2/device — alle Echos des Kontos. */
    public List<AlexaRemoteDevice> listDevices(AlexaSession session) {
        JsonNode root = getJson(session, "/api/devices-v2/device?cached=false");
        List<AlexaRemoteDevice> result = new ArrayList<>();
        for (JsonNode d : root.path("devices")) {
            String serial = d.path("serialNumber").asText(null);
            if (serial == null || serial.isBlank()) {
                continue;
            }
            List<String> caps = new ArrayList<>();
            d.path("capabilities").forEach(c -> caps.add(c.asText()));
            result.add(new AlexaRemoteDevice(
                    serial,
                    d.path("accountName").asText(serial),
                    d.path("deviceType").asText(null),
                    d.path("deviceFamily").asText(null),
                    caps));
        }
        return result;
    }

    /** POST /api/behaviors/preview — spielt die zuvor gebaute Sequenz ab. */
    public void sendBehavior(AlexaSession session, String behaviorBody) {
        HttpResponse<String> response = send(session, "POST", "/api/behaviors/preview", behaviorBody);
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa behaviors/preview HTTP " + response.statusCode()
                    + ": " + response.body());
        }
    }

    private JsonNode getJson(AlexaSession session, String path) {
        HttpResponse<String> response = send(session, "GET", path, null);
        if (response.statusCode() / 100 != 2) {
            throw new AlexaException("Alexa GET " + path + " HTTP " + response.statusCode());
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Antwort konnte nicht gelesen werden: " + path, ex);
        }
    }

    private HttpResponse<String> send(AlexaSession session, String method, String path, String body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .timeout(TIMEOUT)
                    .header("Cookie", session.getCookie())
                    .header("csrf", session.getCsrf())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Household-Manager)")
                    .header("Referer", baseUrl() + "/spa/index.html")
                    .header("Origin", baseUrl());
            if ("GET".equals(method)) {
                req.GET();
            } else {
                req.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
            }
            return httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AlexaException("Alexa-Kommunikation unterbrochen.", ex);
        } catch (Exception ex) {
            throw new AlexaException("Alexa-Kommunikation fehlgeschlagen: " + path, ex);
        }
    }
}
```

- [ ] **Step 2: Kompilieren**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/alexa/AlexaApiClient.java
git commit -m "feat(alexa): add api client for device list and behaviors/preview"
```

---

## Task 7: `AlexaDeviceService` (Rescan-Persistenz, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/AlexaDeviceService.java`
- Test: `backend/src/test/java/com/household/manager/service/AlexaDeviceServiceTest.java`

- [ ] **Step 1: Failing test schreiben**

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaApiClient;
import com.household.manager.alexa.AlexaAuthService;
import com.household.manager.alexa.AlexaRemoteDevice;
import com.household.manager.alexa.AlexaSession;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.repository.AlexaDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlexaDeviceServiceTest {

    private AlexaDeviceRepository repository;
    private AlexaApiClient apiClient;
    private AlexaAuthService authService;
    private AlexaDeviceService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlexaDeviceRepository.class);
        apiClient = mock(AlexaApiClient.class);
        authService = mock(AlexaAuthService.class);
        service = new AlexaDeviceService(repository, apiClient, authService);

        when(authService.getValidSession()).thenReturn(mock(AlexaSession.class));
        // save() gibt das Argument zurueck
        when(repository.save(any(AlexaDevice.class))).thenAnswer(i -> i.getArgument(0));
    }

    private AlexaRemoteDevice remote(String serial, String name, boolean tts) {
        return new AlexaRemoteDevice(serial, name, "A1TYPE", "ROOK",
                tts ? List.of("AUDIO_PLAYER") : List.of());
    }

    @Test
    void rescanInsertsNewDevice() {
        when(apiClient.listDevices(any())).thenReturn(List.of(remote("DSN1", "Kueche", true)));
        when(repository.findBySerialNumber("DSN1")).thenReturn(Optional.empty());

        service.rescan();

        verify(repository).save(argThat(d ->
                d.getSerialNumber().equals("DSN1")
                        && d.getName().equals("Kueche")
                        && d.isTtsCapable()));
    }

    @Test
    void rescanUpdatesExistingDeviceName() {
        AlexaDevice existing = AlexaDevice.builder()
                .serialNumber("DSN1").name("Alt").deviceType("A1TYPE").ttsCapable(true).build();
        when(apiClient.listDevices(any())).thenReturn(List.of(remote("DSN1", "Neu", true)));
        when(repository.findBySerialNumber("DSN1")).thenReturn(Optional.of(existing));

        service.rescan();

        verify(repository).save(argThat(d -> d.getName().equals("Neu")));
    }

    @Test
    void rescanDoesNotDeleteDevicesMissingFromCloud() {
        when(apiClient.listDevices(any())).thenReturn(new ArrayList<>());

        service.rescan();

        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll();
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaDeviceServiceTest`
Expected: FAIL — `AlexaDeviceService` existiert noch nicht.

- [ ] **Step 3: `AlexaDeviceService` implementieren**

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaApiClient;
import com.household.manager.alexa.AlexaAuthService;
import com.household.manager.alexa.AlexaRemoteDevice;
import com.household.manager.alexa.AlexaSession;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.repository.AlexaDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Persistiert die Echo-Geraete des Kontos. Rescan legt neue an und aktualisiert
 * vorhandene ueber die stabile serialNumber; es wird nichts automatisch geloescht.
 */
@Service
@Slf4j
public class AlexaDeviceService {

    private final AlexaDeviceRepository repository;
    private final AlexaApiClient apiClient;
    private final AlexaAuthService authService;

    public AlexaDeviceService(AlexaDeviceRepository repository,
                              AlexaApiClient apiClient,
                              AlexaAuthService authService) {
        this.repository = repository;
        this.apiClient = apiClient;
        this.authService = authService;
    }

    public List<AlexaDevice> getDevices() {
        return repository.findAll();
    }

    /** Holt die aktuelle Geraeteliste aus der Cloud und synchronisiert die DB. */
    public List<AlexaDevice> rescan() {
        AlexaSession session = authService.getValidSession();
        List<AlexaRemoteDevice> remotes = apiClient.listDevices(session);
        for (AlexaRemoteDevice remote : remotes) {
            AlexaDevice device = repository.findBySerialNumber(remote.serialNumber())
                    .orElseGet(() -> AlexaDevice.builder()
                            .serialNumber(remote.serialNumber())
                            .build());
            device.setName(remote.accountName());
            device.setDeviceType(remote.deviceType());
            device.setTtsCapable(remote.isTtsCapable());
            repository.save(device);
        }
        log.info("Alexa-Rescan: {} Geraete aus der Cloud verarbeitet", remotes.size());
        return getDevices();
    }
}
```

- [ ] **Step 4: `mockito-core` verfügbar?**

`spring-boot-starter-test` (in `pom.xml`) enthält Mockito bereits. Kein Dependency-Zusatz nötig.

- [ ] **Step 5: Test ausführen — muss bestehen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaDeviceServiceTest`
Expected: PASS (3 Tests grün).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/AlexaDeviceService.java backend/src/test/java/com/household/manager/service/AlexaDeviceServiceTest.java
git commit -m "feat(alexa): add device service with rescan persistence"
```

---

## Task 8: `AlexaAnnouncementService`

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/AlexaAnnouncementService.java`

- [ ] **Step 1: `AlexaAnnouncementService` implementieren**

```java
package com.household.manager.service;

import com.household.manager.alexa.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachschnittstelle fuer Alexa-Durchsagen. Genutzt von Controller, Scheduler und
 * kuenftig anderen Services (interner Baustein fuer automatische Benachrichtigungen).
 */
@Service
@Slf4j
public class AlexaAnnouncementService {

    private static final String LOCALE = "de-DE";

    private final AlexaAuthService authService;
    private final AlexaApiClient apiClient;
    private final AlexaSequenceBuilder sequenceBuilder;

    public AlexaAnnouncementService(AlexaAuthService authService,
                                    AlexaApiClient apiClient,
                                    AlexaSequenceBuilder sequenceBuilder) {
        this.authService = authService;
        this.apiClient = apiClient;
        this.sequenceBuilder = sequenceBuilder;
    }

    /**
     * Spricht {@code text} auf den Geraeten mit den angegebenen Seriennummern.
     *
     * @param text          zu sprechender Text (nicht leer)
     * @param serialNumbers Ziel-Seriennummern (mindestens eine)
     * @param mode          SPEAK (ein Geraet, ohne Ton) oder ANNOUNCE (mit Signalton)
     */
    public void announce(String text, List<String> serialNumbers, AlexaTtsMode mode) {
        if (text == null || text.isBlank()) {
            throw new AlexaException("Ansagetext darf nicht leer sein.");
        }
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            throw new AlexaException("Es wurde kein Zielgeraet ausgewaehlt.");
        }

        AlexaSession session = authService.getValidSession();
        List<AlexaRemoteDevice> allDevices = apiClient.listDevices(session);
        List<AlexaRemoteDevice> targets = allDevices.stream()
                .filter(d -> serialNumbers.contains(d.serialNumber()))
                .toList();

        if (targets.isEmpty()) {
            throw new AlexaException("Keines der gewaehlten Geraete wurde in der Cloud gefunden.");
        }

        if (mode == AlexaTtsMode.ANNOUNCE) {
            String body = sequenceBuilder.buildAnnouncement(
                    targets, session.getCustomerId(), LOCALE, text);
            apiClient.sendBehavior(session, body);
        } else {
            // SPEAK adressiert je genau ein Geraet -> pro Ziel ein Aufruf
            for (AlexaRemoteDevice target : targets) {
                String body = sequenceBuilder.buildSpeak(
                        target, session.getCustomerId(), LOCALE, text);
                apiClient.sendBehavior(session, body);
            }
        }
        log.info("Alexa-Ansage ({}) an {} Geraet(e) gesendet", mode, targets.size());
    }
}
```

- [ ] **Step 2: Kompilieren**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/AlexaAnnouncementService.java
git commit -m "feat(alexa): add announcement service as internal building block"
```

---

## Task 9: `AlexaScheduledAnnouncementService` (Fälligkeit, TDD)

Die Fälligkeitslogik (Uhrzeit-Fenster, Wochentag, enabled, kein Nachholen, kein Doppelfeuern) wird in eine reine Methode `isDue(announcement, now)` extrahiert und getestet.

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/AlexaScheduledAnnouncementService.java`
- Test: `backend/src/test/java/com/household/manager/service/AlexaScheduledAnnouncementServiceTest.java`

- [ ] **Step 1: Failing test schreiben**

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import com.household.manager.repository.AlexaScheduledAnnouncementRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AlexaScheduledAnnouncementServiceTest {

    private final AlexaScheduledAnnouncementService service = new AlexaScheduledAnnouncementService(
            mock(AlexaScheduledAnnouncementRepository.class),
            mock(AlexaAnnouncementService.class));

    private AlexaScheduledAnnouncement announcement(String weekdays, LocalTime time,
                                                    boolean enabled, LocalDateTime lastRun) {
        return AlexaScheduledAnnouncement.builder()
                .text("Test").timeOfDay(time).weekdays(weekdays)
                .mode(AlexaTtsMode.ANNOUNCE).enabled(enabled).lastRun(lastRun)
                .build();
    }

    // 2026-07-08 ist ein Mittwoch (WEDNESDAY)
    private static final LocalDateTime WED_08_00 = LocalDateTime.of(2026, 7, 8, 8, 0, 30);

    @Test
    void dueWhenWeekdayAndTimeMatchAndNotRunYet() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), true, null);
        assertThat(service.isDue(a, WED_08_00)).isTrue();
    }

    @Test
    void notDueOnWrongWeekday() {
        AlexaScheduledAnnouncement a = announcement("MONDAY", LocalTime.of(8, 0), true, null);
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void notDueWhenDisabled() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), false, null);
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void notDueOutsideTimeWindow() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 5), true, null);
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void notDueWhenAlreadyRunThisMinute() {
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), true,
                LocalDateTime.of(2026, 7, 8, 8, 0, 10));
        assertThat(service.isDue(a, WED_08_00)).isFalse();
    }

    @Test
    void dueAgainAfterMissedNextDayNotBackfilled() {
        // lastRun gestern -> heute im Fenster wieder faellig (kein Nachholen verpasster Slots,
        // aber der heutige Slot feuert normal)
        AlexaScheduledAnnouncement a = announcement("WEDNESDAY", LocalTime.of(8, 0), true,
                LocalDateTime.of(2026, 7, 1, 8, 0, 5));
        assertThat(service.isDue(a, WED_08_00)).isTrue();
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaScheduledAnnouncementServiceTest`
Expected: FAIL — Klasse/Methode fehlt.

- [ ] **Step 3: `AlexaScheduledAnnouncementService` implementieren**

Die Prüfung läuft minütlich; das Fälligkeitsfenster ist „gleiche Minute wie `timeOfDay`" und „nicht bereits in derselben Minute ausgeführt".

```java
package com.household.manager.service;

import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import com.household.manager.repository.AlexaScheduledAnnouncementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Verwaltung (CRUD) und minuetliche Ausloesung zeitgeplanter Ansagen. */
@Service
@Slf4j
public class AlexaScheduledAnnouncementService {

    private final AlexaScheduledAnnouncementRepository repository;
    private final AlexaAnnouncementService announcementService;

    public AlexaScheduledAnnouncementService(AlexaScheduledAnnouncementRepository repository,
                                             AlexaAnnouncementService announcementService) {
        this.repository = repository;
        this.announcementService = announcementService;
    }

    public List<AlexaScheduledAnnouncement> getAll() {
        return repository.findAll();
    }

    public AlexaScheduledAnnouncement create(AlexaScheduledAnnouncement announcement) {
        announcement.setId(null);
        return repository.save(announcement);
    }

    public AlexaScheduledAnnouncement update(Long id, AlexaScheduledAnnouncement update) {
        AlexaScheduledAnnouncement existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ansage nicht gefunden: " + id));
        existing.setText(update.getText());
        existing.setTimeOfDay(update.getTimeOfDay());
        existing.setWeekdays(update.getWeekdays());
        existing.setMode(update.getMode());
        existing.setEnabled(update.isEnabled());
        existing.setTargetSerialNumbers(update.getTargetSerialNumbers());
        return repository.save(existing);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * Reine Faelligkeitslogik: wird die Ansage zum Zeitpunkt {@code now} ausgeloest?
     * Faellig, wenn aktiviert, der Wochentag passt, die Minute mit timeOfDay uebereinstimmt
     * und sie nicht bereits in dieser Minute lief. Verpasste Slots werden nicht nachgeholt.
     */
    boolean isDue(AlexaScheduledAnnouncement a, LocalDateTime now) {
        if (!a.isEnabled()) {
            return false;
        }
        Set<DayOfWeek> days = parseWeekdays(a.getWeekdays());
        if (!days.contains(now.getDayOfWeek())) {
            return false;
        }
        if (now.getHour() != a.getTimeOfDay().getHour()
                || now.getMinute() != a.getTimeOfDay().getMinute()) {
            return false;
        }
        if (a.getLastRun() != null
                && a.getLastRun().getYear() == now.getYear()
                && a.getLastRun().getDayOfYear() == now.getDayOfYear()
                && a.getLastRun().getHour() == now.getHour()
                && a.getLastRun().getMinute() == now.getMinute()) {
            return false;
        }
        return true;
    }

    private Set<DayOfWeek> parseWeekdays(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> DayOfWeek.valueOf(s.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toSet());
    }

    /** Minuetliche Pruefung; feuert faellige Ansagen und protokolliert Fehler pro Ansage. */
    @Scheduled(fixedDelayString = "${alexa.scheduled.check-interval-ms:60000}")
    public void runDueAnnouncements() {
        LocalDateTime now = LocalDateTime.now();
        for (AlexaScheduledAnnouncement a : repository.findByEnabledTrue()) {
            if (!isDue(a, now)) {
                continue;
            }
            try {
                announcementService.announce(
                        a.getText(),
                        List.copyOf(a.getTargetSerialNumbers()),
                        a.getMode());
                a.setLastRun(now);
                a.setLastError(null);
            } catch (Exception ex) {
                a.setLastError(ex.getMessage());
                log.warn("Geplante Ansage {} fehlgeschlagen: {}", a.getId(), ex.getMessage());
            }
            repository.save(a);
        }
    }
}
```

- [ ] **Step 4: `@EnableScheduling` sicherstellen**

Run: `grep -rn "EnableScheduling" backend/src/main/java`
Expected: bereits vorhanden (Weather/Tasmota nutzen `@Scheduled`). Falls nicht gefunden, `@EnableScheduling` an `HouseholdManagerApplication` ergänzen.

- [ ] **Step 5: Test ausführen — muss bestehen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaScheduledAnnouncementServiceTest`
Expected: PASS (6 Tests grün).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/AlexaScheduledAnnouncementService.java backend/src/test/java/com/household/manager/service/AlexaScheduledAnnouncementServiceTest.java
git commit -m "feat(alexa): add scheduled announcement service with tested due logic"
```

---

## Task 10: DTOs & `AlexaController`

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/alexa/AlexaLoginRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/AlexaMfaRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/AlexaLoginResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/AlexaAuthStatusResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/AlexaDeviceResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/AnnounceRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/ScheduledAnnouncementRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/alexa/ScheduledAnnouncementResponse.java`
- Create: `backend/src/main/java/com/household/manager/controller/AlexaController.java`

- [ ] **Step 1: Request-/Response-DTOs anlegen (Records)**

```java
// AlexaLoginRequest.java
package com.household.manager.dto.alexa;

public record AlexaLoginRequest(String email, String password, String captcha) {}
```

```java
// AlexaMfaRequest.java
package com.household.manager.dto.alexa;

public record AlexaMfaRequest(String code) {}
```

```java
// AlexaLoginResponse.java
package com.household.manager.dto.alexa;

/** status ist einer von OK, MFA_REQUIRED, CAPTCHA_REQUIRED, FAILED. */
public record AlexaLoginResponse(String status, String captchaImageUrl, String message) {}
```

```java
// AlexaAuthStatusResponse.java
package com.household.manager.dto.alexa;

public record AlexaAuthStatusResponse(boolean loggedIn, String accountName, boolean reauthRequired) {}
```

```java
// AlexaDeviceResponse.java
package com.household.manager.dto.alexa;

public record AlexaDeviceResponse(String serialNumber, String name, String deviceType, boolean ttsCapable) {}
```

```java
// AnnounceRequest.java
package com.household.manager.dto.alexa;

import com.household.manager.alexa.AlexaTtsMode;
import java.util.List;

public record AnnounceRequest(String text, List<String> serialNumbers, AlexaTtsMode mode) {}
```

```java
// ScheduledAnnouncementRequest.java
package com.household.manager.dto.alexa;

import com.household.manager.alexa.AlexaTtsMode;
import java.time.LocalTime;
import java.util.List;

public record ScheduledAnnouncementRequest(
        String text,
        LocalTime timeOfDay,
        List<String> weekdays,
        List<String> serialNumbers,
        AlexaTtsMode mode,
        boolean enabled) {}
```

```java
// ScheduledAnnouncementResponse.java
package com.household.manager.dto.alexa;

import com.household.manager.alexa.AlexaTtsMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record ScheduledAnnouncementResponse(
        Long id,
        String text,
        LocalTime timeOfDay,
        List<String> weekdays,
        List<String> serialNumbers,
        AlexaTtsMode mode,
        boolean enabled,
        LocalDateTime lastRun,
        String lastError) {}
```

- [ ] **Step 2: `AlexaController` implementieren**

```java
package com.household.manager.controller;

import com.household.manager.alexa.AlexaAuthService;
import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.dto.alexa.*;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import com.household.manager.service.AlexaAnnouncementService;
import com.household.manager.service.AlexaDeviceService;
import com.household.manager.service.AlexaScheduledAnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;

/** REST-Endpunkte der Alexa-Integration. Basispfad ergibt sich aus dem Servlet-Context-Path. */
@RestController
@RequestMapping("/v1/alexa")
@RequiredArgsConstructor
public class AlexaController {

    private final AlexaAuthService authService;
    private final AlexaDeviceService deviceService;
    private final AlexaAnnouncementService announcementService;
    private final AlexaScheduledAnnouncementService scheduledService;

    // ---------- Auth ----------

    @PostMapping("/auth/login")
    public AlexaLoginResponse login(@RequestBody AlexaLoginRequest request) {
        AlexaAuthService.LoginStep step =
                authService.login(request.email(), request.password(), request.captcha());
        return new AlexaLoginResponse(step.getResult().name(), step.getCaptchaImageUrl(), step.getMessage());
    }

    @PostMapping("/auth/mfa")
    public AlexaLoginResponse mfa(@RequestBody AlexaMfaRequest request) {
        AlexaAuthService.LoginStep step = authService.submitMfa(request.code());
        return new AlexaLoginResponse(step.getResult().name(), step.getCaptchaImageUrl(), step.getMessage());
    }

    @GetMapping("/auth/status")
    public AlexaAuthStatusResponse status() {
        return new AlexaAuthStatusResponse(
                authService.isLoggedIn(), authService.getAccountName(), authService.isReauthRequired());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }

    // ---------- Devices ----------

    @GetMapping("/devices")
    public List<AlexaDeviceResponse> devices(@RequestParam(defaultValue = "false") boolean rescan) {
        List<AlexaDevice> devices = rescan ? deviceService.rescan() : deviceService.getDevices();
        return devices.stream()
                .map(d -> new AlexaDeviceResponse(
                        d.getSerialNumber(), d.getName(), d.getDeviceType(), d.isTtsCapable()))
                .toList();
    }

    // ---------- Announce ----------

    @PostMapping("/announce")
    public ResponseEntity<Void> announce(@RequestBody AnnounceRequest request) {
        AlexaTtsMode mode = request.mode() == null ? AlexaTtsMode.ANNOUNCE : request.mode();
        announcementService.announce(request.text(), request.serialNumbers(), mode);
        return ResponseEntity.noContent().build();
    }

    // ---------- Scheduled ----------

    @GetMapping("/scheduled-announcements")
    public List<ScheduledAnnouncementResponse> listScheduled() {
        return scheduledService.getAll().stream().map(this::toResponse).toList();
    }

    @PostMapping("/scheduled-announcements")
    public ScheduledAnnouncementResponse createScheduled(@RequestBody ScheduledAnnouncementRequest request) {
        return toResponse(scheduledService.create(toEntity(request)));
    }

    @PutMapping("/scheduled-announcements/{id}")
    public ScheduledAnnouncementResponse updateScheduled(@PathVariable Long id,
                                                         @RequestBody ScheduledAnnouncementRequest request) {
        return toResponse(scheduledService.update(id, toEntity(request)));
    }

    @DeleteMapping("/scheduled-announcements/{id}")
    public ResponseEntity<Void> deleteScheduled(@PathVariable Long id) {
        scheduledService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Mapping ----------

    private AlexaScheduledAnnouncement toEntity(ScheduledAnnouncementRequest r) {
        return AlexaScheduledAnnouncement.builder()
                .text(r.text())
                .timeOfDay(r.timeOfDay())
                .weekdays(String.join(",", r.weekdays()))
                .mode(r.mode() == null ? AlexaTtsMode.ANNOUNCE : r.mode())
                .enabled(r.enabled())
                .targetSerialNumbers(new HashSet<>(r.serialNumbers()))
                .build();
    }

    private ScheduledAnnouncementResponse toResponse(AlexaScheduledAnnouncement a) {
        List<String> weekdays = a.getWeekdays() == null || a.getWeekdays().isBlank()
                ? List.of()
                : List.of(a.getWeekdays().split(","));
        return new ScheduledAnnouncementResponse(
                a.getId(), a.getText(), a.getTimeOfDay(), weekdays,
                List.copyOf(a.getTargetSerialNumbers()), a.getMode(), a.isEnabled(),
                a.getLastRun(), a.getLastError());
    }
}
```

- [ ] **Step 3: Fehler-Mapping prüfen**

Run: `grep -rln "AlexaException\|@ControllerAdvice\|@ExceptionHandler" backend/src/main/java/com/household/manager/exception`
- Falls ein globaler `@ControllerAdvice` existiert, ergänze dort einen Handler für `AlexaException` → HTTP 502/400 mit `message`. Andernfalls fängt Springs Default-Handling die RuntimeException als 500 — für V1 akzeptabel; die Fehlermeldung landet im Response-Body.

- [ ] **Step 4: Kompilieren**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Context-Path bestätigen (API-Präfix)**

Run: `grep -n "context-path\|server.servlet" backend/src/main/resources/application.properties`
- Notiere das Präfix (z. B. `/api`). Die Frontend-`baseUrl` in Task 12 muss dazu passen (`/api/v1/alexa`). Falls kein Context-Path gesetzt ist, prüfe wie andere Services das `/api`-Präfix erreichen (Proxy/Controller-Mapping) und richte die Frontend-URL entsprechend aus.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/alexa backend/src/main/java/com/household/manager/controller/AlexaController.java
git commit -m "feat(alexa): add REST controller and DTOs for auth, devices, announce and scheduling"
```

---

## Task 11: Frontend — Model, Service & Tests

**Files:**
- Create: `frontend/src/app/models/alexa.model.ts`
- Create: `frontend/src/app/services/alexa.service.ts`
- Create: `frontend/src/app/services/alexa.service.spec.ts`

- [ ] **Step 1: Model anlegen**

```typescript
// frontend/src/app/models/alexa.model.ts

export type AlexaTtsMode = 'SPEAK' | 'ANNOUNCE';
export type AlexaLoginStatus = 'OK' | 'MFA_REQUIRED' | 'CAPTCHA_REQUIRED' | 'FAILED';

export interface AlexaLoginResponse {
  status: AlexaLoginStatus;
  captchaImageUrl?: string;
  message?: string;
}

export interface AlexaAuthStatus {
  loggedIn: boolean;
  accountName?: string;
  reauthRequired: boolean;
}

export interface AlexaDevice {
  serialNumber: string;
  name: string;
  deviceType?: string;
  ttsCapable: boolean;
}

export interface ScheduledAnnouncement {
  id?: number;
  text: string;
  timeOfDay: string;          // "HH:mm"
  weekdays: string[];         // z. B. ["MONDAY","TUESDAY"]
  serialNumbers: string[];
  mode: AlexaTtsMode;
  enabled: boolean;
  lastRun?: string;
  lastError?: string;
}
```

- [ ] **Step 2: Failing spec schreiben**

```typescript
// frontend/src/app/services/alexa.service.spec.ts
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AlexaService } from './alexa.service';

describe('AlexaService', () => {
  let service: AlexaService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AlexaService]
    });
    service = TestBed.inject(AlexaService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('sendet Durchsage per POST an /announce', () => {
    service.announce({ text: 'Hallo', serialNumbers: ['DSN1'], mode: 'ANNOUNCE' })
      .subscribe();
    const req = httpMock.expectOne('/api/v1/alexa/announce');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.text).toBe('Hallo');
    req.flush(null);
  });

  it('laedt Geraete mit rescan-Flag', () => {
    service.getDevices(true).subscribe();
    const req = httpMock.expectOne('/api/v1/alexa/devices?rescan=true');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('fragt Login-Status ab', () => {
    service.getAuthStatus().subscribe();
    const req = httpMock.expectOne('/api/v1/alexa/auth/status');
    expect(req.request.method).toBe('GET');
    req.flush({ loggedIn: false, reauthRequired: false });
  });
});
```

> **Hinweis:** Die URL `/api/v1/alexa/...` setzt voraus, dass der Backend-Context-Path `/api` ist (Task 10, Step 5). Passe `baseUrl` im Service und die erwarteten URLs hier gemeinsam an, falls das Präfix abweicht.

- [ ] **Step 3: Spec ausführen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --include='**/alexa.service.spec.ts'`
Expected: FAIL — `AlexaService` existiert noch nicht.

- [ ] **Step 4: Service implementieren**

```typescript
// frontend/src/app/services/alexa.service.ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AlexaAuthStatus, AlexaDevice, AlexaLoginResponse,
  AlexaTtsMode, ScheduledAnnouncement
} from '../models/alexa.model';

/** Service fuer die Alexa-TTS-Integration. */
@Injectable({ providedIn: 'root' })
export class AlexaService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/alexa';

  getAuthStatus(): Observable<AlexaAuthStatus> {
    return this.http.get<AlexaAuthStatus>(`${this.baseUrl}/auth/status`).pipe(catchError(this.handleError));
  }

  login(email: string, password: string, captcha?: string): Observable<AlexaLoginResponse> {
    return this.http.post<AlexaLoginResponse>(`${this.baseUrl}/auth/login`, { email, password, captcha })
      .pipe(catchError(this.handleError));
  }

  submitMfa(code: string): Observable<AlexaLoginResponse> {
    return this.http.post<AlexaLoginResponse>(`${this.baseUrl}/auth/mfa`, { code })
      .pipe(catchError(this.handleError));
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/logout`, {}).pipe(catchError(this.handleError));
  }

  getDevices(rescan = false): Observable<AlexaDevice[]> {
    return this.http.get<AlexaDevice[]>(`${this.baseUrl}/devices?rescan=${rescan}`)
      .pipe(catchError(this.handleError));
  }

  announce(payload: { text: string; serialNumbers: string[]; mode: AlexaTtsMode }): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/announce`, payload).pipe(catchError(this.handleError));
  }

  getScheduled(): Observable<ScheduledAnnouncement[]> {
    return this.http.get<ScheduledAnnouncement[]>(`${this.baseUrl}/scheduled-announcements`)
      .pipe(catchError(this.handleError));
  }

  createScheduled(a: ScheduledAnnouncement): Observable<ScheduledAnnouncement> {
    return this.http.post<ScheduledAnnouncement>(`${this.baseUrl}/scheduled-announcements`, a)
      .pipe(catchError(this.handleError));
  }

  updateScheduled(id: number, a: ScheduledAnnouncement): Observable<ScheduledAnnouncement> {
    return this.http.put<ScheduledAnnouncement>(`${this.baseUrl}/scheduled-announcements/${id}`, a)
      .pipe(catchError(this.handleError));
  }

  deleteScheduled(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/scheduled-announcements/${id}`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Alexa-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Alexa-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 5: Spec ausführen — muss bestehen**

Run: `cd frontend && npm test -- --watch=false --include='**/alexa.service.spec.ts'`
Expected: PASS (3 Specs grün).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/alexa.model.ts frontend/src/app/services/alexa.service.ts frontend/src/app/services/alexa.service.spec.ts
git commit -m "feat(alexa): add frontend model and service with tests"
```

---

## Task 12: Frontend — Seite „Ansagen", Route & Navigation

**Files:**
- Create: `frontend/src/app/pages/announcements/announcements.component.ts`
- Create: `frontend/src/app/pages/announcements/announcements.component.html`
- Create: `frontend/src/app/pages/announcements/announcements.component.scss`
- Create: `frontend/src/app/pages/announcements/announcements.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`

- [ ] **Step 1: Component-TS anlegen**

```typescript
// frontend/src/app/pages/announcements/announcements.component.ts
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlexaService } from '../../services/alexa.service';
import {
  AlexaAuthStatus, AlexaDevice, AlexaLoginStatus,
  AlexaTtsMode, ScheduledAnnouncement
} from '../../models/alexa.model';

/** Seite fuer Alexa-Durchsagen: Konto, manuelle Durchsage und geplante Ansagen. */
@Component({
  selector: 'app-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './announcements.component.html',
  styleUrl: './announcements.component.scss'
})
export class AnnouncementsComponent implements OnInit {
  private readonly alexa = inject(AlexaService);

  readonly weekdayOptions = [
    { key: 'MONDAY', label: 'Mo' }, { key: 'TUESDAY', label: 'Di' },
    { key: 'WEDNESDAY', label: 'Mi' }, { key: 'THURSDAY', label: 'Do' },
    { key: 'FRIDAY', label: 'Fr' }, { key: 'SATURDAY', label: 'Sa' },
    { key: 'SUNDAY', label: 'So' }
  ];

  readonly authStatus = signal<AlexaAuthStatus | null>(null);
  readonly devices = signal<AlexaDevice[]>([]);
  readonly scheduled = signal<ScheduledAnnouncement[]>([]);
  readonly loginStage = signal<AlexaLoginStatus | 'NONE'>('NONE');
  readonly captchaUrl = signal<string | undefined>(undefined);
  readonly message = signal<string>('');

  // Login-Formular
  email = '';
  password = '';
  captcha = '';
  mfaCode = '';

  // Durchsage-Formular
  announceText = '';
  announceMode: AlexaTtsMode = 'ANNOUNCE';
  selectedSerials: Record<string, boolean> = {};

  // Neue geplante Ansage
  newSchedule: ScheduledAnnouncement = {
    text: '', timeOfDay: '08:00', weekdays: [], serialNumbers: [], mode: 'ANNOUNCE', enabled: true
  };

  ngOnInit(): void {
    this.refreshStatus();
  }

  refreshStatus(): void {
    this.alexa.getAuthStatus().subscribe({
      next: s => {
        this.authStatus.set(s);
        if (s.loggedIn) {
          this.loadDevices(false);
          this.loadScheduled();
        }
      },
      error: e => this.message.set(e.message)
    });
  }

  login(): void {
    this.alexa.login(this.email, this.password, this.captcha || undefined).subscribe({
      next: r => this.handleLoginResponse(r),
      error: e => this.message.set(e.message)
    });
  }

  submitMfa(): void {
    this.alexa.submitMfa(this.mfaCode).subscribe({
      next: r => this.handleLoginResponse(r),
      error: e => this.message.set(e.message)
    });
  }

  private handleLoginResponse(r: { status: AlexaLoginStatus; captchaImageUrl?: string; message?: string }): void {
    this.loginStage.set(r.status);
    this.captchaUrl.set(r.captchaImageUrl);
    this.message.set(r.message ?? '');
    if (r.status === 'OK') {
      this.email = this.password = this.captcha = this.mfaCode = '';
      this.loginStage.set('NONE');
      this.refreshStatus();
    }
  }

  logout(): void {
    this.alexa.logout().subscribe({ next: () => this.refreshStatus() });
  }

  loadDevices(rescan: boolean): void {
    this.alexa.getDevices(rescan).subscribe({
      next: d => this.devices.set(d),
      error: e => this.message.set(e.message)
    });
  }

  loadScheduled(): void {
    this.alexa.getScheduled().subscribe({
      next: s => this.scheduled.set(s),
      error: e => this.message.set(e.message)
    });
  }

  private selectedSerialNumbers(): string[] {
    return Object.keys(this.selectedSerials).filter(k => this.selectedSerials[k]);
  }

  sendAnnouncement(): void {
    const serials = this.selectedSerialNumbers();
    if (!this.announceText.trim() || serials.length === 0) {
      this.message.set('Bitte Text eingeben und mindestens ein Geraet waehlen.');
      return;
    }
    this.alexa.announce({ text: this.announceText, serialNumbers: serials, mode: this.announceMode })
      .subscribe({
        next: () => this.message.set('Durchsage gesendet.'),
        error: e => this.message.set(e.message)
      });
  }

  toggleNewScheduleWeekday(key: string): void {
    const days = this.newSchedule.weekdays;
    this.newSchedule.weekdays = days.includes(key) ? days.filter(d => d !== key) : [...days, key];
  }

  createSchedule(): void {
    this.newSchedule.serialNumbers = this.selectedSerialNumbers();
    if (!this.newSchedule.text.trim() || this.newSchedule.serialNumbers.length === 0
        || this.newSchedule.weekdays.length === 0) {
      this.message.set('Bitte Text, Wochentage und Geraete fuer die geplante Ansage waehlen.');
      return;
    }
    this.alexa.createScheduled(this.newSchedule).subscribe({
      next: () => {
        this.loadScheduled();
        this.newSchedule = { text: '', timeOfDay: '08:00', weekdays: [], serialNumbers: [], mode: 'ANNOUNCE', enabled: true };
      },
      error: e => this.message.set(e.message)
    });
  }

  toggleScheduleEnabled(a: ScheduledAnnouncement): void {
    this.alexa.updateScheduled(a.id!, { ...a, enabled: !a.enabled })
      .subscribe({ next: () => this.loadScheduled() });
  }

  deleteSchedule(a: ScheduledAnnouncement): void {
    this.alexa.deleteScheduled(a.id!).subscribe({ next: () => this.loadScheduled() });
  }
}
```

- [ ] **Step 2: Template anlegen**

```html
<!-- frontend/src/app/pages/announcements/announcements.component.html -->
<div class="announcements">
  <h1>Ansagen</h1>

  <p class="announcements__message" *ngIf="message()">{{ message() }}</p>

  <!-- Konto -->
  <section class="card">
    <h2>Amazon-Konto</h2>

    <ng-container *ngIf="authStatus() as status">
      <div *ngIf="status.loggedIn; else loginForm">
        <p>Angemeldet als <strong>{{ status.accountName || 'Amazon-Konto' }}</strong>.</p>
        <p class="announcements__warn" *ngIf="status.reauthRequired">
          Sitzung abgelaufen — bitte neu anmelden.
        </p>
        <button type="button" (click)="logout()">Abmelden</button>
      </div>
    </ng-container>

    <ng-template #loginForm>
      <ng-container *ngIf="loginStage() !== 'MFA_REQUIRED'">
        <label>E-Mail <input type="email" [(ngModel)]="email" name="email"></label>
        <label>Passwort <input type="password" [(ngModel)]="password" name="password"></label>
        <div *ngIf="loginStage() === 'CAPTCHA_REQUIRED'">
          <img [src]="captchaUrl()" alt="Captcha">
          <label>Captcha <input type="text" [(ngModel)]="captcha" name="captcha"></label>
        </div>
        <button type="button" (click)="login()">Anmelden</button>
      </ng-container>

      <ng-container *ngIf="loginStage() === 'MFA_REQUIRED'">
        <label>Bestaetigungscode <input type="text" [(ngModel)]="mfaCode" name="mfaCode"></label>
        <button type="button" (click)="submitMfa()">Code bestaetigen</button>
      </ng-container>
    </ng-template>
  </section>

  <!-- Durchsage -->
  <section class="card" *ngIf="authStatus()?.loggedIn">
    <h2>Durchsage</h2>
    <button type="button" (click)="loadDevices(true)">Geraete neu suchen</button>

    <div class="devices">
      <label *ngFor="let d of devices()" [class.devices__disabled]="!d.ttsCapable">
        <input type="checkbox" [(ngModel)]="selectedSerials[d.serialNumber]"
               [name]="'dev-' + d.serialNumber" [disabled]="!d.ttsCapable">
        {{ d.name }}
      </label>
    </div>

    <label>Text <textarea [(ngModel)]="announceText" name="announceText"></textarea></label>

    <label>Modus
      <select [(ngModel)]="announceMode" name="announceMode">
        <option value="ANNOUNCE">Durchsage (mit Ton)</option>
        <option value="SPEAK">Sprechen (ohne Ton)</option>
      </select>
    </label>

    <button type="button" (click)="sendAnnouncement()">Senden</button>
  </section>

  <!-- Geplante Ansagen -->
  <section class="card" *ngIf="authStatus()?.loggedIn">
    <h2>Geplante Ansagen</h2>

    <ul class="schedule-list">
      <li *ngFor="let a of scheduled()">
        <span>{{ a.timeOfDay }} — {{ a.text }} ({{ a.weekdays.join(', ') }})</span>
        <span class="schedule-list__error" *ngIf="a.lastError">Fehler: {{ a.lastError }}</span>
        <button type="button" (click)="toggleScheduleEnabled(a)">
          {{ a.enabled ? 'Deaktivieren' : 'Aktivieren' }}
        </button>
        <button type="button" (click)="deleteSchedule(a)">Loeschen</button>
      </li>
    </ul>

    <h3>Neue geplante Ansage</h3>
    <label>Text <input type="text" [(ngModel)]="newSchedule.text" name="schedText"></label>
    <label>Uhrzeit <input type="time" [(ngModel)]="newSchedule.timeOfDay" name="schedTime"></label>
    <div class="weekdays">
      <button type="button" *ngFor="let w of weekdayOptions"
              [class.weekdays__active]="newSchedule.weekdays.includes(w.key)"
              (click)="toggleNewScheduleWeekday(w.key)">{{ w.label }}</button>
    </div>
    <p class="hint">Zielgeraete werden aus der Durchsage-Auswahl oben uebernommen.</p>
    <button type="button" (click)="createSchedule()">Anlegen</button>
  </section>
</div>
```

- [ ] **Step 3: SCSS anlegen**

```scss
// frontend/src/app/pages/announcements/announcements.component.scss
.announcements {
  max-width: 800px;
  margin: 0 auto;
  padding: 1rem;

  &__message { padding: .5rem; background: #eef; border-radius: 4px; }
  &__warn { color: #a60; }
}

.card {
  margin-bottom: 1.5rem;
  padding: 1rem;
  border: 1px solid #ddd;
  border-radius: 8px;

  label { display: block; margin: .5rem 0; }
  textarea { width: 100%; min-height: 3rem; }
}

.devices {
  display: flex;
  flex-wrap: wrap;
  gap: .5rem;

  &__disabled { opacity: .5; }
}

.weekdays {
  display: flex;
  gap: .25rem;

  button { padding: .25rem .5rem; }
  &__active { background: #46c; color: #fff; }
}

.schedule-list {
  list-style: none;
  padding: 0;

  li { display: flex; gap: .5rem; align-items: center; padding: .25rem 0; }
  &__error { color: #c00; }
}

.hint { font-size: .85rem; color: #666; }
```

- [ ] **Step 4: Smoke-Spec anlegen**

```typescript
// frontend/src/app/pages/announcements/announcements.component.spec.ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { AnnouncementsComponent } from './announcements.component';

describe('AnnouncementsComponent', () => {
  let fixture: ComponentFixture<AnnouncementsComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnnouncementsComponent, HttpClientTestingModule]
    }).compileComponents();
    fixture = TestBed.createComponent(AnnouncementsComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  it('laedt beim Init den Auth-Status', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne('/api/v1/alexa/auth/status');
    req.flush({ loggedIn: false, reauthRequired: false });
    expect(fixture.componentInstance.authStatus()?.loggedIn).toBeFalse();
    httpMock.verify();
  });
});
```

- [ ] **Step 5: Route ergänzen**

In `frontend/src/app/app.routes.ts` einen Eintrag hinzufügen (nach `devices`, vor `admin`):

```typescript
  {
    path: 'announcements',
    loadComponent: () => import('./pages/announcements/announcements.component').then(m => m.AnnouncementsComponent),
    title: 'Ansagen - Household Manager'
  },
```

- [ ] **Step 6: Navigation ergänzen**

In `frontend/src/app/components/header/header.component.ts` im `navLinks`-Array nach `{ path: '/devices', label: 'Geraete' }` einfügen:

```typescript
    { path: '/announcements', label: 'Ansagen' },
```

- [ ] **Step 7: Frontend-Tests & Build**

Run: `cd frontend && npm test -- --watch=false --include='**/announcements.component.spec.ts'`
Expected: PASS.

Run: `cd frontend && npx ng build --configuration development`
Expected: Build erfolgreich (keine Template-/Typfehler).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/pages/announcements frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(alexa): add announcements page with route and nav entry"
```

---

## Task 13: Amazon-Netzwerk-Flow fertigstellen & manuelle Verifikation

Dieser Task schließt die in Task 5 markierten Amazon-Schritte ab und verifiziert das Ganze end-to-end. Er lässt sich nicht per Unit-Test abschließen (echtes Amazon-Konto nötig).

**Files:**
- Modify: `backend/src/main/java/com/household/manager/alexa/AlexaAuthService.java`

- [ ] **Step 1: `alexa-cookie`-Flow portieren**

Ersetze die drei `UnsupportedOperationException`-Stellen in `AlexaAuthService` (`login`, `submitMfa`/`completeRegistration`, `buildSessionFromRefreshToken`) durch die konkreten HTTP-Aufrufe gemäß der Endpunkt-Referenz in Task 5. Orientierung: `Apollon77/alexa-cookie` (`alexa-cookie.js`), Schritte 1–8 und der Token-Refresh-Flow.

Prüfe nach dem Portieren: **keine** `UnsupportedOperationException` mehr im Code:

Run: `grep -rn "UnsupportedOperationException" backend/src/main/java/com/household/manager/alexa`
Expected: keine Treffer.

- [ ] **Step 2: Voller Backend-Testlauf**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn -q test -Dtest=AlexaSequenceBuilderTest,AlexaDeviceServiceTest,AlexaScheduledAnnouncementServiceTest`
Expected: alle Alexa-Tests grün. (Ein voller `mvn test` schlägt bei lokalen DB-Tests by design fehl — das ist bekannt und kein Fehler dieses Features.)

- [ ] **Step 3: App starten & Migration prüfen**

Run: `cd backend && JAVA_HOME="C:\Program Files\Java\jdk-21.0.10" mvn spring-boot:run`
Erwartet in den Logs: Liquibase legt `alexa_account`, `alexa_device`, `alexa_scheduled_announcement`, `alexa_scheduled_announcement_device` an; Anwendung startet ohne Fehler. (Setzt eine erreichbare lokale DB voraus.)

- [ ] **Step 4: End-to-End im Browser**

Mit laufendem Backend und `npm start` im Frontend:
1. Seite „Ansagen" öffnen → Login-Status „nicht angemeldet".
2. Amazon-Login (E-Mail/Passwort, dann MFA-Code) → Status „angemeldet als …".
3. „Geraete neu suchen" → Echos erscheinen.
4. Ein Echo wählen, Text eingeben, „Senden" → Ansage ist hörbar auf dem Gerät.
5. Modus „Sprechen" testen → ohne Signalton.
6. Geplante Ansage auf die aktuelle Uhrzeit + 1 Minute anlegen → feuert automatisch; `lastRun` wird gesetzt.

- [ ] **Step 5: Verifikations-Skill (falls vorhanden)**

Nutze die `verify`-Skill, um den End-to-End-Flow zu bestätigen, bevor du das Feature als fertig meldest. Dokumentiere beobachtetes Verhalten (kein bloßes „Tests grün").

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/alexa/AlexaAuthService.java
git commit -m "feat(alexa): complete amazon login and refresh network flow"
```

---

## Task 14: Dokumentation & Abschluss

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Smart Device Integrations")

- [ ] **Step 1: CLAUDE.md ergänzen**

Unter „Smart Device Integrations" einen Abschnitt hinzufügen:

```markdown
### Amazon Alexa (Text-to-Speech)
- Inoffizielle Anbindung an alexa.amazon.<domain> (wie alexa-remote-control / alexa_media_player)
- Login als App-Flow (E-Mail/Passwort + MFA); gespeichert wird nur das Refresh-Token
- Manuelle Durchsagen, geplante Ansagen und interner AnnouncementService als Baustein
- TTS via /api/behaviors/preview: Alexa.Speak (ein Geraet) bzw. AlexaAnnouncement (mehrere)
- Implementierung in backend/src/main/java/com/household/manager/alexa/
```

- [ ] **Step 2: Memory aktualisieren**

Lege eine Projekt-Memory an (`alexa-tts-integration.md`) mit dem Kernwissen: inoffizielle API, nur Refresh-Token persistiert, Payload-Formen Speak/Announce, Geräteidentität über serialNumber, Amazon kann den Flow jederzeit brechen. Ergänze die Zeile im `MEMORY.md`-Index. Verlinke `[[tapo-local-control-only]]` und `[[kasa-device-identity]]`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(alexa): document Alexa TTS integration in CLAUDE.md"
```

- [ ] **Step 4: Abschluss**

Nutze die `finishing-a-development-branch`-Skill, um über Merge/PR/Cleanup zu entscheiden.

---

## Self-Review (durchgeführt)

**Spec-Abdeckung:**
- Manuelle Durchsagen → Task 8/10/12 ✓
- Geplante Ansagen → Task 9/10/12 ✓
- Interner Baustein (`AlexaAnnouncementService`) → Task 8 ✓
- Login-Flow in der App (E-Mail/Passwort/MFA/Captcha) → Task 5/10/12/13 ✓
- Nur Refresh-Token gespeichert → Task 2 (`AlexaAccount`), Task 5 ✓
- Datenmodell (`alexa_account`, `alexa_device` mit serialNumber, scheduled + Join-Tabelle) → Task 2/3 ✓
- REST-API (alle Endpunkte aus Spec) → Task 10 ✓
- Frontend-Seite „Ansagen" (Konto-/Durchsage-/Planungs-Karte) → Task 12 ✓
- Fehlerbehandlung (graceful, reauthRequired, last_error, kein Nachholen) → Task 5/9/11 ✓
- Tests (Auth-Zustände, Payloads, Rescan-Persistenz, Scheduler-Fälligkeit; Frontend-Service/Form) → Task 4/7/9/11 ✓
- Isolation des Amazon-Codes → Task 5/6 ✓

**Platzhalter:** Die einzigen bewusst offenen Stellen sind die Amazon-Netzwerkschritte in Task 5, klar als „aus alexa-cookie portieren" markiert und in Task 13 verbindlich geschlossen (mit `grep`-Gate gegen zurückbleibende `UnsupportedOperationException`). Das ist eine externe, nicht CI-testbare Grenze — analog zu `TapoCloudService`.

**Typkonsistenz:** `AlexaRemoteDevice` (Record) durchgängig; `AlexaTtsMode` in Entity/DTO/Service/Frontend identisch; `getValidSession()`/`AlexaSession`-Getter (`getCookie`/`getCsrf`/`getCustomerId`) konsistent zwischen `AlexaAuthService`, `AlexaApiClient`, `AlexaAnnouncementService`; `isDue(a, now)` einheitlich in Test und Implementierung.
