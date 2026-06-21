# DWD-Wetterintegration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lokales Wetter, Stundenvorhersage, „Regen demnächst"-Hinweis und amtliche DWD-Wetterwarnungen in der App anzeigen, plus Verlauf der Ist-Bedingungen in der DB persistieren.

**Architecture:** Backend ruft die DWD-WarnWetter-App-API (`stationOverviewExtended`) über die vorhandene `RestTemplate`-Bean ab, parst die Antwort in Domain-DTOs (mit TTL-Cache), liefert sie live an `/v1/weather/overview`, und ein `@Scheduled`-Polling-Service speichert pro Lauf einen Ist-Bedingungen-Snapshot (`weather_readings`). Das Angular-Frontend zeigt eine eigene `/weather`-Seite plus ein Dashboard-Widget. Alles gespiegelt am bestehenden Airrohr-Muster.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Lombok, Liquibase, MariaDB, JUnit 5; Angular 19 standalone, RxJS, ngx-echarts, SCSS.

**Referenz-Muster im Code (vor Start ansehen):**
- `backend/.../service/AirrohrService.java` (Fetch + Parse mit ObjectMapper)
- `backend/.../service/AirrohrPollingService.java` (@Scheduled, Status, triggerOnce)
- `backend/.../controller/AirrohrReadingController.java` + `AirrohrPollingAdminController.java` (Basis `/v1`)
- `backend/.../model/entity/AirrohrReading.java` + Changeset `20260211-0007-...xml`
- `frontend/.../services/airrohr.service.ts`, `pages/airrohr-charts/airrohr-charts.component.ts`
- `frontend/.../components/header/header.component.ts` (`navLinks`)

**Wichtige API-Fakten (verifiziert am 2026-06-21):**
- Endpoint: `GET https://app-prod-ws.warnwetter.de/v30/stationOverviewExtended?stationIds=10637` (kein Key).
- Top-Level pro Station-ID: `forecastStart` (epoch ms), `forecast1` (`temperature[]`, `precipitationTotal[]`, `icon1h[]`, `windSpeed[]`, `windDirection[]`, `humidity[]`, `surfacePressure[]`, plus `start`/`timeStep`), `days[]`, `warnings[]`.
- `warnings[]`: `event`, `level` (int), `start`/`end` (epoch ms), `headline`, `descriptionText`, `instruction`, `warnId`.
- Skalierung: Temperatur in Zehntel-°C (`260` → `26.0`). Niederschlag, Druck analog in Zehnteln (`/10`). Genaue Faktoren werden in Task 9 (manuelle Verifikation) gegen Live-Magnituden gegengeprüft.

---

## Backend

### Task 1: Konfiguration + Domain-DTOs

**Files:**
- Modify: `backend/src/main/resources/application.properties` (ans Ende anfügen)
- Create: `backend/src/main/java/com/household/manager/dto/WeatherConditions.java`
- Create: `backend/src/main/java/com/household/manager/dto/WeatherForecastHour.java`
- Create: `backend/src/main/java/com/household/manager/dto/WeatherWarning.java`
- Create: `backend/src/main/java/com/household/manager/dto/WeatherOverviewResponse.java`

- [ ] **Step 1: Konfiguration anfügen**

In `application.properties` ergänzen:

```properties

# DWD Wetter (WarnWetter-App-API)
dwd.base-url=https://app-prod-ws.warnwetter.de/v30/stationOverviewExtended
dwd.station-id=10637
dwd.cache-ttl-ms=600000
dwd.polling.interval-ms=900000
dwd.polling.initial-delay-ms=20000
```

- [ ] **Step 2: DTO `WeatherConditions`**

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Aktuelle Wetterbedingungen (erster Vorhersagewert). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherConditions {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime time;

    private BigDecimal temperature;     // °C
    private BigDecimal precipitation;   // mm
    private BigDecimal windSpeed;       // wie geliefert (in Task 9 verifizieren)
    private Integer windDirection;      // Grad
    private Integer humidity;           // %
    private BigDecimal pressure;        // hPa
    private Integer icon;               // DWD-Icon-Code
}
```

- [ ] **Step 3: DTO `WeatherForecastHour`**

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Ein Stundenpunkt der Vorhersage. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherForecastHour {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime time;

    private BigDecimal temperature;   // °C
    private BigDecimal precipitation; // mm
    private Integer icon;             // DWD-Icon-Code
}
```

- [ ] **Step 4: DTO `WeatherWarning`**

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Eine amtliche DWD-Wetterwarnung. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherWarning {

    private Long warnId;
    private String event;
    private Integer level;
    private String headline;
    private String description;
    private String instruction;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime start;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime end;
}
```

- [ ] **Step 5: DTO `WeatherOverviewResponse`**

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Gesamtantwort der Wetterseite: aktuell + Vorhersage + Warnungen + nextRain. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherOverviewResponse {

    private String stationId;
    private WeatherConditions current;
    private List<WeatherForecastHour> hourlyForecast;
    private List<WeatherWarning> warnings;

    /** Zeitpunkt des nächsten erwarteten Regens, oder null. */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime nextRain;
}
```

- [ ] **Step 6: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/application.properties backend/src/main/java/com/household/manager/dto/Weather*.java
git commit -m "feat(weather): add DWD config and weather DTOs"
```

---

### Task 2: `DwdWeatherService` (Parsing, nextRain, TTL-Cache)

Der Service trennt **Parsing** (`parseOverview(String, String)`, package-private, ohne HTTP) von **Abruf** (`getOverview()` mit Fetch + Cache). So ist das Parsing deterministisch testbar.

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/DwdWeatherService.java`
- Test: `backend/src/test/java/com/household/manager/service/DwdWeatherServiceTest.java`

- [ ] **Step 1: Failing test schreiben**

```java
package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.WeatherOverviewResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DwdWeatherServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DwdWeatherService service =
            new DwdWeatherService(null, objectMapper);

    /** forecastStart = 2026-06-21T12:00:00 Europe/Berlin, timeStep = 1h. */
    private static final long FORECAST_START =
            ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
                    .toInstant().toEpochMilli();

    private String sampleJson() {
        return "{\"10637\":{"
                + "\"forecastStart\":" + FORECAST_START + ","
                + "\"forecast1\":{"
                + "\"start\":" + FORECAST_START + ",\"timeStep\":3600000,"
                + "\"temperature\":[205,210,230,235],"
                + "\"precipitationTotal\":[0,0,5,12],"
                + "\"icon1h\":[1,1,8,8],"
                + "\"windSpeed\":[120,130,140,150],"
                + "\"windDirection\":[180,185,190,200],"
                + "\"humidity\":[60,62,70,72],"
                + "\"surfacePressure\":[10132,10130,10125,10120]"
                + "},"
                + "\"warnings\":[{"
                + "\"warnId\":42,\"event\":\"GEWITTER\",\"level\":3,"
                + "\"headline\":\"Amtliche WARNUNG vor GEWITTER\","
                + "\"descriptionText\":\"Es treten Gewitter auf.\","
                + "\"instruction\":\"Meiden Sie freie Flaechen.\","
                + "\"start\":" + FORECAST_START + ",\"end\":" + (FORECAST_START + 7200000) + "}]"
                + "}}";
    }

    @Test
    void parsesCurrentConditionsWithScaling() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getCurrent().getTemperature()).isEqualByComparingTo("20.5");
        assertThat(result.getCurrent().getHumidity()).isEqualTo(60);
        assertThat(result.getCurrent().getPressure()).isEqualByComparingTo("1013.2");
        assertThat(result.getCurrent().getIcon()).isEqualTo(1);
    }

    @Test
    void buildsHourlyForecast() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getHourlyForecast()).hasSize(4);
        assertThat(result.getHourlyForecast().get(2).getTemperature())
                .isEqualByComparingTo("23.0");
        assertThat(result.getHourlyForecast().get(2).getPrecipitation())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void detectsNextRainAtFirstPositivePrecipitation() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        // Index 2 ist der erste Wert > 0 -> forecastStart + 2h
        assertThat(result.getNextRain())
                .isEqualTo(ZonedDateTime.of(2026, 6, 21, 14, 0, 0, 0,
                        ZoneId.of("Europe/Berlin")).toLocalDateTime());
    }

    @Test
    void mapsWarnings() {
        WeatherOverviewResponse result = service.parseOverview(sampleJson(), "10637");

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).getEvent()).isEqualTo("GEWITTER");
        assertThat(result.getWarnings().get(0).getLevel()).isEqualTo(3);
        assertThat(result.getWarnings().get(0).getInstruction())
                .isEqualTo("Meiden Sie freie Flaechen.");
    }

    @Test
    void returnsNoNextRainWhenDry() {
        String dry = sampleJson().replace("[0,0,5,12]", "[0,0,0,0]");
        WeatherOverviewResponse result = service.parseOverview(dry, "10637");

        assertThat(result.getNextRain()).isNull();
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `cd backend && mvn -q -Dtest=DwdWeatherServiceTest test`
Expected: FAIL (Klasse `DwdWeatherService` existiert nicht / kompiliert nicht)

- [ ] **Step 3: `DwdWeatherService` implementieren**

```java
package com.household.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.WeatherConditions;
import com.household.manager.dto.WeatherForecastHour;
import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherWarning;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Ruft Wetterdaten der DWD-WarnWetter-App-API ab und parst sie.
 * Hält eine kurze Zwischenspeicherung (TTL), um die DWD-Server zu schonen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DwdWeatherService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final int FORECAST_HOURS = 24;
    private static final BigDecimal TENTH = BigDecimal.valueOf(10);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${dwd.base-url}")
    private String baseUrl;

    @Value("${dwd.station-id}")
    private String stationId;

    @Value("${dwd.cache-ttl-ms:600000}")
    private long cacheTtlMs;

    private volatile WeatherOverviewResponse cached;
    private volatile long cachedAtMs;

    public synchronized WeatherOverviewResponse getOverview() {
        long now = Instant.now().toEpochMilli();
        if (cached != null && now - cachedAtMs < cacheTtlMs) {
            return cached;
        }
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("stationIds", stationId)
                .toUriString();
        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("DWD returned an empty response.");
        }
        WeatherOverviewResponse overview = parseOverview(json, stationId);
        cached = overview;
        cachedAtMs = now;
        return overview;
    }

    WeatherOverviewResponse parseOverview(String json, String station) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode stationNode = root.path(station);
            if (stationNode.isMissingNode()) {
                throw new IllegalStateException("DWD response missing station " + station);
            }

            JsonNode forecast1 = stationNode.path("forecast1");
            long start = forecast1.path("start").asLong(stationNode.path("forecastStart").asLong());
            long step = forecast1.path("timeStep").asLong(3600000L);

            List<WeatherForecastHour> hours = buildHourly(forecast1, start, step);
            WeatherConditions current = hours.isEmpty() ? null : toCurrent(forecast1, hours.get(0));
            LocalDateTime nextRain = findNextRain(hours);
            List<WeatherWarning> warnings = buildWarnings(stationNode.path("warnings"));

            return WeatherOverviewResponse.builder()
                    .stationId(station)
                    .current(current)
                    .hourlyForecast(hours)
                    .warnings(warnings)
                    .nextRain(nextRain)
                    .build();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse DWD response.", ex);
        }
    }

    private List<WeatherForecastHour> buildHourly(JsonNode forecast1, long start, long step) {
        JsonNode temps = forecast1.path("temperature");
        JsonNode precip = forecast1.path("precipitationTotal");
        JsonNode icons = forecast1.path("icon1h");
        int count = Math.min(temps.size(), FORECAST_HOURS);

        List<WeatherForecastHour> hours = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hours.add(WeatherForecastHour.builder()
                    .time(toLocal(start + (long) i * step))
                    .temperature(scaleTenth(temps.path(i)))
                    .precipitation(scaleTenth(precip.path(i)))
                    .icon(icons.path(i).isMissingNode() ? null : icons.path(i).asInt())
                    .build());
        }
        return hours;
    }

    private WeatherConditions toCurrent(JsonNode forecast1, WeatherForecastHour first) {
        return WeatherConditions.builder()
                .time(first.getTime())
                .temperature(first.getTemperature())
                .precipitation(first.getPrecipitation())
                .windSpeed(scaleTenth(forecast1.path("windSpeed").path(0)))
                .windDirection(intOrNull(forecast1.path("windDirection").path(0)))
                .humidity(intOrNull(forecast1.path("humidity").path(0)))
                .pressure(scaleTenth(forecast1.path("surfacePressure").path(0)))
                .icon(first.getIcon())
                .build();
    }

    private LocalDateTime findNextRain(List<WeatherForecastHour> hours) {
        for (WeatherForecastHour hour : hours) {
            if (hour.getPrecipitation() != null
                    && hour.getPrecipitation().compareTo(BigDecimal.ZERO) > 0) {
                return hour.getTime();
            }
        }
        return null;
    }

    private List<WeatherWarning> buildWarnings(JsonNode warningsNode) {
        List<WeatherWarning> warnings = new ArrayList<>();
        if (!warningsNode.isArray()) {
            return warnings;
        }
        for (JsonNode w : warningsNode) {
            warnings.add(WeatherWarning.builder()
                    .warnId(w.path("warnId").isMissingNode() ? null : w.path("warnId").asLong())
                    .event(w.path("event").asText(null))
                    .level(intOrNull(w.path("level")))
                    .headline(w.path("headline").asText(null))
                    .description(w.path("descriptionText").asText(null))
                    .instruction(w.path("instruction").asText(null))
                    .start(epochOrNull(w.path("start")))
                    .end(epochOrNull(w.path("end")))
                    .build());
        }
        return warnings;
    }

    private BigDecimal scaleTenth(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return BigDecimal.valueOf(node.asDouble())
                .divide(TENTH, 1, RoundingMode.HALF_UP);
    }

    private Integer intOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asInt();
    }

    private LocalDateTime toLocal(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDateTime();
    }

    private LocalDateTime epochOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull())
                ? null : toLocal(node.asLong());
    }
}
```

- [ ] **Step 4: Test ausführen — muss bestehen**

Run: `cd backend && mvn -q -Dtest=DwdWeatherServiceTest test`
Expected: PASS (5 Tests grün)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/DwdWeatherService.java backend/src/test/java/com/household/manager/service/DwdWeatherServiceTest.java
git commit -m "feat(weather): add DwdWeatherService with parsing, nextRain and TTL cache"
```

---

### Task 3: Entity, Liquibase-Migration, Repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/WeatherReading.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260621-0015-create-weather-readings-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: `backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java`

- [ ] **Step 1: Entity `WeatherReading`**

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Snapshot der tatsächlichen Wetterbedingungen zu einem Abrufzeitpunkt. */
@Entity
@Table(name = "weather_readings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reading_time", nullable = false)
    private LocalDateTime readingTime;

    @Column(name = "temperature", precision = 10, scale = 2)
    private BigDecimal temperature;

    @Column(name = "precipitation", precision = 10, scale = 2)
    private BigDecimal precipitation;

    @Column(name = "wind_speed", precision = 10, scale = 2)
    private BigDecimal windSpeed;

    @Column(name = "wind_direction")
    private Integer windDirection;

    @Column(name = "humidity")
    private Integer humidity;

    @Column(name = "pressure", precision = 10, scale = 2)
    private BigDecimal pressure;

    @Column(name = "icon")
    private Integer icon;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Liquibase-Changeset**

Create `20260621-0015-create-weather-readings-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260621-0015" author="household-manager">
        <comment>Create weather_readings table for DWD weather snapshots</comment>

        <createTable tableName="weather_readings">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="reading_time" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="temperature" type="DECIMAL(10,2)"/>
            <column name="precipitation" type="DECIMAL(10,2)"/>
            <column name="wind_speed" type="DECIMAL(10,2)"/>
            <column name="wind_direction" type="INT"/>
            <column name="humidity" type="INT"/>
            <column name="pressure" type="DECIMAL(10,2)"/>
            <column name="icon" type="INT"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex indexName="idx_weather_readings_time" tableName="weather_readings">
            <column name="reading_time"/>
        </createIndex>

        <rollback>
            <dropTable tableName="weather_readings"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 3: Master-Changelog einbinden**

In `db.changelog-master.xml` vor `</databaseChangeLog>` einfügen:

```xml

    <!-- DWD Weather Feature -->
    <include file="db/changelog/changes/20260621-0015-create-weather-readings-table.xml"/>
```

- [ ] **Step 4: Repository**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.WeatherReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** Repository für {@link WeatherReading}. */
@Repository
public interface WeatherReadingRepository extends JpaRepository<WeatherReading, Long> {

    List<WeatherReading> findByReadingTimeBetweenOrderByReadingTimeAsc(
            LocalDateTime from, LocalDateTime to);

    List<WeatherReading> findAllByOrderByReadingTimeAsc();
}
```

- [ ] **Step 5: Schema-Validierung über Build**

`spring.jpa.hibernate.ddl-auto=validate` erzwingt Übereinstimmung von Entity und Migration. Kompilieren und Test-Kontext laden:

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

(Optionale Vollverifikation der Migration erfolgt beim App-Start in Task 9.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/WeatherReading.java backend/src/main/java/com/household/manager/repository/WeatherReadingRepository.java backend/src/main/resources/db/changelog/
git commit -m "feat(weather): add WeatherReading entity, migration and repository"
```

---

### Task 4: History-Service + Polling-Service

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/WeatherReadingHistoryResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/WeatherPollingStatusResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/WeatherReadingService.java`
- Create: `backend/src/main/java/com/household/manager/service/WeatherPollingService.java`

- [ ] **Step 1: DTO `WeatherReadingHistoryResponse`**

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Persistierter Wetter-Snapshot für den Verlauf-Chart. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherReadingHistoryResponse {

    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    private BigDecimal temperature;
    private BigDecimal precipitation;
    private BigDecimal windSpeed;
    private Integer windDirection;
    private Integer humidity;
    private BigDecimal pressure;
    private Integer icon;
}
```

- [ ] **Step 2: DTO `WeatherPollingStatusResponse`**

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Status der Wetter-Polling-Aufgabe. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherPollingStatusResponse {

    private String stationId;
    private String schedule;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPollTime;

    private String lastError;
}
```

- [ ] **Step 3: `WeatherReadingService`**

```java
package com.household.manager.service;

import com.household.manager.dto.WeatherReadingHistoryResponse;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Liest persistierte Wetter-Snapshots und mappt sie auf History-DTOs. */
@Service
@RequiredArgsConstructor
public class WeatherReadingService {

    private final WeatherReadingRepository repository;

    public List<WeatherReadingHistoryResponse> getAllReadings() {
        return repository.findAllByOrderByReadingTimeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    private WeatherReadingHistoryResponse toResponse(WeatherReading reading) {
        return WeatherReadingHistoryResponse.builder()
                .id(reading.getId())
                .readingTime(reading.getReadingTime())
                .temperature(reading.getTemperature())
                .precipitation(reading.getPrecipitation())
                .windSpeed(reading.getWindSpeed())
                .windDirection(reading.getWindDirection())
                .humidity(reading.getHumidity())
                .pressure(reading.getPressure())
                .icon(reading.getIcon())
                .build();
    }
}
```

- [ ] **Step 4: `WeatherPollingService`**

```java
package com.household.manager.service;

import com.household.manager.dto.WeatherConditions;
import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherPollingStatusResponse;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;

/** Pollt DWD-Wetter und persistiert einen Ist-Bedingungen-Snapshot. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeatherPollingService {

    private static final String SCHEDULE = "Alle 15 Minuten";

    private final DwdWeatherService dwdWeatherService;
    private final WeatherReadingRepository repository;
    private final TaskScheduler taskScheduler;

    @Value("${dwd.station-id}")
    private String stationId;

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public WeatherPollingStatusResponse getStatus() {
        return WeatherPollingStatusResponse.builder()
                .stationId(stationId)
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${dwd.polling.interval-ms:900000}",
            initialDelayString = "${dwd.polling.initial-delay-ms:20000}"
    )
    public void scheduledPoll() {
        safePoll();
    }

    private void safePoll() {
        try {
            lastPollTime = LocalDateTime.now();
            WeatherOverviewResponse overview = dwdWeatherService.getOverview();
            WeatherConditions current = overview.getCurrent();
            if (current == null) {
                throw new IllegalStateException("DWD overview has no current conditions.");
            }

            WeatherReading entity = WeatherReading.builder()
                    .readingTime(current.getTime())
                    .temperature(current.getTemperature())
                    .precipitation(current.getPrecipitation())
                    .windSpeed(current.getWindSpeed())
                    .windDirection(current.getWindDirection())
                    .humidity(current.getHumidity())
                    .pressure(current.getPressure())
                    .icon(current.getIcon())
                    .build();

            repository.save(entity);
            lastError = null;
            log.debug("Saved weather reading at {}", current.getTime());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Failed to poll DWD weather", ex);
        }
    }
}
```

- [ ] **Step 5: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/WeatherReadingHistoryResponse.java backend/src/main/java/com/household/manager/dto/WeatherPollingStatusResponse.java backend/src/main/java/com/household/manager/service/WeatherReadingService.java backend/src/main/java/com/household/manager/service/WeatherPollingService.java
git commit -m "feat(weather): add weather history and polling services"
```

---

### Task 5: REST-Controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/WeatherController.java`
- Create: `backend/src/main/java/com/household/manager/controller/WeatherPollingAdminController.java`

- [ ] **Step 1: `WeatherController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherReadingHistoryResponse;
import com.household.manager.service.DwdWeatherService;
import com.household.manager.service.WeatherReadingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-Endpunkte für DWD-Wetter.
 * Basis-URL: /api/v1/weather
 */
@RestController
@RequestMapping("/v1/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final DwdWeatherService dwdWeatherService;
    private final WeatherReadingService weatherReadingService;

    @GetMapping("/overview")
    public ResponseEntity<WeatherOverviewResponse> getOverview() {
        return ResponseEntity.ok(dwdWeatherService.getOverview());
    }

    @GetMapping("/history")
    public ResponseEntity<List<WeatherReadingHistoryResponse>> getHistory() {
        return ResponseEntity.ok(weatherReadingService.getAllReadings());
    }
}
```

- [ ] **Step 2: `WeatherPollingAdminController`**

```java
package com.household.manager.controller;

import com.household.manager.dto.WeatherPollingStatusResponse;
import com.household.manager.service.WeatherPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Endpunkte zur Steuerung des Wetter-Pollings.
 * Basis-URL: /api/v1/admin/weather-polling
 */
@RestController
@RequestMapping("/v1/admin/weather-polling")
@RequiredArgsConstructor
@Slf4j
public class WeatherPollingAdminController {

    private final WeatherPollingService pollingService;

    @GetMapping
    public ResponseEntity<WeatherPollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Triggering weather polling");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
```

- [ ] **Step 3: Kompilieren + Gesamttests**

Run: `cd backend && mvn -q test`
Expected: BUILD SUCCESS, alle Tests grün

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/WeatherController.java backend/src/main/java/com/household/manager/controller/WeatherPollingAdminController.java
git commit -m "feat(weather): add weather and polling admin REST controllers"
```

---

## Frontend

### Task 6: Model + Service

**Files:**
- Create: `frontend/src/app/models/weather.model.ts`
- Create: `frontend/src/app/services/weather.service.ts`
- Test: `frontend/src/app/services/weather.service.spec.ts`

- [ ] **Step 1: Model `weather.model.ts`**

```typescript
export interface WeatherConditions {
  time: string;
  temperature: number | null;
  precipitation: number | null;
  windSpeed: number | null;
  windDirection: number | null;
  humidity: number | null;
  pressure: number | null;
  icon: number | null;
}

export interface WeatherForecastHour {
  time: string;
  temperature: number | null;
  precipitation: number | null;
  icon: number | null;
}

export interface WeatherWarning {
  warnId: number | null;
  event: string | null;
  level: number | null;
  headline: string | null;
  description: string | null;
  instruction: string | null;
  start: string | null;
  end: string | null;
}

export interface WeatherOverview {
  stationId: string;
  current: WeatherConditions | null;
  hourlyForecast: WeatherForecastHour[];
  warnings: WeatherWarning[];
  nextRain: string | null;
}

export interface WeatherHistoryReading {
  id: number;
  readingTime: Date;
  temperature: number | null;
  precipitation: number | null;
  windSpeed: number | null;
  windDirection: number | null;
  humidity: number | null;
  pressure: number | null;
  icon: number | null;
}
```

- [ ] **Step 2: Failing service spec**

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { WeatherService } from './weather.service';

describe('WeatherService', () => {
  let service: WeatherService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [WeatherService, provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(WeatherService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads the overview', () => {
    service.getOverview().subscribe(overview => {
      expect(overview.stationId).toBe('10637');
    });
    const req = httpMock.expectOne('/api/v1/weather/overview');
    expect(req.request.method).toBe('GET');
    req.flush({ stationId: '10637', current: null, hourlyForecast: [], warnings: [], nextRain: null });
  });

  it('converts history readingTime to Date', () => {
    service.getHistory().subscribe(readings => {
      expect(readings[0].readingTime instanceof Date).toBe(true);
    });
    const req = httpMock.expectOne('/api/v1/weather/history');
    req.flush([{ id: 1, readingTime: '2026-06-21T12:00:00', temperature: 20.5,
      precipitation: 0, windSpeed: 12, windDirection: 180, humidity: 60, pressure: 1013.2, icon: 1 }]);
  });
});
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/weather.service.spec.ts'`
Expected: FAIL (`WeatherService` existiert nicht)

- [ ] **Step 4: Service `weather.service.ts`**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { WeatherHistoryReading, WeatherOverview } from '../models/weather.model';

/** Service für DWD-Wetterdaten. */
@Injectable({
  providedIn: 'root'
})
export class WeatherService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/weather';

  getOverview(): Observable<WeatherOverview> {
    return this.http.get<WeatherOverview>(`${this.baseUrl}/overview`).pipe(
      catchError(this.handleError)
    );
  }

  getHistory(): Observable<WeatherHistoryReading[]> {
    return this.http.get<WeatherHistoryReading[]>(`${this.baseUrl}/history`).pipe(
      map(readings => readings.map(reading => ({
        ...reading,
        readingTime: new Date(reading.readingTime)
      }))),
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Wetter-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Wetterdaten.'));
  }
}
```

- [ ] **Step 5: Test ausführen — muss bestehen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/weather.service.spec.ts'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/weather.model.ts frontend/src/app/services/weather.service.ts frontend/src/app/services/weather.service.spec.ts
git commit -m "feat(weather): add frontend weather model and service"
```

---

### Task 7: Icon-Helper + Wetterseite + Route + Nav

**Files:**
- Create: `frontend/src/app/shared/weather-icon.util.ts`
- Create: `frontend/src/app/pages/weather/weather.component.ts`
- Create: `frontend/src/app/pages/weather/weather.component.html`
- Create: `frontend/src/app/pages/weather/weather.component.scss`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts:38` (navLinks)

- [ ] **Step 1: Icon-Helper `weather-icon.util.ts`**

```typescript
/**
 * Mappt DWD-Icon-Codes (icon1h) auf Emoji + deutsche Beschreibung.
 * Codes folgen der WarnWetter-App-Konvention. Unbekannte Codes -> Standard.
 */
export interface WeatherSymbol {
  emoji: string;
  label: string;
}

const ICONS: Record<number, WeatherSymbol> = {
  1: { emoji: '☀️', label: 'Klar' },
  2: { emoji: '🌤️', label: 'Leicht bewölkt' },
  3: { emoji: '⛅', label: 'Wolkig' },
  4: { emoji: '☁️', label: 'Bedeckt' },
  5: { emoji: '🌫️', label: 'Nebel' },
  6: { emoji: '🌫️', label: 'Gefrierender Nebel' },
  7: { emoji: '🌦️', label: 'Leichter Regen' },
  8: { emoji: '🌧️', label: 'Regen' },
  9: { emoji: '🌧️', label: 'Starker Regen' },
  10: { emoji: '🌧️', label: 'Gefrierender Regen' },
  11: { emoji: '🌨️', label: 'Schneeregen' },
  12: { emoji: '🌨️', label: 'Leichter Schnee' },
  13: { emoji: '❄️', label: 'Schnee' },
  14: { emoji: '🌦️', label: 'Leichter Schauer' },
  15: { emoji: '🌧️', label: 'Schauer' },
  16: { emoji: '⛈️', label: 'Gewitter' }
};

const DEFAULT_SYMBOL: WeatherSymbol = { emoji: '🌡️', label: 'Unbekannt' };

export function weatherSymbol(icon: number | null | undefined): WeatherSymbol {
  if (icon == null) {
    return DEFAULT_SYMBOL;
  }
  return ICONS[icon] ?? DEFAULT_SYMBOL;
}

/**
 * Schweregrad einer Warnung aus dem DWD-`level`.
 * Robust gegen verschiedene Skalen (1-4 oder Vielfache von 10).
 */
export type WarnSeverity = 'info' | 'moderate' | 'severe' | 'extreme';

export function warnSeverity(level: number | null | undefined): WarnSeverity {
  if (level == null) {
    return 'info';
  }
  const normalized = level >= 10 ? Math.floor(level / 10) : level;
  if (normalized >= 4) return 'extreme';
  if (normalized === 3) return 'severe';
  if (normalized === 2) return 'moderate';
  return 'info';
}
```

- [ ] **Step 2: Komponente `weather.component.ts`**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart, BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { WeatherService } from '../../services/weather.service';
import { WeatherHistoryReading, WeatherOverview } from '../../models/weather.model';
import { weatherSymbol, warnSeverity, WeatherSymbol, WarnSeverity } from '../../shared/weather-icon.util';

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

@Component({
  selector: 'app-weather',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './weather.component.html',
  styleUrl: './weather.component.scss'
})
export class WeatherComponent implements OnInit {
  private readonly weatherService = inject(WeatherService);

  overview: WeatherOverview | null = null;
  forecastChartOptions: Record<string, unknown> | null = null;
  historyChartOptions: Record<string, unknown> | null = null;

  isLoading = true;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.loadOverview();
    this.loadHistory();
  }

  symbolFor(icon: number | null | undefined): WeatherSymbol {
    return weatherSymbol(icon);
  }

  severityFor(level: number | null | undefined): WarnSeverity {
    return warnSeverity(level);
  }

  get nextRainText(): string {
    if (!this.overview?.nextRain) {
      return 'Kein Regen in den nächsten 24 Stunden';
    }
    const time = new Date(this.overview.nextRain).toLocaleTimeString('de-DE', {
      hour: '2-digit',
      minute: '2-digit'
    });
    return `Regen ab ${time} Uhr`;
  }

  private loadOverview(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.weatherService.getOverview().subscribe({
      next: overview => {
        this.overview = overview;
        this.forecastChartOptions = this.buildForecastChart(overview);
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Wetters:', error);
        this.errorMessage = 'Fehler beim Laden der Wetterdaten. Bitte erneut versuchen.';
        this.isLoading = false;
      }
    });
  }

  private loadHistory(): void {
    this.weatherService.getHistory().subscribe({
      next: readings => {
        this.historyChartOptions = readings.length ? this.buildHistoryChart(readings) : null;
      },
      error: (error: Error) => console.error('Fehler beim Laden des Wetterverlaufs:', error)
    });
  }

  private buildForecastChart(overview: WeatherOverview): Record<string, unknown> | null {
    if (!overview.hourlyForecast.length) {
      return null;
    }
    const labels = overview.hourlyForecast.map(h =>
      new Date(h.time).toLocaleTimeString('de-DE', { hour: '2-digit' }));
    return {
      grid: { left: 48, right: 48, top: 24, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { data: ['Temperatur', 'Niederschlag'], top: 0 },
      xAxis: { type: 'category', data: labels, axisLabel: { color: '#94a3b8', fontSize: 11 } },
      yAxis: [
        { type: 'value', axisLabel: { color: '#94a3b8', formatter: '{value} °C' }, splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } } },
        { type: 'value', position: 'right', axisLabel: { color: '#94a3b8', formatter: '{value} mm' }, splitLine: { show: false } }
      ],
      series: [
        { name: 'Temperatur', type: 'line', yAxisIndex: 0, smooth: true, symbol: 'circle', symbolSize: 6,
          data: overview.hourlyForecast.map(h => h.temperature), lineStyle: { width: 2.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' } },
        { name: 'Niederschlag', type: 'bar', yAxisIndex: 1,
          data: overview.hourlyForecast.map(h => h.precipitation), itemStyle: { color: '#0ea5e9' } }
      ]
    };
  }

  private buildHistoryChart(readings: WeatherHistoryReading[]): Record<string, unknown> {
    const sorted = [...readings].sort((a, b) => a.readingTime.getTime() - b.readingTime.getTime());
    const labels = sorted.map(r => r.readingTime.toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit' }));
    return {
      grid: { left: 56, right: 24, top: 24, bottom: 36, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: labels, axisLabel: { color: '#94a3b8', fontSize: 11 } },
      yAxis: { type: 'value', axisLabel: { color: '#94a3b8', formatter: '{value} °C' }, splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } } },
      series: [
        { name: 'Temperatur', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, connectNulls: true,
          data: sorted.map(r => r.temperature), lineStyle: { width: 2.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' } }
      ]
    };
  }
}
```

- [ ] **Step 3: Template `weather.component.html`**

```html
<section class="weather">
  <header class="weather__header">
    <h1 class="weather__title">Wetter</h1>
  </header>

  @if (isLoading) {
    <p class="weather__status">Lade Wetterdaten…</p>
  } @else if (errorMessage) {
    <p class="weather__status weather__status--error">{{ errorMessage }}</p>
  } @else if (overview) {

    @if (overview.warnings.length) {
      <div class="weather__warnings">
        @for (warning of overview.warnings; track warning.warnId) {
          <div class="weather__warning" [attr.data-severity]="severityFor(warning.level)">
            <strong class="weather__warning-headline">{{ warning.headline }}</strong>
            @if (warning.description) {
              <p class="weather__warning-text">{{ warning.description }}</p>
            }
            @if (warning.instruction) {
              <p class="weather__warning-instruction">{{ warning.instruction }}</p>
            }
          </div>
        }
      </div>
    }

    @if (overview.current) {
      <div class="weather__current">
        <div class="weather__current-symbol">{{ symbolFor(overview.current.icon).emoji }}</div>
        <div class="weather__current-main">
          <span class="weather__current-temp">{{ overview.current.temperature }} °C</span>
          <span class="weather__current-label">{{ symbolFor(overview.current.icon).label }}</span>
        </div>
        <ul class="weather__current-details">
          <li>Wind: {{ overview.current.windSpeed }} </li>
          <li>Luftfeuchte: {{ overview.current.humidity }} %</li>
          <li>Druck: {{ overview.current.pressure }} hPa</li>
        </ul>
      </div>
    }

    <div class="weather__rain" [class.weather__rain--active]="overview.nextRain">
      {{ nextRainText }}
    </div>

    @if (forecastChartOptions) {
      <div class="weather__card">
        <h2 class="weather__card-title">Stundenvorhersage</h2>
        <div echarts [options]="forecastChartOptions" class="weather__chart"></div>
      </div>
    }

    @if (historyChartOptions) {
      <div class="weather__card">
        <h2 class="weather__card-title">Wetterverlauf</h2>
        <div echarts [options]="historyChartOptions" class="weather__chart"></div>
      </div>
    }
  }
</section>
```

- [ ] **Step 4: Styles `weather.component.scss`**

```scss
.weather {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  padding: 1.5rem;

  &__title {
    margin: 0;
    font-size: 1.5rem;
    font-weight: 600;
  }

  &__status {
    color: #64748b;

    &--error {
      color: #dc2626;
    }
  }

  &__warnings {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  &__warning {
    border-radius: 0.5rem;
    padding: 0.75rem 1rem;
    border-left: 4px solid #94a3b8;
    background: #f1f5f9;

    &[data-severity='moderate'] { border-left-color: #eab308; background: #fefce8; }
    &[data-severity='severe'] { border-left-color: #f97316; background: #fff7ed; }
    &[data-severity='extreme'] { border-left-color: #dc2626; background: #fef2f2; }
  }

  &__warning-headline { display: block; }
  &__warning-text, &__warning-instruction { margin: 0.25rem 0 0; font-size: 0.875rem; color: #475569; }

  &__current {
    display: flex;
    align-items: center;
    gap: 1.25rem;
    padding: 1.25rem;
    background: #fff;
    border-radius: 0.75rem;
    box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
  }

  &__current-symbol { font-size: 3rem; line-height: 1; }
  &__current-main { display: flex; flex-direction: column; }
  &__current-temp { font-size: 2rem; font-weight: 700; }
  &__current-label { color: #64748b; }
  &__current-details { list-style: none; margin: 0 0 0 auto; padding: 0; color: #475569; font-size: 0.875rem; }

  &__rain {
    padding: 0.75rem 1rem;
    border-radius: 0.5rem;
    background: #f1f5f9;
    color: #475569;
    font-weight: 500;

    &--active { background: #e0f2fe; color: #0369a1; }
  }

  &__card {
    background: #fff;
    border-radius: 0.75rem;
    padding: 1.25rem;
    box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
  }

  &__card-title { margin: 0 0 1rem; font-size: 1.125rem; font-weight: 600; }
  &__chart { width: 100%; height: 320px; }
}
```

- [ ] **Step 5: Route registrieren**

In `app.routes.ts` nach dem `air-quality`-Block (vor `utility-prices`) einfügen:

```typescript
  {
    path: 'weather',
    loadComponent: () => import('./pages/weather/weather.component').then(m => m.WeatherComponent),
    title: 'Wetter - Household Manager'
  },
```

- [ ] **Step 6: Nav-Eintrag ergänzen**

In `header.component.ts` das `navLinks`-Array: nach dem `air-quality`-Eintrag (Zeile 38) ergänzen:

```typescript
    { path: '/weather', label: 'Wetter' },
```

- [ ] **Step 7: Build ausführen**

Run: `cd frontend && npm run build`
Expected: Build erfolgreich, keine TypeScript-Fehler

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/shared/weather-icon.util.ts frontend/src/app/pages/weather/ frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(weather): add weather page with forecast, warnings and history"
```

---

### Task 8: Dashboard-Widget

**Files:**
- Create: `frontend/src/app/components/weather-widget/weather-widget.component.ts`
- Create: `frontend/src/app/components/weather-widget/weather-widget.component.html`
- Create: `frontend/src/app/components/weather-widget/weather-widget.component.scss`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (Import + imports-Array)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Widget einfügen)

> **Vor Start:** `dashboard.component.ts` und `.html` lesen, um Platzierung und Imports-Stil zu übernehmen.

- [ ] **Step 1: Widget-Komponente `weather-widget.component.ts`**

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { weatherSymbol, WeatherSymbol } from '../../shared/weather-icon.util';

/** Kompaktes Wetter-Widget fürs Dashboard, verlinkt auf /weather. */
@Component({
  selector: 'app-weather-widget',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './weather-widget.component.html',
  styleUrl: './weather-widget.component.scss'
})
export class WeatherWidgetComponent implements OnInit {
  private readonly weatherService = inject(WeatherService);

  overview: WeatherOverview | null = null;
  hasError = false;

  ngOnInit(): void {
    this.weatherService.getOverview().subscribe({
      next: overview => (this.overview = overview),
      error: () => (this.hasError = true)
    });
  }

  symbolFor(icon: number | null | undefined): WeatherSymbol {
    return weatherSymbol(icon);
  }
}
```

- [ ] **Step 2: Template `weather-widget.component.html`**

```html
<a class="weather-widget" routerLink="/weather">
  @if (overview?.current) {
    <span class="weather-widget__symbol">{{ symbolFor(overview!.current!.icon).emoji }}</span>
    <span class="weather-widget__temp">{{ overview!.current!.temperature }} °C</span>
    <span class="weather-widget__label">Wetter</span>
    @if (overview!.warnings.length) {
      <span class="weather-widget__badge">{{ overview!.warnings.length }} Warnung(en)</span>
    }
  } @else if (hasError) {
    <span class="weather-widget__label">Wetter nicht verfügbar</span>
  } @else {
    <span class="weather-widget__label">Lade Wetter…</span>
  }
</a>
```

- [ ] **Step 3: Styles `weather-widget.component.scss`**

```scss
.weather-widget {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem 1.25rem;
  border-radius: 0.75rem;
  background: #fff;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
  text-decoration: none;
  color: inherit;

  &__symbol { font-size: 1.75rem; }
  &__temp { font-size: 1.25rem; font-weight: 700; }
  &__label { color: #64748b; }
  &__badge {
    margin-left: auto;
    padding: 0.2rem 0.6rem;
    border-radius: 999px;
    background: #fef2f2;
    color: #dc2626;
    font-size: 0.75rem;
    font-weight: 600;
  }
}
```

- [ ] **Step 4: Ins Dashboard einbinden**

In `dashboard.component.ts`: `WeatherWidgetComponent` importieren und dem `imports`-Array der `@Component`-Dekoration hinzufügen (dem vorhandenen Stil folgen).

In `dashboard.component.html`: an passender Stelle (z.B. oben im Grid) einfügen:

```html
<app-weather-widget></app-weather-widget>
```

- [ ] **Step 5: Build ausführen**

Run: `cd frontend && npm run build`
Expected: Build erfolgreich

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/components/weather-widget/ frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.html
git commit -m "feat(weather): add dashboard weather widget"
```

---

### Task 9: Manuelle End-to-End-Verifikation

**Files:** keine (Verifikation)

- [ ] **Step 1: Live-Magnituden gegenprüfen**

Endpoint einmal real abrufen und Größenordnungen prüfen:

Run: `curl -s "https://app-prod-ws.warnwetter.de/v30/stationOverviewExtended?stationIds=10637"`

Sicherstellen, dass nach `/10`-Skalierung plausible Werte entstehen: Temperatur in °C (z.B. 5–35), `surfacePressure/10` ≈ 980–1040 hPa, `precipitationTotal/10` in mm. Falls eine Größe offensichtlich daneben liegt (z.B. Druck = 101.3 statt 1013), den Skalierungsfaktor im jeweiligen Feld in `DwdWeatherService` anpassen und Task-2-Tests erneut grün machen.

- [ ] **Step 2: Backend starten und Migration prüfen**

Run: `cd backend && mvn spring-boot:run`
Expected: App startet, Liquibase legt `weather_readings` an (kein `ddl-auto=validate`-Fehler).

- [ ] **Step 3: Endpunkte prüfen**

Run: `curl -s http://localhost:8080/api/v1/weather/overview`
Expected: JSON mit `current`, `hourlyForecast`, `warnings`, `nextRain`.

Run: `curl -s -X POST http://localhost:8080/api/v1/admin/weather-polling/trigger`
Expected: HTTP 202; danach `curl -s http://localhost:8080/api/v1/weather/history` liefert mindestens einen Eintrag.

- [ ] **Step 4: Frontend prüfen**

Run: `cd frontend && npm start`
Im Browser `http://localhost:4200/weather` öffnen: aktuelle Bedingungen, „Regen demnächst"-Hinweis, ggf. Warnungen, Vorhersage- und Verlauf-Chart. Dashboard (`/`) zeigt das Wetter-Widget mit korrekter Verlinkung.

- [ ] **Step 5: Abschluss-Commit (falls Anpassungen)**

```bash
git add -A
git commit -m "fix(weather): adjust scaling after live verification"
```

---

## Self-Review-Ergebnis

- **Spec-Abdeckung:** aktuelles Wetter (Task 2/7), Vorhersage (Task 2/7), nextRain (Task 2/7), Warnungen (Task 2/7), Persistenz/Polling (Task 3/4), Live+Cache (Task 2), eigene Seite + Nav (Task 7), Dashboard-Widget (Task 8), Admin-Endpoint (Task 5), Tests (Task 2/6) — alle abgedeckt.
- **Typkonsistenz:** `parseOverview(String, String)`, `getOverview()`, DTO-Felder und Frontend-Interfaces durchgängig gleich benannt.
- **Offen/bewusst:** exakte Skalierungsfaktoren (Wind/Druck/Niederschlag) werden in Task 9 an Live-Daten verifiziert; Warnungs-`level`-Skala über robusten `warnSeverity`-Helper abgefangen. Admin-UI-Karte ist optional und nicht Teil dieses Plans (nur der Endpoint).
