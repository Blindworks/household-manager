# Zigbee MQTT Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zigbee-Sensoren (Klima, Kontakt, Bewegung, Wasserleck, Helligkeit) lesend erfassen — über zigbee2mqtt + eigenen Mosquitto-Broker (beide im Docker), konsumiert vom Spring-Backend per HiveMQ-MQTT-Client, mit Live-Anzeige (SSE) und historischer Speicherung, dargestellt auf einer Angular-Seite.

**Architecture:** zigbee2mqtt liest den seriellen Port der ZBBridge (TCP) und publiziert sauberes JSON an Mosquitto. Das Backend abonniert `zigbee2mqtt/#`, parst die Nachrichten in ein generisches Geräte-/Messwert-Modell, persistiert sie und streamt sie live per SSE. Das Frontend zeigt Live-Kacheln und ECharts-Verläufe.

**Tech Stack:** Spring Boot 3.4.1 / Java 21, HiveMQ MQTT Client (bereits vorhanden), JPA/MariaDB, Liquibase, Angular 19 (standalone) + ngx-echarts, Docker Compose (eclipse-mosquitto, koenkk/zigbee2mqtt).

---

## Wichtige Plan-Entscheidung (Verfeinerung der Spec)

Aus einem zigbee2mqtt-**Wert-Topic** (`zigbee2mqtt/<friendly_name>`) ist nur der **Friendly Name** verlässlich ableitbar, nicht die IEEE-Adresse (die steht nur im retained Topic `zigbee2mqtt/bridge/devices`). Daher ist der **eindeutige Geschäftsschlüssel `friendly_name`** (NOT NULL, UNIQUE); `ieee_address` ist nullable und bleibt für eine spätere Anreicherung reserviert. Das ist eine bewusste Abweichung vom Spec-Wortlaut „zb_address/ieee unique" und erhöht die Korrektheit.

## Dateistruktur (Überblick)

**Docker / Infra**
- `docker-compose.yml` (modify) — Services `mosquitto`, `zigbee2mqtt`, Backend-Env, Volumes
- `mosquitto/config/mosquitto.conf` (create)
- `zigbee2mqtt/data/configuration.yaml` (create)

**Backend — neues Package `com.household.manager.zigbee`**
- `model/MeasurementType.java` (create) — Enum + Default-Einheit
- `model/entity/ZigbeeDevice.java` (create)
- `model/entity/ZigbeeMeasurement.java` (create)
- `repository/ZigbeeDeviceRepository.java` (create)
- `repository/ZigbeeMeasurementRepository.java` (create)
- `parser/ParsedZigbeeMessage.java`, `parser/ZigbeeMeasurementValue.java` (create) — Parser-Records
- `service/ZigbeeMessageParser.java` (create) — reine Parselogik (TDD-Kern)
- `service/ZigbeeReadingService.java` (create) — Upsert + Persistenz + Broadcast
- `service/ZigbeeLiveService.java` (create) — SSE-Broadcast (push)
- `config/ZigbeeMqttProperties.java` (create) — `@ConfigurationProperties`
- `config/ZigbeeMqttConfig.java` (create) — HiveMQ-Client, Subscribe, Wiring
- `dto/ZigbeeDeviceResponse.java`, `dto/ZigbeeMeasurementResponse.java`, `dto/ZigbeeLiveResponse.java` (create)
- `controller/ZigbeeController.java` (create)
- `backend/src/main/resources/db/changelog/changes/20260621-0016-create-zigbee-tables.xml` (create)
- `backend/src/main/resources/db/changelog/db.changelog-master.xml` (modify) — include neuer Changeset
- `backend/src/main/resources/application.properties` (modify) — Zigbee-MQTT-Properties
- Tests:
  - `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeMessageParserTest.java`
  - `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeReadingServiceTest.java`

**Frontend**
- `frontend/src/app/models/zigbee.model.ts` (create)
- `frontend/src/app/services/zigbee.service.ts` (create) — REST
- `frontend/src/app/services/zigbee-live.service.ts` (create) — SSE
- `frontend/src/app/pages/zigbee/zigbee.component.ts` / `.html` / `.scss` (create)
- `frontend/src/app/app.routes.ts` (modify) — Route `zigbee`
- `frontend/src/app/components/header/header.component.ts/.html` (modify) — Nav-Eintrag (falls Navliste dort gepflegt wird)

---

## Task 1: Mosquitto-Broker im Docker

**Files:**
- Create: `mosquitto/config/mosquitto.conf`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Mosquitto-Konfiguration anlegen**

Create `mosquitto/config/mosquitto.conf`:

```conf
listener 1883
allow_anonymous false
password_file /mosquitto/config/passwd
persistence true
persistence_location /mosquitto/data/
log_dest stdout
```

- [ ] **Step 2: Passwortdatei erzeugen**

Run (erzeugt User `household` mit Passwort `change-me-zigbee`, danach Passwort anpassen):

```bash
docker run --rm -v "$(pwd)/mosquitto/config:/mosquitto/config" eclipse-mosquitto:2 \
  mosquitto_passwd -b -c /mosquitto/config/passwd household change-me-zigbee
```

Expected: Datei `mosquitto/config/passwd` existiert und enthält eine Zeile `household:$7$...`.

- [ ] **Step 3: Mosquitto-Service + Volumes in docker-compose.yml ergänzen**

Modify `docker-compose.yml` — füge unter `services:` (vor `networks:`) hinzu:

```yaml
  mosquitto:
    image: eclipse-mosquitto:2
    restart: unless-stopped
    volumes:
      - ./mosquitto/config:/mosquitto/config
      - mosquitto_data:/mosquitto/data
      - mosquitto_log:/mosquitto/log
    ports:
      - "1883:1883"
    networks:
      - app_net
```

Und erweitere den `networks:`-Block am Dateiende um einen `volumes:`-Block (neuer Top-Level-Key):

```yaml
volumes:
  mosquitto_data:
  mosquitto_log:
  zigbee2mqtt_data:
```

- [ ] **Step 4: Broker testen**

Run:

```bash
docker compose up -d mosquitto
docker compose logs --no-color mosquitto | tail -n 20
```

Expected: Log zeigt `mosquitto version 2.x running`, keine Fehler. Port 1883 offen.

- [ ] **Step 5: Commit**

```bash
git add mosquitto/config/mosquitto.conf docker-compose.yml
git commit -m "feat(docker): add Mosquitto MQTT broker service"
```

---

## Task 2: zigbee2mqtt im Docker

**Files:**
- Create: `zigbee2mqtt/data/configuration.yaml`
- Modify: `docker-compose.yml`

- [ ] **Step 1: zigbee2mqtt-Konfiguration anlegen**

Create `zigbee2mqtt/data/configuration.yaml` (Passwort identisch zu Task 1; `network_key` wird beim ersten Start automatisch generiert, wenn `GENERATE` gesetzt ist):

```yaml
homeassistant: false
permit_join: false
mqtt:
  base_topic: zigbee2mqtt
  server: mqtt://mosquitto:1883
  user: household
  password: change-me-zigbee
serial:
  port: tcp://192.168.1.121:8888
  adapter: ezsp
frontend:
  port: 8081
advanced:
  log_level: info
  network_key: GENERATE
```

- [ ] **Step 2: zigbee2mqtt-Service in docker-compose.yml ergänzen**

Modify `docker-compose.yml` — unter `services:` hinzufügen:

```yaml
  zigbee2mqtt:
    image: koenkk/zigbee2mqtt
    restart: unless-stopped
    volumes:
      - ./zigbee2mqtt/data:/app/data
    ports:
      - "8081:8081"
    environment:
      TZ: Europe/Berlin
    depends_on:
      - mosquitto
    networks:
      - app_net
```

- [ ] **Step 3: Hinweis-Datei für den Cutover dokumentieren**

Hänge ans Ende von `zigbee2mqtt/data/configuration.yaml` keinen Code an, sondern halte im Commit-Body fest: zigbee2mqtt kann die Bridge erst übernehmen, wenn Home Assistant gestoppt ist (serieller Port ist exklusiv). Erst danach `docker compose up -d zigbee2mqtt` und Pairing prüfen.

- [ ] **Step 4: Commit**

```bash
git add zigbee2mqtt/data/configuration.yaml docker-compose.yml
git commit -m "feat(docker): add zigbee2mqtt service (replaces HA ZHA after cutover)"
```

---

## Task 3: MeasurementType-Enum

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/model/MeasurementType.java`

- [ ] **Step 1: Enum implementieren**

Create the file:

```java
package com.household.manager.zigbee.model;

import lombok.Getter;

/**
 * Typ einer Zigbee-Messgröße samt zugehöriger Standard-Einheit.
 */
@Getter
public enum MeasurementType {

    TEMPERATURE("°C"),
    HUMIDITY("%"),
    PRESSURE("hPa"),
    CONTACT(""),
    OCCUPANCY(""),
    ILLUMINANCE("lx"),
    WATER_LEAK("");

    private final String defaultUnit;

    MeasurementType(String defaultUnit) {
        this.defaultUnit = defaultUnit;
    }
}
```

- [ ] **Step 2: Kompilieren**

Run: `cd backend && mvn -q -o compile` (bzw. ohne `-o`, falls Offline-Cache fehlt)
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/model/MeasurementType.java
git commit -m "feat(zigbee): add MeasurementType enum"
```

---

## Task 4: Entities (ZigbeeDevice, ZigbeeMeasurement)

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/model/entity/ZigbeeDevice.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/model/entity/ZigbeeMeasurement.java`

- [ ] **Step 1: ZigbeeDevice-Entity anlegen**

```java
package com.household.manager.zigbee.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Register eines bekannten Zigbee-Geräts. Schlüssel ist der zigbee2mqtt friendly name.
 */
@Entity
@Table(name = "zigbee_device")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "friendly_name", nullable = false, unique = true)
    private String friendlyName;

    @Column(name = "ieee_address")
    private String ieeeAddress;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "model")
    private String model;

    @Column(name = "last_battery_percent")
    private Integer lastBatteryPercent;

    @Column(name = "last_link_quality")
    private Integer lastLinkQuality;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: ZigbeeMeasurement-Entity anlegen**

```java
package com.household.manager.zigbee.model.entity;

import com.household.manager.zigbee.model.MeasurementType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ein einzelner Zigbee-Messwert (generisch über alle Sensortypen).
 */
@Entity
@Table(name = "zigbee_measurement",
        indexes = @Index(name = "idx_zigbee_measurement_device_type_time",
                columnList = "device_id, measurement_type, measured_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private ZigbeeDevice device;

    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_type", nullable = false, length = 32)
    private MeasurementType measurementType;

    @Column(name = "value", nullable = false, precision = 12, scale = 3)
    private BigDecimal value;

    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/model/entity/
git commit -m "feat(zigbee): add ZigbeeDevice and ZigbeeMeasurement entities"
```

---

## Task 5: Liquibase-Changeset

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260621-0016-create-zigbee-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Changeset-Datei anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260621-0016" author="household-manager">
        <comment>Create zigbee_device and zigbee_measurement tables</comment>

        <createTable tableName="zigbee_device">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="friendly_name" type="VARCHAR(128)">
                <constraints nullable="false" unique="true"/>
            </column>
            <column name="ieee_address" type="VARCHAR(32)"/>
            <column name="device_type" type="VARCHAR(64)"/>
            <column name="model" type="VARCHAR(128)"/>
            <column name="last_battery_percent" type="INT"/>
            <column name="last_link_quality" type="INT"/>
            <column name="last_seen" type="TIMESTAMP"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createTable tableName="zigbee_measurement">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="device_id" type="BIGINT">
                <constraints nullable="false"
                             foreignKeyName="fk_zigbee_measurement_device"
                             references="zigbee_device(id)"/>
            </column>
            <column name="measurement_type" type="VARCHAR(32)">
                <constraints nullable="false"/>
            </column>
            <column name="value" type="DECIMAL(12,3)">
                <constraints nullable="false"/>
            </column>
            <column name="unit" type="VARCHAR(16)"/>
            <column name="measured_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_zigbee_measurement_device_type_time" tableName="zigbee_measurement">
            <column name="device_id"/>
            <column name="measurement_type"/>
            <column name="measured_at"/>
        </createIndex>

        <rollback>
            <dropTable tableName="zigbee_measurement"/>
            <dropTable tableName="zigbee_device"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Master-Changelog erweitern**

Modify `backend/src/main/resources/db/changelog/db.changelog-master.xml` — vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Zigbee Sensors Feature -->
    <include file="db/changelog/changes/20260621-0016-create-zigbee-tables.xml"/>
```

- [ ] **Step 3: Schema-Validierung prüfen**

Run: `cd backend && mvn -q test -Dtest=NoExistingTestThatMatters 2>/dev/null; mvn -q spring-boot:run` lokal nur falls DB verfügbar. Alternativ: `mvn -q compile` und Review.
Expected: Keine Liquibase-/Hibernate-`validate`-Fehler beim nächsten Start (Entities passen zur Tabelle).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat(zigbee): add Liquibase changeset for zigbee tables"
```

---

## Task 6: Repositories

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/repository/ZigbeeDeviceRepository.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/repository/ZigbeeMeasurementRepository.java`

- [ ] **Step 1: ZigbeeDeviceRepository**

```java
package com.household.manager.zigbee.repository;

import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ZigbeeDeviceRepository extends JpaRepository<ZigbeeDevice, Long> {

    Optional<ZigbeeDevice> findByFriendlyName(String friendlyName);
}
```

- [ ] **Step 2: ZigbeeMeasurementRepository**

```java
package com.household.manager.zigbee.repository;

import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ZigbeeMeasurementRepository extends JpaRepository<ZigbeeMeasurement, Long> {

    List<ZigbeeMeasurement> findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            Long deviceId, MeasurementType measurementType, LocalDateTime from, LocalDateTime to);

    List<ZigbeeMeasurement> findByDeviceIdOrderByMeasuredAtAsc(Long deviceId);
}
```

- [ ] **Step 3: Kompilieren & Commit**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS.

```bash
git add backend/src/main/java/com/household/manager/zigbee/repository/
git commit -m "feat(zigbee): add device and measurement repositories"
```

---

## Task 7: Parser-Records

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/parser/ZigbeeMeasurementValue.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/parser/ParsedZigbeeMessage.java`

- [ ] **Step 1: ZigbeeMeasurementValue (Record)**

```java
package com.household.manager.zigbee.parser;

import com.household.manager.zigbee.model.MeasurementType;

import java.math.BigDecimal;

/**
 * Ein einzelner geparster Messwert vor der Persistenz.
 */
public record ZigbeeMeasurementValue(MeasurementType type, BigDecimal value, String unit) {
}
```

- [ ] **Step 2: ParsedZigbeeMessage (Record)**

```java
package com.household.manager.zigbee.parser;

import java.util.List;

/**
 * Ergebnis des Parsens einer zigbee2mqtt-Gerätenachricht.
 */
public record ParsedZigbeeMessage(
        String friendlyName,
        Integer batteryPercent,
        Integer linkQuality,
        List<ZigbeeMeasurementValue> measurements
) {
}
```

- [ ] **Step 3: Kompilieren & Commit**

Run: `cd backend && mvn -q compile`

```bash
git add backend/src/main/java/com/household/manager/zigbee/parser/
git commit -m "feat(zigbee): add parser result records"
```

---

## Task 8: ZigbeeMessageParser (TDD-Kern)

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeMessageParserTest.java`

- [ ] **Step 1: Failing test schreiben**

Create the test file:

```java
package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeMessageParserTest {

    private ZigbeeMessageParser parser;

    @BeforeEach
    void setUp() {
        parser = new ZigbeeMessageParser(new ObjectMapper());
    }

    private BigDecimal valueOf(ParsedZigbeeMessage msg, MeasurementType type) {
        return msg.measurements().stream()
                .filter(m -> m.type() == type)
                .map(ZigbeeMeasurementValue::value)
                .findFirst().orElse(null);
    }

    @Test
    void parsesClimateSensor() {
        String payload = "{\"battery\":90,\"humidity\":55.3,\"linkquality\":120,\"pressure\":1013.2,\"temperature\":21.5,\"voltage\":3000}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Wohnzimmer-Klima", payload);

        assertThat(result).isPresent();
        ParsedZigbeeMessage msg = result.get();
        assertThat(msg.friendlyName()).isEqualTo("Wohnzimmer-Klima");
        assertThat(msg.batteryPercent()).isEqualTo(90);
        assertThat(msg.linkQuality()).isEqualTo(120);
        assertThat(valueOf(msg, MeasurementType.TEMPERATURE)).isEqualByComparingTo("21.5");
        assertThat(valueOf(msg, MeasurementType.HUMIDITY)).isEqualByComparingTo("55.3");
        assertThat(valueOf(msg, MeasurementType.PRESSURE)).isEqualByComparingTo("1013.2");
    }

    @Test
    void parsesContactSensorBooleanAsZeroOne() {
        String payload = "{\"battery\":100,\"contact\":false,\"linkquality\":80}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Haustuer", payload);

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.CONTACT)).isEqualByComparingTo("0");
    }

    @Test
    void parsesMotionAndIlluminance() {
        String payload = "{\"battery\":75,\"illuminance\":12,\"linkquality\":60,\"occupancy\":true}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Bewegung", payload);

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.OCCUPANCY)).isEqualByComparingTo("1");
        assertThat(valueOf(result.get(), MeasurementType.ILLUMINANCE)).isEqualByComparingTo("12");
    }

    @Test
    void parsesWaterLeak() {
        String payload = "{\"battery\":88,\"linkquality\":40,\"water_leak\":true}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Keller-Wasser", payload);

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.WATER_LEAK)).isEqualByComparingTo("1");
    }

    @Test
    void ignoresAvailabilityTopic() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Haustuer/availability", "online");
        assertThat(result).isEmpty();
    }

    @Test
    void ignoresBridgeTopics() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/bridge/state", "{\"state\":\"online\"}");
        assertThat(result).isEmpty();
    }

    @Test
    void ignoresMalformedPayload() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Defekt", "not-json");
        assertThat(result).isEmpty();
    }

    @Test
    void ignoresMessageWithNoUsableFields() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Leer", "{\"voltage\":3000,\"update\":{\"state\":\"idle\"}}");
        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `cd backend && mvn -q -Dtest=ZigbeeMessageParserTest test`
Expected: FAIL — `ZigbeeMessageParser` existiert noch nicht (Kompilierfehler).

- [ ] **Step 3: Parser implementieren**

Create `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java`:

```java
package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parst zigbee2mqtt-Wert-Topics (zigbee2mqtt/&lt;friendly_name&gt;) in {@link ParsedZigbeeMessage}.
 * Steuer-/Meta-Topics (bridge/*, /availability, /set, /get) werden ignoriert.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeMessageParser {

    private static final String TOPIC_PREFIX = "zigbee2mqtt/";

    /** zigbee2mqtt-Feldname -> Messgröße. */
    private static final Map<String, MeasurementType> FIELD_TYPES = new LinkedHashMap<>();

    static {
        FIELD_TYPES.put("temperature", MeasurementType.TEMPERATURE);
        FIELD_TYPES.put("humidity", MeasurementType.HUMIDITY);
        FIELD_TYPES.put("pressure", MeasurementType.PRESSURE);
        FIELD_TYPES.put("contact", MeasurementType.CONTACT);
        FIELD_TYPES.put("occupancy", MeasurementType.OCCUPANCY);
        FIELD_TYPES.put("illuminance", MeasurementType.ILLUMINANCE);
        FIELD_TYPES.put("illuminance_lux", MeasurementType.ILLUMINANCE);
        FIELD_TYPES.put("water_leak", MeasurementType.WATER_LEAK);
    }

    private final ObjectMapper objectMapper;

    public Optional<ParsedZigbeeMessage> parse(String topic, String payload) {
        if (!isDeviceTopic(topic)) {
            return Optional.empty();
        }
        String friendlyName = topic.substring(TOPIC_PREFIX.length());

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.debug("Zigbee payload not parseable for topic {}: {}", topic, ex.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        Integer battery = intOrNull(root, "battery");
        Integer linkQuality = intOrNull(root, "linkquality");

        List<ZigbeeMeasurementValue> measurements = new ArrayList<>();
        for (Map.Entry<String, MeasurementType> entry : FIELD_TYPES.entrySet()) {
            JsonNode node = root.get(entry.getKey());
            BigDecimal value = toDecimal(node);
            if (value == null) {
                continue;
            }
            MeasurementType type = entry.getValue();
            measurements.add(new ZigbeeMeasurementValue(type, value, type.getDefaultUnit()));
        }

        if (measurements.isEmpty() && battery == null && linkQuality == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedZigbeeMessage(friendlyName, battery, linkQuality, measurements));
    }

    private boolean isDeviceTopic(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
            return false;
        }
        String rest = topic.substring(TOPIC_PREFIX.length());
        return !rest.isEmpty() && !rest.contains("/") && !rest.equals("bridge");
    }

    private BigDecimal toDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return null;
    }

    private Integer intOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node != null && node.isNumber()) ? node.asInt() : null;
    }
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

Run: `cd backend && mvn -q -Dtest=ZigbeeMessageParserTest test`
Expected: PASS (8 Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java \
        backend/src/test/java/com/household/manager/zigbee/service/ZigbeeMessageParserTest.java
git commit -m "feat(zigbee): add MQTT message parser with tests"
```

---

## Task 9: Live-DTO + ZigbeeLiveService

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeLiveResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeLiveService.java`

- [ ] **Step 1: Live-DTO anlegen**

```java
package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.zigbee.model.MeasurementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Live-Event eines eingetroffenen Zigbee-Messwerts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeLiveResponse {

    private String friendlyName;
    private MeasurementType measurementType;
    private BigDecimal value;
    private String unit;
    private Integer batteryPercent;
    private Integer linkQuality;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime measuredAt;
}
```

- [ ] **Step 2: ZigbeeLiveService anlegen (push-basiert, kein Polling)**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.dto.ZigbeeLiveResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verteilt eingetroffene Zigbee-Messwerte per SSE an verbundene Clients.
 * Push-getrieben aus dem MQTT-Empfang; kein eigener Scheduler.
 */
@Service
@Slf4j
public class ZigbeeLiveService {

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(ZigbeeLiveResponse event) {
        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("live").data(event));
            } catch (Exception ex) {
                emitters.remove(emitter);
            }
        });
    }
}
```

- [ ] **Step 3: Kompilieren & Commit**

Run: `cd backend && mvn -q compile`

```bash
git add backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeLiveResponse.java \
        backend/src/main/java/com/household/manager/zigbee/service/ZigbeeLiveService.java
git commit -m "feat(zigbee): add live SSE service and DTO"
```

---

## Task 10: ZigbeeReadingService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeReadingService.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeReadingServiceTest.java`

- [ ] **Step 1: Failing test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import com.household.manager.zigbee.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.repository.ZigbeeMeasurementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ZigbeeReadingServiceTest {

    @Mock private ZigbeeDeviceRepository deviceRepository;
    @Mock private ZigbeeMeasurementRepository measurementRepository;
    @Mock private ZigbeeLiveService liveService;

    @InjectMocks private ZigbeeReadingService service;

    private ParsedZigbeeMessage climateMessage;

    @BeforeEach
    void setUp() {
        climateMessage = new ParsedZigbeeMessage(
                "Wohnzimmer-Klima", 90, 120,
                List.of(new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("21.5"), "°C")));
    }

    @Test
    void createsDeviceOnFirstMessage() {
        when(deviceRepository.findByFriendlyName("Wohnzimmer-Klima")).thenReturn(Optional.empty());
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        ArgumentCaptor<ZigbeeDevice> captor = ArgumentCaptor.forClass(ZigbeeDevice.class);
        verify(deviceRepository).save(captor.capture());
        ZigbeeDevice saved = captor.getValue();
        assertThat(saved.getFriendlyName()).isEqualTo("Wohnzimmer-Klima");
        assertThat(saved.getLastBatteryPercent()).isEqualTo(90);
        assertThat(saved.getLastLinkQuality()).isEqualTo(120);
        assertThat(saved.getLastSeen()).isNotNull();
    }

    @Test
    void updatesExistingDevice() {
        ZigbeeDevice existing = ZigbeeDevice.builder()
                .id(1L).friendlyName("Wohnzimmer-Klima").lastBatteryPercent(50).build();
        when(deviceRepository.findByFriendlyName("Wohnzimmer-Klima")).thenReturn(Optional.of(existing));
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        assertThat(existing.getLastBatteryPercent()).isEqualTo(90);
        verify(deviceRepository, never()).save(argThat(d -> d.getId() == null));
    }

    @Test
    void persistsMeasurementWithDeviceUnitAndType() {
        when(deviceRepository.findByFriendlyName(any())).thenReturn(Optional.empty());
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        ArgumentCaptor<ZigbeeMeasurement> captor = ArgumentCaptor.forClass(ZigbeeMeasurement.class);
        verify(measurementRepository).save(captor.capture());
        ZigbeeMeasurement m = captor.getValue();
        assertThat(m.getMeasurementType()).isEqualTo(MeasurementType.TEMPERATURE);
        assertThat(m.getValue()).isEqualByComparingTo("21.5");
        assertThat(m.getUnit()).isEqualTo("°C");
        assertThat(m.getMeasuredAt()).isNotNull();
    }

    @Test
    void broadcastsEachMeasurementLive() {
        when(deviceRepository.findByFriendlyName(any())).thenReturn(Optional.empty());
        when(deviceRepository.save(any(ZigbeeDevice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.record(climateMessage);

        verify(liveService, times(1)).broadcast(any());
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `cd backend && mvn -q -Dtest=ZigbeeReadingServiceTest test`
Expected: FAIL — `ZigbeeReadingService` existiert noch nicht.

- [ ] **Step 3: Service implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.dto.ZigbeeLiveResponse;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeMeasurement;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import com.household.manager.zigbee.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.repository.ZigbeeMeasurementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Aktualisiert das Geräte-Register, persistiert Messwerte und broadcastet sie live.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZigbeeReadingService {

    private final ZigbeeDeviceRepository deviceRepository;
    private final ZigbeeMeasurementRepository measurementRepository;
    private final ZigbeeLiveService liveService;

    @Transactional
    public void record(ParsedZigbeeMessage message) {
        LocalDateTime now = LocalDateTime.now();
        ZigbeeDevice device = upsertDevice(message, now);

        for (ZigbeeMeasurementValue value : message.measurements()) {
            ZigbeeMeasurement measurement = ZigbeeMeasurement.builder()
                    .device(device)
                    .measurementType(value.type())
                    .value(value.value())
                    .unit(value.unit())
                    .measuredAt(now)
                    .build();
            measurementRepository.save(measurement);

            liveService.broadcast(ZigbeeLiveResponse.builder()
                    .friendlyName(device.getFriendlyName())
                    .measurementType(value.type())
                    .value(value.value())
                    .unit(value.unit())
                    .batteryPercent(device.getLastBatteryPercent())
                    .linkQuality(device.getLastLinkQuality())
                    .measuredAt(now)
                    .build());
        }
    }

    private ZigbeeDevice upsertDevice(ParsedZigbeeMessage message, LocalDateTime now) {
        ZigbeeDevice device = deviceRepository.findByFriendlyName(message.friendlyName())
                .orElseGet(() -> ZigbeeDevice.builder().friendlyName(message.friendlyName()).build());

        if (message.batteryPercent() != null) {
            device.setLastBatteryPercent(message.batteryPercent());
        }
        if (message.linkQuality() != null) {
            device.setLastLinkQuality(message.linkQuality());
        }
        device.setLastSeen(now);
        return deviceRepository.save(device);
    }
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

Run: `cd backend && mvn -q -Dtest=ZigbeeReadingServiceTest test`
Expected: PASS (4 Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/service/ZigbeeReadingService.java \
        backend/src/test/java/com/household/manager/zigbee/service/ZigbeeReadingServiceTest.java
git commit -m "feat(zigbee): add reading service with upsert, persistence and live broadcast"
```

---

## Task 11: MQTT-Properties + ZigbeeMqttConfig (Wiring)

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttProperties.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Properties-Klasse anlegen**

```java
package com.household.manager.zigbee.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "zigbee.mqtt")
@Getter
@Setter
public class ZigbeeMqttProperties {
    private boolean enabled = true;
    private String host = "localhost";
    private int port = 1883;
    private String username = "";
    private String password = "";
    private String topicFilter = "zigbee2mqtt/#";
    private String clientId = "household-manager-zigbee";
}
```

- [ ] **Step 2: MQTT-Config/Subscriber anlegen**

```java
package com.household.manager.zigbee.config;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.service.ZigbeeMessageParser;
import com.household.manager.zigbee.service.ZigbeeReadingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Verbindet sich beim Start mit dem MQTT-Broker, abonniert die zigbee2mqtt-Topics
 * und leitet jede Nachricht durch Parser + ReadingService. Startet die App auch
 * dann, wenn der Broker (noch) nicht erreichbar ist (Auto-Reconnect).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeMqttConfig {

    private final ZigbeeMqttProperties properties;
    private final ZigbeeMessageParser parser;
    private final ZigbeeReadingService readingService;

    private Mqtt3AsyncClient client;

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            log.info("Zigbee MQTT integration disabled");
            return;
        }

        Mqtt3AsyncClient builtClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier(properties.getClientId())
                .serverHost(properties.getHost())
                .serverPort(properties.getPort())
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener(ctx -> subscribe())
                .buildAsync();
        this.client = builtClient;

        var connectBuilder = builtClient.connectWith();
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            connectBuilder = connectBuilder.simpleAuth()
                    .username(properties.getUsername())
                    .password(properties.getPassword().getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth();
        }
        connectBuilder.send().whenComplete((ack, throwable) -> {
            if (throwable != null) {
                log.warn("Zigbee MQTT initial connect failed (will auto-reconnect): {}", throwable.getMessage());
            } else {
                log.info("Zigbee MQTT connected to {}:{}", properties.getHost(), properties.getPort());
            }
        });
    }

    private void subscribe() {
        if (client == null) {
            return;
        }
        client.subscribeWith()
                .topicFilter(properties.getTopicFilter())
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handle)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        log.warn("Zigbee MQTT subscribe failed: {}", throwable.getMessage());
                    } else {
                        log.info("Zigbee MQTT subscribed to {}", properties.getTopicFilter());
                    }
                });
    }

    private void handle(com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish publish) {
        try {
            String topic = publish.getTopic().toString();
            String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            Optional<ParsedZigbeeMessage> parsed = parser.parse(topic, payload);
            parsed.ifPresent(readingService::record);
        } catch (Exception ex) {
            log.debug("Failed to handle Zigbee MQTT message: {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception ex) {
                log.debug("Error during MQTT disconnect: {}", ex.getMessage());
            }
        }
    }
}
```

- [ ] **Step 3: Properties in application.properties ergänzen**

Modify `backend/src/main/resources/application.properties` — am Dateiende anhängen:

```properties
# Zigbee MQTT (zigbee2mqtt via Mosquitto)
zigbee.mqtt.enabled=${ZIGBEE_MQTT_ENABLED:true}
zigbee.mqtt.host=${ZIGBEE_MQTT_HOST:localhost}
zigbee.mqtt.port=${ZIGBEE_MQTT_PORT:1883}
zigbee.mqtt.username=${ZIGBEE_MQTT_USER:household}
zigbee.mqtt.password=${ZIGBEE_MQTT_PASSWORD:}
zigbee.mqtt.topic-filter=zigbee2mqtt/#
zigbee.mqtt.client-id=household-manager-zigbee
```

- [ ] **Step 4: Backend-Env in docker-compose.yml ergänzen**

Modify `docker-compose.yml` — im `backend`-Service unter `environment:` hinzufügen:

```yaml
      # Zigbee MQTT
      ZIGBEE_MQTT_HOST: mosquitto
      ZIGBEE_MQTT_PORT: 1883
      ZIGBEE_MQTT_USER: household
      ZIGBEE_MQTT_PASSWORD: change-me-zigbee
```

Und im `backend`-Service `depends_on: [mosquitto]` ergänzen (neuen Schlüssel anlegen, da bisher keiner existiert):

```yaml
    depends_on:
      - mosquitto
```

- [ ] **Step 5: Kompilieren & Boot-Check**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ \
        backend/src/main/resources/application.properties docker-compose.yml
git commit -m "feat(zigbee): wire MQTT subscriber to parser and reading service"
```

---

## Task 12: History-DTOs + ZigbeeController

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeDeviceResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeMeasurementResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/controller/ZigbeeController.java`

- [ ] **Step 1: ZigbeeDeviceResponse**

```java
package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceResponse {
    private Long id;
    private String friendlyName;
    private String ieeeAddress;
    private String deviceType;
    private String model;
    private Integer lastBatteryPercent;
    private Integer lastLinkQuality;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastSeen;
}
```

- [ ] **Step 2: ZigbeeMeasurementResponse**

```java
package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.zigbee.model.MeasurementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeMeasurementResponse {
    private MeasurementType measurementType;
    private BigDecimal value;
    private String unit;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime measuredAt;
}
```

- [ ] **Step 3: Controller anlegen**

```java
package com.household.manager.zigbee.controller;

import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.dto.ZigbeeMeasurementResponse;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import com.household.manager.zigbee.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.service.ZigbeeLiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST + SSE für Zigbee-Sensoren. Basis-URL: /api/v1/zigbee
 */
@RestController
@RequestMapping("/v1/zigbee")
@RequiredArgsConstructor
@Slf4j
public class ZigbeeController {

    private final ZigbeeDeviceRepository deviceRepository;
    private final ZigbeeMeasurementRepository measurementRepository;
    private final ZigbeeLiveService liveService;

    @GetMapping("/devices")
    public ResponseEntity<List<ZigbeeDeviceResponse>> getDevices() {
        List<ZigbeeDeviceResponse> devices = deviceRepository.findAll().stream()
                .map(this::toDeviceResponse)
                .toList();
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/devices/{friendlyName}/measurements")
    public ResponseEntity<List<ZigbeeMeasurementResponse>> getMeasurements(
            @PathVariable String friendlyName,
            @RequestParam MeasurementType type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        ZigbeeDevice device = deviceRepository.findByFriendlyName(friendlyName).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        LocalDateTime start = (from != null) ? from : LocalDateTime.now().minusDays(7);
        LocalDateTime end = (to != null) ? to : LocalDateTime.now();

        List<ZigbeeMeasurementResponse> result = measurementRepository
                .findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                        device.getId(), type, start, end)
                .stream()
                .map(m -> ZigbeeMeasurementResponse.builder()
                        .measurementType(m.getMeasurementType())
                        .value(m.getValue())
                        .unit(m.getUnit())
                        .measuredAt(m.getMeasuredAt())
                        .build())
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveService.subscribe();
    }

    private ZigbeeDeviceResponse toDeviceResponse(ZigbeeDevice device) {
        return ZigbeeDeviceResponse.builder()
                .id(device.getId())
                .friendlyName(device.getFriendlyName())
                .ieeeAddress(device.getIeeeAddress())
                .deviceType(device.getDeviceType())
                .model(device.getModel())
                .lastBatteryPercent(device.getLastBatteryPercent())
                .lastLinkQuality(device.getLastLinkQuality())
                .lastSeen(device.getLastSeen())
                .build();
    }
}
```

- [ ] **Step 4: Kompilieren & Commit**

Run: `cd backend && mvn -q compile`

```bash
git add backend/src/main/java/com/household/manager/zigbee/dto/ \
        backend/src/main/java/com/household/manager/zigbee/controller/
git commit -m "feat(zigbee): add REST/SSE controller for devices and measurements"
```

- [ ] **Step 5: Volle Backend-Testsuite**

Run: `cd backend && mvn -q test`
Expected: Alle Tests grün (inkl. der neuen Parser- und ReadingService-Tests).

---

## Task 13: Frontend — Model + Services

**Files:**
- Create: `frontend/src/app/models/zigbee.model.ts`
- Create: `frontend/src/app/services/zigbee.service.ts`
- Create: `frontend/src/app/services/zigbee-live.service.ts`

- [ ] **Step 1: Model anlegen**

```ts
export type ZigbeeMeasurementType =
  | 'TEMPERATURE'
  | 'HUMIDITY'
  | 'PRESSURE'
  | 'CONTACT'
  | 'OCCUPANCY'
  | 'ILLUMINANCE'
  | 'WATER_LEAK';

export interface ZigbeeDevice {
  id: number;
  friendlyName: string;
  ieeeAddress?: string;
  deviceType?: string;
  model?: string;
  lastBatteryPercent?: number;
  lastLinkQuality?: number;
  lastSeen?: string;
}

export interface ZigbeeMeasurement {
  measurementType: ZigbeeMeasurementType;
  value: number;
  unit: string;
  measuredAt: string;
}

export interface ZigbeeLiveEvent {
  friendlyName: string;
  measurementType: ZigbeeMeasurementType;
  value: number;
  unit: string;
  batteryPercent?: number;
  linkQuality?: number;
  measuredAt: string;
}
```

- [ ] **Step 2: REST-Service anlegen**

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ZigbeeDevice, ZigbeeMeasurement, ZigbeeMeasurementType } from '../models/zigbee.model';

/**
 * REST-Service für Zigbee-Sensoren.
 */
@Injectable({ providedIn: 'root' })
export class ZigbeeService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/zigbee';

  getDevices(): Observable<ZigbeeDevice[]> {
    return this.http.get<ZigbeeDevice[]>(`${this.baseUrl}/devices`).pipe(
      catchError(this.handleError)
    );
  }

  getMeasurements(
    friendlyName: string,
    type: ZigbeeMeasurementType,
    from?: string,
    to?: string
  ): Observable<ZigbeeMeasurement[]> {
    let params = new HttpParams().set('type', type);
    if (from) { params = params.set('from', from); }
    if (to) { params = params.set('to', to); }
    return this.http
      .get<ZigbeeMeasurement[]>(`${this.baseUrl}/devices/${encodeURIComponent(friendlyName)}/measurements`, { params })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Zigbee API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Zigbee-Daten.'));
  }
}
```

- [ ] **Step 3: SSE-Live-Service anlegen**

```ts
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { ZigbeeLiveEvent } from '../models/zigbee.model';

type LiveStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * SSE-Service für Live-Zigbee-Messwerte.
 */
@Injectable({ providedIn: 'root' })
export class ZigbeeLiveService {
  private readonly url = '/api/v1/zigbee/live';
  private eventSource: EventSource | null = null;
  private readonly statusSubject = new BehaviorSubject<LiveStatus>('disconnected');

  getStatusStream(): Observable<LiveStatus> {
    return this.statusSubject.asObservable();
  }

  getLiveStream(): Observable<ZigbeeLiveEvent> {
    return new Observable<ZigbeeLiveEvent>((observer) => {
      this.connect();

      this.eventSource?.addEventListener('live', (event: MessageEvent) => {
        try {
          observer.next(JSON.parse(event.data) as ZigbeeLiveEvent);
        } catch (error) {
          observer.error(error);
        }
      });

      if (this.eventSource) {
        this.eventSource.onopen = () => this.statusSubject.next('connected');
        this.eventSource.onerror = (error) => {
          this.statusSubject.next('error');
          observer.error(error);
        };
      }

      return () => this.disconnect();
    });
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.statusSubject.next('disconnected');
  }

  private connect(): void {
    if (this.eventSource) { return; }
    this.statusSubject.next('connecting');
    this.eventSource = new EventSource(this.url);
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/zigbee.model.ts \
        frontend/src/app/services/zigbee.service.ts \
        frontend/src/app/services/zigbee-live.service.ts
git commit -m "feat(zigbee-ui): add model and REST/SSE services"
```

---

## Task 14: Frontend — Zigbee-Seite (Live-Kacheln + Verlauf)

**Files:**
- Create: `frontend/src/app/pages/zigbee/zigbee.component.ts`
- Create: `frontend/src/app/pages/zigbee/zigbee.component.html`
- Create: `frontend/src/app/pages/zigbee/zigbee.component.scss`
- Modify: `frontend/src/app/app.routes.ts`

- [ ] **Step 1: Component-Klasse anlegen**

```ts
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { Subscription } from 'rxjs';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeLiveService } from '../../services/zigbee-live.service';
import {
  ZigbeeDevice,
  ZigbeeLiveEvent,
  ZigbeeMeasurementType
} from '../../models/zigbee.model';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface LiveValue {
  value: number;
  unit: string;
  measuredAt: string;
}

/**
 * Übersicht der Zigbee-Sensoren: Live-Kacheln je Gerät + Verlaufschart.
 */
@Component({
  selector: 'app-zigbee',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './zigbee.component.html',
  styleUrl: './zigbee.component.scss'
})
export class ZigbeeComponent implements OnInit, OnDestroy {
  private readonly zigbeeService = inject(ZigbeeService);
  private readonly liveService = inject(ZigbeeLiveService);

  devices: ZigbeeDevice[] = [];
  /** friendlyName -> (measurementType -> aktueller Wert) */
  liveValues: Record<string, Record<string, LiveValue>> = {};

  selectedDevice?: string;
  selectedType: ZigbeeMeasurementType = 'TEMPERATURE';
  chartOptions: any = null;

  private liveSub?: Subscription;

  ngOnInit(): void {
    this.loadDevices();
    this.liveSub = this.liveService.getLiveStream().subscribe({
      next: (event) => this.applyLiveEvent(event),
      error: () => { /* SSE-Fehler werden über Reconnect des Browsers abgefangen */ }
    });
  }

  ngOnDestroy(): void {
    this.liveSub?.unsubscribe();
    this.liveService.disconnect();
  }

  loadDevices(): void {
    this.zigbeeService.getDevices().subscribe((devices) => {
      this.devices = devices;
      if (!this.selectedDevice && devices.length > 0) {
        this.selectedDevice = devices[0].friendlyName;
        this.loadHistory();
      }
    });
  }

  loadHistory(): void {
    if (!this.selectedDevice) { return; }
    this.zigbeeService.getMeasurements(this.selectedDevice, this.selectedType).subscribe((measurements) => {
      this.chartOptions = {
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'time' },
        yAxis: { type: 'value' },
        series: [{
          type: 'line',
          showSymbol: false,
          data: measurements.map(m => [m.measuredAt, m.value])
        }]
      };
    });
  }

  private applyLiveEvent(event: ZigbeeLiveEvent): void {
    const byType = this.liveValues[event.friendlyName] ?? {};
    byType[event.measurementType] = {
      value: event.value,
      unit: event.unit,
      measuredAt: event.measuredAt
    };
    this.liveValues[event.friendlyName] = byType;

    if (!this.devices.some(d => d.friendlyName === event.friendlyName)) {
      this.loadDevices();
    }
  }

  liveTypesFor(friendlyName: string): string[] {
    return Object.keys(this.liveValues[friendlyName] ?? {});
  }
}
```

- [ ] **Step 2: Template anlegen**

Create `frontend/src/app/pages/zigbee/zigbee.component.html`:

```html
<section class="zigbee-page">
  <h1>Zigbee-Sensoren</h1>

  <div class="device-grid">
    <article class="device-card" *ngFor="let device of devices">
      <header>
        <h2>{{ device.friendlyName }}</h2>
        <span class="meta" *ngIf="device.lastBatteryPercent != null">🔋 {{ device.lastBatteryPercent }}%</span>
        <span class="meta" *ngIf="device.lastLinkQuality != null">📶 {{ device.lastLinkQuality }}</span>
      </header>
      <ul class="values">
        <li *ngFor="let type of liveTypesFor(device.friendlyName)">
          <span class="type">{{ type }}</span>
          <span class="value">
            {{ liveValues[device.friendlyName][type].value }}
            {{ liveValues[device.friendlyName][type].unit }}
          </span>
        </li>
      </ul>
      <p class="last-seen" *ngIf="device.lastSeen">Zuletzt: {{ device.lastSeen }}</p>
    </article>
  </div>

  <div class="history">
    <h2>Verlauf</h2>
    <div class="controls">
      <select [(ngModel)]="selectedDevice" (change)="loadHistory()">
        <option *ngFor="let device of devices" [value]="device.friendlyName">{{ device.friendlyName }}</option>
      </select>
      <select [(ngModel)]="selectedType" (change)="loadHistory()">
        <option value="TEMPERATURE">Temperatur</option>
        <option value="HUMIDITY">Luftfeuchte</option>
        <option value="PRESSURE">Luftdruck</option>
        <option value="CONTACT">Kontakt</option>
        <option value="OCCUPANCY">Bewegung</option>
        <option value="ILLUMINANCE">Helligkeit</option>
        <option value="WATER_LEAK">Wasserleck</option>
      </select>
    </div>
    <div echarts [options]="chartOptions" class="chart" *ngIf="chartOptions"></div>
  </div>
</section>
```

- [ ] **Step 3: SCSS anlegen**

Create `frontend/src/app/pages/zigbee/zigbee.component.scss`:

```scss
.zigbee-page {
  padding: 1.5rem;

  .device-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: 1rem;
    margin-bottom: 2rem;
  }

  .device-card {
    border: 1px solid #e0e0e0;
    border-radius: 8px;
    padding: 1rem;

    header {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0.5rem;

      h2 { font-size: 1rem; margin: 0; flex: 1 1 100%; }
      .meta { font-size: 0.85rem; color: #666; }
    }

    .values {
      list-style: none;
      padding: 0;
      margin: 0.75rem 0 0;

      li {
        display: flex;
        justify-content: space-between;
        padding: 0.15rem 0;
        .type { color: #888; font-size: 0.8rem; }
        .value { font-weight: 600; }
      }
    }

    .last-seen { font-size: 0.75rem; color: #aaa; margin-top: 0.5rem; }
  }

  .history {
    .controls {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 1rem;
      select { padding: 0.35rem 0.5rem; }
    }
    .chart { width: 100%; height: 360px; }
  }
}
```

- [ ] **Step 4: Route registrieren**

Modify `frontend/src/app/app.routes.ts` — vor dem `'**'`-Wildcard-Eintrag einfügen:

```ts
  {
    path: 'zigbee',
    loadComponent: () => import('./pages/zigbee/zigbee.component').then(m => m.ZigbeeComponent),
    title: 'Zigbee-Sensoren - Household Manager'
  },
```

- [ ] **Step 5: Build prüfen**

Run: `cd frontend && npm run build`
Expected: Build erfolgreich. Falls ein `anyComponentStyle`-Budget-Fehler auftritt (siehe Commit-Historie), SCSS kürzen oder Budget in `angular.json` analog zur bestehenden Anpassung erhöhen.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/zigbee/ frontend/src/app/app.routes.ts
git commit -m "feat(zigbee-ui): add Zigbee sensor page with live tiles and history chart"
```

---

## Task 15: Navigations-Eintrag

**Files:**
- Modify: `frontend/src/app/components/header/header.component.html` (oder die Stelle, an der die Navigationslinks gepflegt werden)

- [ ] **Step 1: Aktuelle Navigationsstruktur ansehen**

Run: `cat frontend/src/app/components/header/header.component.html`
Expected: Liste der `routerLink`-Einträge sichtbar.

- [ ] **Step 2: Zigbee-Link ergänzen**

Füge analog zu den bestehenden Einträgen (z. B. neben „Luftqualität"/„Wetter") einen Link hinzu:

```html
<a routerLink="/zigbee" routerLinkActive="active">Zigbee-Sensoren</a>
```

(Exakte Markup-Struktur an die vorhandenen Links anpassen.)

- [ ] **Step 3: Build prüfen & Commit**

Run: `cd frontend && npm run build`
Expected: Build erfolgreich, Link erscheint in der Navigation.

```bash
git add frontend/src/app/components/header/
git commit -m "feat(zigbee-ui): add navigation entry for Zigbee sensors"
```

---

## Task 16: End-to-End-Verifikation (nach HA-Cutover)

> Dieser Task ist **manuell** und kann erst nach dem Cutover laufen (Home Assistant stoppen, damit zigbee2mqtt den seriellen Port der Bridge übernimmt).

- [ ] **Step 1: Stack starten**

Run:

```bash
docker compose up -d mosquitto zigbee2mqtt backend frontend
docker compose logs --no-color zigbee2mqtt | tail -n 40
```

Expected: zigbee2mqtt verbindet sich mit der Bridge (`tcp://192.168.1.121:8888`) und mit Mosquitto; gekoppelte Geräte erscheinen im Log.

- [ ] **Step 2: MQTT-Fluss prüfen**

Run:

```bash
python - <<'PY'
import paho.mqtt.client as mqtt, time
def on_connect(c,u,f,rc,*a): print("rc",rc); c.subscribe("zigbee2mqtt/#")
def on_message(c,u,m): print(m.topic, m.payload.decode("utf-8","replace")[:200])
cli=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
cli.username_pw_set("household","change-me-zigbee")
cli.on_connect=on_connect; cli.on_message=on_message
cli.connect("localhost",1883,30); cli.loop_start(); time.sleep(15); cli.loop_stop()
PY
```

Expected: echte `zigbee2mqtt/<name>`-Nachrichten mit Sensorwerten. **Falls Feldnamen abweichen** (z. B. zusätzliche Typen), `FIELD_TYPES` im Parser ergänzen und Parser-Tests um die realen Beispiele erweitern.

- [ ] **Step 3: Backend-API prüfen**

Run:

```bash
curl -s http://localhost:8080/api/v1/zigbee/devices | head -c 1000
```

Expected: JSON-Liste der Geräte mit `lastBatteryPercent`/`lastLinkQuality`/`lastSeen`.

- [ ] **Step 4: Frontend prüfen**

Öffne `http://localhost:4200/zigbee`.
Expected: Live-Kacheln aktualisieren sich; Verlaufschart zeigt Werte nach kurzer Laufzeit.

- [ ] **Step 5: Abschluss**

Wenn echte Feldnamen Anpassungen erforderten: separater Commit `fix(zigbee): align parser field mapping with real devices` inkl. erweiterter Parser-Tests.

---

## Self-Review-Notiz (vom Plan-Autor)

- **Spec-Abdeckung:** Docker (Mosquitto+zigbee2mqtt) → Tasks 1–2; Datenmodell Ansatz A → Tasks 3–5; Parser+Mapping → Task 8; Persistenz/Upsert → Task 10; MQTT-Subscriber+Resilienz → Task 11; REST/SSE → Tasks 9, 12; Frontend (Live+Verlauf, Route, Nav) → Tasks 13–15; Tests → Tasks 8, 10, 12; E2E/Cutover → Task 16. Buttons/`action` bewusst out-of-scope (Spec-konform).
- **Typkonsistenz:** `record(...)` (ReadingService), `parse(...)` (Parser), `broadcast(...)`/`subscribe()` (LiveService), `findByFriendlyName` (Repository) durchgängig identisch verwendet.
