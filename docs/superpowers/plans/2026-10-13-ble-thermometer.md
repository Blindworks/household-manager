# Bluetooth-Thermometer (ThermoBeacon) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zwei SensorBlue/ThermoBeacon-Bluetooth-Thermometer werden über die Android-Kiosk-App empfangen, im Backend als Entitäten und Historie geführt, auf einer Admin-Seite benannt und freigeschaltet und erscheinen in der Temperaturansicht.

**Architecture:** Die Kiosk-App scannt passiv BLE-Advertisements (Systemfilter auf Service-UUID `fff0`), parst das ThermoBeacon-Format und schickt einmal pro Minute eine Sammelmeldung per Service-Token an `POST /v1/ble-sensors/readings`. Das Backend-Modul `blesensor/` registriert Sensoren implizit je MAC (inaktiv), schreibt für **aktive** Sensoren Historie (`ble_sensor_reading`) und Entitäten (`sensor.ble_<mac12>_temperature|_humidity`), markiert stille Sensoren nach 10 min `unavailable` und bietet eine ADMIN-API für Übernahme, Umbenennen und Löschen. `TemperatureSeriesService` bekommt die vierte Quelle `ble`.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / MariaDB (Backend); Angular 19 standalone + Karma (Frontend); Kotlin + Android BLE API (`BluetoothLeScanner`), OkHttp (Tablet-App).

**Spec:** `docs/superpowers/specs/2026-10-13-ble-thermometer-design.md`

## Global Constraints

- Backend-Build nur mit JDK 21: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn` (aus `backend/`). `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` sind lokal rot (keine DB) — Baseline, keine Regression.
- Frontend-Tests: `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`; Baseline 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent`).
- Tablet-App: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'`, dann `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` aus `tablet-app/` (PowerShell); `local.properties` mit `sdk.dir=C\:\\Users\\bened\\AppData\\Local\\Android\\Sdk` muss existieren.
- Alle JPA-Repositories liegen in `com.household.manager.repository` (JpaConfig schränkt das Scanning ein), JPA-Entities in `com.household.manager.model.entity`.
- Jedes DDL in einem eigenen Liquibase-Changeset (MariaDB-DDL-Autocommit); `author="claude"`, Schema-Version 4.20 wie die Nachbarn.
- Keine Fremdschlüssel auf `entity_states`. **Kein `deviceClass`** auf den neuen Entitäten.
- Entity-Ids ausschließlich über `EntityIds.build`, einzige Definition in `BleSensorEntityMapper.entityId`.
- Fehler aus dem Modul sind 400/404/409/500 — **nie 401**.
- MAC-Normalform: kleingeschrieben mit Doppelpunkten (`aa:bb:cc:dd:ee:ff`); Entity-Ref: ohne Doppelpunkte (`aabbccddeeff`).
- Commit-Messages ohne Umlaute (Projektkonvention: `ue/ae/oe`), Format `type(scope): Beschreibung`, letzte Zeile `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Commit per `git commit -F <datei>` (PowerShell zerlegt Anführungszeichen in Here-Strings).

## Review Focus

1. **Tablet meldet eine MAC in Großbuchstaben oder ohne führende Nullen-Form (`AA:BB:…`)** — muss auf dieselbe `ble_sensor`-Zeile treffen wie die kleingeschriebene Form; sonst entsteht ein Doppelgänger ohne Namen. Test in Task 4.
2. **Ein Sensor wird deaktiviert und schickt weiter** — es darf kein Historienpunkt und kein Entity-Update entstehen, und die Entität muss genau einmal `unavailable` geworden sein, nicht bei jeder Meldung erneut. Tests in Task 4 und Task 6.
3. **Watchdog bei schon `unavailable` Entität** — darf nicht minütlich erneut melden (Flow-Engine, DB-Schreibrauschen). Test in Task 5.
4. **`ageSeconds` größer als das Alter der Verbindung oder negativ** — negatives Alter darf keine `measured_at` in der Zukunft erzeugen; wird auf 0 geklemmt. Test in Task 4.
5. **Umbenennen auf leeren Namen bei aktivem Sensor** — muss 400 liefern, sonst steht in Diagramm und Flows ein Sensor ohne Namen. Test in Task 6.

---

## Dateistruktur

**Backend (neu):**
- `backend/src/main/resources/db/changelog/changes/20261013-0066-create-ble-sensor.xml`
- `backend/src/main/resources/db/changelog/changes/20261013-0067-create-ble-sensor-reading.xml`
- `backend/src/main/resources/db/changelog/changes/20261013-0068-create-ble-sensor-reading-index.xml`
- `backend/src/main/java/com/household/manager/model/entity/BleSensor.java`
- `backend/src/main/java/com/household/manager/model/entity/BleSensorReading.java`
- `backend/src/main/java/com/household/manager/repository/BleSensorRepository.java`
- `backend/src/main/java/com/household/manager/repository/BleSensorReadingRepository.java`
- `backend/src/main/java/com/household/manager/blesensor/BleBatteryEstimator.java` — Spannung → Prozent
- `backend/src/main/java/com/household/manager/blesensor/BleMacs.java` — MAC-Normalisierung/Validierung
- `backend/src/main/java/com/household/manager/blesensor/BleSensorEntityMapper.java` — Entity-Ids und Updates
- `backend/src/main/java/com/household/manager/blesensor/BleSensorReadingRecorder.java` — Dedupe-Regel der Historie
- `backend/src/main/java/com/household/manager/blesensor/BleSensorIngestService.java` — Verarbeitung der Sammelmeldung
- `backend/src/main/java/com/household/manager/blesensor/BleSensorAvailabilityWatchdog.java` — 10-min-Stille
- `backend/src/main/java/com/household/manager/blesensor/BleSensorAdminService.java` — Liste/Übernahme/Löschen
- `backend/src/main/java/com/household/manager/blesensor/BleSensorDtos.java` — alle Request/Response-Records
- `backend/src/main/java/com/household/manager/blesensor/BleSensorController.java`
- Tests spiegelnd unter `backend/src/test/java/com/household/manager/blesensor/`

**Backend (geändert):** `EntitySource.java` (+`BLE`), `SecurityConfig.java`, `SecurityRulesTest.java`, `TemperatureSeriesService.java` (+Quelle), `TemperatureSeriesServiceTest.java`, `db.changelog-master.xml`, `application.properties`.

**Frontend (neu):** `models/ble-sensor.model.ts`, `services/ble-sensor.service.ts`, `pages/admin-ble-sensors/*` (ts/html/scss/spec).
**Frontend (geändert):** `models/temperature.model.ts` (+`'BLE'`), `app.routes.ts`, `components/header/header.component.ts`, `styles.scss` (`$admin-scopes`).

**Tablet-App (neu):** `ble/ThermoBeaconReading.kt`, `ble/ThermoBeaconParser.kt`, `ble/ThermoBeaconScanner.kt`, `ble/SensorReporter.kt`, Test `ble/ThermoBeaconParserTest.kt`, `ble/SensorReporterTest.kt`.
**Tablet-App (geändert):** `AndroidManifest.xml`, `AppSettings.kt`, `SettingsActivity.kt`, `res/layout/activity_settings.xml`, `KioskActivity.kt`, `README.md`.

---

### Task 1: Schema, Entities, Repositories, EntitySource

**Files:**
- Create: die drei Changesets 0066–0068, `BleSensor.java`, `BleSensorReading.java`, `BleSensorRepository.java`, `BleSensorReadingRepository.java`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`, `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`

**Interfaces:**
- Produces: `BleSensor` (Felder `id, mac, name, active, tabletId, lastSeenAt, lastTemperature, lastHumidity, lastBatteryMv, lastRssi, createdAt`), `BleSensorReading` (`id, sensorId, measuredAt, temperature, humidity, batteryMv`), `BleSensorRepository.findByMac(String)`, `findByActiveTrue()`, `findAllByOrderByActiveAscNameAscMacAsc()`; `BleSensorReadingRepository.findTopBySensorIdOrderByMeasuredAtDesc(Long)`, `findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(Long, LocalDateTime, LocalDateTime)`; `EntitySource.BLE`.

- [ ] **Step 1: Changesets anlegen**

`20261013-0066-create-ble-sensor.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20261013-0066-create-ble-sensor" author="claude">
        <comment>Bluetooth-Thermometer (ThermoBeacon), je gesehener MAC eine Zeile; aktiv erst nach Uebernahme.</comment>
        <createTable tableName="ble_sensor">
            <column name="id" type="BIGINT" autoIncrement="true"><constraints primaryKey="true" nullable="false"/></column>
            <column name="mac" type="VARCHAR(17)"><constraints nullable="false" unique="true" uniqueConstraintName="uk_ble_sensor_mac"/></column>
            <column name="name" type="VARCHAR(100)"/>
            <column name="active" type="BOOLEAN" defaultValueBoolean="false"><constraints nullable="false"/></column>
            <column name="tablet_id" type="VARCHAR(100)"/>
            <column name="last_seen_at" type="DATETIME"/>
            <column name="last_temperature" type="DECIMAL(5,2)"/>
            <column name="last_humidity" type="DECIMAL(5,2)"/>
            <column name="last_battery_mv" type="INT"/>
            <column name="last_rssi" type="INT"/>
            <column name="created_at" type="DATETIME"><constraints nullable="false"/></column>
        </createTable>
        <rollback><dropTable tableName="ble_sensor"/></rollback>
    </changeSet>

</databaseChangeLog>
```

`20261013-0067-create-ble-sensor-reading.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20261013-0067-create-ble-sensor-reading" author="claude">
        <comment>Historie der Bluetooth-Thermometer, hoechstens ein Punkt pro Minute und Sensor.</comment>
        <createTable tableName="ble_sensor_reading">
            <column name="id" type="BIGINT" autoIncrement="true"><constraints primaryKey="true" nullable="false"/></column>
            <column name="sensor_id" type="BIGINT"><constraints nullable="false"/></column>
            <column name="measured_at" type="DATETIME"><constraints nullable="false"/></column>
            <column name="temperature" type="DECIMAL(5,2)"/>
            <column name="humidity" type="DECIMAL(5,2)"/>
            <column name="battery_mv" type="INT"/>
        </createTable>
        <addForeignKeyConstraint baseTableName="ble_sensor_reading" baseColumnNames="sensor_id"
                                 referencedTableName="ble_sensor" referencedColumnNames="id"
                                 constraintName="fk_ble_sensor_reading_sensor" onDelete="CASCADE"/>
        <rollback><dropTable tableName="ble_sensor_reading"/></rollback>
    </changeSet>

</databaseChangeLog>
```

`20261013-0068-create-ble-sensor-reading-index.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20261013-0068-create-ble-sensor-reading-index" author="claude">
        <comment>Index fuer Zeitreihenabfragen je Sensor (eigenes Changeset wegen MariaDB-DDL-Autocommit).</comment>
        <createIndex tableName="ble_sensor_reading" indexName="idx_ble_sensor_reading_sensor_time">
            <column name="sensor_id"/>
            <column name="measured_at"/>
        </createIndex>
        <rollback><dropIndex tableName="ble_sensor_reading" indexName="idx_ble_sensor_reading_sensor_time"/></rollback>
    </changeSet>

</databaseChangeLog>
```

In `db.changelog-master.xml` vor `</databaseChangeLog>`:
```xml
    <!-- Bluetooth-Thermometer (ThermoBeacon) ueber das Wandtablet -->
    <include file="db/changelog/changes/20261013-0066-create-ble-sensor.xml"/>
    <include file="db/changelog/changes/20261013-0067-create-ble-sensor-reading.xml"/>
    <include file="db/changelog/changes/20261013-0068-create-ble-sensor-reading-index.xml"/>
```

- [ ] **Step 2: EntitySource erweitern**

In `EntitySource.java` nach `THERMOSTAT` (Komma ergänzen):
```java
    /** Bluetooth-Thermometer (ThermoBeacon/SensorBlue), empfangen ueber das Wandtablet; je Sensor Temperatur + Feuchte. */
    BLE
```

- [ ] **Step 3: JPA-Entities**

`model/entity/BleSensor.java`:
```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein ueber das Wandtablet gesehenes Bluetooth-Thermometer. Entsteht implizit mit der ersten
 * Meldung (inaktiv, ohne Namen); Historie und Entitaeten gibt es erst nach der Uebernahme
 * (active = true). Die Letztwerte stehen hier, damit die Admin-Liste auch nicht uebernommene
 * Sensoren zum Wiedererkennen zeigt, ohne dass sie eine Historie bekommen.
 */
@Entity
@Table(name = "ble_sensor")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BleSensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalform: kleingeschrieben mit Doppelpunkten. */
    @Column(nullable = false, length = 17, unique = true)
    private String mac;

    @Column(length = 100)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = false;

    @Column(name = "tablet_id", length = 100)
    private String tabletId;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_temperature", precision = 5, scale = 2)
    private BigDecimal lastTemperature;

    @Column(name = "last_humidity", precision = 5, scale = 2)
    private BigDecimal lastHumidity;

    @Column(name = "last_battery_mv")
    private Integer lastBatteryMv;

    @Column(name = "last_rssi")
    private Integer lastRssi;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

`model/entity/BleSensorReading.java`:
```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein Verlaufspunkt eines Bluetooth-Thermometers. Der Fremdschluessel (ON DELETE CASCADE)
 * lebt in der Datenbank; die Entity haelt nur die Id (Muster ThermostatHistory).
 */
@Entity
@Table(name = "ble_sensor_reading")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BleSensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", nullable = false)
    private Long sensorId;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "battery_mv")
    private Integer batteryMv;
}
```

- [ ] **Step 4: Repositories**

`repository/BleSensorRepository.java`:
```java
package com.household.manager.repository;

import com.household.manager.model.entity.BleSensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BleSensorRepository extends JpaRepository<BleSensor, Long> {

    Optional<BleSensor> findByMac(String mac);

    List<BleSensor> findByActiveTrue();

    /** Nicht uebernommene (active=false) zuerst, dann nach Name und MAC. */
    List<BleSensor> findAllByOrderByActiveAscNameAscMacAsc();
}
```

`repository/BleSensorReadingRepository.java`:
```java
package com.household.manager.repository;

import com.household.manager.model.entity.BleSensorReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BleSensorReadingRepository extends JpaRepository<BleSensorReading, Long> {

    Optional<BleSensorReading> findTopBySensorIdOrderByMeasuredAtDesc(Long sensorId);

    List<BleSensorReading> findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long sensorId, LocalDateTime from, LocalDateTime to);
}
```

- [ ] **Step 5: Kompilieren**

Run (aus `backend/`): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q compile`
Expected: BUILD SUCCESS, keine Fehler.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/BleSensor*.java backend/src/main/java/com/household/manager/repository/BleSensor*.java backend/src/main/java/com/household/manager/entitystate/EntitySource.java
git commit -F msg.txt   # "feat(blesensor): Schema, Entities und Repositories fuer Bluetooth-Thermometer"
```

---

### Task 2: Batteriekurve, MAC-Normalisierung und Entity-Mapper

**Files:**
- Create: `blesensor/BleBatteryEstimator.java`, `blesensor/BleMacs.java`, `blesensor/BleSensorEntityMapper.java`
- Test: `blesensor/BleBatteryEstimatorTest.java`, `blesensor/BleMacsTest.java`, `blesensor/BleSensorEntityMapperTest.java`

**Interfaces:**
- Produces: `BleBatteryEstimator.percent(Integer millivolts) : Integer` (null bei null); `BleMacs.normalize(String) : String` (wirft `IllegalArgumentException` bei ungültiger Syntax), `BleMacs.entityRef(String normalizedMac) : String`; `BleSensorEntityMapper.Measurement { TEMPERATURE, HUMIDITY }` mit `suffix()`/`unit()`/`label()`; `BleSensorEntityMapper.entityId(String mac, Measurement m) : String` (static); `mapper.updates(BleSensor sensor) : List<EntityStateUpdate>` (aus den Letztwerten der Zeile); `mapper.unavailable(BleSensor sensor) : List<EntityStateUpdate>`; Konstante `BleSensorEntityMapper.STATE_UNAVAILABLE = "unavailable"`.

- [ ] **Step 1: Failing Tests schreiben**

`BleBatteryEstimatorTest.java`:
```java
package com.household.manager.blesensor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BleBatteryEstimatorTest {

    @Test
    void vollAbDreiVolt() {
        assertThat(BleBatteryEstimator.percent(3000)).isEqualTo(100);
        assertThat(BleBatteryEstimator.percent(3140)).isEqualTo(100);
    }

    @Test
    void stueckweiseLinearWieHomeAssistant() {
        assertThat(BleBatteryEstimator.percent(2800)).isEqualTo(80);  // 60 + 200 * 0.1
        assertThat(BleBatteryEstimator.percent(2550)).isEqualTo(50);  // 40 + 50 * 0.2
        assertThat(BleBatteryEstimator.percent(2475)).isEqualTo(30);  // 20 + 25 * 0.4
        assertThat(BleBatteryEstimator.percent(2400)).isEqualTo(0);
    }

    @Test
    void nullBleibtNull() {
        assertThat(BleBatteryEstimator.percent(null)).isNull();
    }
}
```

`BleMacsTest.java`:
```java
package com.household.manager.blesensor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BleMacsTest {

    @Test
    void normalisiertGrossbuchstabenUndBindestriche() {
        assertThat(BleMacs.normalize("AA:BB:CC:DD:EE:FF")).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(BleMacs.normalize("aa-bb-cc-dd-ee-ff")).isEqualTo("aa:bb:cc:dd:ee:ff");
        assertThat(BleMacs.normalize(" aabbccddeeff ")).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void lehntUngueltigeSyntaxAb() {
        assertThatThrownBy(() -> BleMacs.normalize("aa:bb:cc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BleMacs.normalize("zz:bb:cc:dd:ee:ff")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BleMacs.normalize(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityRefIstMacOhneDoppelpunkte() {
        assertThat(BleMacs.entityRef("aa:bb:cc:dd:ee:ff")).isEqualTo("aabbccddeeff");
    }
}
```

`BleSensorEntityMapperTest.java`:
```java
package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BleSensorEntityMapperTest {

    private final BleSensorEntityMapper mapper = new BleSensorEntityMapper();

    private BleSensor sensor() {
        return BleSensor.builder()
                .id(7L).mac("aa:bb:cc:dd:ee:ff").name("Küche").active(true).tabletId("wandtablet")
                .lastTemperature(new BigDecimal("21.56")).lastHumidity(new BigDecimal("53.56"))
                .lastBatteryMv(3030).lastRssi(-61).lastSeenAt(LocalDateTime.of(2026, 10, 13, 9, 0))
                .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .build();
    }

    @Test
    void entityIdsHaengenAnDerMacNichtAmNamen() {
        assertThat(BleSensorEntityMapper.entityId("aa:bb:cc:dd:ee:ff", BleSensorEntityMapper.Measurement.TEMPERATURE))
                .isEqualTo("sensor.ble_aabbccddeeff_temperature");
        assertThat(BleSensorEntityMapper.entityId("aa:bb:cc:dd:ee:ff", BleSensorEntityMapper.Measurement.HUMIDITY))
                .isEqualTo("sensor.ble_aabbccddeeff_humidity");
    }

    @Test
    void updatesTragenWerteEinheitBatterieUndTablet() {
        List<EntityStateUpdate> updates = mapper.updates(sensor());

        assertThat(updates).hasSize(2);
        EntityStateUpdate temp = updates.get(0);
        assertThat(temp.entityId()).isEqualTo("sensor.ble_aabbccddeeff_temperature");
        assertThat(temp.domain()).isEqualTo(EntityDomain.SENSOR);
        assertThat(temp.source()).isEqualTo(EntitySource.BLE);
        assertThat(temp.sourceRef()).isEqualTo("aabbccddeeff");
        assertThat(temp.friendlyName()).isEqualTo("Küche Temperatur");
        assertThat(temp.state()).isEqualTo("21.56");
        assertThat(temp.attributes()).containsEntry("unit", "°C")
                .containsEntry("batteryPercent", 100)
                .containsEntry("batteryMillivolts", 3030)
                .containsEntry("rssi", -61)
                .containsEntry("tabletId", "wandtablet")
                .doesNotContainKey("deviceClass");

        EntityStateUpdate hum = updates.get(1);
        assertThat(hum.entityId()).isEqualTo("sensor.ble_aabbccddeeff_humidity");
        assertThat(hum.friendlyName()).isEqualTo("Küche Luftfeuchte");
        assertThat(hum.state()).isEqualTo("53.56");
        assertThat(hum.attributes()).containsEntry("unit", "%");
    }

    @Test
    void stateIstOhneNachlaufendeNullen() {
        BleSensor s = sensor();
        s.setLastTemperature(new BigDecimal("21.00"));
        assertThat(mapper.updates(s).get(0).state()).isEqualTo("21");
    }

    @Test
    void fehlendeWerteLiefernKeinUpdateFuerDieseMessgroesse() {
        BleSensor s = sensor();
        s.setLastHumidity(null);
        assertThat(mapper.updates(s)).hasSize(1);
        assertThat(mapper.updates(s).get(0).entityId()).endsWith("_temperature");
    }

    @Test
    void unavailableBehaeltAttribute() {
        List<EntityStateUpdate> updates = mapper.unavailable(sensor());
        assertThat(updates).hasSize(2);
        assertThat(updates).allSatisfy(u -> {
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsKey("unit").containsEntry("batteryPercent", 100);
        });
    }

    @Test
    void ohneNamenFaelltFriendlyNameAufDieMacZurueck() {
        BleSensor s = sensor();
        s.setName(null);
        assertThat(mapper.updates(s).get(0).friendlyName()).isEqualTo("aa:bb:cc:dd:ee:ff Temperatur");
    }
}
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest='BleBatteryEstimatorTest,BleMacsTest,BleSensorEntityMapperTest'`
Expected: Kompilierfehler (Klassen fehlen).

- [ ] **Step 3: Implementierung**

`BleBatteryEstimator.java`:
```java
package com.household.manager.blesensor;

/**
 * Batterieprozent aus der Spannung der ThermoBeacon-Knopfzelle, stueckweise linear —
 * dieselbe Kurve wie die Home-Assistant-Integration (thermobeacon-ble). Einzige Definition.
 */
public final class BleBatteryEstimator {

    private BleBatteryEstimator() {
    }

    public static Integer percent(Integer millivolts) {
        if (millivolts == null) {
            return null;
        }
        int mv = millivolts;
        double pct;
        if (mv >= 3000) {
            pct = 100;
        } else if (mv >= 2600) {
            pct = 60 + (mv - 2600) * 0.1;
        } else if (mv >= 2500) {
            pct = 40 + (mv - 2500) * 0.2;
        } else if (mv >= 2450) {
            pct = 20 + (mv - 2450) * 0.4;
        } else {
            pct = 0;
        }
        return (int) Math.round(pct);
    }
}
```

`BleMacs.java`:
```java
package com.household.manager.blesensor;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MAC-Normalform des Moduls: kleingeschrieben, Doppelpunkte. Toleriert Grossbuchstaben,
 * Bindestriche und fehlende Trenner, damit Tablet und Admin auf dieselbe Zeile treffen.
 */
public final class BleMacs {

    private static final Pattern HEX12 = Pattern.compile("^[0-9a-f]{12}$");

    private BleMacs() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("MAC fehlt.");
        }
        String hex = raw.trim().toLowerCase(Locale.ROOT).replace(":", "").replace("-", "");
        if (!HEX12.matcher(hex).matches()) {
            throw new IllegalArgumentException("Ungueltige MAC-Adresse: '" + raw + "'");
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    /** Referenz fuer Entity-Ids: die Normalform ohne Doppelpunkte. */
    public static String entityRef(String normalizedMac) {
        return normalizedMac.replace(":", "");
    }
}
```

`BleSensorEntityMapper.java`:
```java
package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Einzige Definition der Entity-Ids der Bluetooth-Thermometer und Bau der Entity-Updates aus
 * den Letztwerten der {@link BleSensor}-Zeile. Die Id haengt an der MAC, nie am Namen.
 * Bewusst kein deviceClass (projektweit nur fuer door und power ausgewertet).
 */
@Component
public class BleSensorEntityMapper {

    public static final String STATE_UNAVAILABLE = "unavailable";

    public enum Measurement {
        TEMPERATURE("temperature", "°C", "Temperatur"),
        HUMIDITY("humidity", "%", "Luftfeuchte");

        private final String suffix;
        private final String unit;
        private final String label;

        Measurement(String suffix, String unit, String label) {
            this.suffix = suffix;
            this.unit = unit;
            this.label = label;
        }

        public String suffix() { return suffix; }
        public String unit() { return unit; }
        public String label() { return label; }
    }

    public static String entityId(String mac, Measurement measurement) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.BLE, BleMacs.entityRef(mac), measurement.suffix());
    }

    public List<EntityStateUpdate> updates(BleSensor sensor) {
        List<EntityStateUpdate> result = new ArrayList<>(2);
        if (sensor.getLastTemperature() != null) {
            result.add(update(sensor, Measurement.TEMPERATURE, plain(sensor.getLastTemperature())));
        }
        if (sensor.getLastHumidity() != null) {
            result.add(update(sensor, Measurement.HUMIDITY, plain(sensor.getLastHumidity())));
        }
        return result;
    }

    /** unavailable MIT erhaltenen Attributen aus den Letztwerten der Zeile (EntityStateWriter.upsert ueberschreibt sonst alles). */
    public List<EntityStateUpdate> unavailable(BleSensor sensor) {
        return List.of(
                update(sensor, Measurement.TEMPERATURE, STATE_UNAVAILABLE),
                update(sensor, Measurement.HUMIDITY, STATE_UNAVAILABLE));
    }

    private EntityStateUpdate update(BleSensor sensor, Measurement measurement, String state) {
        String displayName = sensor.getName() != null && !sensor.getName().isBlank() ? sensor.getName() : sensor.getMac();
        return EntityStateUpdate.builder()
                .entityId(entityId(sensor.getMac(), measurement))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.BLE)
                .sourceRef(BleMacs.entityRef(sensor.getMac()))
                .friendlyName(displayName + " " + measurement.label())
                .state(state)
                .attributes(attributes(sensor, measurement))
                .build();
    }

    private Map<String, Object> attributes(BleSensor sensor, Measurement measurement) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("unit", measurement.unit());
        Integer percent = BleBatteryEstimator.percent(sensor.getLastBatteryMv());
        if (percent != null) {
            attrs.put("batteryPercent", percent);
            attrs.put("batteryMillivolts", sensor.getLastBatteryMv());
        }
        if (sensor.getLastRssi() != null) {
            attrs.put("rssi", sensor.getLastRssi());
        }
        if (sensor.getTabletId() != null) {
            attrs.put("tabletId", sensor.getTabletId());
        }
        return attrs;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `mvn -q test -Dtest='BleBatteryEstimatorTest,BleMacsTest,BleSensorEntityMapperTest'`
Expected: Tests run: 12, Failures: 0.

- [ ] **Step 5: Commit**

`feat(blesensor): Batteriekurve, MAC-Normalform und Entity-Mapper`

---

### Task 3: Historien-Recorder (Dedupe-Regel)

**Files:**
- Create: `blesensor/BleSensorReadingRecorder.java`
- Test: `blesensor/BleSensorReadingRecorderTest.java`

**Interfaces:**
- Consumes: `BleSensorReadingRepository` (Task 1).
- Produces: `recorder.record(BleSensor sensor, LocalDateTime measuredAt)` — liest Temperatur/Feuchte/Spannung aus den Letztwerten der Zeile; wirft nie.

- [ ] **Step 1: Failing Test**

```java
package com.household.manager.blesensor;

import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.BleSensorReading;
import com.household.manager.repository.BleSensorReadingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorReadingRecorderTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Mock private BleSensorReadingRepository repository;
    @InjectMocks private BleSensorReadingRecorder recorder;

    private BleSensor sensor(String temp, String hum) {
        return BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").active(true)
                .lastTemperature(new BigDecimal(temp)).lastHumidity(new BigDecimal(hum)).lastBatteryMv(3030).build();
    }

    private BleSensorReading last(String temp, String hum, LocalDateTime at) {
        return BleSensorReading.builder().sensorId(7L).temperature(new BigDecimal(temp))
                .humidity(new BigDecimal(hum)).measuredAt(at).build();
    }

    @Test
    void ersterPunktWirdImmerGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.empty());

        recorder.record(sensor("21.5", "50"), T0);

        ArgumentCaptor<BleSensorReading> captor = ArgumentCaptor.forClass(BleSensorReading.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSensorId()).isEqualTo(7L);
        assertThat(captor.getValue().getMeasuredAt()).isEqualTo(T0);
        assertThat(captor.getValue().getBatteryMv()).isEqualTo(3030);
    }

    @Test
    void unveraenderterWertInnerhalbVonFuenfMinutenWirdNichtGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.50", "50.00"), T0.plusMinutes(4));

        verify(repository, never()).save(any());
    }

    @Test
    void geaenderteTemperaturWirdSofortGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.6", "50"), T0.plusMinutes(1));

        verify(repository).save(any());
    }

    @Test
    void geaenderteFeuchteWirdSofortGeschrieben() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.5", "51"), T0.plusMinutes(1));

        verify(repository).save(any());
    }

    @Test
    void nachFuenfMinutenKommtEinHeartbeatPunkt() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenReturn(Optional.of(last("21.5", "50", T0)));

        recorder.record(sensor("21.5", "50"), T0.plusMinutes(5));

        verify(repository).save(any());
    }

    @Test
    void repositoryFehlerWirdVerschluckt() {
        when(repository.findTopBySensorIdOrderByMeasuredAtDesc(7L)).thenThrow(new RuntimeException("db weg"));

        recorder.record(sensor("21.5", "50"), T0); // darf nicht werfen
    }
}
```

- [ ] **Step 2: Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=BleSensorReadingRecorderTest` — Expected: Kompilierfehler.

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.blesensor;

import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.BleSensorReading;
import com.household.manager.repository.BleSensorReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Schreibt einen Verlaufspunkt nur bei Aenderung von Temperatur oder Feuchte oder wenn seit
 * {@value #HEARTBEAT_MINUTES} Minuten keiner entstand — hoechstens ein Punkt pro Minute und
 * Sensor. Wirft nie (Muster ThermostatHistoryRecorder): dieselbe Meldung versorgt die Entitaeten.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BleSensorReadingRecorder {

    static final int HEARTBEAT_MINUTES = 5;

    private final BleSensorReadingRepository repository;

    public void record(BleSensor sensor, LocalDateTime measuredAt) {
        try {
            BleSensorReading last = repository.findTopBySensorIdOrderByMeasuredAtDesc(sensor.getId()).orElse(null);
            if (last != null && !isDue(last, sensor, measuredAt)) {
                return;
            }
            repository.save(BleSensorReading.builder()
                    .sensorId(sensor.getId())
                    .measuredAt(measuredAt)
                    .temperature(sensor.getLastTemperature())
                    .humidity(sensor.getLastHumidity())
                    .batteryMv(sensor.getLastBatteryMv())
                    .build());
        } catch (Exception ex) {
            log.warn("BLE-Verlauf fuer {} nicht gespeichert: {}", sensor.getMac(), ex.getMessage());
        }
    }

    private boolean isDue(BleSensorReading last, BleSensor sensor, LocalDateTime now) {
        boolean changed = !sameValue(last.getTemperature(), sensor.getLastTemperature())
                || !sameValue(last.getHumidity(), sensor.getLastHumidity());
        boolean heartbeatDue = !Duration.between(last.getMeasuredAt(), now).minusMinutes(HEARTBEAT_MINUTES).isNegative();
        return changed || heartbeatDue;
    }

    /** compareTo statt equals: 21.5 und 21.50 sind derselbe Wert. */
    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
```

- [ ] **Step 4: Tests grün** — `mvn -q test -Dtest=BleSensorReadingRecorderTest`, Expected: 6 Tests, 0 Failures.

- [ ] **Step 5: Commit** — `feat(blesensor): Historien-Recorder mit Dedupe-Regel`

---

### Task 4: Ingest-Service und DTOs

**Files:**
- Create: `blesensor/BleSensorDtos.java`, `blesensor/BleSensorIngestService.java`
- Test: `blesensor/BleSensorIngestServiceTest.java`

**Interfaces:**
- Consumes: `BleSensorRepository`, `BleSensorReadingRecorder.record`, `BleSensorEntityMapper.updates`, `EntityStateService.reportState`, `Clock`.
- Produces: `BleSensorDtos.ReadingsRequest(String tabletId, List<ReadingItem> readings)`, `BleSensorDtos.ReadingItem(String mac, BigDecimal temperature, BigDecimal humidity, Integer batteryMillivolts, Integer rssi, Long ageSeconds)`, `BleSensorDtos.SensorAdminResponse(Long id, String mac, String name, boolean active, String tabletId, LocalDateTime lastSeenAt, BigDecimal lastTemperature, BigDecimal lastHumidity, Integer batteryPercent, Integer lastRssi)` mit `static from(BleSensor)`, `BleSensorDtos.SensorUpdateRequest(String name, Boolean active)`; `ingestService.ingest(ReadingsRequest)`; Konstante `BleSensorIngestService.MAX_READINGS = 50`.

- [ ] **Step 1: DTOs**

```java
package com.household.manager.blesensor;

import com.household.manager.model.entity.BleSensor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Request-/Response-Records des Moduls. */
public final class BleSensorDtos {

    private BleSensorDtos() {
    }

    /** Sammelmeldung des Tablets. */
    public record ReadingsRequest(String tabletId, List<ReadingItem> readings) {
    }

    public record ReadingItem(String mac, BigDecimal temperature, BigDecimal humidity,
                              Integer batteryMillivolts, Integer rssi, Long ageSeconds) {
    }

    /** Admin-Liste: auch nicht uebernommene Sensoren, zum Wiedererkennen mit Letztwerten. */
    public record SensorAdminResponse(Long id, String mac, String name, boolean active, String tabletId,
                                      LocalDateTime lastSeenAt, BigDecimal lastTemperature, BigDecimal lastHumidity,
                                      Integer batteryPercent, Integer lastRssi) {

        public static SensorAdminResponse from(BleSensor s) {
            return new SensorAdminResponse(s.getId(), s.getMac(), s.getName(), s.isActive(), s.getTabletId(),
                    s.getLastSeenAt(), s.getLastTemperature(), s.getLastHumidity(),
                    BleBatteryEstimator.percent(s.getLastBatteryMv()), s.getLastRssi());
        }
    }

    /** Voll-PUT: fehlendes active gilt als true (Muster Netzwerk-Geraete). */
    public record SensorUpdateRequest(String name, Boolean active) {
    }
}
```

- [ ] **Step 2: Failing Tests**

```java
package com.household.manager.blesensor;

import com.household.manager.blesensor.BleSensorDtos.ReadingItem;
import com.household.manager.blesensor.BleSensorDtos.ReadingsRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorIngestServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T07:00:00Z"), BERLIN);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Mock private BleSensorRepository repository;
    @Mock private BleSensorReadingRecorder recorder;
    @Mock private EntityStateService entityStateService;

    private BleSensorIngestService service;

    @BeforeEach
    void setUp() {
        service = new BleSensorIngestService(repository, recorder, new BleSensorEntityMapper(), entityStateService, CLOCK);
        // lenient: der Strukturfehler-Test ruft save() nie auf (strikte Stubs wuerfen sonst)
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ReadingItem item(String mac, String temp, String hum, long age) {
        return new ReadingItem(mac, new BigDecimal(temp), new BigDecimal(hum), 3030, -60, age);
    }

    @Test
    void neueMacWirdInaktivUndOhneNamenAngelegt() {
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("AA:BB:CC:DD:EE:FF", "21.5", "50", 10))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        BleSensor saved = captor.getValue();
        assertThat(saved.getMac()).isEqualTo("aa:bb:cc:dd:ee:ff");   // Review Focus 1: Grossbuchstaben treffen dieselbe Zeile
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getName()).isNull();
        assertThat(saved.getTabletId()).isEqualTo("wandtablet");
        assertThat(saved.getLastSeenAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(saved.getLastTemperature()).isEqualByComparingTo("21.5");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        verifyNoInteractions(recorder, entityStateService);
    }

    @Test
    void aktiverSensorBekommtHistorieUndEntitaeten() {
        BleSensor active = BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").name("Küche").active(true)
                .createdAt(NOW.minusDays(1)).build();
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.of(active));

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        verify(recorder).record(active, NOW);
        ArgumentCaptor<EntityStateUpdate> updates = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.times(2)).reportState(updates.capture());
        assertThat(updates.getAllValues()).extracting(EntityStateUpdate::entityId)
                .containsExactly("sensor.ble_aabbccddeeff_temperature", "sensor.ble_aabbccddeeff_humidity");
    }

    @Test
    void inaktiverBestandssensorBekommtNurLetztwerte() {
        BleSensor inactive = BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").active(false).createdAt(NOW.minusDays(1)).build();
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.of(inactive));

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        verify(repository).save(inactive);
        verifyNoInteractions(recorder, entityStateService);   // Review Focus 2
    }

    @Test
    void negativesAlterWirdAufNullGeklemmt() {
        when(repository.findByMac(anyString())).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb:cc:dd:ee:ff", "21.5", "50", -30))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLastSeenAt()).isEqualTo(NOW);   // Review Focus 4
    }

    @Test
    void unplausiblerWertWirdUebersprungenAndereLaufenWeiter() {
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(
                item("11:22:33:44:55:66", "250", "50", 0),      // Temperatur > 100
                item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        ArgumentCaptor<BleSensor> captor = ArgumentCaptor.forClass(BleSensor.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        assertThat(captor.getValue().getMac()).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    void fehlerBeiEinemSensorReisstDieAnderenNichtMit() {
        when(repository.findByMac("11:22:33:44:55:66")).thenThrow(new RuntimeException("db"));
        when(repository.findByMac("aa:bb:cc:dd:ee:ff")).thenReturn(Optional.empty());

        service.ingest(new ReadingsRequest("wandtablet", List.of(
                item("11:22:33:44:55:66", "20", "50", 0),
                item("aa:bb:cc:dd:ee:ff", "21.5", "50", 0))));

        verify(repository).save(any());
    }

    @Test
    void strukturfehlerSindVierhundert() {
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest(null, List.of(item("aa:bb:cc:dd:ee:ff", "20", "50", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest("wandtablet", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest("wandtablet", List.of(item("aa:bb", "20", "50", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(new ReadingsRequest("wandtablet",
                Collections.nCopies(51, item("aa:bb:cc:dd:ee:ff", "20", "50", 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }
}
```

- [ ] **Step 3: Fehlschlag bestätigen** — `mvn -q test -Dtest=BleSensorIngestServiceTest`, Expected: Kompilierfehler.

- [ ] **Step 4: Implementierung**

```java
package com.household.manager.blesensor;

import com.household.manager.blesensor.BleSensorDtos.ReadingItem;
import com.household.manager.blesensor.BleSensorDtos.ReadingsRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Verarbeitet die Sammelmeldung des Tablets. Strukturfehler (fehlende Liste, zu viele Eintraege,
 * ungueltige MAC-Syntax) lehnen die ganze Meldung ab (400); ein einzelner unplausibler Wert wird
 * uebersprungen und geloggt, die uebrigen Eintraege laufen weiter (Muster Blink-Bewegungs-Webhook).
 * Nur AKTIVE Sensoren bekommen Historie und Entitaeten.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BleSensorIngestService {

    static final int MAX_READINGS = 50;
    private static final BigDecimal MIN_TEMP = new BigDecimal("-40");
    private static final BigDecimal MAX_TEMP = new BigDecimal("100");
    private static final BigDecimal MIN_HUM = BigDecimal.ZERO;
    private static final BigDecimal MAX_HUM = new BigDecimal("100");
    private static final int MAX_MV = 5000;

    private final BleSensorRepository repository;
    private final BleSensorReadingRecorder recorder;
    private final BleSensorEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final Clock clock;

    public void ingest(ReadingsRequest request) {
        validateStructure(request);
        LocalDateTime now = LocalDateTime.now(clock);
        for (ReadingItem item : request.readings()) {
            String mac = BleMacs.normalize(item.mac());   // Syntax ist schon geprueft
            try {
                ingestOne(request.tabletId().trim(), mac, item, now);
            } catch (Exception ex) {
                log.warn("BLE-Meldung fuer {} nicht verarbeitet: {}", mac, ex.getMessage(), ex);
            }
        }
    }

    private void ingestOne(String tabletId, String mac, ReadingItem item, LocalDateTime now) {
        if (!plausible(item)) {
            log.warn("BLE-Meldung fuer {} uebersprungen, unplausible Werte: {}", mac, item);
            return;
        }
        long age = item.ageSeconds() == null ? 0 : Math.max(0, item.ageSeconds());
        LocalDateTime measuredAt = now.minusSeconds(age);

        BleSensor sensor = repository.findByMac(mac).orElseGet(() -> BleSensor.builder()
                .mac(mac).active(false).createdAt(now).build());
        sensor.setTabletId(tabletId);
        sensor.setLastSeenAt(measuredAt);
        sensor.setLastTemperature(item.temperature());
        sensor.setLastHumidity(item.humidity());
        sensor.setLastBatteryMv(item.batteryMillivolts());
        sensor.setLastRssi(item.rssi());
        BleSensor saved = repository.save(sensor);

        if (!saved.isActive()) {
            return;
        }
        recorder.record(saved, measuredAt);
        mapper.updates(saved).forEach(entityStateService::reportState);
    }

    private static boolean plausible(ReadingItem item) {
        if (item.temperature() == null && item.humidity() == null) {
            return false;
        }
        if (item.temperature() != null
                && (item.temperature().compareTo(MIN_TEMP) < 0 || item.temperature().compareTo(MAX_TEMP) > 0)) {
            return false;
        }
        if (item.humidity() != null
                && (item.humidity().compareTo(MIN_HUM) < 0 || item.humidity().compareTo(MAX_HUM) > 0)) {
            return false;
        }
        return item.batteryMillivolts() == null || (item.batteryMillivolts() >= 0 && item.batteryMillivolts() <= MAX_MV);
    }

    private static void validateStructure(ReadingsRequest request) {
        if (request == null || request.tabletId() == null || request.tabletId().isBlank()) {
            throw new IllegalArgumentException("tabletId fehlt.");
        }
        if (request.readings() == null) {
            throw new IllegalArgumentException("readings fehlt.");
        }
        if (request.readings().size() > MAX_READINGS) {
            throw new IllegalArgumentException("Hoechstens " + MAX_READINGS + " Sensoren je Meldung.");
        }
        for (ReadingItem item : request.readings()) {
            if (item == null) {
                throw new IllegalArgumentException("Leerer Eintrag in readings.");
            }
            BleMacs.normalize(item.mac());   // wirft IllegalArgumentException bei ungueltiger Syntax
        }
    }
}
```

- [ ] **Step 5: Tests grün** — `mvn -q test -Dtest=BleSensorIngestServiceTest`, Expected: 7 Tests, 0 Failures.

- [ ] **Step 6: Commit** — `feat(blesensor): Ingest der Sammelmeldung mit impliziter Registrierung`

---

### Task 5: Availability-Watchdog

**Files:**
- Create: `blesensor/BleSensorAvailabilityWatchdog.java`
- Modify: `backend/src/main/resources/application.properties`
- Test: `blesensor/BleSensorAvailabilityWatchdogTest.java`

**Interfaces:**
- Consumes: `BleSensorRepository.findByActiveTrue()`, `BleSensorEntityMapper.unavailable`, `EntityStateService.getByEntityId`, `EntityStateService.reportState`, `Clock`.
- Produces: `watchdog.check()` (`@Scheduled`), Konstruktor `(repository, mapper, entityStateService, clock, @Value staleAfterMinutes)`.

- [ ] **Step 1: Properties**

In `application.properties` (neuer Block am Ende, Kommentar auf Deutsch ohne Umlaute wie die Nachbarn):
```properties
# Bluetooth-Thermometer (ThermoBeacon) ueber das Wandtablet
# Aktiver Sensor ohne Meldung seit so vielen Minuten => Entitaeten unavailable (Attribute bleiben erhalten)
ble-sensor.stale-after-minutes=10
ble-sensor.watchdog-interval-ms=60000
```

- [ ] **Step 2: Failing Test**

```java
package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.BleSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorAvailabilityWatchdogTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T07:00:00Z"), BERLIN);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 9, 0);

    @Mock private BleSensorRepository repository;
    @Mock private EntityStateService entityStateService;

    private BleSensorAvailabilityWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new BleSensorAvailabilityWatchdog(repository, new BleSensorEntityMapper(), entityStateService, CLOCK, 10);
    }

    private BleSensor sensor(LocalDateTime lastSeen) {
        return BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").name("Küche").active(true)
                .lastSeenAt(lastSeen).lastTemperature(new BigDecimal("21.5")).lastHumidity(new BigDecimal("50"))
                .lastBatteryMv(3030).build();
    }

    @Test
    void stillerSensorWirdUnavailableMitAttributen() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(NOW.minusMinutes(11))));
        when(entityStateService.getByEntityId(anyString()))
                .thenReturn(Optional.of(EntityState.builder().state("21.5").build()));

        watchdog.check();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(u -> {
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsEntry("batteryPercent", 100).containsKey("unit");
        });
    }

    @Test
    void frischerSensorBleibtUnberuehrt() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(NOW.minusMinutes(9))));

        watchdog.check();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void bereitsUnavailableWirdNichtErneutGemeldet() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(NOW.minusMinutes(60))));
        when(entityStateService.getByEntityId(anyString()))
                .thenReturn(Optional.of(EntityState.builder().state("unavailable").build()));

        watchdog.check();

        verify(entityStateService, never()).reportState(any());   // Review Focus 3
    }

    @Test
    void sensorOhneJeEineMeldungWirdUebersprungen() {
        when(repository.findByActiveTrue()).thenReturn(List.of(sensor(null)));

        watchdog.check();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void wirftNie() {
        when(repository.findByActiveTrue()).thenThrow(new RuntimeException("db weg"));

        watchdog.check();
    }
}
```

- [ ] **Step 3: Fehlschlag bestätigen** — `mvn -q test -Dtest=BleSensorAvailabilityWatchdogTest`.

- [ ] **Step 4: Implementierung**

```java
package com.household.manager.blesensor;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.BleSensorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Markiert die Entitaeten aktiver Sensoren ohne Meldung seit {@code ble-sensor.stale-after-minutes}
 * als unavailable — mit erhaltenen Attributen (aus den Letztwerten der Zeile), genau einmal je
 * Ausfall (eine schon unavailable Entitaet wird nicht erneut gemeldet). Wirft nie.
 */
@Component
@Slf4j
public class BleSensorAvailabilityWatchdog {

    private final BleSensorRepository repository;
    private final BleSensorEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final Clock clock;
    private final int staleAfterMinutes;

    public BleSensorAvailabilityWatchdog(BleSensorRepository repository,
                                         BleSensorEntityMapper mapper,
                                         EntityStateService entityStateService,
                                         Clock clock,
                                         @Value("${ble-sensor.stale-after-minutes:10}") int staleAfterMinutes) {
        this.repository = repository;
        this.mapper = mapper;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.staleAfterMinutes = staleAfterMinutes;
    }

    @Scheduled(fixedDelayString = "${ble-sensor.watchdog-interval-ms:60000}")
    public void check() {
        try {
            LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(staleAfterMinutes);
            for (BleSensor sensor : repository.findByActiveTrue()) {
                if (sensor.getLastSeenAt() == null || !sensor.getLastSeenAt().isBefore(threshold)) {
                    continue;
                }
                markUnavailable(sensor);
            }
        } catch (Exception ex) {
            log.warn("BLE-Watchdog fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private void markUnavailable(BleSensor sensor) {
        for (EntityStateUpdate update : mapper.unavailable(sensor)) {
            boolean alreadyUnavailable = entityStateService.getByEntityId(update.entityId())
                    .map(EntityState::getState)
                    .filter(BleSensorEntityMapper.STATE_UNAVAILABLE::equals)
                    .isPresent();
            if (alreadyUnavailable) {
                continue;
            }
            log.warn("BLE-Sensor '{}' ({}) sendet seit {} min nicht, Entitaet geht auf unavailable",
                    sensor.getName(), sensor.getMac(), staleAfterMinutes);
            entityStateService.reportState(update);
        }
    }
}
```

- [ ] **Step 5: Tests grün** — Expected: 5 Tests, 0 Failures.

- [ ] **Step 6: Commit** — `feat(blesensor): Watchdog markiert stille Sensoren unavailable`

---

### Task 6: Admin-Service (Liste, Übernahme, Umbenennen, Löschen)

**Files:**
- Create: `blesensor/BleSensorAdminService.java`
- Test: `blesensor/BleSensorAdminServiceTest.java`

**Interfaces:**
- Consumes: `BleSensorRepository`, `BleSensorEntityMapper`, `EntityStateService.reportState/deleteByEntityId`, `AuditService.record(String, String)`.
- Produces: `list() : List<SensorAdminResponse>`, `update(Long id, SensorUpdateRequest) : SensorAdminResponse`, `delete(Long id)`.

- [ ] **Step 1: Failing Tests**

```java
package com.household.manager.blesensor;

import com.household.manager.audit.AuditService;
import com.household.manager.blesensor.BleSensorDtos.SensorUpdateRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BleSensorAdminServiceTest {

    @Mock private BleSensorRepository repository;
    @Mock private EntityStateService entityStateService;
    @Mock private AuditService auditService;

    private BleSensorAdminService service;

    @BeforeEach
    void setUp() {
        service = new BleSensorAdminService(repository, new BleSensorEntityMapper(), entityStateService, auditService);
    }

    private BleSensor sensor(boolean active, String name) {
        return BleSensor.builder().id(7L).mac("aa:bb:cc:dd:ee:ff").name(name).active(active)
                .createdAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build();
    }

    @Test
    void listeLiefertNichtUebernommeneZuerst() {
        BleSensor unadopted = BleSensor.builder().id(1L).mac("11:22:33:44:55:66").active(false).build();
        when(repository.findAllByOrderByActiveAscNameAscMacAsc()).thenReturn(List.of(unadopted, sensor(true, "Küche")));

        var list = service.list();

        assertThat(list).extracting(r -> r.mac()).containsExactly("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff");
    }

    @Test
    void uebernehmenSetztNamenUndAktivUndAuditiert() {
        BleSensor s = sensor(false, null);
        when(repository.findById(7L)).thenReturn(Optional.of(s));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(7L, new SensorUpdateRequest("  Küche ", true));

        assertThat(response.name()).isEqualTo("Küche");
        assertThat(response.active()).isTrue();
        verify(auditService).record(eq("ble-sensor.update"), any());
    }

    @Test
    void aktivierenOhneNamenIstVierhundert() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(false, null)));

        assertThatThrownBy(() -> service.update(7L, new SensorUpdateRequest("  ", true)))
                .isInstanceOf(IllegalArgumentException.class);   // Review Focus 5
        verify(repository, never()).save(any());
    }

    @Test
    void fehlendesActiveGiltAlsAktiv() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(false, null)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(7L, new SensorUpdateRequest("Bad", null)).active()).isTrue();
    }

    @Test
    void deaktivierenMeldetEinmalUnavailable() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(true, "Küche")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, new SensorUpdateRequest("Küche", false));

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).allMatch(u -> "unavailable".equals(u.state()));   // Review Focus 2
    }

    @Test
    void umbenennenEinesAktivenMeldetKeinUnavailable() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(true, "Küche")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, new SensorUpdateRequest("Esszimmer", true));

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void loeschenEinesAktivenIstKonflikt() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(true, "Küche")));

        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(repository, never()).delete(any());
    }

    @Test
    void loeschenEinesInaktivenEntferntZeileUndEntitaeten() {
        when(repository.findById(7L)).thenReturn(Optional.of(sensor(false, "Küche")));

        service.delete(7L);

        verify(repository).delete(any());
        verify(entityStateService).deleteByEntityId("sensor.ble_aabbccddeeff_temperature");
        verify(entityStateService).deleteByEntityId("sensor.ble_aabbccddeeff_humidity");
        verify(auditService).record(eq("ble-sensor.delete"), any());
    }

    @Test
    void unbekannteIdIstVierhundertvier() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
```

- [ ] **Step 2: Fehlschlag bestätigen** — `mvn -q test -Dtest=BleSensorAdminServiceTest`.

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.blesensor;

import com.household.manager.audit.AuditService;
import com.household.manager.blesensor.BleSensorDtos.SensorAdminResponse;
import com.household.manager.blesensor.BleSensorDtos.SensorUpdateRequest;
import com.household.manager.blesensor.BleSensorEntityMapper.Measurement;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Pflege der Bluetooth-Thermometer: Uebernahme (Name + aktiv), Umbenennen, Deaktivieren, Loeschen.
 * Loeschen nur fuer inaktive Zeilen (409 sonst); dabei werden die Entitaeten entfernt, sonst
 * stuenden sie fuer immer auf unavailable. Deaktivieren meldet die Entitaeten einmal unavailable.
 */
@Service
@RequiredArgsConstructor
public class BleSensorAdminService {

    private final BleSensorRepository repository;
    private final BleSensorEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<SensorAdminResponse> list() {
        return repository.findAllByOrderByActiveAscNameAscMacAsc().stream()
                .map(SensorAdminResponse::from)
                .toList();
    }

    @Transactional
    public SensorAdminResponse update(Long id, SensorUpdateRequest request) {
        BleSensor sensor = findOrThrow(id);
        boolean active = request.active() == null || request.active();
        String name = request.name() == null ? null : request.name().trim();
        if (active && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("Ein aktiver Sensor braucht einen Namen.");
        }
        if (name != null && name.length() > 100) {
            throw new IllegalArgumentException("Der Name darf hoechstens 100 Zeichen haben.");
        }
        boolean wasActive = sensor.isActive();
        sensor.setName(name == null || name.isBlank() ? sensor.getName() : name);
        sensor.setActive(active);
        BleSensor saved = repository.save(sensor);

        if (wasActive && !active) {
            mapper.unavailable(saved).forEach(entityStateService::reportState);
        }
        auditService.record("ble-sensor.update", "%s (%s) aktiv=%s".formatted(saved.getName(), saved.getMac(), saved.isActive()));
        return SensorAdminResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        BleSensor sensor = findOrThrow(id);
        if (sensor.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ein aktiver Sensor kann nicht geloescht werden. Bitte zuerst deaktivieren.");
        }
        repository.delete(sensor);
        for (Measurement measurement : Measurement.values()) {
            entityStateService.deleteByEntityId(BleSensorEntityMapper.entityId(sensor.getMac(), measurement));
        }
        auditService.record("ble-sensor.delete", "%s (%s)".formatted(sensor.getName(), sensor.getMac()));
    }

    private BleSensor findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("BleSensor", "id", id));
    }
}
```

- [ ] **Step 4: Tests grün** — Expected: 9 Tests, 0 Failures.

- [ ] **Step 5: Commit** — `feat(blesensor): Admin-Service fuer Uebernahme, Umbenennen und Loeschen`

---

### Task 7: Controller und Security

**Files:**
- Create: `blesensor/BleSensorController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

**Interfaces:**
- Consumes: `BleSensorIngestService.ingest`, `BleSensorAdminService.list/update/delete`.
- Produces: `POST /v1/ble-sensors/readings` (204), `GET /v1/ble-sensors` (200), `PUT /v1/ble-sensors/{id}` (200), `DELETE /v1/ble-sensors/{id}` (204).

- [ ] **Step 1: Security-Tests ergänzen**

In `SecurityRulesTest.java` hinter den Tablet-Presence-Tests (ca. Zeile 349):
```java
    @Test
    void serviceTokenDarfBleSensorenMelden() throws Exception {
        mockMvc.perform(post("/v1/ble-sensors/readings")
                        .header("X-API-Token", "hm_ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tabletId\":\"wandtablet\",\"readings\":[]}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void browserSessionOhneServiceAuthorityKommtNichtAnBleReadings() throws Exception {
        mockMvc.perform(post("/v1/ble-sensors/readings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tabletId\":\"wandtablet\",\"readings\":[]}"))
                .andExpect(status().isForbidden());
    }

    /** Die Liste enthaelt MACs fremder Geraete in Funkreichweite — kein KIOSK-Lesen. */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskKommtNichtAnBleSensorliste() throws Exception {
        mockMvc.perform(get("/v1/ble-sensors")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberKommtNichtAnBleSensoren() throws Exception {
        mockMvc.perform(get("/v1/ble-sensors")).andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/ble-sensors/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/ble-sensors/1").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnBleSensorenVorbei() throws Exception {
        // 200/404 statt 403: die Regel laesst durch, der Controller antwortet (Liste leer bzw. Id unbekannt)
        mockMvc.perform(get("/v1/ble-sensors")).andExpect(status().isOk());
        mockMvc.perform(delete("/v1/ble-sensors/1").with(csrf())).andExpect(status().isNotFound());
    }
```
Hinweis für den Implementierer: `SecurityRulesTest` ist ein `@SpringBootTest` mit gemockten Services; schaue am Dateianfang nach, wie die Nachbar-Services (`NetworkDeviceService` o. ä.) als `@MockitoBean` bereitgestellt werden, und ergänze `BleSensorIngestService` und `BleSensorAdminService` genauso. Für den 404 im Admin-Test den gemockten `BleSensorAdminService.delete(1L)` mit `doThrow(new ResourceNotFoundException("BleSensor","id",1L))` stubben; `list()` liefert als Mock standardmäßig eine leere Liste.

- [ ] **Step 2: Fehlschlag bestätigen** — `mvn -q test -Dtest=SecurityRulesTest`, Expected: die neuen Tests scheitern (404/403 statt der erwarteten Codes).

- [ ] **Step 3: Controller**

```java
package com.household.manager.blesensor;

import com.household.manager.blesensor.BleSensorDtos.ReadingsRequest;
import com.household.manager.blesensor.BleSensorDtos.SensorAdminResponse;
import com.household.manager.blesensor.BleSensorDtos.SensorUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bluetooth-Thermometer: Sammelmeldung des Tablets (SERVICE) und Admin-Pflege (ADMIN).
 * Rollen stehen in SecurityConfig, nicht hier.
 */
@RestController
@RequestMapping("/v1/ble-sensors")
@RequiredArgsConstructor
public class BleSensorController {

    private final BleSensorIngestService ingestService;
    private final BleSensorAdminService adminService;

    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readings(@RequestBody ReadingsRequest request) {
        ingestService.ingest(request);
    }

    @GetMapping
    public List<SensorAdminResponse> list() {
        return adminService.list();
    }

    @PutMapping("/{id}")
    public SensorAdminResponse update(@PathVariable Long id, @RequestBody SensorUpdateRequest request) {
        return adminService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminService.delete(id);
    }
}
```

- [ ] **Step 4: SecurityConfig**

Im SERVICE-Block (direkt nach der Zeile `.requestMatchers("/v1/tablet-presence/**").hasAuthority(SERVICE_AUTHORITY)`):
```java
                        // Bluetooth-Thermometer: Sammelmeldung des Wandtablets (Muster tablet-presence)
                        .requestMatchers(HttpMethod.POST, "/v1/ble-sensors/readings").hasAuthority(SERVICE_AUTHORITY)
```
Im ADMIN-Matcher (die lange `requestMatchers(...)`-Liste, die mit `"/v1/agent", "/v1/agent/**").hasRole("ADMIN")` endet) vor `"/v1/agent"` einfügen:
```java
                                // Bluetooth-Thermometer: die Liste traegt MACs fremder Geraete in Funkreichweite,
                                // auch LESEN ist ADMIN. Methodenlos, beide Formen, VOR der generischen GET-Regel.
                                "/v1/ble-sensors", "/v1/ble-sensors/**",
```

- [ ] **Step 5: Tests grün** — `mvn -q test -Dtest=SecurityRulesTest`, Expected: alle Tests grün (inkl. der 5 neuen).

- [ ] **Step 6: Gesamter Modul-Testlauf** — `mvn -q test -Dtest='com.household.manager.blesensor.*Test,SecurityRulesTest'`, Expected: 0 Failures.

- [ ] **Step 7: Commit** — `feat(blesensor): REST-Endpunkte und Security-Regeln`

---

### Task 8: Vierte Quelle im TemperatureSeriesService

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/TemperatureSeriesService.java`, `frontend/src/app/models/temperature.model.ts:11`
- Test: `backend/src/test/java/com/household/manager/service/TemperatureSeriesServiceTest.java`

**Interfaces:**
- Consumes: `BleSensorRepository.findByActiveTrue/findById`, `BleSensorReadingRepository.findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc`, `BleSensorEntityMapper.entityId`.
- Produces: `sensorId = "ble:<id>"`, `source = "BLE"`.

- [ ] **Step 1: Failing Tests** (in `TemperatureSeriesServiceTest` zwei `@Mock`-Felder ergänzen: `@Mock private BleSensorRepository bleSensorRepository; @Mock private BleSensorReadingRepository bleReadingRepository;` — `@InjectMocks` findet den erweiterten Konstruktor)

```java
    private BleSensor bleSensor(long id, String name, LocalDateTime lastSeen) {
        return BleSensor.builder().id(id).mac("aa:bb:cc:dd:ee:ff").name(name).active(true)
                .lastSeenAt(lastSeen).lastTemperature(new BigDecimal("21.5")).lastHumidity(new BigDecimal("50"))
                .build();
    }

    @Test
    void bleSeriesLiefertTemperaturUndFeuchteAktiverSensoren() {
        LocalDateTime now = LocalDateTime.now();
        when(bleSensorRepository.findByActiveTrue()).thenReturn(List.of(bleSensor(7L, "Küche", now)));
        when(bleReadingRepository.findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(eq(7L), any(), any()))
                .thenReturn(List.of(BleSensorReading.builder().sensorId(7L).measuredAt(now)
                        .temperature(new BigDecimal("21.5")).humidity(new BigDecimal("50")).build()));
        when(downsampler.downsample(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));

        List<TemperatureSensorSeries> series = service.getSeries(SeriesRange.DAY);

        TemperatureSensorSeries ble = series.stream().filter(s -> s.getSource().equals("BLE")).findFirst().orElseThrow();
        assertThat(ble.getSensorId()).isEqualTo("ble:7");
        assertThat(ble.getName()).isEqualTo("Küche");
        assertThat(ble.getTemperature()).hasSize(1);
        assertThat(ble.getHumidity()).hasSize(1);
    }

    @Test
    void bleCurrentNutztDieLetztwerteUndDieMapperEntityId() {
        LocalDateTime now = LocalDateTime.now();
        when(bleSensorRepository.findByActiveTrue()).thenReturn(List.of(bleSensor(7L, "Küche", now.minusMinutes(2))));

        List<CurrentTemperatureReading> current = service.getCurrent();

        CurrentTemperatureReading ble = current.stream().filter(c -> c.getSource().equals("BLE")).findFirst().orElseThrow();
        assertThat(ble.getSensorId()).isEqualTo("ble:7");
        assertThat(ble.getEntityId())
                .isEqualTo(BleSensorEntityMapper.entityId("aa:bb:cc:dd:ee:ff", BleSensorEntityMapper.Measurement.TEMPERATURE));
        assertThat(ble.getTemperature()).isEqualByComparingTo("21.5");
        assertThat(ble.getMeasuredAt()).isEqualTo(now.minusMinutes(2));
    }

    @Test
    void bleCurrentLaesstSensorenOhneWertInDenLetztenSiebenTagenWeg() {
        when(bleSensorRepository.findByActiveTrue()).thenReturn(List.of(bleSensor(7L, "Küche", LocalDateTime.now().minusDays(8))));

        assertThat(service.getCurrent()).noneMatch(c -> c.getSource().equals("BLE"));
    }

    @Test
    void bleSensorSeriesUnbekannteOderInaktiveIdIst404() {
        when(bleSensorRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getSensorSeries("ble:9", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);

        BleSensor inactive = bleSensor(8L, "Alt", LocalDateTime.now());
        inactive.setActive(false);
        when(bleSensorRepository.findById(8L)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.getSensorSeries("ble:8", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getSensorSeries("ble:x", SeriesRange.DAY)).isInstanceOf(ResourceNotFoundException.class);
    }
```
Imports ergänzen: `com.household.manager.blesensor.BleSensorEntityMapper`, `com.household.manager.model.entity.BleSensor`, `com.household.manager.model.entity.BleSensorReading`, `com.household.manager.repository.BleSensorRepository`, `com.household.manager.repository.BleSensorReadingRepository`.

- [ ] **Step 2: Fehlschlag bestätigen** — `mvn -q test -Dtest=TemperatureSeriesServiceTest`.

- [ ] **Step 3: Implementierung in `TemperatureSeriesService`**

Felder ergänzen (nach `alexaRepository`):
```java
    private final BleSensorRepository bleSensorRepository;
    private final BleSensorReadingRepository bleReadingRepository;
```
In `getSeries`: `series.addAll(safe("ble", () -> bleSeries(from, to)));` — in `getCurrent`: `result.addAll(safe("ble", () -> bleCurrent(since)));` — in `getSensorSeries` vor dem abschließenden `throw`:
```java
        if (sensorId.startsWith("ble:")) {
            return bleSensorSeries(sensorId.substring("ble:".length()), from, to, range);
        }
```
Neue Methoden:
```java
    private static final String BLE_SOURCE = "BLE";

    private List<TemperatureSensorSeries> bleSeries(LocalDateTime from, LocalDateTime to) {
        List<TemperatureSensorSeries> result = new ArrayList<>();
        for (BleSensor sensor : activeBleSensorsSorted()) {
            result.add(bleSeriesFor(sensor, from, to, null));
        }
        return result;
    }

    private TemperatureSensorSeries bleSensorSeries(String rawId, LocalDateTime from, LocalDateTime to, SeriesRange range) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException ex) {
            throw new ResourceNotFoundException("Sensor", "sensorId", "ble:" + rawId);
        }
        BleSensor sensor = bleSensorRepository.findById(id)
                .filter(BleSensor::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Sensor", "sensorId", "ble:" + id));
        return bleSeriesFor(sensor, from, to, range);
    }

    /** range == null: Rohpunkte (Sammelabfrage); sonst gemittelt (Einzelsensor) — wie bei Zigbee. */
    private TemperatureSensorSeries bleSeriesFor(BleSensor sensor, LocalDateTime from, LocalDateTime to, SeriesRange range) {
        List<BleSensorReading> readings = bleReadingRepository
                .findBySensorIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(sensor.getId(), from, to);
        List<TimeValue> temperature = readings.stream()
                .filter(r -> r.getTemperature() != null)
                .map(r -> point(r.getMeasuredAt(), r.getTemperature()))
                .toList();
        List<TimeValue> humidity = readings.stream()
                .filter(r -> r.getHumidity() != null)
                .map(r -> point(r.getMeasuredAt(), r.getHumidity()))
                .toList();
        return TemperatureSensorSeries.builder()
                .sensorId("ble:" + sensor.getId())
                .name(temperatureName(EntitySource.BLE, BleMacs.entityRef(sensor.getMac()), bleDisplayName(sensor)))
                .source(BLE_SOURCE)
                .temperature(range == null ? temperature : downsampler.downsample(temperature, range))
                .humidity(range == null ? humidity : downsampler.downsample(humidity, range))
                .build();
    }

    /** Aktuellwerte direkt aus den Letztwerten der Zeile — kein Historienzugriff noetig. */
    private List<CurrentTemperatureReading> bleCurrent(LocalDateTime since) {
        List<CurrentTemperatureReading> result = new ArrayList<>();
        for (BleSensor sensor : activeBleSensorsSorted()) {
            if (sensor.getLastSeenAt() == null || sensor.getLastSeenAt().isBefore(since) || sensor.getLastTemperature() == null) {
                continue;
            }
            result.add(CurrentTemperatureReading.builder()
                    .sensorId("ble:" + sensor.getId())
                    .entityId(BleSensorEntityMapper.entityId(sensor.getMac(), BleSensorEntityMapper.Measurement.TEMPERATURE))
                    .name(temperatureName(EntitySource.BLE, BleMacs.entityRef(sensor.getMac()), bleDisplayName(sensor)))
                    .source(BLE_SOURCE)
                    .temperature(sensor.getLastTemperature())
                    .humidity(sensor.getLastHumidity())
                    .measuredAt(sensor.getLastSeenAt())
                    .build());
        }
        return result;
    }

    private List<BleSensor> activeBleSensorsSorted() {
        return bleSensorRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparing(this::bleDisplayName))
                .toList();
    }

    private String bleDisplayName(BleSensor sensor) {
        return sensor.getName() != null && !sensor.getName().isBlank() ? sensor.getName() : sensor.getMac();
    }
```
Prüfen: Hat `TemperatureSeriesService` bereits eine `point(LocalDateTime, BigDecimal)`-Hilfsmethode (ja, siehe `weatherSeries`). Die `entityId` im Aktuellwert kommt aus `BleSensorEntityMapper.entityId`, **nicht** aus `temperatureEntityId(...)`; `temperatureName` nutzt `temperatureEntityId(EntitySource.BLE, entityRef)` — beide ergeben dieselbe Id (`sensor.ble_<ref>_temperature`), der Test `bleCurrentNutztDieLetztwerteUndDieMapperEntityId` hält das fest. Imports: `BleSensorEntityMapper`, `BleMacs`, `BleSensor`, `BleSensorReading`, `BleSensorRepository`, `BleSensorReadingRepository`.

- [ ] **Step 4: Frontend-Typ** — in `frontend/src/app/models/temperature.model.ts` Zeile 11:
```ts
export type TemperatureSource = 'ZIGBEE' | 'WEATHER' | 'ALEXA' | 'BLE';
```
Danach `grep -rn "'ALEXA'" frontend/src/app --include=*.ts` ausführen: Trifft eine `switch`/Record-Zuordnung über `TemperatureSource` (z. B. ein Icon je Quelle), dort `BLE` ergänzen (Icon `bluetooth`). Trifft nichts, ist nichts weiter zu tun.

- [ ] **Step 5: Tests grün** — `mvn -q test -Dtest=TemperatureSeriesServiceTest` (alle, inkl. bestehender); Frontend: `npx tsc -p tsconfig.app.json --noEmit` aus `frontend/`, Expected: keine Fehler.

- [ ] **Step 6: Commit** — `feat(temperature): Bluetooth-Thermometer als vierte Quelle der Temperaturansicht`

---

### Task 9: Admin-Seite „Bluetooth-Thermometer"

**Files:**
- Create: `frontend/src/app/models/ble-sensor.model.ts`, `frontend/src/app/services/ble-sensor.service.ts`, `frontend/src/app/pages/admin-ble-sensors/admin-ble-sensors.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/app.routes.ts` (nach dem `admin/presence`-Eintrag), `frontend/src/app/components/header/header.component.ts:82` (nach „Anwesenheit"), `frontend/src/styles.scss:574` (`$admin-scopes`)

**Interfaces:**
- Consumes: `GET/PUT/DELETE /api/v1/ble-sensors` aus Task 7.
- Produces: Route `admin/ble-sensors`, Komponente `AdminBleSensorsComponent`, Wurzelklasse `.admin-ble-sensors`.

- [ ] **Step 1: Modell und Service**

`models/ble-sensor.model.ts`:
```ts
/** Ein vom Wandtablet gesehenes Bluetooth-Thermometer (Admin-Sicht, inkl. nicht uebernommener). */
export interface BleSensorAdminResponse {
  id: number;
  mac: string;
  name: string | null;
  active: boolean;
  tabletId: string | null;
  lastSeenAt: string | null;
  lastTemperature: number | null;
  lastHumidity: number | null;
  batteryPercent: number | null;
  lastRssi: number | null;
}

/** Voll-PUT: `active` immer mitsenden, ein fehlendes Feld gilt serverseitig als aktiv. */
export interface BleSensorUpdateRequest {
  name: string | null;
  active: boolean;
}
```

`services/ble-sensor.service.ts`:
```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BleSensorAdminResponse, BleSensorUpdateRequest } from '../models/ble-sensor.model';

/** REST-Service fuer die Admin-Pflege der Bluetooth-Thermometer. */
@Injectable({ providedIn: 'root' })
export class BleSensorService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/ble-sensors';

  list(): Observable<BleSensorAdminResponse[]> {
    return this.http.get<BleSensorAdminResponse[]>(this.baseUrl);
  }

  update(id: number, request: BleSensorUpdateRequest): Observable<BleSensorAdminResponse> {
    return this.http.put<BleSensorAdminResponse>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
```

- [ ] **Step 2: Failing Spec**

`admin-ble-sensors.component.spec.ts`:
```ts
import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminBleSensorsComponent } from './admin-ble-sensors.component';
import { BleSensorAdminResponse } from '../../models/ble-sensor.model';

const URL = '/api/v1/ble-sensors';

const UNADOPTED: BleSensorAdminResponse = {
  id: 1, mac: '11:22:33:44:55:66', name: null, active: false, tabletId: 'wandtablet',
  lastSeenAt: '2026-10-13T09:00:00', lastTemperature: 19.3, lastHumidity: 61, batteryPercent: 80, lastRssi: -70
};
const KUECHE: BleSensorAdminResponse = {
  id: 2, mac: 'aa:bb:cc:dd:ee:ff', name: 'Küche', active: true, tabletId: 'wandtablet',
  lastSeenAt: '2026-10-13T09:00:30', lastTemperature: 21.5, lastHumidity: 50, batteryPercent: 100, lastRssi: -60
};
const ALT: BleSensorAdminResponse = { ...KUECHE, id: 3, mac: '01:02:03:04:05:06', name: 'Alt', active: false };

describe('AdminBleSensorsComponent', () => {
  let fixture: ComponentFixture<AdminBleSensorsComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminBleSensorsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminBleSensorsComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  async function loadWith(sensors: BleSensorAdminResponse[]): Promise<void> {
    fixture.detectChanges();
    httpMock.expectOne(URL).flush(sensors);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-ble-sensors__table tbody tr'));
  }

  function button(row: HTMLElement, label: string): HTMLButtonElement {
    const btn = Array.from(row.querySelectorAll('button')).find(b => b.textContent?.trim() === label);
    if (!btn) { throw new Error(`Kein Knopf „${label}“ in Zeile`); }
    return btn as HTMLButtonElement;
  }

  it('zeigt alle Sensoren mit Status-Badge, MAC als Fallback-Name', async () => {
    await loadWith([UNADOPTED, KUECHE, ALT]);
    const r = rows();
    expect(r.length).toBe(3);
    expect(r[0].textContent).toContain('11:22:33:44:55:66');
    expect(r[0].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Nicht übernommen');
    expect(r[1].textContent).toContain('Küche');
    expect(r[1].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Aktiv');
    expect(r[2].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Inaktiv');
  });

  it('zeigt Letztwerte und Batterie', async () => {
    await loadWith([KUECHE]);
    expect(rows()[0].textContent).toContain('21,5 °C');
    expect(rows()[0].textContent).toContain('50 %');
    expect(rows()[0].textContent).toContain('100 %');
  });

  it('Uebernehmen fragt den Namen und sendet active=true per Voll-PUT', async () => {
    await loadWith([UNADOPTED]);
    button(rows()[0], 'Übernehmen').click();
    fixture.detectChanges();
    await fixture.whenStable();
    const input = el.querySelector('.admin-ble-sensors__name-input') as HTMLInputElement;
    input.value = 'Bad';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    button(rows()[0], 'Speichern').click();

    const req = httpMock.expectOne(`${URL}/1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'Bad', active: true });
    req.flush({ ...UNADOPTED, name: 'Bad', active: true });
    fixture.detectChanges();
    expect(rows()[0].querySelector('.admin-ble-sensors__badge')?.textContent?.trim()).toBe('Aktiv');
  });

  it('Speichern ist ohne Namen gesperrt', async () => {
    await loadWith([UNADOPTED]);
    button(rows()[0], 'Übernehmen').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(button(rows()[0], 'Speichern').disabled).toBeTrue();
  });

  it('Deaktivieren sendet active=false und behaelt den Namen', async () => {
    await loadWith([KUECHE]);
    button(rows()[0], 'Deaktivieren').click();
    const req = httpMock.expectOne(`${URL}/2`);
    expect(req.request.body).toEqual({ name: 'Küche', active: false });
    req.flush({ ...KUECHE, active: false });
  });

  it('Loeschen gibt es nur fuer inaktive Sensoren und fragt nach', async () => {
    await loadWith([KUECHE, ALT]);
    expect(Array.from(rows()[0].querySelectorAll('button')).map(b => b.textContent?.trim())).not.toContain('Löschen');
    spyOn(window, 'confirm').and.returnValue(true);
    button(rows()[1], 'Löschen').click();
    const req = httpMock.expectOne(`${URL}/3`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    fixture.detectChanges();
    expect(rows().length).toBe(1);
  });

  it('ein fehlgeschlagener Refresh behaelt den letzten Stand', fakeAsync(() => {
    fixture.detectChanges();
    httpMock.expectOne(URL).flush([KUECHE]);
    fixture.detectChanges();
    tick(30_000);
    httpMock.expectOne(URL).flush('kaputt', { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(rows().length).toBe(1);
    discardPeriodicTasks();
  }));

  it('ein fehlgeschlagener Erstabruf blendet die Tabelle aus und meldet den Fehler', async () => {
    fixture.detectChanges();
    httpMock.expectOne(URL).flush('kaputt', { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(el.querySelector('.admin-ble-sensors__table')).toBeNull();
    expect(el.textContent).toContain('konnten nicht geladen werden');
  });
});
```

- [ ] **Step 3: Fehlschlag bestätigen** — `npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-ble-sensors.component.spec.ts'` aus `frontend/`, Expected: Kompilierfehler (Komponente fehlt).

- [ ] **Step 4: Komponente**

`admin-ble-sensors.component.ts`:
```ts
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subscription, interval } from 'rxjs';
import { BleSensorService } from '../../services/ble-sensor.service';
import { BleSensorAdminResponse } from '../../models/ble-sensor.model';

/** Refresh-Takt: beim Rollout erkennt man am steigenden Wert, welches Thermometer man in der Hand hat. */
const REFRESH_MS = 30_000;

type SensorStatus = 'active' | 'inactive' | 'unadopted';

/**
 * Admin-Seite „Bluetooth-Thermometer“: Uebernehmen (Name vergeben), Umbenennen, Deaktivieren,
 * Loeschen (nur inaktiv). Nicht uebernommene Sensoren stehen oben — die Liste kommt vom Server
 * bereits in dieser Reihenfolge.
 */
@Component({
  selector: 'app-admin-ble-sensors',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-ble-sensors.component.html',
  styleUrl: './admin-ble-sensors.component.scss'
})
export class AdminBleSensorsComponent implements OnInit, OnDestroy {
  private readonly api = inject(BleSensorService);

  readonly sensors = signal<BleSensorAdminResponse[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  /** Id des Sensors, dessen Namenszeile gerade offen ist (Uebernehmen oder Umbenennen). */
  editingId: number | null = null;
  editingName = '';

  private refresh?: Subscription;

  ngOnInit(): void {
    this.load(true);
    this.refresh = interval(REFRESH_MS).subscribe(() => this.load(false));
  }

  ngOnDestroy(): void {
    this.refresh?.unsubscribe();
  }

  status(sensor: BleSensorAdminResponse): SensorStatus {
    if (sensor.active) { return 'active'; }
    return sensor.name ? 'inactive' : 'unadopted';
  }

  statusLabel(sensor: BleSensorAdminResponse): string {
    switch (this.status(sensor)) {
      case 'active': return 'Aktiv';
      case 'inactive': return 'Inaktiv';
      case 'unadopted': return 'Nicht übernommen';
    }
  }

  displayName(sensor: BleSensorAdminResponse): string {
    return sensor.name ?? sensor.mac;
  }

  startEdit(sensor: BleSensorAdminResponse): void {
    this.editingId = sensor.id;
    this.editingName = sensor.name ?? '';
    this.errorMessage.set(null);
  }

  cancelEdit(): void {
    this.editingId = null;
    this.editingName = '';
  }

  /** Uebernehmen und Umbenennen: beides setzt active=true mit dem eingegebenen Namen. */
  saveName(): void {
    const id = this.editingId;
    const name = this.editingName.trim();
    if (id === null || !name) { return; }
    // Regel confirmToggle: gegen die aktuelle Liste aufloesen, der Refresh laeuft weiter
    const current = this.sensors().find(s => s.id === id);
    if (!current) { this.cancelEdit(); return; }
    this.mutate(this.api.update(id, { name, active: true }), updated => {
      this.replace(updated);
      this.cancelEdit();
    });
  }

  deactivate(sensor: BleSensorAdminResponse): void {
    const current = this.sensors().find(s => s.id === sensor.id);
    if (!current || !current.active) { return; }
    this.mutate(this.api.update(current.id, { name: current.name, active: false }), updated => this.replace(updated));
  }

  remove(sensor: BleSensorAdminResponse): void {
    const current = this.sensors().find(s => s.id === sensor.id);
    if (!current || current.active) { return; }
    if (!confirm(`Sensor „${this.displayName(current)}“ samt Historie endgültig löschen?`)) { return; }
    this.mutate(this.api.delete(current.id), () => this.sensors.update(list => list.filter(s => s.id !== current.id)));
  }

  private load(initial: boolean): void {
    this.api.list().subscribe({
      next: list => {
        this.sensors.set(list);
        this.loading.set(false);
        this.loadFailed.set(false);
      },
      error: () => {
        // Nur der Erstabruf meldet; ein fehlgeschlagener Refresh behaelt den letzten Stand.
        if (initial) {
          this.loading.set(false);
          this.loadFailed.set(true);
        }
      }
    });
  }

  private mutate<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    this.saving.set(true);
    this.errorMessage.set(null);
    request.subscribe({
      next: value => { this.saving.set(false); onSuccess(value); },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(err.error?.message ?? 'Die Aktion ist fehlgeschlagen.');
      }
    });
  }

  private replace(updated: BleSensorAdminResponse): void {
    this.sensors.update(list => list.map(s => (s.id === updated.id ? updated : s)));
  }
}
```
`admin-ble-sensors.component.html`:
```html
<div class="admin-ble-sensors">
  <h1>Bluetooth-Thermometer</h1>

  <p class="admin-ble-sensors__hint">
    Das Wandtablet meldet alle ThermoBeacon-Thermometer in Funkreichweite. Ein Sensor liefert erst
    nach dem Übernehmen Entitäten und Historie; fremde Geräte bleiben einfach in dieser Liste stehen.
    Zum Wiedererkennen ein Thermometer in die Hand nehmen — der Wert steigt binnen einer Minute.
  </p>

  @if (errorMessage()) {
    <div class="admin-ble-sensors__error" role="alert">{{ errorMessage() }}</div>
  }

  @if (loading()) {
    <p>Wird geladen …</p>
  } @else if (loadFailed()) {
    <p>Die Sensoren konnten nicht geladen werden. Bitte die Seite neu laden.</p>
  } @else if (sensors().length === 0) {
    <p>Noch kein Thermometer gesehen. Läuft die Tablet-App mit Bluetooth-Berechtigung?</p>
  } @else {
    <table class="admin-ble-sensors__table">
      <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Status</th>
          <th scope="col">Temperatur</th>
          <th scope="col">Feuchte</th>
          <th scope="col">Batterie</th>
          <th scope="col">Zuletzt gesehen</th>
          <th scope="col">Tablet</th>
          <th scope="col"><span class="admin-ble-sensors__sr-only">Aktionen</span></th>
        </tr>
      </thead>
      <tbody>
        @for (sensor of sensors(); track sensor.id) {
          <tr [class.admin-ble-sensors__row--inactive]="!sensor.active">
            <td>
              @if (editingId === sensor.id) {
                <input class="admin-ble-sensors__name-input" type="text" maxlength="100"
                       [(ngModel)]="editingName" name="name-{{ sensor.id }}" placeholder="z. B. Küche">
                <div class="admin-ble-sensors__mac">{{ sensor.mac }}</div>
              } @else {
                <div>{{ displayName(sensor) }}</div>
                @if (sensor.name) { <div class="admin-ble-sensors__mac">{{ sensor.mac }}</div> }
              }
            </td>
            <td><span class="admin-ble-sensors__badge admin-ble-sensors__badge--{{ status(sensor) }}">{{ statusLabel(sensor) }}</span></td>
            <td>{{ sensor.lastTemperature === null ? '–' : (sensor.lastTemperature | number:'1.1-1':'de') + ' °C' }}</td>
            <td>{{ sensor.lastHumidity === null ? '–' : (sensor.lastHumidity | number:'1.0-0':'de') + ' %' }}</td>
            <td>{{ sensor.batteryPercent === null ? '–' : sensor.batteryPercent + ' %' }}</td>
            <td>{{ sensor.lastSeenAt ? (sensor.lastSeenAt | date:'dd.MM. HH:mm:ss') : '–' }}</td>
            <td>{{ sensor.tabletId ?? '–' }}</td>
            <td class="admin-ble-sensors__actions">
              @if (editingId === sensor.id) {
                <button type="button" (click)="saveName()" [disabled]="saving() || !editingName.trim()">Speichern</button>
                <button type="button" class="ghost" (click)="cancelEdit()">Abbrechen</button>
              } @else if (sensor.active) {
                <button type="button" class="ghost" (click)="startEdit(sensor)" [disabled]="saving()">Umbenennen</button>
                <button type="button" class="ghost" (click)="deactivate(sensor)" [disabled]="saving()">Deaktivieren</button>
              } @else {
                <button type="button" (click)="startEdit(sensor)" [disabled]="saving()">Übernehmen</button>
                <button type="button" class="danger" (click)="remove(sensor)" [disabled]="saving()">Löschen</button>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  }
</div>
```
Prüfen, ob die Locale `de` für `DecimalPipe` registriert ist (`grep -rn "registerLocaleData\|LOCALE_ID" frontend/src/app frontend/src/main.ts`). Ist sie nicht registriert, das `:'de'`-Argument weglassen und im Spec `21.5 °C` statt `21,5 °C` erwarten.

`admin-ble-sensors.component.scss` (an `admin-network-devices.component.scss` orientieren; Klassenpräfix `admin-ble-sensors__`):
```scss
.admin-ble-sensors {
  max-width: 1100px;
  margin: 0 auto;
  padding: 1.5rem;

  &__hint { color: #555; max-width: 70ch; }
  &__error { background: #fde8e8; color: #8a1f1f; padding: 0.75rem 1rem; border-radius: 6px; margin-bottom: 1rem; }
  &__table { width: 100%; border-collapse: collapse; margin-top: 1rem;
    th, td { padding: 0.5rem 0.75rem; text-align: left; border-bottom: 1px solid #e3e3e3; vertical-align: top; }
  }
  &__row--inactive td { color: #777; }
  &__mac { font-family: monospace; font-size: 0.8rem; color: #777; }
  &__name-input { width: 12rem; }
  &__badge { display: inline-block; padding: 0.1rem 0.5rem; border-radius: 999px; font-size: 0.8rem;
    &--active { background: #e3f6e8; color: #1d6b32; }
    &--inactive { background: #eee; color: #555; }
    &--unadopted { background: #fff4d6; color: #7a5200; }
  }
  &__actions { white-space: nowrap; button + button { margin-left: 0.5rem; } }
  &__sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
}
```

- [ ] **Step 5: Route, Navi, Admin-Scope**

`app.routes.ts`, nach dem `admin/presence`-Block:
```ts
  {
    path: 'admin/ble-sensors',
    loadComponent: () => import('./pages/admin-ble-sensors/admin-ble-sensors.component')
      .then(m => m.AdminBleSensorsComponent),
    canActivate: [adminGuard],
    title: 'Bluetooth-Thermometer'
  },
```
(`title` nur, wenn die Nachbar-Einträge ebenfalls eines tragen — sonst weglassen.)

`header.component.ts`, nach der Zeile `{ path: '/admin/presence', label: 'Anwesenheit', minRole: 'ADMIN' },`:
```ts
        { path: '/admin/ble-sensors', label: 'Bluetooth-Thermometer', minRole: 'ADMIN' },
```

`styles.scss:574`: `.admin-ble-sensors` an `$admin-scopes` anhängen:
```scss
$admin-scopes: '.admin-users, .admin-tokens, .audit-log, .admin-tractive, .admin-calendar-categories, .vision, .pets-page, .admin-presence, .admin-mode-quick-access, .admin-charging, .admin-heating, .admin-agent, .admin-ble-sensors';
```

- [ ] **Step 6: Spec grün** — `npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-ble-sensors.component.spec.ts'`, Expected: 8 SUCCESS. Danach der Gesamtlauf `npm test -- --watch=false --browsers=ChromeHeadless`, Expected: nur die 3 Baseline-Fails.

- [ ] **Step 7: Commit** — `feat(frontend): Admin-Seite Bluetooth-Thermometer`

---

### Task 10: ThermoBeacon-Parser (Tablet-App)

**Files:**
- Create: `tablet-app/app/src/main/java/com/household/manager/tabletapp/ble/ThermoBeaconReading.kt`, `.../ble/ThermoBeaconParser.kt`
- Test: `tablet-app/app/src/test/java/com/household/manager/tabletapp/ble/ThermoBeaconParserTest.kt`

**Interfaces:**
- Produces: `data class ThermoBeaconReading(val mac: String, val temperature: Double, val humidity: Double, val batteryMillivolts: Int)`; `object ThermoBeaconParser { const val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"; fun parse(mac: String, manufacturerId: Int, payload: ByteArray): ThermoBeaconReading? }` — rein, ohne Android-Abhängigkeit (`ScanRecord` wird erst im Scanner aufgelöst).

- [ ] **Step 1: Failing Test**

Fixtures sind die Bytes aus der Home-Assistant-Testsuite (`thermobeacon-ble/tests/test_parser.py`); die Hersteller-ID ist dort der Dictionary-Schlüssel, der Payload die Bytes ohne die 2 Schlüsselbytes.
```kotlin
package com.household.manager.tabletapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThermoBeaconParserTest {

    private val mac = "aa:bb:cc:dd:ee:ff"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** HA-Fixture MFR_16_LEN_18: 28.31 °C, 40.44 %, 3.14 V. */
    private val mfr16 = bytes(0x00, 0x00, 0x9c, 0x0d, 0x00, 0x00, 0xfe, 0x44, 0x45, 0x0c, 0xc5, 0x01, 0x87, 0x02, 0x17, 0x00, 0x00, 0x00)

    /** HA-Fixture MFR_48: 21.56 °C, 53.56 %, 3.03 V. */
    private val mfr48 = bytes(0x00, 0x00, 0x8b, 0x01, 0x00, 0x00, 0x5d, 0x44, 0xd6, 0x0b, 0x59, 0x01, 0x59, 0x03, 0x44, 0x00, 0x0c, 0x00)

    /** HA-Fixture MFR_22: 20 Byte Payload = Metadatenpaket, keine Messwerte. */
    private val mfr21Meta = bytes(0x00, 0x00, 0xf0, 0x05, 0x00, 0x00, 0xd7, 0x6e, 0xbe, 0x01, 0x65, 0x00, 0x00, 0x00, 0xa7, 0x01, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun `parst Lanyard-Hygrometer (ID 0x10)`() {
        val reading = ThermoBeaconParser.parse(mac, 0x10, mfr16)
        assertNotNull(reading)
        assertEquals(28.3125, reading!!.temperature, 0.0001)
        assertEquals(40.4375, reading.humidity, 0.0001)
        assertEquals(3141, reading.batteryMillivolts)
        assertEquals(mac, reading.mac)
    }

    @Test
    fun `parst Smart-Hygrometer (ID 0x30)`() {
        val reading = ThermoBeaconParser.parse(mac, 0x30, mfr48)
        assertNotNull(reading)
        assertEquals(21.5625, reading!!.temperature, 0.0001)
        assertEquals(53.5625, reading.humidity, 0.0001)
        assertEquals(3030, reading.batteryMillivolts)
    }

    @Test
    fun `negative Temperatur ist vorzeichenbehaftet`() {
        val payload = mfr48.copyOf()
        payload[10] = 0xe0.toByte(); payload[11] = 0xff.toByte()   // int16 -32 / 16 = -2.0
        assertEquals(-2.0, ThermoBeaconParser.parse(mac, 0x30, payload)!!.temperature, 0.0001)
    }

    @Test
    fun `Metadatenpaket mit 22 Byte Gesamtlaenge wird verworfen`() {
        assertNull(ThermoBeaconParser.parse(mac, 0x15, mfr21Meta))
    }

    @Test
    fun `unbekannte Hersteller-ID wird verworfen`() {
        assertNull(ThermoBeaconParser.parse(mac, 0x4c, mfr16))   // Apple
    }

    @Test
    fun `falsche Laenge wird verworfen`() {
        assertNull(ThermoBeaconParser.parse(mac, 0x10, mfr16.copyOf(17)))
        assertNull(ThermoBeaconParser.parse(mac, 0x10, mfr16 + bytes(0x00)))
    }

    @Test
    fun `Feuchte ueber 100 wird verworfen`() {
        val payload = mfr48.copyOf()
        payload[12] = 0x50.toByte(); payload[13] = 0x06.toByte()   // 0x0650 = 1616 / 16 = 101
        assertNull(ThermoBeaconParser.parse(mac, 0x30, payload))
    }
}
```

- [ ] **Step 2: Fehlschlag bestätigen** — PowerShell aus `tablet-app/`: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; .\gradlew.bat :app:testDebugUnitTest --tests '*ThermoBeaconParserTest*'`, Expected: Kompilierfehler.

- [ ] **Step 3: Implementierung**

`ThermoBeaconReading.kt`:
```kotlin
package com.household.manager.tabletapp.ble

/** Ein geparstes ThermoBeacon-Advertisement. Batterie als Rohspannung; das Prozent rechnet das Backend. */
data class ThermoBeaconReading(
    val mac: String,
    val temperature: Double,
    val humidity: Double,
    val batteryMillivolts: Int
)
```

`ThermoBeaconParser.kt`:
```kotlin
package com.household.manager.tabletapp.ble

/**
 * Parser fuer das ThermoBeacon-Advertisement (SensorBlue, Brifit, Oria, ThermoPlus), nach den
 * Regeln der Home-Assistant-Integration thermobeacon-ble:
 *  - Hersteller-ID + Payload ergeben zusammen 20 Byte (22 = Metadatenpaket, wird verworfen)
 *  - Hersteller-ID muss eine bekannte Geraetekennung sein
 *  - Bytes 10..15 (gezaehlt inkl. der 2 ID-Bytes) little-endian: Spannung mV (u16), Temperatur/16 (i16), Feuchte/16 (u16)
 *  - Temperatur oder Feuchte > 100 => verwerfen
 * Reines Kotlin ohne Android-Abhaengigkeit, damit es im JVM-Unit-Test laeuft.
 */
object ThermoBeaconParser {

    const val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"

    private const val DATA_PACKET_LENGTH = 20
    private val KNOWN_MANUFACTURER_IDS = setOf(0x10, 0x11, 0x14, 0x15, 0x18, 0x1B, 0x30)

    fun parse(mac: String, manufacturerId: Int, payload: ByteArray): ThermoBeaconReading? {
        if (manufacturerId !in KNOWN_MANUFACTURER_IDS) return null
        // HA stellt die ID als 2 Byte LE vor den Payload; Gesamtlaenge muss 20 sein
        if (payload.size + 2 != DATA_PACKET_LENGTH) return null

        // Offsets im kombinierten Paket 10..15 => im Payload 8..13
        val voltage = u16(payload, 8)
        val temperature = i16(payload, 10) / 16.0
        val humidity = u16(payload, 12) / 16.0
        if (temperature > 100 || humidity > 100) return null

        return ThermoBeaconReading(mac, temperature, humidity, voltage)
    }

    private fun u16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8)

    private fun i16(b: ByteArray, offset: Int): Int = u16(b, offset).toShort().toInt()
}
```

- [ ] **Step 4: Tests grün** — derselbe Gradle-Aufruf, Expected: BUILD SUCCESSFUL, 7 Tests.

- [ ] **Step 5: Commit** — `feat(tablet-app): ThermoBeacon-Parser fuer Bluetooth-Thermometer`

---

### Task 11: Scanner, Reporter, Berechtigungen und Einstellungen (Tablet-App)

**Files:**
- Create: `.../ble/ThermoBeaconScanner.kt`, `.../ble/SensorReporter.kt`
- Modify: `tablet-app/app/src/main/AndroidManifest.xml`, `.../AppSettings.kt`, `.../SettingsActivity.kt`, `tablet-app/app/src/main/res/layout/activity_settings.xml`, `.../KioskActivity.kt`, `tablet-app/README.md`
- Test: `.../ble/SensorReporterTest.kt`

**Interfaces:**
- Consumes: `ThermoBeaconParser.parse`, `AppSettings.backendBaseUrl/tabletId/apiToken`.
- Produces: `class ThermoBeaconScanner(context, onFailure: (String) -> Unit)` mit `start()`, `stop()`, `snapshot(nowElapsedMs: Long): List<SeenReading>`; `data class SeenReading(val reading: ThermoBeaconReading, val rssi: Int, val seenAtElapsedMs: Long)`; `class SensorReporter(settings, scope, readings: (Long) -> List<SeenReading>, intervalMs = 60_000, maxAgeMs = 300_000)` mit `start()` und `buildPayload(now: Long): String?` (null = nichts zu senden); `AppSettings.bleReportingEnabled: Boolean`.

- [ ] **Step 1: Failing Test für den Reporter (reine Payload-Logik)**

```kotlin
package com.household.manager.tabletapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorReporterTest {

    private fun seen(mac: String, ageMs: Long, now: Long) = SeenReading(
        ThermoBeaconReading(mac, 21.5625, 50.0, 3030), rssi = -60, seenAtElapsedMs = now - ageMs
    )

    @Test
    fun `Payload traegt tabletId, MAC, Werte, Spannung, RSSI und Alter in Sekunden`() {
        val now = 1_000_000L
        val json = SensorReporter.buildPayload("wandtablet", listOf(seen("aa:bb:cc:dd:ee:ff", 12_000, now)), now, 300_000)
        assertEquals(
            """{"tabletId":"wandtablet","readings":[{"mac":"aa:bb:cc:dd:ee:ff","temperature":21.5625,"humidity":50.0,"batteryMillivolts":3030,"rssi":-60,"ageSeconds":12}]}""",
            json
        )
    }

    @Test
    fun `Pakete aelter als fuenf Minuten fehlen`() {
        val now = 1_000_000L
        val json = SensorReporter.buildPayload("wandtablet",
            listOf(seen("aa:bb:cc:dd:ee:ff", 301_000, now), seen("11:22:33:44:55:66", 10_000, now)), now, 300_000)!!
        assertTrue(json.contains("11:22:33:44:55:66"))
        assertTrue(!json.contains("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `leere Liste ergibt keinen Payload`() {
        assertNull(SensorReporter.buildPayload("wandtablet", emptyList(), 0, 300_000))
        assertNull(SensorReporter.buildPayload("wandtablet", listOf(seen("aa:bb:cc:dd:ee:ff", 400_000, 1_000_000)), 1_000_000, 300_000))
    }

    @Test
    fun `tabletId wird JSON-escaped`() {
        val json = SensorReporter.buildPayload("wand\"tablet", listOf(seen("aa:bb:cc:dd:ee:ff", 0, 10)), 10, 300_000)!!
        assertTrue(json.startsWith("""{"tabletId":"wand\"tablet""""))
    }
}
```

- [ ] **Step 2: Fehlschlag bestätigen** — `.\gradlew.bat :app:testDebugUnitTest --tests '*SensorReporterTest*'`.

- [ ] **Step 3: Scanner und Reporter**

`ThermoBeaconScanner.kt`:
```kotlin
package com.household.manager.tabletapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Letztes geparstes Paket eines Sensors samt Empfangszeit (elapsedRealtime) und RSSI. */
data class SeenReading(val reading: ThermoBeaconReading, val rssi: Int, val seenAtElapsedMs: Long)

/**
 * Passiver Dauerscan auf die ThermoBeacon-Service-UUID im Stromsparmodus. Der Filter laeuft im
 * Bluetooth-Chip. Der Scan wird NICHT pro Zyklus neu gestartet (Android drosselt > 5 Starts in
 * 30 s still). Fail-safe wie die Kamera: ohne Adapter/Berechtigung nur eine Warnung; ein
 * Receiver auf ACTION_STATE_CHANGED startet den Scan neu, wenn Bluetooth wieder angeht.
 */
class ThermoBeaconScanner(
    private val context: Context,
    private val onFailure: (String) -> Unit
) {

    private val seen = ConcurrentHashMap<String, SeenReading>()
    private var scanning = false
    private var receiverRegistered = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val data = record.manufacturerSpecificData ?: return
            for (i in 0 until data.size()) {
                val reading = ThermoBeaconParser.parse(result.device.address.lowercase(), data.keyAt(i), data.valueAt(i))
                    ?: continue
                seen[reading.mac] = SeenReading(reading, result.rssi, SystemClock.elapsedRealtime())
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onFailure("BLE-Scan fehlgeschlagen, Code $errorCode")
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScan()
                BluetoothAdapter.STATE_OFF -> scanning = false
            }
        }
    }

    fun start() {
        if (!receiverRegistered) {
            context.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            receiverRegistered = true
        }
        startScan()
    }

    fun stop() {
        if (receiverRegistered) {
            context.unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        adapter()?.bluetoothLeScanner?.let { if (hasScanPermission()) runCatching { it.stopScan(callback) } }
        scanning = false
    }

    fun snapshot(): List<SeenReading> = seen.values.toList()

    private fun startScan() {
        if (scanning) return
        val adapter = adapter() ?: run { onFailure("Kein Bluetooth-Adapter"); return }
        if (!adapter.isEnabled) { onFailure("Bluetooth ist ausgeschaltet"); return }
        if (!hasScanPermission()) { onFailure("Bluetooth-Scan-Berechtigung fehlt"); return }
        val scanner = adapter.bluetoothLeScanner ?: run { onFailure("Kein BLE-Scanner"); return }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(ThermoBeaconParser.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            Log.i(TAG, "BLE-Scan auf ThermoBeacon gestartet")
        } catch (ex: SecurityException) {
            onFailure("Bluetooth-Scan ohne Berechtigung: ${ex.message}")
        } catch (ex: IllegalStateException) {
            onFailure("Bluetooth-Scan nicht moeglich: ${ex.message}")
        }
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Android 10/11: Scan braucht Standort — bewusst nicht unterstuetzt (siehe Spec)
            return false
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "ThermoBeaconScanner"
    }
}
```

`SensorReporter.kt`:
```kotlin
package com.household.manager.tabletapp.ble

import android.os.SystemClock
import android.util.Log
import com.household.manager.tabletapp.AppSettings
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
 * Schickt einmal pro Minute EINE Sammelmeldung aller frisch gesehenen Sensoren an
 * POST /v1/ble-sensors/readings (Service-Token wie die Praesenz). Fehler werden nur geloggt,
 * keine Wiederholung — die naechste Minute bringt frische Werte.
 */
class SensorReporter(
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val readings: () -> List<SeenReading>,
    private val intervalMs: Long = 60_000,
    private val maxAgeMs: Long = 300_000
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                val payload = buildPayload(settings.tabletId, readings(), SystemClock.elapsedRealtime(), maxAgeMs)
                    ?: continue
                post(payload)
            }
        }
    }

    private fun post(payload: String) {
        val url = "${settings.backendBaseUrl}/v1/ble-sensors/readings"
        val builder = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType()))
        if (settings.apiToken.isNotBlank()) {
            builder.addHeader("X-API-Token", settings.apiToken)
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Backend antwortete mit ${response.code} auf $url")
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "BLE-Meldung fehlgeschlagen: ${ex.message}")
        }
    }

    companion object {
        private const val TAG = "SensorReporter"

        /** Reine Funktion fuer den Unit-Test: null, wenn nichts Frisches zu melden ist. */
        fun buildPayload(tabletId: String, seen: List<SeenReading>, nowElapsedMs: Long, maxAgeMs: Long): String? {
            val fresh = seen.filter { nowElapsedMs - it.seenAtElapsedMs <= maxAgeMs }
            if (fresh.isEmpty()) return null
            val items = fresh.joinToString(",") {
                val age = (nowElapsedMs - it.seenAtElapsedMs) / 1000
                """{"mac":"${it.reading.mac}","temperature":${it.reading.temperature},"humidity":${it.reading.humidity},""" +
                    """"batteryMillivolts":${it.reading.batteryMillivolts},"rssi":${it.rssi},"ageSeconds":$age}"""
            }
            return """{"tabletId":"${escape(tabletId)}","readings":[$items]}"""
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
```

- [ ] **Step 4: Manifest, Einstellungen, Verdrahtung**

`AndroidManifest.xml` — nach `INTERNET`:
```xml
    <!-- BLE-Scan auf ThermoBeacon-Thermometer. Ab Android 12 ohne Standort (neverForLocation);
         auf Android 10/11 braeuchte der Scan Standort und wird bewusst nicht unterstuetzt. -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="s" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```
und am `<manifest>`-Tag `xmlns:tools="http://schemas.android.com/tools"` ergänzen.

`AppSettings.kt` — neue Property und Key:
```kotlin
    /** Bluetooth-Thermometer scannen und ans Backend melden (Default an). */
    var bleReportingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLE_REPORTING, true)
        set(value) = prefs.edit().putBoolean(KEY_BLE_REPORTING, value).apply()
```
```kotlin
        const val KEY_BLE_REPORTING = "bleReportingEnabled"
```

`activity_settings.xml` — vor dem Speichern-Button:
```xml
        <CheckBox
            android:id="@+id/check_ble_reporting"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="Bluetooth-Thermometer melden" />
```

`SettingsActivity.kt` — Checkbox lesen/schreiben:
```kotlin
        val bleReporting = findViewById<android.widget.CheckBox>(R.id.check_ble_reporting)
        bleReporting.isChecked = settings.bleReportingEnabled
        // im Save-Listener:
        settings.bleReportingEnabled = bleReporting.isChecked
```

`KioskActivity.kt`:
- Imports: `com.household.manager.tabletapp.ble.SensorReporter`, `com.household.manager.tabletapp.ble.ThermoBeaconScanner`, `android.os.Build`.
- Felder:
```kotlin
    private var bleScanner: ThermoBeaconScanner? = null
    private var sensorReporter: SensorReporter? = null
    private var bleFailed = false

    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startBleScanning() else onBleFailure("Bluetooth-Berechtigung verweigert")
        }
```
- In `onCreate` nach `cameraPermission.launch(...)`:
```kotlin
        if (settings.bleReportingEnabled) {
            requestBluetoothOrStart()
        }
```
- Neue Methoden:
```kotlin
    private fun requestBluetoothOrStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onBleFailure("Android < 12: BLE-Scan braeuchte Standort, nicht unterstuetzt")
            return
        }
        bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
    }

    private fun startBleScanning() {
        val scanner = ThermoBeaconScanner(this) { reason -> runOnUiThread { onBleFailure(reason) } }
        bleScanner = scanner
        scanner.start()
        sensorReporter = SensorReporter(settings, scope, { scanner.snapshot() }).also { it.start() }
    }

    /** Fail-safe: ein Bluetooth-Problem beruehrt Dashboard und Praesenz nicht. */
    private fun onBleFailure(reason: String) {
        if (!bleFailed) {
            Log.w(TAG, "Bluetooth-Thermometer nicht nutzbar: $reason")
        }
        bleFailed = true
    }
```
- In `onDestroy` vor `scope.cancel()`: `bleScanner?.stop()`.

`tablet-app/README.md` — im Installationsabschnitt nach der Kamera-Berechtigung: „Beim ersten Start zusätzlich die Bluetooth-Berechtigung („Geräte in der Nähe") erteilen; danach erscheinen ThermoBeacon-Thermometer in Funkreichweite im Backend unter Admin → Bluetooth-Thermometer. Schalter „Bluetooth-Thermometer melden" in den Einstellungen."

- [ ] **Step 5: Tests und Build** — `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, Expected: BUILD SUCCESSFUL (SensorReporterTest 4 Tests, ParserTest 7 Tests, bestehende Tests grün), APK unter `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 6: Commit** — `feat(tablet-app): BLE-Scan und Sammelmeldung der ThermoBeacon-Thermometer`

---

### Task 12: Dokumentation (CLAUDE.md) und Gesamtlauf

**Files:**
- Modify: `CLAUDE.md` (neuer Abschnitt nach „Heizungssteuerung"), Eintrag `EntitySource.BLE` ist schon dokumentiert über den Abschnitt

- [ ] **Step 1: CLAUDE.md-Abschnitt**

```markdown
### Bluetooth-Thermometer (SensorBlue/ThermoBeacon über das Wandtablet)
- Spec: `docs/superpowers/specs/2026-10-13-ble-thermometer-design.md`, Plan `docs/superpowers/plans/2026-10-13-ble-thermometer.md`. Backend-Modul `blesensor/`, Tabellen `ble_sensor` (eine Zeile je gesehener MAC) und `ble_sensor_reading` (Changesets `20261013-0066/-0067/-0068`, nie gegen die echte DB gelaufen — Backup vor dem Deploy). Der Server hat kein Bluetooth (Docker-Bridge), Empfänger ist die Kiosk-App: passiver Dauerscan mit Systemfilter auf Service-UUID `fff0` (`ThermoBeaconScanner`), Parser nach den Regeln der Home-Assistant-Integration `thermobeacon-ble` (`ThermoBeaconParser`, 20 Byte Daten / 22 Byte Metadaten, Bytes 10–15 = Spannung mV, Temperatur/16, Feuchte/16), **eine** Sammelmeldung pro Minute an `POST /v1/ble-sensors/readings` (SERVICE-Authority, derselbe Token wie die Präsenz)
- **Erst freischalten:** eine neue MAC entsteht inaktiv und ohne Namen; Historie und Entitäten gibt es erst nach „Übernehmen" auf der Admin-Seite `admin/ble-sensors` (ADMIN, auch Lesen — die Liste trägt MACs fremder Geräte in Funkreichweite). Letztwerte stehen in `ble_sensor`, damit man beim Übernehmen am steigenden Wert erkennt, welches Thermometer man in der Hand hält
- Entitäten `sensor.ble_<mac12>_temperature` / `_humidity` (`EntitySource.BLE`, `BleSensorEntityMapper.entityId` ist die einzige Id-Definition, Id hängt an der MAC — Umbenennen ist für Flows gefahrlos). Attribute `unit`, `batteryPercent` (Spannungskurve wie HA, `BleBatteryEstimator`), `batteryMillivolts`, `rssi`, `tabletId`. **Kein `deviceClass`** (siehe Blink). Historie nur bei Änderung oder alle 5 min (`BleSensorReadingRecorder`), **keine Retention** (wie `zigbee_measurement`)
- `BleSensorAvailabilityWatchdog` (minütlich): aktiver Sensor still ≥ `ble-sensor.stale-after-minutes` (10) ⇒ beide Entitäten `unavailable` **mit erhaltenen Attributen**, genau einmal (eine schon `unavailable` Entität wird nicht erneut gemeldet). Deaktivieren meldet einmal `unavailable`; Löschen nur inaktiv (409), entfernt Zeile, Historie (Kaskade) und Entitäten. Ein Trigger auf `value: "unavailable"` kann nie feuern
- Temperaturansicht: vierte Quelle `ble:<id>` in `TemperatureSeriesService` (nur aktive Sensoren, Aktuellwerte aus den Letztwerten der Zeile, `entityId` aus dem Mapper — Test hält Mapper und `temperatureEntityId` gleich). Außenfühler: Entity-ID in beide Listen (`ventilation.outdoor-sensor-entity-ids`, `OUTDOOR_SENSOR_ENTITY_IDS`)
- Tablet-App: Berechtigung `BLUETOOTH_SCAN` mit `neverForLocation` (+ `BLUETOOTH_CONNECT`), **Android 10/11 scannt nicht** (bräuchte Standort, bewusst nicht unterstützt); Schalter „Bluetooth-Thermometer melden" in den Einstellungen; fail-safe wie die Kamera. Der Scan wird nie pro Zyklus neu gestartet (Android drosselt > 5 Starts/30 s still)
- **Realtest offen** (nie gegen echte Hardware gelaufen): Sendeintervall der Sensoren, ob der Chip des Lenovo Tab M11 den UUID-Scanfilter umsetzt (sonst ungefiltert scannen und in der App filtern), ob der Scan bei Soft-Off durchläuft, feste MAC. Kalibrierung aus der SensorBlue-App wirkt nicht (nur Display)
- Audit `ble-sensor.update/delete`, Security-Richtungen in `SecurityRulesTest`. Rollout: Deploy → APK installieren, Bluetooth-Berechtigung erteilen → Admin-Seite: zwei Sensoren übernehmen → Temperaturansicht prüfen
```
Außerdem in der Feature-Liste am Anfang von CLAUDE.md unter „Current Features" eine Zeile: `- **Bluetooth-Thermometer**: SensorBlue/ThermoBeacon-Sensoren über das Wandtablet, Admin-Freischaltung, Temperaturhistorie`.

- [ ] **Step 2: Gesamtläufe**

- Backend: `mvn -q test` aus `backend/` — Expected: nur die zwei bekannten DB-Fails (`contextLoads`, `HealthControllerTest`).
- Frontend: `npm test -- --watch=false --browsers=ChromeHeadless` — Expected: nur die 3 Baseline-Fails. Zusätzlich `ng build --configuration production` — Expected: Erfolg (Budget-Warnungen des Dashboards sind bekannt).
- Tablet: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit** — `docs: Bluetooth-Thermometer in CLAUDE.md dokumentiert`

---

## Self-Review (durchgeführt beim Schreiben)

- **Spec-Abdeckung:** Berechtigungen/Scanner/Parser/Reporter/Einstellung (Task 10, 11); Tabellen + Schreibregel (1, 3); Ingest inkl. Plausibilität und Fehlerisolierung (4); Entitäten ohne deviceClass, Batteriekurve (2); Watchdog (5); Übernahme/Löschen/Audit (6); API + Security-Reihenfolge (7); vierte Quelle + Frontend-Typ (8); Admin-Seite, Route, Navi, `$admin-scopes` (9); Rollout/Grenzen/Realtest in CLAUDE.md (12). Retention bewusst keine. Keine Lücke gefunden.
- **Typkonsistenz:** `BleSensorDtos.ReadingsRequest/ReadingItem/SensorAdminResponse/SensorUpdateRequest` werden in Task 4, 6, 7 gleich benannt; `BleSensorEntityMapper.entityId(String, Measurement)` statisch in 2, 5, 6, 8; `SeenReading` in 11 für Scanner und Reporter; `BleMacs.normalize/entityRef` in 2, 4, 8.
- **Review Focus:** 1 → Task 4 (`neueMacWirdInaktivUndOhneNamenAngelegt`), 2 → Task 4 (`inaktiverBestandssensorBekommtNurLetztwerte`) und Task 6 (`deaktivierenMeldetEinmalUnavailable`), 3 → Task 5 (`bereitsUnavailableWirdNichtErneutGemeldet`), 4 → Task 4 (`negativesAlterWirdAufNullGeklemmt`), 5 → Task 6 (`aktivierenOhneNamenIstVierhundert`).
- **Offene Prüfpunkte für den Implementierer** (keine Platzhalter, sondern Verzweigungen, die vom Repo-Zustand abhängen): `SecurityRulesTest`-Mock-Mechanik (Task 7, Step 1), `DecimalPipe`-Locale (Task 9, Step 4), `title` in Routen (Task 9, Step 5), Icon-Zuordnung je `TemperatureSource` (Task 8, Step 4).
