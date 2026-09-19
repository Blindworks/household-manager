# Ladesäulen-Übersicht Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tablet-Ansicht `/tablet/charging` und Website-Seite `/charging` mit Karte der Ladesäulen im Umkreis, Favoriten mit Belegungsdauer je Ladepunkt, Admin-Seite für Zuhause/Radius/Mindestleistung, Entitäten je Favorit.

**Architecture:** Neues Backend-Modul `charging/` pollt das inoffizielle EnBW-Backend (Umkreis alle 5 min, Favoriten jede Minute), hält den Stand im Speicher, schreibt Belegungsübergänge je Ladepunkt in `charging_point_occupancy` und meldet je Favorit `sensor.charging_<stationId>_free`. Frontend liest ausschließlich `/api/v1/charging/**`; Tablet- und Website-Komponente teilen nur Service, Modell und eine Util.

**Tech Stack:** Spring Boot 3.4 / Java 21, Java `HttpClient` (HTTP/1.1), Jackson, Liquibase, JUnit 5 + Mockito + AssertJ; Angular 19 standalone, Leaflet 1.9, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-09-19-ladesaeulen-uebersicht-design.md`

**Build-Hinweise (Windows, diese Maschine):**
- Backend: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` vor jedem `mvn`, aus `backend/`. `contextLoads`/`HealthControllerTest` scheitern lokal an der DB — vorbestehend, ignorieren.
- Frontend: `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`; Baseline 3 vorbestehende Fails (App/Hero).
- Git-Commit-Messages per `git commit -F -` mit Heredoc (Anführungszeichen im PowerShell-Here-String zerlegen den Aufruf).
- Vor jedem Commit `git branch --show-current` prüfen (parallele Sessions können den Branch wechseln). Arbeit auf Branch `feature/charging-overview`.

---

## Dateiübersicht

**Backend (neu, Paket `com.household.manager.charging`):**
- `ChargingProperties.java` — `charging.*`-Properties
- `ChargingSettings.java`, `ChargingSettingsService.java`, `ChargingSettingsController.java` — DB-Einstellungen (Kategorie `CHARGING`)
- `ChargePointStatus.java`, `ChargePoint.java`, `ChargingStation.java`, `ChargingStationDetails.java`, `BoundingBox.java` — Domänenmodell
- `ChargingStationSource.java` (Interface), `EnbwChargingClient.java`, `dto/EnbwStationDto.java`, `dto/EnbwStationDetailsDto.java`, `dto/EnbwChargePointDto.java`, `dto/EnbwConnectorDto.java`
- `ChargingSourceException.java`, `ChargingRateLimitException.java`
- `ChargingAreaFilter.java` — Kreis-Schnitt + Mindestleistung (reine Funktion)
- `ChargingOccupancyTracker.java` — Belegungsübergänge
- `ChargingFavoriteService.java`
- `ChargingSnapshot.java`, `ChargingPollingService.java`
- `ChargingDtos.java`, `ChargingQueryService.java`, `ChargingController.java`
- `model/entity/ChargingPointOccupancy.java`, `model/entity/ChargingFavorite.java`
- `repository/ChargingPointOccupancyRepository.java`, `repository/ChargingFavoriteRepository.java`
- `entitystate/mapper/ChargingEntityMapper.java`; `EntitySource.CHARGING`
- `db/changelog/changes/20260919-0055-create-charging-tables.xml`
- Änderungen: `SecurityConfig`, `GlobalExceptionHandler`, `application.properties`, `db.changelog-master.xml`

**Frontend (neu):**
- `models/charging.model.ts`, `services/charging.service.ts`, `shared/charging-status.util.ts` (+ spec)
- `pages/tablet-charging/` (ts, html, scss, spec)
- `pages/charging/` (ts, html, scss, spec)
- `pages/admin-charging/` (ts, html, scss)
- Änderungen: `shared/tablet-views.ts`, `app.routes.ts`, `components/header/header.component.ts`, `styles.scss` (`$admin-scopes`)

---

### Task 1: Branch und Realtest gegen das EnBW-Backend (Fixture aufzeichnen)

**Files:**
- Create: `backend/src/test/resources/charging/enbw-area.json`
- Create: `backend/src/test/resources/charging/enbw-station.json`
- Create: `scripts/probe-enbw-charging.sh`

- [ ] **Step 1: Branch anlegen**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager && git checkout -b feature/charging-overview
```

- [ ] **Step 2: API-Key ermitteln**

Der Key ist in der EnBW-Web-App öffentlich eingebettet. Im Browser `https://www.enbw.com/elektromobilitaet/produkte/ladetarife` öffnen, DevTools → Netzwerk, die Karte bewegen, einen Request auf `enbw-emp.azure-api.net/emobility-public-api/api/v1/chargestations` anklicken und den Header `Ocp-Apim-Subscription-Key` kopieren. Als Env setzen: `export ENBW_KEY=<wert>`. Notiere auch den gesendeten `Origin`/`Referer`-Header.

- [ ] **Step 3: Probeskript schreiben**

```bash
#!/usr/bin/env bash
# Zeichnet die EnBW-Antworten als Test-Fixtures auf. Aufruf:
#   ENBW_KEY=... ./scripts/probe-enbw-charging.sh <lat> <lon>
set -euo pipefail
LAT="${1:?lat}"; LON="${2:?lon}"
BASE="https://enbw-emp.azure-api.net/emobility-public-api/api/v1"
OUT="backend/src/test/resources/charging"
mkdir -p "$OUT"
# ~10 km Kasten: 0.09° Breite, 0.14° Länge (bei ~50° N)
FROM_LAT=$(awk "BEGIN{print $LAT-0.09}"); TO_LAT=$(awk "BEGIN{print $LAT+0.09}")
FROM_LON=$(awk "BEGIN{print $LON-0.14}"); TO_LON=$(awk "BEGIN{print $LON+0.14}")
curl -sS --http1.1 -H "Ocp-Apim-Subscription-Key: $ENBW_KEY" -H "Origin: https://www.enbw.com" \
  "$BASE/chargestations?fromLat=$FROM_LAT&toLat=$TO_LAT&fromLon=$FROM_LON&toLon=$TO_LON&grouping=false" \
  -o "$OUT/enbw-area.json" -w "area: HTTP %{http_code}\n"
STATION_ID=$(python -c "import json;d=json.load(open('$OUT/enbw-area.json'));print(d[0]['stationId'])")
curl -sS --http1.1 -H "Ocp-Apim-Subscription-Key: $ENBW_KEY" -H "Origin: https://www.enbw.com" \
  "$BASE/chargestations/$STATION_ID" -o "$OUT/enbw-station.json" -w "station: HTTP %{http_code}\n"
echo "Fixtures unter $OUT"
```

- [ ] **Step 4: Skript ausführen und Antwort prüfen**

Run: `chmod +x scripts/probe-enbw-charging.sh && ENBW_KEY=$ENBW_KEY ./scripts/probe-enbw-charging.sh <lat> <lon>`
Expected: zweimal `HTTP 200`, beide JSON-Dateien nicht leer.

Dann `head -c 1500 backend/src/test/resources/charging/enbw-area.json` und `head -c 2500 .../enbw-station.json` lesen und die **Feldnamen notieren**. Die DTOs in Task 4 nehmen an: Liste = Array von Objekten mit `stationId`, `lat`, `lon`, `operator`, `numberOfChargePoints`, `availableChargePoints`, `maxPowerInKw`, `address {street, postalCode, city}`, `stationName` (evtl. fehlt); Detail = Objekt mit `stationId`, `chargePoints[] {evseId, status, connectors[] {plugTypeName, maxPowerInKw}}`. **Weicht die Antwort ab, werden in Task 4 die `@JsonProperty`-Namen an die Fixture angepasst, nicht umgekehrt.** Ist die Antwort eine gzip-Datei statt JSON, `--compressed` zu curl ergänzen. Antwortet die API 401/403, ist der Key falsch oder ein Pflicht-Header fehlt — den Header-Satz aus den DevTools 1:1 übernehmen.

- [ ] **Step 5: Personenbezug aus Fixtures entfernen und Fixtures kürzen**

Die Area-Fixture auf die ersten 5 Stationen kürzen (`python -c "import json;d=json.load(open(p));json.dump(d[:5],open(p,'w'),indent=1)"`), damit sie im Repo klein bleibt. Keine Zugangsdaten enthalten (nur öffentliche Daten).

- [ ] **Step 6: Commit**

```bash
git add scripts/probe-enbw-charging.sh backend/src/test/resources/charging/
git commit -F - <<'EOF'
chore(charging): EnBW-Probeskript und aufgezeichnete Fixtures

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 2: Liquibase-Tabellen, Entities, Repositories, EntitySource

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260919-0055-create-charging-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (vor `</databaseChangeLog>`)
- Create: `backend/src/main/java/com/household/manager/model/entity/ChargingFavorite.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/ChargingPointOccupancy.java`
- Create: `backend/src/main/java/com/household/manager/repository/ChargingFavoriteRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/ChargingPointOccupancyRepository.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`

- [ ] **Step 1: Changeset schreiben** (zwei Changesets — MariaDB committet DDL implizit, Muster `20260908-0052`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260919-0055-create-charging-favorite" author="claude">
        <comment>
            Favorisierte Ladesaeulen. Name/Betreiber/Koordinaten werden beim Favorisieren aus
            dem aktuellen API-Stand uebernommen, damit die Liste auch bei Quellenausfall lesbar ist.
        </comment>
        <createTable tableName="charging_favorite">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="station_id" type="VARCHAR(191)">
                <constraints nullable="false" unique="true"
                             uniqueConstraintName="uk_charging_favorite_station_id"/>
            </column>
            <column name="display_name" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="operator" type="VARCHAR(255)"/>
            <column name="lat" type="DOUBLE">
                <constraints nullable="false"/>
            </column>
            <column name="lon" type="DOUBLE">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <rollback>
            <dropTable tableName="charging_favorite"/>
        </rollback>
    </changeSet>

    <changeSet id="20260919-0055-create-charging-point-occupancy" author="claude">
        <comment>
            Belegungsbeginn je Ladepunkt eines Favoriten. occupied_since NULL = beim ersten
            Poll schon belegt (Beginn unbekannt, Anzeige "seit mind."). Kein FK auf
            charging_favorite - beim Entfernen eines Favoriten raeumt der Service auf.
        </comment>
        <createTable tableName="charging_point_occupancy">
            <column name="chargepoint_id" type="VARCHAR(191)">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="station_id" type="VARCHAR(191)">
                <constraints nullable="false"/>
            </column>
            <column name="occupied_since" type="DATETIME"/>
            <column name="first_seen_occupied_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <createIndex tableName="charging_point_occupancy" indexName="ix_charging_point_occupancy_station">
            <column name="station_id"/>
        </createIndex>
        <rollback>
            <dropTable tableName="charging_point_occupancy"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Master-Changelog erweitern** — vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Ladesaeulen-Uebersicht: Favoriten und Belegungsbeginn je Ladepunkt -->
    <include file="db/changelog/changes/20260919-0055-create-charging-tables.xml"/>
```

- [ ] **Step 3: Entities schreiben**

`ChargingFavorite.java`:
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

import java.time.Instant;

/** Eine favorisierte Ladesaeule (stationId = stabile Id der Quelle). */
@Entity
@Table(name = "charging_favorite")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(length = 255)
    private String operator;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

`ChargingPointOccupancy.java`:
```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Seit wann ein Ladepunkt belegt ist. {@code occupiedSince == null} heisst: beim ersten
 * Poll schon belegt, der Beginn ist unbekannt - die Anzeige sagt dann "seit mind.".
 */
@Entity
@Table(name = "charging_point_occupancy")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargingPointOccupancy {

    @Id
    @Column(name = "chargepoint_id", nullable = false, length = 191)
    private String chargePointId;

    @Column(name = "station_id", nullable = false, length = 191)
    private String stationId;

    @Column(name = "occupied_since")
    private Instant occupiedSince;

    @Column(name = "first_seen_occupied_at", nullable = false)
    private Instant firstSeenOccupiedAt;
}
```

- [ ] **Step 4: Repositories schreiben** (müssen in `com.household.manager.repository` liegen — `JpaConfig` scannt nur dort)

```java
package com.household.manager.repository;

import com.household.manager.model.entity.ChargingFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChargingFavoriteRepository extends JpaRepository<ChargingFavorite, Long> {

    List<ChargingFavorite> findAllByOrderByCreatedAtAscIdAsc();

    Optional<ChargingFavorite> findByStationId(String stationId);

    boolean existsByStationId(String stationId);
}
```

```java
package com.household.manager.repository;

import com.household.manager.model.entity.ChargingPointOccupancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ChargingPointOccupancyRepository extends JpaRepository<ChargingPointOccupancy, String> {

    List<ChargingPointOccupancy> findByStationId(String stationId);

    /**
     * Bulk-Delete mit eigener Transaktion (Muster WasteCollectionEventRepository): eine
     * abgeleitete deleteBy-Methode brachte KEINE Transaktion mit und wuerfe gefangen
     * TransactionRequiredException - das Aufraeumen waere still wirkungslos.
     */
    @Transactional
    @Modifying
    @Query("delete from ChargingPointOccupancy o where o.stationId = :stationId")
    int deleteByStationId(String stationId);
}
```

- [ ] **Step 5: `EntitySource.CHARGING` ergänzen** — nach `BLINK` in `EntitySource.java`:

```java
    /** Blink-Kameras (Scharf-Status via blink-vision-Sidecar). */
    BLINK,
    /** Oeffentliche Ladesaeulen (inoffizielles EnBW-Backend), je Favorit sensor.charging_<stationId>_free. */
    CHARGING
```

- [ ] **Step 6: Kompilieren**

Run: `cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q compile`
Expected: BUILD SUCCESS (keine Ausgabe bei `-q`).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/Charging*.java backend/src/main/java/com/household/manager/repository/Charging*.java backend/src/main/java/com/household/manager/entitystate/EntitySource.java
git commit -F - <<'EOF'
feat(charging): Tabellen, Entities, Repositories und EntitySource.CHARGING

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 3: Domänenmodell, Properties, Exceptions, Settings (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/charging/ChargingProperties.java`
- Create: `.../charging/ChargePointStatus.java`, `ChargePoint.java`, `ChargingStation.java`, `ChargingStationDetails.java`, `BoundingBox.java`
- Create: `.../charging/ChargingSourceException.java`, `ChargingRateLimitException.java`
- Create: `.../charging/ChargingSettings.java`, `ChargingSettingsService.java`
- Test: `backend/src/test/java/com/household/manager/charging/ChargingSettingsServiceTest.java`, `BoundingBoxTest.java`
- Modify: `backend/src/main/resources/application.properties`

- [ ] **Step 1: Properties und Modell schreiben**

`ChargingProperties.java`:
```java
package com.household.manager.charging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Verbindungs- und Poll-Einstellungen der Ladesaeulen-Anbindung. Zuhause, Radius und
 * Mindestleistung stehen bewusst NICHT hier, sondern in der DB ({@link ChargingSettingsService}).
 */
@Configuration
@ConfigurationProperties(prefix = "charging")
@Data
public class ChargingProperties {

    private boolean enabled = true;
    private Enbw enbw = new Enbw();
    private long areaPollSeconds = 300;
    private long favoritePollSeconds = 60;
    private long initialDelayMs = 25000;
    private int httpTimeoutMs = 10000;

    @Data
    public static class Enbw {
        private String baseUrl = "https://enbw-emp.azure-api.net/emobility-public-api/api/v1";
        /** Oeffentlich in der EnBW-Web-App eingebetteter Key; kein Geheimnis, aber per Env nachziehbar. */
        private String apiKey = "";
        private String origin = "https://www.enbw.com";
    }
}
```

`ChargePointStatus.java`:
```java
package com.household.manager.charging;

/** Zustand eines einzelnen Ladepunkts. Unbekannte Quelltexte werden UNKNOWN, nie FREE. */
public enum ChargePointStatus {
    FREE, OCCUPIED, OUT_OF_SERVICE, UNKNOWN
}
```

`ChargePoint.java`:
```java
package com.household.manager.charging;

/** Ein Ladepunkt (EVSE) einer Station. */
public record ChargePoint(String chargePointId, ChargePointStatus status, Double maxPowerKw, String connector) {
}
```

`ChargingStation.java`:
```java
package com.household.manager.charging;

/** Ein Standort aus der Umkreis-Suche (nur Zaehlwerte, keine Ladepunkte einzeln). */
public record ChargingStation(String stationId, String name, String operator, String address,
                              double lat, double lon, Double maxPowerKw, int total, int free) {
}
```

`ChargingStationDetails.java`:
```java
package com.household.manager.charging;

import java.util.List;

/** Detailantwort zu einer Station: die Ladepunkte einzeln. */
public record ChargingStationDetails(String stationId, List<ChargePoint> chargePoints) {
}
```

`BoundingBox.java`:
```java
package com.household.manager.charging;

/**
 * Rechteck fuer die Umkreis-Suche. Die Quelle kennt nur Rechtecke; der Kreis-Schnitt
 * passiert danach in {@link ChargingAreaFilter}.
 */
public record BoundingBox(double fromLat, double toLat, double fromLon, double toLon) {

    private static final double KM_PER_DEGREE_LAT = 111.32;

    /** Umschliessendes Rechteck eines Kreises; die Laengengrad-Spanne haengt vom Breitengrad ab. */
    public static BoundingBox around(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / KM_PER_DEGREE_LAT;
        double kmPerDegreeLon = KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat));
        double dLon = radiusKm / Math.max(kmPerDegreeLon, 0.001);
        return new BoundingBox(lat - dLat, lat + dLat, lon - dLon, lon + dLon);
    }
}
```

`ChargingSourceException.java`:
```java
package com.household.manager.charging;

/** Die Ladesaeulen-Quelle hat nicht oder unbrauchbar geantwortet (wird zu 502). */
public class ChargingSourceException extends RuntimeException {
    public ChargingSourceException(String message) {
        super(message);
    }

    public ChargingSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ChargingRateLimitException.java` — erbt von `TooManyRequestsException`, damit der bestehende 429-Handler greift:
```java
package com.household.manager.charging;

import com.household.manager.exception.TooManyRequestsException;

/** Die Quelle hat 429 gemeldet oder der Mindestabstand des erzwungenen Abrufs ist unterschritten. */
public class ChargingRateLimitException extends TooManyRequestsException {
    public ChargingRateLimitException(String message) {
        super(message);
    }
}
```

`ChargingSettings.java`:
```java
package com.household.manager.charging;

/** Zuhause, Radius und Mindestleistung, wie sie in der DB stehen. Koordinaten null = nicht konfiguriert. */
public record ChargingSettings(Double homeLatitude, Double homeLongitude, double radiusKm, double minPowerKw) {

    public boolean isConfigured() {
        return homeLatitude != null && homeLongitude != null;
    }
}
```

- [ ] **Step 2: Failing Tests für Settings-Service und BoundingBox schreiben**

`ChargingSettingsServiceTest.java`:
```java
package com.household.manager.charging;

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
class ChargingSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;

    private ChargingSettings settingsFrom(Map<String, String> stored) {
        when(applicationSettings.getSettingsByCategory("CHARGING")).thenReturn(stored);
        return new ChargingSettingsService(applicationSettings, auditService).getSettings();
    }

    @Test
    void ohneWerteGeltenDieDefaultsUndNichtsIstKonfiguriert() {
        ChargingSettings settings = settingsFrom(Map.of());

        assertThat(settings.isConfigured()).isFalse();
        assertThat(settings.radiusKm()).isEqualTo(10.0);
        assertThat(settings.minPowerKw()).isEqualTo(50.0);
    }

    @Test
    void unlesbarerRadiusFaelltAufDenDefaultZurueck() {
        ChargingSettings settings = settingsFrom(Map.of("radius_km", "zehn"));

        assertThat(settings.radiusKm()).isEqualTo(10.0);
    }

    @Test
    void radiusAusserhalbDerSchrankenFaelltAufDenDefaultZurueck() {
        assertThat(settingsFrom(Map.of("radius_km", "500")).radiusKm()).isEqualTo(10.0);
        assertThat(settingsFrom(Map.of("radius_km", "0")).radiusKm()).isEqualTo(10.0);
    }

    @Test
    void halbeKoordinateZaehltAlsNichtKonfiguriert() {
        ChargingSettings settings = settingsFrom(Map.of("home_lat", "50.1"));

        assertThat(settings.isConfigured()).isFalse();
        assertThat(settings.homeLongitude()).isNull();
    }

    @Test
    void nanKoordinateZaehltAlsNichtKonfiguriert() {
        ChargingSettings settings = settingsFrom(Map.of("home_lat", "NaN", "home_lon", "8.5"));

        assertThat(settings.isConfigured()).isFalse();
    }

    @Test
    void speichernSchreibtAlleVierWerteUndAuditiert() {
        ChargingSettingsService service = new ChargingSettingsService(applicationSettings, auditService);

        service.saveSettings(new ChargingSettings(50.1, 8.5, 12.0, 100.0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(applicationSettings).saveSettings(eq("CHARGING"), captor.capture());
        assertThat(captor.getValue()).containsEntry("home_lat", "50.1").containsEntry("home_lon", "8.5")
                .containsEntry("radius_km", "12.0").containsEntry("min_power_kw", "100.0");
        verify(auditService).record(eq("charging.settings.update"), org.mockito.ArgumentMatchers.anyString());
    }
}
```

`BoundingBoxTest.java`:
```java
package com.household.manager.charging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BoundingBoxTest {

    @Test
    void umschliesstDenKreisMitBreitengradabhaengigerLaengenspanne() {
        BoundingBox box = BoundingBox.around(50.0, 8.0, 10.0);

        assertThat(box.toLat() - box.fromLat()).isCloseTo(0.1797, within(0.001));
        // Bei 50° N ist ein Laengengrad nur cos(50°) ≈ 0,643 so lang wie ein Breitengrad.
        assertThat(box.toLon() - box.fromLon()).isCloseTo(0.2795, within(0.002));
    }
}
```

- [ ] **Step 3: Tests laufen lassen, Fehlschlag bestätigen**

Run: `cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest='ChargingSettingsServiceTest,BoundingBoxTest'`
Expected: Kompilierfehler „cannot find symbol ChargingSettingsService".

- [ ] **Step 4: `ChargingSettingsService` schreiben**

```java
package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uebersetzt zwischen {@link ChargingSettings} und den String-Werten in application_settings.
 * Lesen wirft nie: der Poller laeuft jede Minute, ein Tippfehler in der DB darf ihn nicht
 * lahmlegen (Muster TractiveHomeSettingsService).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingSettingsService {

    static final String CATEGORY = "CHARGING";
    static final String KEY_LAT = "home_lat";
    static final String KEY_LON = "home_lon";
    static final String KEY_RADIUS_KM = "radius_km";
    static final String KEY_MIN_POWER_KW = "min_power_kw";

    static final double DEFAULT_RADIUS_KM = 10.0;
    static final double DEFAULT_MIN_POWER_KW = 50.0;
    public static final double MIN_RADIUS_KM = 1.0;
    public static final double MAX_RADIUS_KM = 50.0;
    public static final double MIN_POWER_KW = 0.0;
    public static final double MAX_POWER_KW = 400.0;

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public ChargingSettings getSettings() {
        Map<String, String> values = applicationSettings.getSettingsByCategory(CATEGORY);
        Double lat = coordinate(values.get(KEY_LAT), KEY_LAT, 90);
        Double lon = coordinate(values.get(KEY_LON), KEY_LON, 180);
        if (lat == null || lon == null) {
            lat = null;
            lon = null;
        }
        return new ChargingSettings(lat, lon,
                bounded(values.get(KEY_RADIUS_KM), KEY_RADIUS_KM, MIN_RADIUS_KM, MAX_RADIUS_KM, DEFAULT_RADIUS_KM),
                bounded(values.get(KEY_MIN_POWER_KW), KEY_MIN_POWER_KW, MIN_POWER_KW, MAX_POWER_KW, DEFAULT_MIN_POWER_KW));
    }

    public void saveSettings(ChargingSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_LAT, settings.homeLatitude() == null ? "" : String.valueOf(settings.homeLatitude()));
        values.put(KEY_LON, settings.homeLongitude() == null ? "" : String.valueOf(settings.homeLongitude()));
        values.put(KEY_RADIUS_KM, String.valueOf(settings.radiusKm()));
        values.put(KEY_MIN_POWER_KW, String.valueOf(settings.minPowerKw()));
        applicationSettings.saveSettings(CATEGORY, values);
        auditService.record("charging.settings.update", settings.isConfigured()
                ? settings.homeLatitude() + ", " + settings.homeLongitude() + ", " + settings.radiusKm()
                + " km, ab " + settings.minPowerKw() + " kW"
                : "Koordinaten entfernt");
        log.info("Ladesaeulen-Einstellungen gespeichert");
    }

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

    private double bounded(String raw, String key, double min, double max, double defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < min || value > max) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
                return defaultValue;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, key, defaultValue);
            return defaultValue;
        }
    }
}
```

- [ ] **Step 5: Properties ergänzen** — ans Ende von `application.properties` (nach dem `presence.*`-Block):

```properties
# --- Ladesaeulen-Uebersicht (inoffizielles EnBW-Backend) ---
charging.enabled=${CHARGING_ENABLED:true}
charging.enbw.base-url=https://enbw-emp.azure-api.net/emobility-public-api/api/v1
charging.enbw.api-key=${CHARGING_ENBW_API_KEY:}
charging.enbw.origin=https://www.enbw.com
charging.area-poll-seconds=300
charging.favorite-poll-seconds=60
charging.initial-delay-ms=25000
charging.http-timeout-ms=10000
```

Den in Task 1 ermittelten Key als Default eintragen (`charging.enbw.api-key=${CHARGING_ENBW_API_KEY:<key>}`) — er ist öffentlich in der Web-App eingebettet, kein Geheimnis.

- [ ] **Step 6: Tests laufen lassen**

Run: `mvn -q test -Dtest='ChargingSettingsServiceTest,BoundingBoxTest'`
Expected: `Tests run: 7, Failures: 0`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/charging backend/src/test/java/com/household/manager/charging backend/src/main/resources/application.properties
git commit -F - <<'EOF'
feat(charging): Domaenenmodell, Properties und DB-Einstellungen

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 4: `ChargingStationSource` und `EnbwChargingClient` (Parser gegen Fixture, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/charging/ChargingStationSource.java`
- Create: `.../charging/dto/EnbwStationDto.java`, `EnbwAddressDto.java`, `EnbwStationDetailsDto.java`, `EnbwChargePointDto.java`, `EnbwConnectorDto.java`
- Create: `.../charging/EnbwChargingClient.java`
- Test: `backend/src/test/java/com/household/manager/charging/EnbwChargingClientTest.java`

- [ ] **Step 1: Interface schreiben**

```java
package com.household.manager.charging;

import java.util.List;

/**
 * Die einzige Stelle, an der das Modul mit einer Ladesaeulen-Quelle spricht. Heute EnBW
 * (inoffiziell); die Quelle ist hinter diesem Interface austauschbar (Muster WebPushClient).
 */
public interface ChargingStationSource {

    /** Standorte im Rechteck; die Mindestleistung darf die Quelle vorfiltern, muss aber nicht. */
    List<ChargingStation> searchArea(BoundingBox box, double minPowerKw);

    /** Ladepunkte einer Station einzeln. */
    ChargingStationDetails stationDetails(String stationId);
}
```

- [ ] **Step 2: DTOs schreiben** — **Feldnamen gegen die Fixtures aus Task 1 abgleichen** und bei Abweichung die `@JsonProperty`-Werte anpassen.

`dto/EnbwAddressDto.java`:
```java
package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwAddressDto(String street, String postalCode, String city) {
}
```

`dto/EnbwStationDto.java`:
```java
package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwStationDto(
        @JsonProperty("stationId") String stationId,
        @JsonProperty("stationName") String stationName,
        @JsonProperty("operator") String operator,
        @JsonProperty("lat") Double lat,
        @JsonProperty("lon") Double lon,
        @JsonProperty("maxPowerInKw") Double maxPowerInKw,
        @JsonProperty("numberOfChargePoints") Integer numberOfChargePoints,
        @JsonProperty("availableChargePoints") Integer availableChargePoints,
        @JsonProperty("address") EnbwAddressDto address) {
}
```

`dto/EnbwConnectorDto.java`:
```java
package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwConnectorDto(@JsonProperty("plugTypeName") String plugTypeName,
                               @JsonProperty("maxPowerInKw") Double maxPowerInKw) {
}
```

`dto/EnbwChargePointDto.java`:
```java
package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwChargePointDto(@JsonProperty("evseId") String evseId,
                                 @JsonProperty("status") String status,
                                 @JsonProperty("connectors") List<EnbwConnectorDto> connectors) {
}
```

`dto/EnbwStationDetailsDto.java`:
```java
package com.household.manager.charging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnbwStationDetailsDto(@JsonProperty("stationId") String stationId,
                                    @JsonProperty("chargePoints") List<EnbwChargePointDto> chargePoints) {
}
```

- [ ] **Step 3: Failing Test schreiben** (Parser gegen Fixture, Status-Mapping, HTTP/1.1)

```java
package com.household.manager.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class EnbwChargingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String fixture(String name) throws IOException {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/charging/" + name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void httpClientIstAufHttp11Festgelegt() {
        assertThat(EnbwChargingClient.createHttpClient(5000).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void parstDieAufgezeichneteUmkreisAntwort() throws IOException {
        List<ChargingStation> stations = EnbwChargingClient.parseStations(mapper, fixture("enbw-area.json"));

        assertThat(stations).isNotEmpty();
        ChargingStation first = stations.get(0);
        assertThat(first.stationId()).isNotBlank();
        assertThat(first.lat()).isBetween(-90.0, 90.0);
        assertThat(first.total()).isGreaterThanOrEqualTo(first.free());
        assertThat(first.name()).isNotBlank();
    }

    @Test
    void parstDieAufgezeichneteDetailAntwort() throws IOException {
        ChargingStationDetails details = EnbwChargingClient.parseDetails(mapper, fixture("enbw-station.json"));

        assertThat(details.stationId()).isNotBlank();
        assertThat(details.chargePoints()).isNotEmpty();
        assertThat(details.chargePoints()).allSatisfy(cp -> {
            assertThat(cp.chargePointId()).isNotBlank();
            assertThat(cp.status()).isNotNull();
        });
    }

    @Test
    void unbekannterStatusWirdUnknownNieFree() {
        assertThat(EnbwChargingClient.mapStatus("AVAILABLE")).isEqualTo(ChargePointStatus.FREE);
        assertThat(EnbwChargingClient.mapStatus("OCCUPIED")).isEqualTo(ChargePointStatus.OCCUPIED);
        assertThat(EnbwChargingClient.mapStatus("OUT_OF_SERVICE")).isEqualTo(ChargePointStatus.OUT_OF_SERVICE);
        assertThat(EnbwChargingClient.mapStatus("RESERVED")).isEqualTo(ChargePointStatus.UNKNOWN);
        assertThat(EnbwChargingClient.mapStatus(null)).isEqualTo(ChargePointStatus.UNKNOWN);
    }

    @Test
    void stationOhneNamenBekommtBetreiberUndStrasse() throws IOException {
        String json = "[{\"stationId\":\"DE*X*1\",\"operator\":\"Lidl\",\"lat\":50.0,\"lon\":8.0,"
                + "\"maxPowerInKw\":150,\"numberOfChargePoints\":2,\"availableChargePoints\":1,"
                + "\"address\":{\"street\":\"Hauptstr. 1\",\"postalCode\":\"12345\",\"city\":\"Stadt\"}}]";

        ChargingStation station = EnbwChargingClient.parseStations(mapper, json).get(0);

        assertThat(station.name()).isEqualTo("Lidl Hauptstr. 1");
        assertThat(station.address()).isEqualTo("Hauptstr. 1, 12345 Stadt");
    }
}
```

- [ ] **Step 4: Test laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=EnbwChargingClientTest`
Expected: Kompilierfehler „cannot find symbol EnbwChargingClient".

- [ ] **Step 5: Client schreiben**

```java
package com.household.manager.charging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.charging.dto.EnbwAddressDto;
import com.household.manager.charging.dto.EnbwChargePointDto;
import com.household.manager.charging.dto.EnbwStationDetailsDto;
import com.household.manager.charging.dto.EnbwStationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Inoffizielles EnBW-mobility+-Backend. Alle EnBW-Spezifika (URLs, Header, Feldnamen,
 * Statustexte) leben ausschliesslich hier. Kein Login; nur der oeffentlich in der Web-App
 * eingebettete API-Key.
 */
@Component
@Slf4j
public class EnbwChargingClient implements ChargingStationSource {

    private final ChargingProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public EnbwChargingClient(ChargingProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = createHttpClient(properties.getHttpTimeoutMs());
    }

    /** HTTP/1.1 erzwungen (Muster BlinkSidecarClient); eigene Methode fuer den Regressionstest. */
    static HttpClient createHttpClient(int timeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public List<ChargingStation> searchArea(BoundingBox box, double minPowerKw) {
        String query = String.format(Locale.ROOT,
                "/chargestations?fromLat=%.6f&toLat=%.6f&fromLon=%.6f&toLon=%.6f&grouping=false",
                box.fromLat(), box.toLat(), box.fromLon(), box.toLon());
        return parseStations(mapper, get(query));
    }

    @Override
    public ChargingStationDetails stationDetails(String stationId) {
        return parseDetails(mapper, get("/chargestations/" + URLEncoder.encode(stationId, StandardCharsets.UTF_8)));
    }

    static List<ChargingStation> parseStations(ObjectMapper mapper, String json) {
        List<EnbwStationDto> dtos;
        try {
            dtos = mapper.readValue(json, new TypeReference<List<EnbwStationDto>>() { });
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW-Umkreisantwort nicht lesbar: " + ex.getMessage(), ex);
        }
        List<ChargingStation> stations = new ArrayList<>();
        for (EnbwStationDto dto : dtos) {
            if (dto.stationId() == null || dto.lat() == null || dto.lon() == null) {
                continue;
            }
            int total = dto.numberOfChargePoints() == null ? 0 : dto.numberOfChargePoints();
            int free = dto.availableChargePoints() == null ? 0 : Math.min(dto.availableChargePoints(), total);
            stations.add(new ChargingStation(dto.stationId(), displayName(dto), dto.operator(),
                    address(dto.address()), dto.lat(), dto.lon(), dto.maxPowerInKw(), total, free));
        }
        return stations;
    }

    static ChargingStationDetails parseDetails(ObjectMapper mapper, String json) {
        EnbwStationDetailsDto dto;
        try {
            dto = mapper.readValue(json, EnbwStationDetailsDto.class);
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW-Detailantwort nicht lesbar: " + ex.getMessage(), ex);
        }
        List<ChargePoint> points = new ArrayList<>();
        if (dto.chargePoints() != null) {
            for (EnbwChargePointDto cp : dto.chargePoints()) {
                if (cp.evseId() == null) {
                    continue;
                }
                Double power = cp.connectors() == null ? null : cp.connectors().stream()
                        .map(c -> c.maxPowerInKw()).filter(p -> p != null).max(Double::compare).orElse(null);
                String connector = cp.connectors() == null || cp.connectors().isEmpty()
                        ? null : cp.connectors().get(0).plugTypeName();
                points.add(new ChargePoint(cp.evseId(), mapStatus(cp.status()), power, connector));
            }
        }
        return new ChargingStationDetails(dto.stationId(), points);
    }

    /** Fail-safe: nur bekannte Texte werden gedeutet, alles andere ist UNKNOWN - nie FREE. */
    static ChargePointStatus mapStatus(String raw) {
        if (raw == null) {
            return ChargePointStatus.UNKNOWN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "AVAILABLE" -> ChargePointStatus.FREE;
            case "OCCUPIED", "CHARGING" -> ChargePointStatus.OCCUPIED;
            case "OUT_OF_SERVICE", "OUTOFSERVICE", "OFFLINE" -> ChargePointStatus.OUT_OF_SERVICE;
            default -> ChargePointStatus.UNKNOWN;
        };
    }

    private static String displayName(EnbwStationDto dto) {
        if (dto.stationName() != null && !dto.stationName().isBlank()) {
            return dto.stationName().trim();
        }
        String street = dto.address() == null ? null : dto.address().street();
        return Stream.of(dto.operator(), street)
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + " " + b)
                .orElse(dto.stationId());
    }

    private static String address(EnbwAddressDto address) {
        if (address == null) {
            return null;
        }
        String cityLine = Stream.of(address.postalCode(), address.city())
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + " " + b).orElse(null);
        return Stream.of(address.street(), cityLine)
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + ", " + b).orElse(null);
    }

    private String get(String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEnbw().getBaseUrl() + pathAndQuery))
                .timeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .header("Ocp-Apim-Subscription-Key", properties.getEnbw().getApiKey())
                .header("Origin", properties.getEnbw().getOrigin())
                .header("Referer", properties.getEnbw().getOrigin() + "/")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new ChargingRateLimitException("EnBW hat das Rate-Limit gemeldet (429).");
            }
            if (response.statusCode() / 100 != 2) {
                throw new ChargingSourceException("EnBW antwortet HTTP " + response.statusCode()
                        + " fuer " + pathAndQuery);
            }
            return response.body();
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW nicht erreichbar: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ChargingSourceException("EnBW-Abruf unterbrochen", ex);
        }
    }
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `mvn -q test -Dtest=EnbwChargingClientTest`
Expected: `Tests run: 5, Failures: 0`. Schlägt `parstDieAufgezeichnete…` fehl, passen die `@JsonProperty`-Namen nicht zur Fixture — an die Fixture angleichen (Task 1, Step 4).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/charging backend/src/test/java/com/household/manager/charging
git commit -F - <<'EOF'
feat(charging): EnBW-Client hinter ChargingStationSource, Parser gegen Fixture

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 5: `ChargingAreaFilter` (Kreis-Schnitt, Mindestleistung, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/charging/ChargingAreaFilter.java`
- Test: `backend/src/test/java/com/household/manager/charging/ChargingAreaFilterTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.charging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingAreaFilterTest {

    private static ChargingStation station(String id, double lat, double lon, Double kw) {
        return new ChargingStation(id, id, "Op", null, lat, lon, kw, 2, 1);
    }

    @Test
    void behaeltNurStationenImKreisUndUeberDerMindestleistung() {
        // 0.09° Breite ≈ 10 km; die Ecke des Rechtecks liegt ~14 km entfernt und faellt raus.
        List<ChargingStation> input = List.of(
                station("nah", 50.01, 8.0, 150.0),
                station("ecke", 50.09, 8.14, 150.0),
                station("schwach", 50.0, 8.01, 22.0),
                station("ohneLeistung", 50.0, 8.02, null));

        List<ChargingStation> result = ChargingAreaFilter.filter(input, 50.0, 8.0, 10.0, 50.0);

        assertThat(result).extracting(ChargingStation::stationId).containsExactly("nah");
    }

    @Test
    void mindestleistungNullLaesstStationenOhneLeistungsangabeDurch() {
        List<ChargingStation> result = ChargingAreaFilter.filter(
                List.of(station("ohneLeistung", 50.0, 8.0, null)), 50.0, 8.0, 10.0, 0.0);

        assertThat(result).hasSize(1);
    }

    @Test
    void distanzIstHaversine() {
        assertThat(ChargingAreaFilter.distanceMeters(50.0, 8.0, 50.0, 8.0)).isEqualTo(0.0);
        assertThat(ChargingAreaFilter.distanceMeters(50.0, 8.0, 50.009, 8.0)).isBetween(990.0, 1010.0);
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=ChargingAreaFilterTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Filter schreiben** (Haversine aus `GeoZone.distanceMeters` wiederverwendet — sie ist `public static`)

```java
package com.household.manager.charging;

import com.household.manager.tractive.GeoZone;

import java.util.List;

/**
 * Schneidet das Rechteck der Quelle auf den Kreis ums Zuhause und sortiert Stationen unter
 * der Mindestleistung aus. Reine Funktion, keine Abhaengigkeiten.
 */
public final class ChargingAreaFilter {

    private ChargingAreaFilter() {
    }

    public static List<ChargingStation> filter(List<ChargingStation> stations, double homeLat, double homeLon,
                                               double radiusKm, double minPowerKw) {
        double radiusMeters = radiusKm * 1000;
        return stations.stream()
                .filter(s -> distanceMeters(homeLat, homeLon, s.lat(), s.lon()) <= radiusMeters)
                .filter(s -> minPowerKw <= 0 || (s.maxPowerKw() != null && s.maxPowerKw() >= minPowerKw))
                .toList();
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        return GeoZone.distanceMeters(lat1, lon1, lat2, lon2);
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `mvn -q test -Dtest=ChargingAreaFilterTest`
Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/charging/ChargingAreaFilter.java backend/src/test/java/com/household/manager/charging/ChargingAreaFilterTest.java
git commit -F - <<'EOF'
feat(charging): Kreis-Schnitt und Mindestleistung als reine Funktion

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 6: `ChargingOccupancyTracker` (Belegungsübergänge, TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/charging/ChargingOccupancyTracker.java`
- Test: `backend/src/test/java/com/household/manager/charging/ChargingOccupancyTrackerTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.charging;

import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.repository.ChargingPointOccupancyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingOccupancyTrackerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-19T09:20:00Z");

    @Mock
    private ChargingPointOccupancyRepository repository;

    private ChargingOccupancyTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ChargingOccupancyTracker(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ChargePoint point(String id, ChargePointStatus status) {
        return new ChargePoint(id, status, 150.0, "CCS");
    }

    private static ChargingStationDetails details(ChargePointStatus status) {
        return new ChargingStationDetails("S1", List.of(point("P1", status)));
    }

    private static ChargingPointOccupancy existingRow() {
        return ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("S1").occupiedSince(EARLIER).firstSeenOccupiedAt(EARLIER).build();
    }

    @Test
    void erstsichtungBelegtSetztNurFirstSeenOhneBeginn() {
        when(repository.findByStationId("S1")).thenReturn(List.of());

        tracker.record(details(ChargePointStatus.OCCUPIED));

        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOccupiedSince()).isNull();
        assertThat(captor.getValue().getFirstSeenOccupiedAt()).isEqualTo(NOW);
    }

    @Test
    void uebergangFreiNachBelegtSetztDenBeginn() {
        when(repository.findByStationId("S1")).thenReturn(List.of());
        tracker.record(details(ChargePointStatus.FREE));

        tracker.record(details(ChargePointStatus.OCCUPIED));

        ArgumentCaptor<ChargingPointOccupancy> captor = ArgumentCaptor.forClass(ChargingPointOccupancy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOccupiedSince()).isEqualTo(NOW);
    }

    @Test
    void belegtNachFreiLoeschtDieZeile() {
        ChargingPointOccupancy existing = existingRow();
        when(repository.findByStationId("S1")).thenReturn(List.of(existing));

        tracker.record(details(ChargePointStatus.FREE));

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void weiterhinBelegtLaesstDieZeileUnberuehrt() {
        when(repository.findByStationId("S1")).thenReturn(List.of(existingRow()));

        tracker.record(details(ChargePointStatus.OCCUPIED));

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void unknownAendertNichtsAnEinerBestehendenZeile() {
        when(repository.findByStationId("S1")).thenReturn(List.of(existingRow()));

        tracker.record(details(ChargePointStatus.UNKNOWN));

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void ausserBetriebLoeschtDieZeile() {
        ChargingPointOccupancy existing = existingRow();
        when(repository.findByStationId("S1")).thenReturn(List.of(existing));

        tracker.record(details(ChargePointStatus.OUT_OF_SERVICE));

        verify(repository).delete(existing);
    }

    @Test
    void forgetStationLoeschtAlleZeilenDerStation() {
        tracker.forgetStation("S1");

        verify(repository).deleteByStationId("S1");
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=ChargingOccupancyTrackerTest`
Expected: Kompilierfehler.

- [ ] **Step 3: Tracker schreiben**

```java
package com.household.manager.charging;

import com.household.manager.model.entity.ChargingPointOccupancy;
import com.household.manager.repository.ChargingPointOccupancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Schreibt Belegungsuebergaenge je Ladepunkt. Regeln:
 * frei -> belegt (vorheriger Poll sah den Punkt frei): Zeile mit Beginn = jetzt;
 * erstmals belegt gesehen (kein Vorwissen, z. B. nach Neustart): Zeile OHNE Beginn - die
 * Anzeige sagt dann "seit mind."; belegt -> frei/ausser Betrieb: Zeile loeschen;
 * UNKNOWN: nichts anfassen (ein kurzer Statusaussetzer darf die Dauer nicht auf null setzen).
 */
@Service
@RequiredArgsConstructor
public class ChargingOccupancyTracker {

    private final ChargingPointOccupancyRepository repository;
    private final Clock clock;

    /** Zuletzt gesehener Status je Ladepunkt (nur im Speicher; entscheidet "Beginn bekannt?"). */
    private final Map<String, ChargePointStatus> lastSeen = new HashMap<>();

    public synchronized void record(ChargingStationDetails details) {
        Instant now = clock.instant();
        Map<String, ChargingPointOccupancy> existing = repository.findByStationId(details.stationId()).stream()
                .collect(Collectors.toMap(ChargingPointOccupancy::getChargePointId, Function.identity()));

        for (ChargePoint point : details.chargePoints()) {
            ChargingPointOccupancy row = existing.get(point.chargePointId());
            switch (point.status()) {
                case OCCUPIED -> {
                    if (row == null) {
                        boolean beginKnown = lastSeen.get(point.chargePointId()) == ChargePointStatus.FREE;
                        repository.save(ChargingPointOccupancy.builder()
                                .chargePointId(point.chargePointId())
                                .stationId(details.stationId())
                                .occupiedSince(beginKnown ? now : null)
                                .firstSeenOccupiedAt(now)
                                .build());
                    }
                }
                case FREE, OUT_OF_SERVICE -> {
                    if (row != null) {
                        repository.delete(row);
                    }
                }
                case UNKNOWN -> {
                    // bewusst nichts
                }
            }
            if (point.status() != ChargePointStatus.UNKNOWN) {
                lastSeen.put(point.chargePointId(), point.status());
            }
        }
    }

    public Map<String, ChargingPointOccupancy> occupancyFor(String stationId) {
        return repository.findByStationId(stationId).stream()
                .collect(Collectors.toMap(ChargingPointOccupancy::getChargePointId, Function.identity()));
    }

    /** Beim Entfernen eines Favoriten: Zeilen der Station verwerfen (kein FK, deshalb explizit). */
    public void forgetStation(String stationId) {
        repository.deleteByStationId(stationId);
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `mvn -q test -Dtest=ChargingOccupancyTrackerTest`
Expected: `Tests run: 7, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/charging/ChargingOccupancyTracker.java backend/src/test/java/com/household/manager/charging/ChargingOccupancyTrackerTest.java
git commit -F - <<'EOF'
feat(charging): Belegungsbeginn je Ladepunkt, "seit mind." bei unbekanntem Beginn

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 7: `ChargingSnapshot` und `ChargingFavoriteService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/charging/ChargingSnapshot.java`
- Create: `backend/src/main/java/com/household/manager/charging/ChargingFavoriteService.java`
- Test: `backend/src/test/java/com/household/manager/charging/ChargingFavoriteServiceTest.java`

- [ ] **Step 1: Snapshot schreiben** (Speicherstand des Pollers; wird von Favoriten-Service, Query-Service und Poller geteilt)

```java
package com.household.manager.charging;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Letzter erfolgreicher Stand der Quelle, nur im Speicher. Bleibt bei einem Fehler
 * erhalten (Frontend zeigt "Stand von"), ist nach einem Neustart leer bis zum ersten Poll.
 */
@Component
public class ChargingSnapshot {

    private volatile Map<String, ChargingStation> areaStations = Map.of();
    private volatile Map<String, ChargingStationDetails> favoriteDetails = Map.of();
    private volatile Instant lastAreaPolledAt;
    private volatile Instant lastFavoritePolledAt;

    public void updateArea(List<ChargingStation> stations, Instant at) {
        Map<String, ChargingStation> byId = new LinkedHashMap<>();
        stations.forEach(s -> byId.put(s.stationId(), s));
        areaStations = Map.copyOf(byId);
        lastAreaPolledAt = at;
    }

    public void updateFavoriteDetails(Map<String, ChargingStationDetails> details, Instant at) {
        favoriteDetails = Map.copyOf(details);
        lastFavoritePolledAt = at;
    }

    public List<ChargingStation> areaStations() {
        return List.copyOf(areaStations.values());
    }

    public Optional<ChargingStation> station(String stationId) {
        return Optional.ofNullable(areaStations.get(stationId));
    }

    public Optional<ChargingStationDetails> details(String stationId) {
        return Optional.ofNullable(favoriteDetails.get(stationId));
    }

    /** Juengster erfolgreicher Poll beider Pfade; null, solange nie einer gelang. */
    public Instant lastPolledAt() {
        Instant a = lastAreaPolledAt;
        Instant f = lastFavoritePolledAt;
        if (a == null) {
            return f;
        }
        return f == null || a.isAfter(f) ? a : f;
    }
}
```

- [ ] **Step 2: Failing Test für den Favoriten-Service schreiben**

```java
package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingFavoriteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @Mock
    private ChargingFavoriteRepository repository;
    @Mock
    private ChargingOccupancyTracker tracker;
    @Mock
    private AuditService auditService;

    private ChargingSnapshot snapshot;
    private ChargingFavoriteService service;

    @BeforeEach
    void setUp() {
        snapshot = new ChargingSnapshot();
        service = new ChargingFavoriteService(repository, tracker, snapshot, auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void favorisierenUebernimmtNameBetreiberUndKoordinatenAusDemSnapshot() {
        snapshot.updateArea(List.of(new ChargingStation("S1", "Lidl Hauptstr.", "Lidl", "Hauptstr. 1",
                50.0, 8.0, 150.0, 2, 1)), NOW);
        when(repository.existsByStationId("S1")).thenReturn(false);

        service.add("S1");

        ArgumentCaptor<ChargingFavorite> captor = ArgumentCaptor.forClass(ChargingFavorite.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("Lidl Hauptstr.");
        assertThat(captor.getValue().getOperator()).isEqualTo("Lidl");
        assertThat(captor.getValue().getLat()).isEqualTo(50.0);
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(auditService).record(eq("charging.favorite.add"), any());
    }

    @Test
    void unbekannteStationLaesstSichNichtFavorisieren() {
        assertThatThrownBy(() -> service.add("fremd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nicht in der aktuellen Umkreisliste");
        verify(repository, never()).save(any());
    }

    @Test
    void doppeltesFavorisierenIstIdempotent() {
        snapshot.updateArea(List.of(new ChargingStation("S1", "X", null, null, 50.0, 8.0, null, 1, 1)), NOW);
        when(repository.existsByStationId("S1")).thenReturn(true);

        service.add("S1");

        verify(repository, never()).save(any());
    }

    @Test
    void entfernenLoeschtFavoritUndBelegungszeilen() {
        ChargingFavorite favorite = ChargingFavorite.builder().id(1L).stationId("S1").displayName("X")
                .lat(50).lon(8).createdAt(NOW).build();
        when(repository.findByStationId("S1")).thenReturn(Optional.of(favorite));

        service.remove("S1");

        verify(repository).delete(favorite);
        verify(tracker).forgetStation("S1");
        verify(auditService).record(eq("charging.favorite.remove"), any());
    }

    @Test
    void entfernenEinesUnbekanntenFavoritenIstStill() {
        when(repository.findByStationId("S1")).thenReturn(Optional.empty());

        service.remove("S1");

        verify(repository, never()).delete(any());
        verify(tracker, never()).forgetStation(any());
    }
}
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=ChargingFavoriteServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 4: Service schreiben**

```java
package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Favorisierte Ladesaeulen. Name/Betreiber/Koordinaten kommen beim Favorisieren aus dem
 * aktuellen Snapshot, damit die Liste auch bei Quellenausfall lesbar bleibt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingFavoriteService {

    private final ChargingFavoriteRepository repository;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final AuditService auditService;
    private final Clock clock;

    public List<ChargingFavorite> list() {
        return repository.findAllByOrderByCreatedAtAscIdAsc();
    }

    /** Nur eine Station aus dem aktuellen Umkreis-Stand ist favorisierbar (400 sonst). */
    @Transactional
    public void add(String stationId) {
        ChargingStation station = snapshot.station(stationId).orElseThrow(() ->
                new IllegalArgumentException("Die Station " + stationId
                        + " ist nicht in der aktuellen Umkreisliste und kann nicht favorisiert werden."));
        if (repository.existsByStationId(stationId)) {
            return;
        }
        repository.save(ChargingFavorite.builder()
                .stationId(stationId)
                .displayName(station.name())
                .operator(station.operator())
                .lat(station.lat())
                .lon(station.lon())
                .createdAt(clock.instant())
                .build());
        auditService.record("charging.favorite.add", stationId + " (" + station.name() + ")");
        log.info("Ladesaeule {} favorisiert", stationId);
    }

    /** Entfernt Favorit und Belegungszeilen; die Entitaet markiert der Poller beim naechsten Lauf. */
    @Transactional
    public void remove(String stationId) {
        repository.findByStationId(stationId).ifPresent(favorite -> {
            repository.delete(favorite);
            tracker.forgetStation(stationId);
            auditService.record("charging.favorite.remove", stationId + " (" + favorite.getDisplayName() + ")");
            log.info("Ladesaeule {} aus den Favoriten entfernt", stationId);
        });
    }
}
```

- [ ] **Step 5: Tests laufen lassen**

Run: `mvn -q test -Dtest=ChargingFavoriteServiceTest`
Expected: `Tests run: 5, Failures: 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/charging backend/src/test/java/com/household/manager/charging
git commit -F - <<'EOF'
feat(charging): Snapshot im Speicher und Favoriten-Service

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 8: `ChargingEntityMapper` und `ChargingPollingService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/ChargingEntityMapper.java`
- Create: `backend/src/main/java/com/household/manager/charging/ChargingPollingService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/ChargingEntityMapperTest.java`
- Test: `backend/src/test/java/com/household/manager/charging/ChargingPollingServiceTest.java`

- [ ] **Step 1: Failing Mapper-Test schreiben**

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChargingEntityMapperTest {

    private final ChargingEntityMapper mapper = new ChargingEntityMapper();
    private final ChargingFavorite favorite = ChargingFavorite.builder()
            .stationId("DE*LDL*E123").displayName("Lidl Hauptstr.").operator("Lidl")
            .lat(50).lon(8).createdAt(Instant.EPOCH).build();

    @Test
    void bildetFreieLadepunkteAlsStateUndDenAeltestenBeginnAlsAttributAb() {
        Instant since = Instant.parse("2026-09-19T09:00:00Z");
        ChargingStationDetails details = new ChargingStationDetails("DE*LDL*E123", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS"),
                new ChargePoint("P2", ChargePointStatus.FREE, 150.0, "CCS")));
        Map<String, ChargingPointOccupancy> occupancy = Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("DE*LDL*E123").occupiedSince(since).firstSeenOccupiedAt(since).build());

        EntityStateUpdate update = mapper.map(favorite, details, occupancy);

        assertThat(update.entityId()).isEqualTo("sensor.charging_de_ldl_e123_free");
        assertThat(update.domain()).isEqualTo(EntityDomain.SENSOR);
        assertThat(update.source()).isEqualTo(EntitySource.CHARGING);
        assertThat(update.state()).isEqualTo("1");
        assertThat(update.attributes()).containsEntry("total", 2)
                .containsEntry("maxPowerKw", 150.0)
                .containsEntry("stationName", "Lidl Hauptstr.")
                .containsEntry("operator", "Lidl")
                .containsEntry("occupiedSince", since.toString())
                .doesNotContainKey("deviceClass");
    }

    @Test
    void ohneBekanntenBeginnFehltDerSchluesselOccupiedSince() {
        ChargingStationDetails details = new ChargingStationDetails("DE*LDL*E123", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, null, null)));
        Map<String, ChargingPointOccupancy> occupancy = Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("DE*LDL*E123").occupiedSince(null)
                .firstSeenOccupiedAt(Instant.EPOCH).build());

        EntityStateUpdate update = mapper.map(favorite, details, occupancy);

        assertThat(update.state()).isEqualTo("0");
        assertThat(update.attributes()).doesNotContainKey("occupiedSince").doesNotContainKey("maxPowerKw");
    }

    @Test
    void entityIdIstAusDerStationIdAbleitbar() {
        assertThat(ChargingEntityMapper.entityId("DE*LDL*E123")).isEqualTo("sensor.charging_de_ldl_e123_free");
    }
}
```

- [ ] **Step 2: Mapper schreiben**

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Je Favorit eine Entitaet {@code sensor.charging_<stationId>_free} mit State = freie
 * Ladepunkte. Bewusst KEIN deviceClass: die beiden projektweiten Auswertungen ("door" im
 * Modus-Check, "power" im Verbraucher-Service) passen nicht und wuerden falsche Kacheln
 * oder Warnungen erzeugen (siehe BlinkEntityMapper).
 */
@Component
public class ChargingEntityMapper {

    private static final String SUFFIX = "free";

    /** Einzige Definition der Entity-Id; Poller (Markieren) und Mapper fragen dieselbe Stelle. */
    public static String entityId(String stationId) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.CHARGING, stationId, SUFFIX);
    }

    public EntityStateUpdate map(ChargingFavorite favorite, ChargingStationDetails details,
                                 Map<String, ChargingPointOccupancy> occupancy) {
        long free = details.chargePoints().stream().filter(p -> p.status() == ChargePointStatus.FREE).count();
        Double maxPower = details.chargePoints().stream().map(ChargePoint::maxPowerKw)
                .filter(Objects::nonNull).max(Double::compare).orElse(null);
        Instant oldestSince = occupancy.values().stream().map(ChargingPointOccupancy::getOccupiedSince)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("total", details.chargePoints().size());
        if (maxPower != null) {
            attributes.put("maxPowerKw", maxPower);
        }
        attributes.put("stationName", favorite.getDisplayName());
        if (favorite.getOperator() != null) {
            attributes.put("operator", favorite.getOperator());
        }
        if (oldestSince != null) {
            attributes.put("occupiedSince", oldestSince.toString());
        }
        return EntityStateUpdate.builder()
                .entityId(entityId(favorite.getStationId()))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.CHARGING)
                .sourceRef(favorite.getStationId())
                .friendlyName(favorite.getDisplayName() + " frei")
                .state(String.valueOf(free))
                .attributes(attributes)
                .build();
    }
}
```

- [ ] **Step 3: Mapper-Test laufen lassen**

Run: `mvn -q test -Dtest=ChargingEntityMapperTest`
Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 4: Failing Poller-Test schreiben**

```java
package com.household.manager.charging;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.ChargingEntityMapper;
import com.household.manager.model.entity.ChargingFavorite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingPollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    @Mock
    private ChargingStationSource source;
    @Mock
    private ChargingSettingsService settingsService;
    @Mock
    private ChargingFavoriteService favoriteService;
    @Mock
    private ChargingOccupancyTracker tracker;
    @Mock
    private EntityStateService entityStateService;

    private ChargingProperties properties;
    private ChargingSnapshot snapshot;
    private ChargingPollingService service;

    private static ChargingFavorite favorite(String id) {
        return ChargingFavorite.builder().stationId(id).displayName(id).lat(50).lon(8).createdAt(NOW).build();
    }

    private static ChargingStationDetails details(String id, ChargePointStatus status) {
        return new ChargingStationDetails(id, List.of(new ChargePoint(id + "-1", status, 150.0, "CCS")));
    }

    @BeforeEach
    void setUp() {
        properties = new ChargingProperties();
        snapshot = new ChargingSnapshot();
        service = new ChargingPollingService(properties, source, settingsService, favoriteService, tracker,
                snapshot, new ChargingEntityMapper(), entityStateService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
    }

    @Test
    void umkreisPollFiltertAufKreisUndSchreibtDenSnapshot() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of(
                new ChargingStation("nah", "Nah", null, null, 50.01, 8.0, 150.0, 2, 1),
                new ChargingStation("fern", "Fern", null, null, 51.0, 8.0, 150.0, 2, 1)));

        service.pollArea();

        assertThat(snapshot.areaStations()).extracting(ChargingStation::stationId).containsExactly("nah");
        assertThat(snapshot.lastPolledAt()).isEqualTo(NOW);
    }

    @Test
    void umkreisPollOhneZuhauseFragtDieQuelleNicht() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        service.pollArea();

        verify(source, never()).searchArea(any(), anyDouble());
    }

    @Test
    void favoritenPollMeldetEntitaetUndSchreibtBelegung() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1")));
        when(source.stationDetails("S1")).thenReturn(details("S1", ChargePointStatus.FREE));
        when(tracker.occupancyFor("S1")).thenReturn(Map.of());

        service.pollFavorites();

        verify(tracker).record(details("S1", ChargePointStatus.FREE));
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("sensor.charging_s1_free");
        assertThat(captor.getValue().state()).isEqualTo("1");
        assertThat(snapshot.details("S1")).isPresent();
    }

    @Test
    void fehlerBeiEinemFavoritenStoertDieAnderenNichtUndMarkiertNurIhnUnavailable() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1"), favorite("S2")));
        when(source.stationDetails("S1")).thenThrow(new ChargingSourceException("weg"));
        when(source.stationDetails("S2")).thenReturn(details("S2", ChargePointStatus.OCCUPIED));
        when(tracker.occupancyFor("S2")).thenReturn(Map.of());
        // S1 wurde in einem frueheren Lauf schon gemeldet.
        service.pollFavorites();
        org.mockito.Mockito.reset(entityStateService);
        when(source.stationDetails("S1")).thenThrow(new ChargingSourceException("weg"));

        service.pollFavorites();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportState(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(u -> {
            assertThat(u.entityId()).isEqualTo("sensor.charging_s1_free");
            assertThat(u.state()).isEqualTo("unavailable");
            assertThat(u.attributes()).containsKey("stationName");
        });
        assertThat(captor.getAllValues()).anySatisfy(u -> {
            assertThat(u.entityId()).isEqualTo("sensor.charging_s2_free");
            assertThat(u.state()).isEqualTo("0");
        });
    }

    @Test
    void entfernterFavoritWirdEinmalUnavailableGemeldet() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1")));
        when(source.stationDetails("S1")).thenReturn(details("S1", ChargePointStatus.FREE));
        when(tracker.occupancyFor("S1")).thenReturn(Map.of());
        service.pollFavorites();
        org.mockito.Mockito.reset(entityStateService);
        when(favoriteService.list()).thenReturn(List.of());

        service.pollFavorites();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("sensor.charging_s1_free");
        assertThat(captor.getValue().state()).isEqualTo("unavailable");
    }

    @Test
    void rateLimitBrichtDenFavoritenDurchlaufSofortAb() {
        when(favoriteService.list()).thenReturn(List.of(favorite("S1"), favorite("S2")));
        when(source.stationDetails("S1")).thenThrow(new ChargingRateLimitException("429"));

        service.pollFavorites();

        verify(source, never()).stationDetails("S2");
    }

    @Test
    void refreshNowOhneZuhauseWirftIllegalState() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        assertThatThrownBy(() -> service.refreshNow()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshNowZweimalHintereinanderIstRateLimitiert() {
        when(source.searchArea(any(), anyDouble())).thenReturn(List.of());
        when(favoriteService.list()).thenReturn(List.of());
        service.refreshNow();

        assertThatThrownBy(() -> service.refreshNow()).isInstanceOf(ChargingRateLimitException.class);
    }

    @Test
    void refreshNowReichtQuellenfehlerDurch() {
        when(source.searchArea(any(), anyDouble())).thenThrow(new ChargingSourceException("weg"));

        assertThatThrownBy(() -> service.refreshNow()).isInstanceOf(ChargingSourceException.class);
    }
}
```

- [ ] **Step 5: Test laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=ChargingPollingServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 6: Poller schreiben**

```java
package com.household.manager.charging;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.ChargingEntityMapper;
import com.household.manager.model.entity.ChargingFavorite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zwei getrennte Poll-Pfade (Umkreis alle 5 min, Favoriten jede Minute), beide werfen nie.
 * Ein Fehler bei einem Favoriten stoert die anderen nicht; bei einem Rate-Limit bricht der
 * Durchlauf sofort ab (jeder weitere Abruf wuerde es hochschaukeln).
 */
@Service
@Slf4j
public class ChargingPollingService {

    private static final Duration MIN_FORCED_REFRESH_GAP = Duration.ofSeconds(15);

    private final ChargingProperties properties;
    private final ChargingStationSource source;
    private final ChargingSettingsService settingsService;
    private final ChargingFavoriteService favoriteService;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final ChargingEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final Clock clock;

    /** Zuletzt erfolgreich gemeldete Updates je Station; Basis fuer unavailable mit erhaltenen Attributen. */
    private final Map<String, EntityStateUpdate> lastReported = new HashMap<>();
    private volatile Instant lastForcedRefreshAt;

    public ChargingPollingService(ChargingProperties properties, ChargingStationSource source,
                                  ChargingSettingsService settingsService, ChargingFavoriteService favoriteService,
                                  ChargingOccupancyTracker tracker, ChargingSnapshot snapshot,
                                  ChargingEntityMapper mapper, EntityStateService entityStateService, Clock clock) {
        this.properties = properties;
        this.source = source;
        this.settingsService = settingsService;
        this.favoriteService = favoriteService;
        this.tracker = tracker;
        this.snapshot = snapshot;
        this.mapper = mapper;
        this.entityStateService = entityStateService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{${charging.area-poll-seconds:300} * 1000}",
            initialDelayString = "${charging.initial-delay-ms:25000}")
    public void scheduledArea() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            pollArea();
        } catch (Exception ex) {
            log.warn("Ladesaeulen-Umkreis-Poll fehlgeschlagen: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "#{${charging.favorite-poll-seconds:60} * 1000}",
            initialDelayString = "${charging.initial-delay-ms:25000}")
    public void scheduledFavorites() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            pollFavorites();
        } catch (Exception ex) {
            log.warn("Ladesaeulen-Favoriten-Poll fehlgeschlagen: {}", ex.getMessage());
        }
    }

    /** Ein Umkreis-Abruf; wirft bei Quellenfehler (der Scheduler faengt, refreshNow reicht durch). */
    public void pollArea() {
        ChargingSettings settings = settingsService.getSettings();
        if (!settings.isConfigured()) {
            log.debug("Ladesaeulen: kein Zuhause konfiguriert, kein Umkreis-Poll");
            return;
        }
        BoundingBox box = BoundingBox.around(settings.homeLatitude(), settings.homeLongitude(), settings.radiusKm());
        List<ChargingStation> raw = source.searchArea(box, settings.minPowerKw());
        List<ChargingStation> filtered = ChargingAreaFilter.filter(raw, settings.homeLatitude(),
                settings.homeLongitude(), settings.radiusKm(), settings.minPowerKw());
        snapshot.updateArea(filtered, clock.instant());
        log.debug("Ladesaeulen-Umkreis: {} von {} Stationen im Kreis", filtered.size(), raw.size());
    }

    /** Ein Favoriten-Durchlauf; Fehler je Favorit isoliert, Rate-Limit bricht ab. */
    public synchronized void pollFavorites() {
        List<ChargingFavorite> favorites = favoriteService.list();
        Map<String, ChargingStationDetails> details = new LinkedHashMap<>();
        Map<String, ChargingFavorite> current = new LinkedHashMap<>();
        favorites.forEach(f -> current.put(f.getStationId(), f));

        // Entfernte Favoriten: einmal unavailable, dann vergessen (sonst blieben sie ewig auf dem letzten Wert).
        for (String stationId : List.copyOf(lastReported.keySet())) {
            if (!current.containsKey(stationId)) {
                reportUnavailable(stationId);
                lastReported.remove(stationId);
            }
        }

        for (ChargingFavorite favorite : favorites) {
            String stationId = favorite.getStationId();
            try {
                ChargingStationDetails stationDetails = source.stationDetails(stationId);
                tracker.record(stationDetails);
                details.put(stationId, stationDetails);
                EntityStateUpdate update = mapper.map(favorite, stationDetails, tracker.occupancyFor(stationId));
                entityStateService.reportState(update);
                lastReported.put(stationId, update);
            } catch (ChargingRateLimitException ex) {
                log.warn("Ladesaeulen-Favoriten: Rate-Limit bei {}, Durchlauf abgebrochen", stationId);
                markUnavailable(stationId);
                break;
            } catch (Exception ex) {
                log.warn("Ladesaeulen-Favorit {} nicht lesbar: {}", stationId, ex.getMessage());
                markUnavailable(stationId);
            }
        }
        // Erfolgreich gelesene Details ersetzen den Stand; fehlgeschlagene behalten den alten Snapshot-Eintrag.
        Map<String, ChargingStationDetails> merged = new LinkedHashMap<>();
        for (String stationId : current.keySet()) {
            ChargingStationDetails fresh = details.get(stationId);
            if (fresh != null) {
                merged.put(stationId, fresh);
            } else {
                snapshot.details(stationId).ifPresent(old -> merged.put(stationId, old));
            }
        }
        if (!details.isEmpty() || favorites.isEmpty()) {
            snapshot.updateFavoriteDetails(merged, clock.instant());
        } else {
            snapshot.updateFavoriteDetails(merged, snapshot.lastPolledAt() == null ? null : snapshot.lastPolledAt());
        }
    }

    /**
     * Erzwungener Abruf (Knopf "Jetzt aktualisieren"). Reicht Fehler durch: 400 bei fehlendem
     * Zuhause (IllegalStateException), 429 bei Mindestabstand, 502 bei Quellenfehler.
     */
    public void refreshNow() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Die Ladesaeulen-Anbindung ist deaktiviert (charging.enabled=false).");
        }
        if (!settingsService.getSettings().isConfigured()) {
            throw new IllegalStateException("Es ist kein Zuhause fuer die Ladesaeulen-Uebersicht konfiguriert.");
        }
        Instant now = clock.instant();
        Instant last = lastForcedRefreshAt;
        if (last != null && now.isBefore(last.plus(MIN_FORCED_REFRESH_GAP))) {
            throw new ChargingRateLimitException("Der letzte Abruf war gerade eben. Bitte "
                    + MIN_FORCED_REFRESH_GAP.toSeconds() + " Sekunden warten.");
        }
        lastForcedRefreshAt = now;
        pollArea();
        pollFavorites();
    }

    private void markUnavailable(String stationId) {
        if (lastReported.containsKey(stationId)) {
            reportUnavailable(stationId);
        }
    }

    /** unavailable MIT erhaltenen Attributen (EntityStateWriter.upsert ueberschreibt sie sonst komplett). */
    private void reportUnavailable(String stationId) {
        EntityStateUpdate previous = lastReported.get(stationId);
        if (previous == null) {
            return;
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(previous.entityId())
                .domain(previous.domain())
                .source(previous.source())
                .sourceRef(previous.sourceRef())
                .friendlyName(previous.friendlyName())
                .state("unavailable")
                .attributes(previous.attributes())
                .build());
    }
}
```

Vereinfachung, die beim Schreiben umzusetzen ist: der `if (!details.isEmpty() || favorites.isEmpty()) … else …`-Block am Ende von `pollFavorites` ist umständlich. Ersetze ihn durch genau diese zwei Zeilen — der Zeitstempel rückt nur vor, wenn mindestens ein Favorit gelesen wurde oder es keine gibt:

```java
        Instant polledAt = (!details.isEmpty() || favorites.isEmpty()) ? clock.instant() : snapshot.lastPolledAt();
        snapshot.updateFavoriteDetails(merged, polledAt);
```

- [ ] **Step 7: Tests laufen lassen**

Run: `mvn -q test -Dtest='ChargingPollingServiceTest,ChargingEntityMapperTest'`
Expected: `Tests run: 12, Failures: 0`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/charging backend/src/main/java/com/household/manager/entitystate/mapper/ChargingEntityMapper.java backend/src/test/java/com/household/manager
git commit -F - <<'EOF'
feat(charging): Poller (Umkreis 5 min, Favoriten 60 s) und Entitaet je Favorit

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 9: DTOs, `ChargingQueryService`, Controller, Exception-Handler, Security (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/charging/ChargingDtos.java`
- Create: `backend/src/main/java/com/household/manager/charging/ChargingQueryService.java`
- Create: `backend/src/main/java/com/household/manager/charging/ChargingController.java`
- Create: `backend/src/main/java/com/household/manager/charging/ChargingSettingsController.java`
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/household/manager/charging/ChargingQueryServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (erweitern)

- [ ] **Step 1: DTOs schreiben** (alle Zeitstempel als `LocalDateTime` in Haushaltszeit, Muster `NetworkDtos`)

```java
package com.household.manager.charging;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** API-Vertrag von /v1/charging. */
public final class ChargingDtos {

    private ChargingDtos() {
    }

    public record HomeResponse(double lat, double lon) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChargePointResponse(String chargePointId, ChargePointStatus status, Double maxPowerKw,
                                      String connector, LocalDateTime occupiedSince, boolean minimumDuration) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StationResponse(String stationId, String name, String operator, String address,
                                  double lat, double lon, long distanceMeters, Double maxPowerKw,
                                  int total, int free, boolean favorite, List<ChargePointResponse> chargePoints) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StationsResponse(boolean configured, HomeResponse home, Double radiusKm, Double minPowerKw,
                                   LocalDateTime lastPolledAt, List<StationResponse> stations) {
    }

    public record SettingsRequest(Double homeLatitude, Double homeLongitude, double radiusKm, double minPowerKw) {
    }

    public record FavoriteResponse(String stationId, String displayName, String operator, double lat, double lon) {
    }
}
```

- [ ] **Step 2: Failing Test für den Query-Service schreiben**

```java
package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private ChargingSettingsService settingsService;
    @Mock
    private ChargingFavoriteService favoriteService;
    @Mock
    private ChargingOccupancyTracker tracker;

    private ChargingSnapshot snapshot;
    private ChargingQueryService service;

    @BeforeEach
    void setUp() {
        snapshot = new ChargingSnapshot();
        service = new ChargingQueryService(settingsService, favoriteService, tracker, snapshot,
                Clock.fixed(NOW, BERLIN));
    }

    @Test
    void ohneZuhauseAntwortetConfiguredFalse() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(null, null, 10.0, 50.0));

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.configured()).isFalse();
        assertThat(response.stations()).isEmpty();
    }

    @Test
    void ohneErfolgreichenPollIstLastPolledAtNull() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        when(favoriteService.list()).thenReturn(List.of());

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.configured()).isTrue();
        assertThat(response.lastPolledAt()).isNull();
        assertThat(response.stations()).isEmpty();
    }

    @Test
    void favoritenZuerstDannNachEntfernungMitLadepunktenUndDauer() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        snapshot.updateArea(List.of(
                new ChargingStation("fern", "Fern", null, null, 50.05, 8.0, 150.0, 2, 2),
                new ChargingStation("nah", "Nah", null, null, 50.01, 8.0, 150.0, 2, 2),
                new ChargingStation("fav", "Lidl", "Lidl", "Hauptstr. 1", 50.08, 8.0, 150.0, 2, 1)), NOW);
        ChargingFavorite favorite = ChargingFavorite.builder().stationId("fav").displayName("Lidl")
                .operator("Lidl").lat(50.08).lon(8.0).createdAt(NOW).build();
        when(favoriteService.list()).thenReturn(List.of(favorite));
        snapshot.updateFavoriteDetails(Map.of("fav", new ChargingStationDetails("fav", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, 150.0, "CCS"),
                new ChargePoint("P2", ChargePointStatus.FREE, 150.0, "CCS")))), NOW);
        Instant since = Instant.parse("2026-09-19T09:20:00Z");
        when(tracker.occupancyFor("fav")).thenReturn(Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("fav").occupiedSince(since).firstSeenOccupiedAt(since).build()));

        ChargingDtos.StationsResponse response = service.stations();

        assertThat(response.stations()).extracting(ChargingDtos.StationResponse::stationId)
                .containsExactly("fav", "nah", "fern");
        ChargingDtos.StationResponse fav = response.stations().get(0);
        assertThat(fav.favorite()).isTrue();
        assertThat(fav.chargePoints()).hasSize(2);
        ChargingDtos.ChargePointResponse p1 = fav.chargePoints().get(0);
        assertThat(p1.occupiedSince()).isEqualTo(LocalDateTime.ofInstant(since, BERLIN));
        assertThat(p1.minimumDuration()).isFalse();
        assertThat(response.stations().get(1).chargePoints()).isNull();
        assertThat(response.lastPolledAt()).isEqualTo(LocalDateTime.ofInstant(NOW, BERLIN));
    }

    @Test
    void favoritAusserhalbDesUmkreisesBleibtMitGespeichertenStammdatenInDerListe() {
        when(settingsService.getSettings()).thenReturn(new ChargingSettings(50.0, 8.0, 10.0, 50.0));
        ChargingFavorite favorite = ChargingFavorite.builder().stationId("weit").displayName("Weit weg")
                .operator("Op").lat(51.0).lon(9.0).createdAt(NOW).build();
        when(favoriteService.list()).thenReturn(List.of(favorite));
        snapshot.updateFavoriteDetails(Map.of("weit", new ChargingStationDetails("weit", List.of(
                new ChargePoint("P1", ChargePointStatus.OCCUPIED, null, null)))), NOW);
        when(tracker.occupancyFor("weit")).thenReturn(Map.of("P1", ChargingPointOccupancy.builder()
                .chargePointId("P1").stationId("weit").occupiedSince(null).firstSeenOccupiedAt(NOW).build()));

        ChargingDtos.StationsResponse response = service.stations();

        ChargingDtos.StationResponse weit = response.stations().get(0);
        assertThat(weit.name()).isEqualTo("Weit weg");
        assertThat(weit.total()).isEqualTo(1);
        assertThat(weit.free()).isEqualTo(0);
        assertThat(weit.chargePoints().get(0).minimumDuration()).isTrue();
        assertThat(weit.chargePoints().get(0).occupiedSince()).isEqualTo(LocalDateTime.ofInstant(NOW, BERLIN));
    }
}
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag bestätigen**

Run: `mvn -q test -Dtest=ChargingQueryServiceTest`
Expected: Kompilierfehler.

- [ ] **Step 4: Query-Service schreiben**

```java
package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.model.entity.ChargingPointOccupancy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Baut die eine Antwort fuer Tablet und Website: Favoriten zuerst (mit Ladepunkten und
 * Belegungsbeginn), dann die uebrigen Umkreis-Stationen nach Entfernung.
 */
@Service
@RequiredArgsConstructor
public class ChargingQueryService {

    private final ChargingSettingsService settingsService;
    private final ChargingFavoriteService favoriteService;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final Clock clock;

    public ChargingDtos.StationsResponse stations() {
        ChargingSettings settings = settingsService.getSettings();
        if (!settings.isConfigured()) {
            return new ChargingDtos.StationsResponse(false, null, settings.radiusKm(), settings.minPowerKw(),
                    null, List.of());
        }
        double homeLat = settings.homeLatitude();
        double homeLon = settings.homeLongitude();

        Map<String, ChargingFavorite> favorites = new LinkedHashMap<>();
        favoriteService.list().forEach(f -> favorites.put(f.getStationId(), f));

        List<ChargingDtos.StationResponse> favoriteRows = new ArrayList<>();
        for (ChargingFavorite favorite : favorites.values()) {
            favoriteRows.add(favoriteRow(favorite, homeLat, homeLon));
        }
        favoriteRows.sort(Comparator.comparingLong(ChargingDtos.StationResponse::distanceMeters));

        List<ChargingDtos.StationResponse> others = snapshot.areaStations().stream()
                .filter(s -> !favorites.containsKey(s.stationId()))
                .map(s -> areaRow(s, homeLat, homeLon))
                .sorted(Comparator.comparingLong(ChargingDtos.StationResponse::distanceMeters))
                .toList();

        List<ChargingDtos.StationResponse> all = new ArrayList<>(favoriteRows);
        all.addAll(others);
        return new ChargingDtos.StationsResponse(true, new ChargingDtos.HomeResponse(homeLat, homeLon),
                settings.radiusKm(), settings.minPowerKw(), local(snapshot.lastPolledAt()), all);
    }

    private ChargingDtos.StationResponse areaRow(ChargingStation s, double homeLat, double homeLon) {
        long distance = Math.round(ChargingAreaFilter.distanceMeters(homeLat, homeLon, s.lat(), s.lon()));
        return new ChargingDtos.StationResponse(s.stationId(), s.name(), s.operator(), s.address(), s.lat(), s.lon(),
                distance, s.maxPowerKw(), s.total(), s.free(), false, null);
    }

    /** Stammdaten aus dem Umkreis-Stand, wenn vorhanden; sonst die beim Favorisieren gespeicherten. */
    private ChargingDtos.StationResponse favoriteRow(ChargingFavorite favorite, double homeLat, double homeLon) {
        Optional<ChargingStation> area = snapshot.station(favorite.getStationId());
        Optional<ChargingStationDetails> details = snapshot.details(favorite.getStationId());
        Map<String, ChargingPointOccupancy> occupancy = details.isPresent()
                ? tracker.occupancyFor(favorite.getStationId()) : Map.of();

        double lat = area.map(ChargingStation::lat).orElse(favorite.getLat());
        double lon = area.map(ChargingStation::lon).orElse(favorite.getLon());
        String name = area.map(ChargingStation::name).orElse(favorite.getDisplayName());
        String operator = area.map(ChargingStation::operator).orElse(favorite.getOperator());
        String address = area.map(ChargingStation::address).orElse(null);
        long distance = Math.round(ChargingAreaFilter.distanceMeters(homeLat, homeLon, lat, lon));

        List<ChargingDtos.ChargePointResponse> points = details.map(d -> d.chargePoints().stream()
                .map(p -> pointRow(p, occupancy.get(p.chargePointId()))).toList()).orElse(null);
        int total = details.map(d -> d.chargePoints().size()).orElse(area.map(ChargingStation::total).orElse(0));
        int free = details.map(d -> (int) d.chargePoints().stream()
                .filter(p -> p.status() == ChargePointStatus.FREE).count())
                .orElse(area.map(ChargingStation::free).orElse(0));
        Double maxPower = details.map(d -> d.chargePoints().stream().map(ChargePoint::maxPowerKw)
                .filter(Objects::nonNull).max(Double::compare).orElse(null))
                .orElse(area.map(ChargingStation::maxPowerKw).orElse(null));

        return new ChargingDtos.StationResponse(favorite.getStationId(), name, operator, address, lat, lon,
                distance, maxPower, total, free, true, points);
    }

    /**
     * Beginn bekannt -> occupiedSince, minimumDuration=false. Beginn unbekannt (beim ersten Poll
     * schon belegt) -> firstSeenOccupiedAt als Untergrenze, minimumDuration=true.
     */
    private ChargingDtos.ChargePointResponse pointRow(ChargePoint point, ChargingPointOccupancy occupancy) {
        LocalDateTime since = null;
        boolean minimum = false;
        if (occupancy != null && point.status() == ChargePointStatus.OCCUPIED) {
            if (occupancy.getOccupiedSince() != null) {
                since = local(occupancy.getOccupiedSince());
            } else {
                since = local(occupancy.getFirstSeenOccupiedAt());
                minimum = true;
            }
        }
        return new ChargingDtos.ChargePointResponse(point.chargePointId(), point.status(), point.maxPowerKw(),
                point.connector(), since, minimum);
    }

    private LocalDateTime local(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
```

- [ ] **Step 5: Query-Test laufen lassen**

Run: `mvn -q test -Dtest=ChargingQueryServiceTest`
Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 6: Controller schreiben**

`ChargingController.java`:
```java
package com.household.manager.charging;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lese-API fuer Tablet und Website plus Favoriten. Lesen KIOSK (generische GET-Regel),
 * /refresh steht in der KIOSK-POST-Whitelist, Favoriten sind MEMBER (anyRequest).
 */
@RestController
@RequestMapping("/v1/charging")
@RequiredArgsConstructor
public class ChargingController {

    private final ChargingQueryService queryService;
    private final ChargingPollingService pollingService;
    private final ChargingFavoriteService favoriteService;

    @GetMapping("/stations")
    public ResponseEntity<ChargingDtos.StationsResponse> stations() {
        return ResponseEntity.ok(queryService.stations());
    }

    @PostMapping("/refresh")
    public ResponseEntity<ChargingDtos.StationsResponse> refresh() {
        pollingService.refreshNow();
        return ResponseEntity.ok(queryService.stations());
    }

    @GetMapping("/favorites")
    public ResponseEntity<List<ChargingDtos.FavoriteResponse>> favorites() {
        return ResponseEntity.ok(favoriteService.list().stream()
                .map(f -> new ChargingDtos.FavoriteResponse(f.getStationId(), f.getDisplayName(), f.getOperator(),
                        f.getLat(), f.getLon()))
                .toList());
    }

    @PutMapping("/favorites/{stationId}")
    public ResponseEntity<ChargingDtos.StationsResponse> addFavorite(@PathVariable String stationId) {
        favoriteService.add(stationId);
        return ResponseEntity.ok(queryService.stations());
    }

    @DeleteMapping("/favorites/{stationId}")
    public ResponseEntity<ChargingDtos.StationsResponse> removeFavorite(@PathVariable String stationId) {
        favoriteService.remove(stationId);
        return ResponseEntity.ok(queryService.stations());
    }
}
```

`ChargingSettingsController.java`:
```java
package com.household.manager.charging;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** ADMIN-only ueber die Matcher-Reihenfolge in SecurityConfig (methodenloser Matcher vor GET /v1/**). */
@RestController
@RequestMapping("/v1/charging/settings")
@RequiredArgsConstructor
public class ChargingSettingsController {

    private final ChargingSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ChargingSettings> get() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<ChargingSettings> update(@RequestBody ChargingDtos.SettingsRequest request) {
        validate(request);
        settingsService.saveSettings(new ChargingSettings(request.homeLatitude(), request.homeLongitude(),
                request.radiusKm(), request.minPowerKw()));
        return ResponseEntity.ok(settingsService.getSettings());
    }

    /** Double.isFinite ist bei den einseitigen Koordinatenpruefungen tragend (NaN-Falle, siehe Tractive). */
    private void validate(ChargingDtos.SettingsRequest r) {
        boolean hasLat = r.homeLatitude() != null;
        boolean hasLon = r.homeLongitude() != null;
        if (hasLat != hasLon) {
            throw badRequest("Breiten- und Laengengrad muessen gemeinsam gesetzt oder gemeinsam leer sein.");
        }
        if (hasLat && (!Double.isFinite(r.homeLatitude()) || Math.abs(r.homeLatitude()) > 90)) {
            throw badRequest("Der Breitengrad muss zwischen -90 und 90 liegen.");
        }
        if (hasLon && (!Double.isFinite(r.homeLongitude()) || Math.abs(r.homeLongitude()) > 180)) {
            throw badRequest("Der Laengengrad muss zwischen -180 und 180 liegen.");
        }
        if (!Double.isFinite(r.radiusKm()) || r.radiusKm() < ChargingSettingsService.MIN_RADIUS_KM
                || r.radiusKm() > ChargingSettingsService.MAX_RADIUS_KM) {
            throw badRequest("Der Radius muss zwischen 1 und 50 km liegen.");
        }
        if (!Double.isFinite(r.minPowerKw()) || r.minPowerKw() < ChargingSettingsService.MIN_POWER_KW
                || r.minPowerKw() > ChargingSettingsService.MAX_POWER_KW) {
            throw badRequest("Die Mindestleistung muss zwischen 0 und 400 kW liegen.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
```

- [ ] **Step 7: 502-Handler ergänzen** — in `GlobalExceptionHandler.java` Import `import com.household.manager.charging.ChargingSourceException;` und direkt vor `@ExceptionHandler(ShellyException.class)`:

```java
    /** Ladesaeulen-Quelle (EnBW) nicht erreichbar oder unbrauchbar: 502, nie 401. */
    @ExceptionHandler(ChargingSourceException.class)
    public ResponseEntity<ErrorResponse> handleChargingSourceException(
            ChargingSourceException ex, WebRequest request) {

        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_GATEWAY.value())
                .error("Bad Gateway")
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        log.warn("Charging source error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }
```

`ChargingRateLimitException` erbt von `TooManyRequestsException` und braucht keinen eigenen Handler. `IllegalArgumentException` (Favorisieren einer unbekannten Station) wird vom bestehenden Handler auf 400 abgebildet — prüfen mit `grep -n "IllegalArgumentException" GlobalExceptionHandler.java`; fehlt er, analog zum `IllegalStateException`-Handler mit `HttpStatus.BAD_REQUEST` ergänzen.

- [ ] **Step 8: Security-Regeln eintragen** in `SecurityConfig.filterChain`:

Im ADMIN-Block (die Liste, die mit `"/v1/flows/**", "/v1/admin/**", ...` beginnt) ergänzen:
```java
                                "/v1/mode-quick-access", "/v1/mode-quick-access/**",
                                // Ladesaeulen: Zuhause/Radius/Mindestleistung nur ADMIN, auch lesen -
                                // methodenlos, VOR der generischen GET-Regel (Muster tractive/home-settings).
                                "/v1/charging/settings").hasRole("ADMIN")
```

In der KIOSK-POST-Whitelist ergänzen (nach `"/v1/pet-supplies/*/purchases", "/v1/pet-supplies/*/corrections"`):
```java
                                "/v1/pet-supplies/*/purchases", "/v1/pet-supplies/*/corrections",
                                // Ladesaeulen-Refresh zieht nur Daten - sonst waere der Knopf am Tablet tot.
                                "/v1/charging/refresh")
```

- [ ] **Step 9: SecurityRulesTest erweitern** — `ChargingController.class` und `ChargingSettingsController.class` **nicht** in die `controllers`-Liste aufnehmen (404 statt 403 belegt das Durchlassen, Muster `tractive/home-settings`). Tests anhängen:

```java
    // ---- Ladesaeulen-Uebersicht ----

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieLadesaeulenLesen() throws Exception {
        mockMvc.perform(get("/v1/charging/stations")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDenLadesaeulenAbrufErzwingen() throws Exception {
        mockMvc.perform(post("/v1/charging/refresh").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineFavoritenSetzen() throws Exception {
        mockMvc.perform(put("/v1/charging/favorites/DE1").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfFavoritenSetzenUndEntfernen() throws Exception {
        mockMvc.perform(put("/v1/charging/favorites/DE1").with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(delete("/v1/charging/favorites/DE1").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieLadesaeulenEinstellungenNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/charging/settings")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfDieLadesaeulenEinstellungenNichtSchreiben() throws Exception {
        mockMvc.perform(put("/v1/charging/settings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"radiusKm\": 5, \"minPowerKw\": 50}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDarfDieLadesaeulenEinstellungenLesen() throws Exception {
        mockMvc.perform(get("/v1/charging/settings")).andExpect(status().isNotFound());
    }
```

Falls `delete` noch nicht statisch importiert ist: `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;` ergänzen.

- [ ] **Step 10: Alle Charging- und Security-Tests laufen lassen**

Run: `mvn -q test -Dtest='Charging*Test,SecurityRulesTest,EnbwChargingClientTest,BoundingBoxTest'`
Expected: alle grün, `Failures: 0, Errors: 0`.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -F - <<'EOF'
feat(charging): API /v1/charging (stations, refresh, favorites, settings) und Security-Regeln

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 10: Frontend-Modell, Service, Status-Util und Karten-Util (TDD)

**Files:**
- Create: `frontend/src/app/models/charging.model.ts`
- Create: `frontend/src/app/services/charging.service.ts`
- Create: `frontend/src/app/shared/charging-status.util.ts`
- Create: `frontend/src/app/shared/charging-map.util.ts`
- Test: `frontend/src/app/shared/charging-status.util.spec.ts`

Abweichung zur Spec, bewusst: neben `charging-status.util.ts` gibt es `charging-map.util.ts` (Marker-Icon und Popup-Text als reine Leaflet-Helfer). Die beiden Seiten teilen weiterhin keinen Komponenten-Code, aber dieselben Marker — sonst stünde das `divIcon`-HTML zweimal.

- [ ] **Step 1: Modell schreiben**

```typescript
/** API-Vertrag von /api/v1/charging (Zeitstempel als LocalDateTime-Strings in Haushaltszeit). */
export type ChargePointStatus = 'FREE' | 'OCCUPIED' | 'OUT_OF_SERVICE' | 'UNKNOWN';

export interface ChargePoint {
  chargePointId: string;
  status: ChargePointStatus;
  maxPowerKw?: number;
  connector?: string;
  /** Beginn der Belegung; fehlt bei freien Ladepunkten. */
  occupiedSince?: string;
  /** true = Beginn unbekannt, occupiedSince ist nur eine Untergrenze ("seit mind."). */
  minimumDuration: boolean;
}

export interface ChargingStation {
  stationId: string;
  name: string;
  operator?: string;
  address?: string;
  lat: number;
  lon: number;
  distanceMeters: number;
  maxPowerKw?: number;
  total: number;
  free: number;
  favorite: boolean;
  /** Nur bei Favoriten gefuellt. */
  chargePoints?: ChargePoint[];
}

export interface ChargingStationsResponse {
  configured: boolean;
  home?: { lat: number; lon: number };
  radiusKm?: number;
  minPowerKw?: number;
  lastPolledAt: string | null;
  stations: ChargingStation[];
}

export interface ChargingSettings {
  homeLatitude: number | null;
  homeLongitude: number | null;
  radiusKm: number;
  minPowerKw: number;
}
```

- [ ] **Step 2: Service schreiben**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChargingSettings, ChargingStationsResponse } from '../models/charging.model';

/**
 * REST-Service fuer die Ladesaeulen-Uebersicht. Fehler werden bewusst NICHT auf eine
 * Einheitsmeldung reduziert: die Seiten zeigen die Server-Meldung (400 "kein Zuhause",
 * 429 "gerade eben", 502 "EnBW nicht erreichbar") direkt an.
 */
@Injectable({ providedIn: 'root' })
export class ChargingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/charging';

  getStations(): Observable<ChargingStationsResponse> {
    return this.http.get<ChargingStationsResponse>(`${this.baseUrl}/stations`);
  }

  refresh(): Observable<ChargingStationsResponse> {
    return this.http.post<ChargingStationsResponse>(`${this.baseUrl}/refresh`, {});
  }

  addFavorite(stationId: string): Observable<ChargingStationsResponse> {
    return this.http.put<ChargingStationsResponse>(`${this.baseUrl}/favorites/${encodeURIComponent(stationId)}`, {});
  }

  removeFavorite(stationId: string): Observable<ChargingStationsResponse> {
    return this.http.delete<ChargingStationsResponse>(`${this.baseUrl}/favorites/${encodeURIComponent(stationId)}`);
  }

  getSettings(): Observable<ChargingSettings> {
    return this.http.get<ChargingSettings>(`${this.baseUrl}/settings`);
  }

  saveSettings(settings: ChargingSettings): Observable<ChargingSettings> {
    return this.http.put<ChargingSettings>(`${this.baseUrl}/settings`, settings);
  }
}
```

- [ ] **Step 3: Failing Util-Test schreiben**

```typescript
import {
  formatOccupiedDuration,
  formatPower,
  stationTone,
  sortStations
} from './charging-status.util';
import { ChargingStation } from '../models/charging.model';

describe('charging-status.util', () => {
  const NOW = new Date('2026-09-19T12:00:00');

  const station = (overrides: Partial<ChargingStation>): ChargingStation => ({
    stationId: 'x', name: 'X', lat: 50, lon: 8, distanceMeters: 1000, total: 2, free: 1, favorite: false,
    ...overrides
  });

  describe('stationTone', () => {
    it('ist gruen bei mindestens einem freien Ladepunkt', () => {
      expect(stationTone(station({ free: 1 }))).toBe('free');
    });

    it('ist rot ohne freien Ladepunkt', () => {
      expect(stationTone(station({ free: 0, total: 2 }))).toBe('busy');
    });

    it('ist grau ohne Ladepunkte (ausser Betrieb oder unbekannt)', () => {
      expect(stationTone(station({ free: 0, total: 0 }))).toBe('unknown');
    });
  });

  describe('formatOccupiedDuration', () => {
    it('zeigt Minuten bei bekanntem Beginn', () => {
      expect(formatOccupiedDuration('2026-09-19T11:22:00', false, NOW)).toBe('seit 38 min');
    });

    it('zeigt "mind." bei unbekanntem Beginn', () => {
      expect(formatOccupiedDuration('2026-09-19T11:22:00', true, NOW)).toBe('seit mind. 38 min');
    });

    it('zeigt Stunden und Minuten ab einer Stunde', () => {
      expect(formatOccupiedDuration('2026-09-19T09:55:00', false, NOW)).toBe('seit 2 h 05 min');
    });

    it('klemmt Zeitversatz in die Zukunft auf null', () => {
      expect(formatOccupiedDuration('2026-09-19T12:03:00', false, NOW)).toBe('seit 0 min');
    });

    it('liefert leer ohne Beginn', () => {
      expect(formatOccupiedDuration(undefined, false, NOW)).toBe('');
    });
  });

  describe('formatPower', () => {
    it('rundet auf ganze kW', () => {
      expect(formatPower(149.6)).toBe('150 kW');
    });

    it('liefert Strich ohne Angabe', () => {
      expect(formatPower(undefined)).toBe('–');
    });
  });

  describe('sortStations', () => {
    it('stellt Favoriten voran und sortiert innerhalb nach Entfernung', () => {
      const sorted = sortStations([
        station({ stationId: 'fern', distanceMeters: 5000 }),
        station({ stationId: 'favFern', distanceMeters: 8000, favorite: true }),
        station({ stationId: 'nah', distanceMeters: 500 }),
        station({ stationId: 'favNah', distanceMeters: 3000, favorite: true })
      ]);
      expect(sorted.map(s => s.stationId)).toEqual(['favNah', 'favFern', 'nah', 'fern']);
    });
  });
});
```

- [ ] **Step 4: Test laufen lassen, Fehlschlag bestätigen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/charging-status.util.spec.ts'`
Expected: Kompilierfehler (Modul nicht gefunden).

- [ ] **Step 5: Status-Util schreiben**

```typescript
import { ChargingStation } from '../models/charging.model';

/**
 * Einzige Definition von Farbe, Dauerformat und Sortierung der Ladesaeulen - Tablet und
 * Website fragen dieselben Funktionen.
 */
export type StationTone = 'free' | 'busy' | 'unknown';

export function stationTone(station: Pick<ChargingStation, 'free' | 'total'>): StationTone {
  if (station.total <= 0) {
    return 'unknown';
  }
  return station.free > 0 ? 'free' : 'busy';
}

/**
 * "seit 38 min" / "seit mind. 38 min" / "seit 2 h 05 min". Ein Beginn in der Zukunft
 * (Uhrenversatz zwischen Server und Tablet) wird auf 0 geklemmt statt negativ angezeigt.
 */
export function formatOccupiedDuration(
  occupiedSince: string | undefined,
  minimum: boolean,
  now: Date = new Date()
): string {
  if (!occupiedSince) {
    return '';
  }
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(occupiedSince).getTime()) / 60_000));
  const prefix = minimum ? 'seit mind. ' : 'seit ';
  if (minutes < 60) {
    return `${prefix}${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = String(minutes % 60).padStart(2, '0');
  return `${prefix}${hours} h ${rest} min`;
}

export function formatPower(kw: number | undefined): string {
  return kw === undefined || kw === null ? '–' : `${Math.round(kw)} kW`;
}

export function formatDistance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters)} m` : `${(meters / 1000).toFixed(1).replace('.', ',')} km`;
}

/** Favoriten zuerst, innerhalb beider Gruppen nach Entfernung. */
export function sortStations(stations: readonly ChargingStation[]): ChargingStation[] {
  return [...stations].sort((a, b) => {
    if (a.favorite !== b.favorite) {
      return a.favorite ? -1 : 1;
    }
    return a.distanceMeters - b.distanceMeters;
  });
}
```

- [ ] **Step 6: Karten-Util schreiben**

```typescript
import * as L from 'leaflet';
import { ChargingStation } from '../models/charging.model';
import { formatPower, stationTone } from './charging-status.util';

/**
 * Marker-Icon einer Ladesaeule: farbiger Kreis mit der Zahl freier Ladepunkte, Stern bei
 * Favoriten. Die Klassen `charging-marker--free|busy|unknown` und `charging-marker--favorite`
 * stylen beide Seiten in ihrer eigenen SCSS (Leaflet rendert divIcons ausserhalb der
 * Komponenten-Kapselung, deshalb per ::ng-deep bzw. global in der Seite).
 */
export function stationIcon(station: ChargingStation): L.DivIcon {
  const tone = stationTone(station);
  const label = station.total > 0 ? String(station.free) : '–';
  return L.divIcon({
    className: `charging-marker charging-marker--${tone}${station.favorite ? ' charging-marker--favorite' : ''}`,
    html: `<span class="charging-marker__count">${label}</span>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
    popupAnchor: [0, -14]
  });
}

export function stationPopupText(station: ChargingStation): string {
  const lines = [
    `<strong>${escapeHtml(station.name)}</strong>`,
    `${station.free}/${station.total} frei · ${formatPower(station.maxPowerKw)}`
  ];
  if (station.address) {
    lines.push(escapeHtml(station.address));
  }
  return lines.join('<br>');
}

/** Zuhause-Marker: kleiner blauer Punkt. */
export function homeIcon(): L.DivIcon {
  return L.divIcon({ className: 'charging-home', iconSize: [14, 14], iconAnchor: [7, 7] });
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
```

- [ ] **Step 7: Tests laufen lassen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/charging-status.util.spec.ts'`
Expected: `11 SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/models/charging.model.ts frontend/src/app/services/charging.service.ts frontend/src/app/shared/charging-status.util.ts frontend/src/app/shared/charging-status.util.spec.ts frontend/src/app/shared/charging-map.util.ts
git commit -F - <<'EOF'
feat(charging): Frontend-Modell, Service, Status- und Karten-Util

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 11: Tablet-Ansicht `/tablet/charging` (TDD)

**Files:**
- Create: `frontend/src/app/pages/tablet-charging/tablet-charging.component.ts`
- Create: `frontend/src/app/pages/tablet-charging/tablet-charging.component.html`
- Create: `frontend/src/app/pages/tablet-charging/tablet-charging.component.scss`
- Test: `frontend/src/app/pages/tablet-charging/tablet-charging.component.spec.ts`

- [ ] **Step 1: Failing Spec schreiben**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { TabletChargingComponent } from './tablet-charging.component';
import { ChargingService } from '../../services/charging.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { ChargingStationsResponse } from '../../models/charging.model';

describe('TabletChargingComponent', () => {
  let fixture: ComponentFixture<TabletChargingComponent>;
  let component: TabletChargingComponent;
  let chargingSpy: jasmine.SpyObj<ChargingService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const response: ChargingStationsResponse = {
    configured: true,
    home: { lat: 50, lon: 8 },
    radiusKm: 10,
    minPowerKw: 50,
    lastPolledAt: '2026-09-19T11:58:00',
    stations: [
      {
        stationId: 'other', name: 'EnBW Rastplatz', lat: 50.02, lon: 8.0, distanceMeters: 2200,
        maxPowerKw: 300, total: 8, free: 6, favorite: false
      },
      {
        stationId: 'fav', name: 'Lidl Hauptstr.', operator: 'Lidl', lat: 50.01, lon: 8.0, distanceMeters: 1100,
        maxPowerKw: 150, total: 2, free: 0, favorite: true,
        chargePoints: [
          { chargePointId: 'P1', status: 'OCCUPIED', maxPowerKw: 150, connector: 'CCS',
            occupiedSince: '2026-09-19T11:20:00', minimumDuration: false },
          { chargePointId: 'P2', status: 'OCCUPIED', maxPowerKw: 150, connector: 'CCS',
            occupiedSince: '2026-09-19T11:50:00', minimumDuration: true }
        ]
      }
    ]
  };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService', ['getStations', 'refresh']);
    chargingSpy.getStations.and.returnValue(of(response));
    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletChargingComponent],
      providers: [
        provideRouter([]),
        { provide: ChargingService, useValue: chargingSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletChargingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('rendert Favoriten vor den uebrigen Stationen', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-charging__row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Lidl Hauptstr.');
    expect(rows[0].classList).toContain('tablet-charging__row--favorite');
    expect(rows[1].textContent).toContain('EnBW Rastplatz');
  });

  it('zeigt je Ladepunkt eines Favoriten die Belegungsdauer, "mind." bei unbekanntem Beginn', () => {
    const points = (fixture.nativeElement as HTMLElement).querySelectorAll('.tablet-charging__point');
    expect(points.length).toBe(2);
    expect(points[0].textContent).toContain('seit');
    expect(points[1].textContent).toContain('seit mind.');
  });

  it('haelt den Kartencontainer immer im DOM und zeichnet Marker je Station', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.leaflet-container')).not.toBeNull();
    expect(host.querySelectorAll('.charging-marker').length).toBe(2);
    expect(host.querySelectorAll('.charging-marker--favorite').length).toBe(1);
  });

  it('setzt den Kartenausschnitt bei einem Refresh nicht zurueck', () => {
    const map = component.mapForTest()!;
    map.setView([50.5, 8.5], 11);

    component.reload();

    expect(map.getZoom()).toBe(11);
    expect(map.getCenter().lat).toBeCloseTo(50.5, 3);
  });

  it('zeigt einen Hinweis statt Karte, wenn kein Zuhause konfiguriert ist', () => {
    chargingSpy.getStations.and.returnValue(of({ configured: false, lastPolledAt: null, stations: [] }));
    const fresh = TestBed.createComponent(TabletChargingComponent);
    fresh.detectChanges();

    expect((fresh.nativeElement as HTMLElement).querySelector('.tablet-charging__unconfigured')).not.toBeNull();
    fresh.destroy();
  });

  it('meldet einen Fehler nur beim Erstabruf, ein Hintergrund-Refresh behaelt die Werte', () => {
    chargingSpy.getStations.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.reload();
    expect(component.data?.stations.length).toBe(2);
    expect(component.error).toBeNull();

    const fresh = TestBed.createComponent(TabletChargingComponent);
    fresh.detectChanges();
    expect(fresh.componentInstance.error).not.toBeNull();
    fresh.destroy();
  });

  it('zeigt die 429-Meldung des Servers beim erzwungenen Abruf inline', () => {
    chargingSpy.refresh.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 429, error: { message: 'Der letzte Abruf war gerade eben.' }
    })));

    component.refreshNow();

    expect(component.refreshError).toBe('Der letzte Abruf war gerade eben.');
  });

  it('bestellt einen laufenden Abruf ab, bevor der naechste startet', () => {
    const erster = new Subject<ChargingStationsResponse>();
    const zweiter = new Subject<ChargingStationsResponse>();
    chargingSpy.getStations.and.returnValue(erster.asObservable());
    component.reload();
    chargingSpy.getStations.and.returnValue(zweiter.asObservable());
    component.reload();

    const neu: ChargingStationsResponse = { ...response, stations: [] };
    zweiter.next(neu);
    erster.next(response);

    expect(component.data?.stations.length).toBe(0);
  });

  it('gibt Karte und Liste die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Eigener Rahmen statt body (Karma-Geschwister teilen sich sonst die Hoehe; siehe tablet-toni).
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);
    const mapHeight = (): number =>
      host.querySelector('.tablet-charging__map')!.getBoundingClientRect().height;

    frame.style.height = '900px';
    fixture.detectChanges();
    const small = mapHeight();
    frame.style.height = '1200px';
    fixture.detectChanges();
    const large = mapHeight();

    expect(small).toBeGreaterThan(300);
    expect(large).toBeGreaterThan(small + 250);

    document.body.appendChild(host);
    frame.remove();
  });
});
```

- [ ] **Step 2: Spec laufen lassen, Fehlschlag bestätigen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-charging.component.spec.ts'`
Expected: Kompilierfehler.

- [ ] **Step 3: Komponente schreiben**

```typescript
import {
  AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import * as L from 'leaflet';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { ChargingService } from '../../services/charging.service';
import { ChargePoint, ChargingStation, ChargingStationsResponse } from '../../models/charging.model';
import {
  formatDistance, formatOccupiedDuration, formatPower, sortStations, stationTone
} from '../../shared/charging-status.util';
import { homeIcon, stationIcon, stationPopupText } from '../../shared/charging-map.util';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

/**
 * Ladesaeulen-Uebersicht fuer das Wandtablet: Karte links, Liste rechts (Favoriten mit
 * Ladepunkten und Belegungsdauer zuerst, dann die uebrigen nach Entfernung).
 */
@Component({
  selector: 'app-tablet-charging',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-charging.component.html',
  styleUrl: './tablet-charging.component.scss'
})
export class TabletChargingComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60_000;
  /** Die Dauer laeuft zwischen zwei Abrufen weiter - minuetlich neu rechnen. */
  private static readonly TICK_INTERVAL_MS = 60_000;

  private readonly chargingService = inject(ChargingService);

  @ViewChild('mapContainer') private mapContainer?: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private markerLayer?: L.LayerGroup;
  private homeLayer?: L.LayerGroup;
  private viewInitialized = false;
  private refreshTimer: number | null = null;
  private tickTimer: number | null = null;
  private pendingRequest: Subscription | null = null;

  data: ChargingStationsResponse | null = null;
  error: string | null = null;
  refreshing = false;
  refreshError: string | null = null;
  /** Hebt die zuletzt angetippte Station in der Liste hervor. */
  selectedStationId: string | null = null;
  /** Wird minuetlich hochgezaehlt, damit die Dauer-Getter neu ausgewertet werden. */
  now = new Date();

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(() => this.reload(), TabletChargingComponent.REFRESH_INTERVAL_MS);
    this.tickTimer = window.setInterval(() => { this.now = new Date(); }, TabletChargingComponent.TICK_INTERVAL_MS);
  }

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    this.renderMap();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
    }
    if (this.tickTimer !== null) {
      window.clearInterval(this.tickTimer);
    }
    this.pendingRequest?.unsubscribe();
    this.map?.remove();
    this.map = undefined;
  }

  get stations(): ChargingStation[] {
    return this.data ? sortStations(this.data.stations) : [];
  }

  get lastPolledLabel(): string {
    if (!this.data?.lastPolledAt) {
      return 'Noch keine Daten';
    }
    const at = new Date(this.data.lastPolledAt);
    return `Stand ${at.getHours().toString().padStart(2, '0')}:${at.getMinutes().toString().padStart(2, '0')}`;
  }

  reload(): void {
    this.load(true);
  }

  refreshNow(): void {
    this.refreshing = true;
    this.refreshError = null;
    this.chargingService.refresh().subscribe({
      next: response => {
        this.refreshing = false;
        this.apply(response);
      },
      error: (err: HttpErrorResponse) => {
        this.refreshing = false;
        this.refreshError = err.error?.message ?? 'Aktualisierung fehlgeschlagen.';
      }
    });
  }

  select(stationId: string): void {
    this.selectedStationId = stationId;
  }

  tone(station: ChargingStation): string {
    return stationTone(station);
  }

  pointTone(point: ChargePoint): string {
    return point.status === 'FREE' ? 'free' : point.status === 'OCCUPIED' ? 'busy' : 'unknown';
  }

  power(kw: number | undefined): string {
    return formatPower(kw);
  }

  distance(meters: number): string {
    return formatDistance(meters);
  }

  duration(point: ChargePoint): string {
    return formatOccupiedDuration(point.occupiedSince, point.minimumDuration, this.now);
  }

  pointLabel(point: ChargePoint): string {
    switch (point.status) {
      case 'FREE': return 'frei';
      case 'OCCUPIED': return 'belegt';
      case 'OUT_OF_SERVICE': return 'außer Betrieb';
      case 'UNKNOWN': return 'unbekannt';
    }
  }

  /** Nur fuer Tests: Zugriff auf die Karte, um Ausschnitt-Erhalt zu pruefen. */
  mapForTest(): L.Map | undefined {
    return this.map;
  }

  private load(silent: boolean): void {
    if (!silent) {
      this.error = null;
    }
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = this.chargingService.getStations().subscribe({
      next: response => this.apply(response),
      error: (err: HttpErrorResponse) => {
        console.error('Ladesaeulen konnten nicht geladen werden:', err);
        if (!silent) {
          this.error = 'Ladesäulen konnten nicht geladen werden.';
        }
      }
    });
  }

  private apply(response: ChargingStationsResponse): void {
    this.data = response;
    this.error = null;
    this.now = new Date();
    this.renderMap();
  }

  /**
   * Karte einmal anlegen, Ausschnitt nur beim ersten Mal setzen; danach werden nur die
   * Marker ausgetauscht - ein Refresh darf einen gezoomten Blick nicht zuruecksetzen.
   */
  private renderMap(): void {
    const container = this.mapContainer?.nativeElement;
    if (!this.viewInitialized || !container || !this.data?.configured || !this.data.home) {
      return;
    }
    const home: L.LatLngExpression = [this.data.home.lat, this.data.home.lon];
    if (!this.map) {
      this.map = L.map(container, { zoomControl: false });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap', maxZoom: 19
      }).addTo(this.map);
      this.homeLayer = L.layerGroup().addTo(this.map);
      this.markerLayer = L.layerGroup().addTo(this.map);
      const radiusMeters = (this.data.radiusKm ?? 10) * 1000;
      L.circle(home, { radius: radiusMeters, color: '#38bdf8', weight: 1, fillOpacity: 0.04 }).addTo(this.homeLayer);
      L.marker(home, { icon: homeIcon(), interactive: false }).addTo(this.homeLayer);
      this.map.fitBounds(L.latLng(home).toBounds(radiusMeters * 2), { padding: [8, 8] });
    }
    this.markerLayer!.clearLayers();
    for (const station of this.data.stations) {
      L.marker([station.lat, station.lon], { icon: stationIcon(station) })
        .bindPopup(stationPopupText(station))
        .on('click', () => this.select(station.stationId))
        .addTo(this.markerLayer!);
    }
  }
}
```

- [ ] **Step 4: Template schreiben**

```html
<app-tablet-shell heading="Laden">
  <div shellActions class="tablet-charging__controls">
    <span class="tablet-charging__stand">{{ lastPolledLabel }}</span>
    <button type="button" class="tablet-charging__refresh" [disabled]="refreshing" (click)="refreshNow()">
      {{ refreshing ? 'Lädt…' : 'Jetzt aktualisieren' }}
    </button>
  </div>

  <section class="tablet-charging">
    @if (refreshError) {
      <p class="tablet-charging__error">{{ refreshError }}</p>
    }

    @if (data && !data.configured) {
      <p class="tablet-charging__unconfigured">
        Kein Zuhause hinterlegt. Bitte unter Admin → Ladesäulen festlegen.
      </p>
    } @else {
      <div class="tablet-charging__body">
        <!-- Der Kartencontainer steht IMMER im DOM (ViewChild + ngAfterViewInit), sonst hinge der
             Kartenaufbau an der Reihenfolge von Datenankunft und Change Detection (Lehre aus /pets). -->
        <div #mapContainer class="tablet-charging__map"></div>

        <aside class="tablet-charging__list">
          @if (!data) {
            <p class="tablet-charging__hint">{{ error ?? 'Lade Ladesäulen…' }}</p>
          } @else if (stations.length === 0) {
            <p class="tablet-charging__hint">
              {{ data.lastPolledAt ? 'Keine Ladesäulen im Umkreis.' : 'Noch keine Daten.' }}
            </p>
          } @else {
            @for (station of stations; track station.stationId) {
              <article
                class="tablet-charging__row"
                [class.tablet-charging__row--favorite]="station.favorite"
                [class.tablet-charging__row--selected]="station.stationId === selectedStationId"
                [attr.data-tone]="tone(station)"
                (click)="select(station.stationId)">
                <header class="tablet-charging__row-head">
                  <span class="tablet-charging__dot" [attr.data-tone]="tone(station)"></span>
                  <span class="tablet-charging__name">
                    @if (station.favorite) { <span class="tablet-charging__star">★</span> }
                    {{ station.name }}
                  </span>
                  <span class="tablet-charging__meta">
                    {{ station.free }}/{{ station.total }} frei · {{ power(station.maxPowerKw) }} · {{ distance(station.distanceMeters) }}
                  </span>
                </header>
                @if (station.chargePoints; as points) {
                  <ul class="tablet-charging__points">
                    @for (point of points; track point.chargePointId) {
                      <li class="tablet-charging__point" [attr.data-tone]="pointTone(point)">
                        <span class="tablet-charging__point-power">{{ power(point.maxPowerKw) }}</span>
                        <span class="tablet-charging__point-status">{{ pointLabel(point) }}</span>
                        @if (point.status === 'OCCUPIED') {
                          <span class="tablet-charging__point-since">{{ duration(point) }}</span>
                        }
                      </li>
                    }
                  </ul>
                }
              </article>
            }
          }
        </aside>
      </div>
    }
  </section>
</app-tablet-shell>
```

- [ ] **Step 5: SCSS schreiben**

```scss
// Ladesaeulen-Ansicht des Wandtablets. Kopfzeile/Ansichtsleiste liefert app-tablet-shell.
// Hoehenkette: :host -> .tablet-charging -> __body -> __map/__list (flex: 1, min-height: 0).
:host {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.tablet-charging {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 0.6rem;
  color: #e4e2e4;

  &__controls {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__stand {
    font-size: 0.95rem;
    color: rgba(228, 226, 228, 0.7);
  }

  &__refresh {
    border: 1px solid rgba(170, 199, 255, 0.35);
    background: rgba(170, 199, 255, 0.12);
    color: #aac7ff;
    padding: 0.5rem 1.1rem;
    border-radius: 10px;
    cursor: pointer;
    font: inherit;
    font-size: 0.95rem;

    &:disabled { opacity: 0.6; cursor: default; }
  }

  &__error, &__unconfigured, &__hint {
    margin: 0;
    color: #94a3b8;
    font-size: 1.05rem;
    text-align: center;
  }

  &__error { color: #ffb4ab; }

  &__unconfigured {
    flex: 1;
    display: grid;
    place-items: center;
  }

  &__body {
    flex: 1 1 auto;
    display: grid;
    grid-template-columns: 3fr 2fr;
    gap: 1rem;
    min-height: 0;
  }

  &__map {
    min-height: 0;
    border-radius: 1rem;
    overflow: hidden;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: #1e293b;
  }

  &__list {
    min-height: 0;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  &__row {
    padding: 0.6rem 0.8rem;
    border-radius: 0.8rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.03);
    cursor: pointer;

    &--favorite { background: rgba(255, 255, 255, 0.06); }
    &--selected { border-color: #aac7ff; }
  }

  &__row-head {
    display: flex;
    align-items: center;
    gap: 0.6rem;
  }

  &__dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    flex: none;
    background: #64748b;

    &[data-tone='free'] { background: #22c55e; }
    &[data-tone='busy'] { background: #ef4444; }
  }

  &__name {
    flex: 1;
    font-weight: 600;
    font-size: 1.05rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__star { color: #fbbf24; margin-right: 0.2rem; }

  &__meta {
    font-size: 0.9rem;
    color: rgba(228, 226, 228, 0.75);
    white-space: nowrap;
  }

  &__points {
    list-style: none;
    margin: 0.4rem 0 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
  }

  &__point {
    display: flex;
    gap: 0.8rem;
    font-size: 0.95rem;
    padding-left: 1.4rem;

    &[data-tone='free'] .tablet-charging__point-status { color: #22c55e; }
    &[data-tone='busy'] .tablet-charging__point-status { color: #ef4444; }
  }

  &__point-power { min-width: 4.5rem; color: rgba(228, 226, 228, 0.75); }
  &__point-since { color: #fca5a5; margin-left: auto; }
}

// Leaflet rendert divIcons ausserhalb der Komponenten-Kapselung.
:host ::ng-deep {
  .charging-marker {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    color: #fff;
    font-weight: 700;
    font-size: 13px;
    background: #64748b;
    box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.25);

    &--free { background: #22c55e; }
    &--busy { background: #ef4444; }

    &--favorite::after {
      content: '★';
      position: absolute;
      top: -10px;
      right: -8px;
      color: #fbbf24;
      font-size: 14px;
    }
  }

  .charging-home {
    width: 14px;
    height: 14px;
    border-radius: 50%;
    background: #38bdf8;
    box-shadow: 0 0 0 6px rgba(56, 189, 248, 0.25);
  }
}
```

- [ ] **Step 6: Spec laufen lassen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-charging.component.spec.ts'`
Expected: `9 SUCCESS`. Scheitert der Höhentest, fehlt ein `flex: 1`/`min-height: 0` in der Kette; scheitert der Marker-Test, wird `renderMap` vor `ngAfterViewInit` übersprungen — prüfen, dass `apply` `renderMap` ruft und `ngAfterViewInit` es nachholt.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/tablet-charging
git commit -F - <<'EOF'
feat(charging): Tablet-Ansicht /tablet/charging (Karte links, Liste rechts)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 12: Website-Seite `/charging` mit Favorisieren (TDD)

**Files:**
- Create: `frontend/src/app/pages/charging/charging.component.ts`
- Create: `frontend/src/app/pages/charging/charging.component.html`
- Create: `frontend/src/app/pages/charging/charging.component.scss`
- Test: `frontend/src/app/pages/charging/charging.component.spec.ts`

Bewusst **kein** gemeinsamer Komponenten-Code mit der Tablet-Variante (Muster Kameras/Temperaturen); geteilt sind nur Service, Modell und die beiden Utils.

- [ ] **Step 1: Failing Spec schreiben**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ChargingComponent } from './charging.component';
import { ChargingService } from '../../services/charging.service';
import { ChargingStationsResponse } from '../../models/charging.model';

describe('ChargingComponent', () => {
  let fixture: ComponentFixture<ChargingComponent>;
  let component: ChargingComponent;
  let chargingSpy: jasmine.SpyObj<ChargingService>;

  const response: ChargingStationsResponse = {
    configured: true,
    home: { lat: 50, lon: 8 },
    radiusKm: 10,
    minPowerKw: 50,
    lastPolledAt: '2026-09-19T11:58:00',
    stations: [
      { stationId: 'other', name: 'EnBW Rastplatz', lat: 50.02, lon: 8.0, distanceMeters: 2200,
        maxPowerKw: 300, total: 8, free: 6, favorite: false },
      { stationId: 'fav', name: 'Lidl Hauptstr.', lat: 50.01, lon: 8.0, distanceMeters: 1100,
        maxPowerKw: 150, total: 2, free: 1, favorite: true,
        chargePoints: [
          { chargePointId: 'P1', status: 'OCCUPIED', occupiedSince: '2026-09-19T11:20:00', minimumDuration: false },
          { chargePointId: 'P2', status: 'FREE', minimumDuration: false }
        ] }
    ]
  };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService',
      ['getStations', 'refresh', 'addFavorite', 'removeFavorite']);
    chargingSpy.getStations.and.returnValue(of(response));

    await TestBed.configureTestingModule({
      imports: [ChargingComponent],
      providers: [provideRouter([]), { provide: ChargingService, useValue: chargingSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(ChargingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('rendert Favoriten zuerst mit Stern-Knopf zum Entfernen', () => {
    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll('.charging-page__row');
    expect(rows[0].textContent).toContain('Lidl Hauptstr.');
    const star = rows[0].querySelector('.charging-page__star-btn') as HTMLButtonElement;
    expect(star.getAttribute('aria-pressed')).toBe('true');
  });

  it('favorisieren ruft den Service und uebernimmt die Antwort', () => {
    const updated: ChargingStationsResponse = {
      ...response,
      stations: response.stations.map(s => s.stationId === 'other' ? { ...s, favorite: true } : s)
    };
    chargingSpy.addFavorite.and.returnValue(of(updated));

    component.toggleFavorite(response.stations[0]);

    expect(chargingSpy.addFavorite).toHaveBeenCalledOnceWith('other');
    expect(component.data?.stations.find(s => s.stationId === 'other')?.favorite).toBeTrue();
  });

  it('entfavorisieren ruft removeFavorite', () => {
    chargingSpy.removeFavorite.and.returnValue(of(response));

    component.toggleFavorite(response.stations[1]);

    expect(chargingSpy.removeFavorite).toHaveBeenCalledOnceWith('fav');
  });

  it('zeigt die Server-Meldung, wenn Favorisieren scheitert (z. B. 400)', () => {
    chargingSpy.addFavorite.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { message: 'nicht in der aktuellen Umkreisliste' }
    })));

    component.toggleFavorite(response.stations[0]);

    expect(component.actionError).toContain('nicht in der aktuellen Umkreisliste');
  });

  it('haelt den Kartencontainer im DOM und zeichnet Marker', () => {
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.leaflet-container')).not.toBeNull();
    expect(host.querySelectorAll('.charging-marker').length).toBe(2);
  });

  it('zeigt bei fehlendem Zuhause den Hinweis mit Link zur Admin-Seite', () => {
    chargingSpy.getStations.and.returnValue(of({ configured: false, lastPolledAt: null, stations: [] }));
    const fresh = TestBed.createComponent(ChargingComponent);
    fresh.detectChanges();

    const hint = (fresh.nativeElement as HTMLElement).querySelector('.charging-page__unconfigured');
    expect(hint?.querySelector('a')?.getAttribute('href')).toContain('/admin/charging');
    fresh.destroy();
  });
});
```

- [ ] **Step 2: Spec laufen lassen, Fehlschlag bestätigen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/pages/charging/charging.component.spec.ts'`
Expected: Kompilierfehler.

- [ ] **Step 3: Komponente schreiben**

```typescript
import {
  AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import * as L from 'leaflet';
import { ChargingService } from '../../services/charging.service';
import { ChargePoint, ChargingStation, ChargingStationsResponse } from '../../models/charging.model';
import {
  formatDistance, formatOccupiedDuration, formatPower, sortStations, stationTone
} from '../../shared/charging-status.util';
import { homeIcon, stationIcon, stationPopupText } from '../../shared/charging-map.util';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

/** Website-Seite "Ladesaeulen": gleiche Karte/Liste wie am Tablet, plus Favorisieren (MEMBER). */
@Component({
  selector: 'app-charging',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './charging.component.html',
  styleUrl: './charging.component.scss'
})
export class ChargingComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60_000;

  private readonly chargingService = inject(ChargingService);

  @ViewChild('mapContainer') private mapContainer?: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private markerLayer?: L.LayerGroup;
  private viewInitialized = false;
  private refreshTimer: number | null = null;
  private pendingRequest: Subscription | null = null;

  data: ChargingStationsResponse | null = null;
  error: string | null = null;
  actionError: string | null = null;
  refreshing = false;
  busyStationId: string | null = null;
  now = new Date();

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(() => this.load(true), ChargingComponent.REFRESH_INTERVAL_MS);
  }

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    this.renderMap();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
    }
    this.pendingRequest?.unsubscribe();
    this.map?.remove();
    this.map = undefined;
  }

  get stations(): ChargingStation[] {
    return this.data ? sortStations(this.data.stations) : [];
  }

  refreshNow(): void {
    this.refreshing = true;
    this.actionError = null;
    this.chargingService.refresh().subscribe({
      next: response => { this.refreshing = false; this.apply(response); },
      error: (err: HttpErrorResponse) => {
        this.refreshing = false;
        this.actionError = err.error?.message ?? 'Aktualisierung fehlgeschlagen.';
      }
    });
  }

  /**
   * Nach der Antwort wird der komplette Stand uebernommen (kein lokales Umschalten): ein
   * neuer Favorit bringt Ladepunkt-Daten mit, die es vorher nicht gab.
   */
  toggleFavorite(station: ChargingStation): void {
    this.busyStationId = station.stationId;
    this.actionError = null;
    const call = station.favorite
      ? this.chargingService.removeFavorite(station.stationId)
      : this.chargingService.addFavorite(station.stationId);
    call.subscribe({
      next: response => { this.busyStationId = null; this.apply(response); },
      error: (err: HttpErrorResponse) => {
        this.busyStationId = null;
        this.actionError = err.error?.message ?? 'Favorit konnte nicht geändert werden.';
      }
    });
  }

  tone(station: ChargingStation): string {
    return stationTone(station);
  }

  power(kw: number | undefined): string {
    return formatPower(kw);
  }

  distance(meters: number): string {
    return formatDistance(meters);
  }

  duration(point: ChargePoint): string {
    return formatOccupiedDuration(point.occupiedSince, point.minimumDuration, this.now);
  }

  pointLabel(point: ChargePoint): string {
    switch (point.status) {
      case 'FREE': return 'frei';
      case 'OCCUPIED': return 'belegt';
      case 'OUT_OF_SERVICE': return 'außer Betrieb';
      case 'UNKNOWN': return 'unbekannt';
    }
  }

  private load(silent: boolean): void {
    if (!silent) {
      this.error = null;
    }
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = this.chargingService.getStations().subscribe({
      next: response => this.apply(response),
      error: (err: HttpErrorResponse) => {
        console.error('Ladesaeulen konnten nicht geladen werden:', err);
        if (!silent) {
          this.error = 'Ladesäulen konnten nicht geladen werden.';
        }
      }
    });
  }

  private apply(response: ChargingStationsResponse): void {
    this.data = response;
    this.error = null;
    this.now = new Date();
    this.renderMap();
  }

  private renderMap(): void {
    const container = this.mapContainer?.nativeElement;
    if (!this.viewInitialized || !container || !this.data?.configured || !this.data.home) {
      return;
    }
    const home: L.LatLngExpression = [this.data.home.lat, this.data.home.lon];
    if (!this.map) {
      this.map = L.map(container);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap', maxZoom: 19
      }).addTo(this.map);
      const radiusMeters = (this.data.radiusKm ?? 10) * 1000;
      L.circle(home, { radius: radiusMeters, color: '#0284c7', weight: 1, fillOpacity: 0.04 }).addTo(this.map);
      L.marker(home, { icon: homeIcon(), interactive: false }).addTo(this.map);
      this.markerLayer = L.layerGroup().addTo(this.map);
      this.map.fitBounds(L.latLng(home).toBounds(radiusMeters * 2), { padding: [8, 8] });
    }
    this.markerLayer!.clearLayers();
    for (const station of this.data.stations) {
      L.marker([station.lat, station.lon], { icon: stationIcon(station) })
        .bindPopup(stationPopupText(station))
        .addTo(this.markerLayer!);
    }
  }
}
```

- [ ] **Step 4: Template schreiben**

```html
<div class="charging-page">
  <div class="charging-page__head">
    <h1>Ladesäulen</h1>
    <div class="charging-page__actions">
      @if (data?.lastPolledAt) {
        <span class="charging-page__stand">Stand {{ data!.lastPolledAt | date:'HH:mm' }}</span>
      }
      <button type="button" (click)="refreshNow()" [disabled]="refreshing">
        {{ refreshing ? 'Lädt…' : 'Jetzt aktualisieren' }}
      </button>
    </div>
  </div>

  @if (actionError) {
    <p class="charging-page__error">{{ actionError }}</p>
  }

  @if (data && !data.configured) {
    <p class="charging-page__unconfigured">
      Es ist kein Zuhause für die Ladesäulen-Übersicht hinterlegt.
      <a routerLink="/admin/charging">Unter Admin → Ladesäulen festlegen.</a>
    </p>
  } @else {
    <div class="charging-page__body">
      <div #mapContainer class="charging-page__map"></div>

      <div class="charging-page__list">
        @if (!data) {
          <p class="charging-page__hint">{{ error ?? 'Lade Ladesäulen…' }}</p>
        } @else if (stations.length === 0) {
          <p class="charging-page__hint">{{ data.lastPolledAt ? 'Keine Ladesäulen im Umkreis.' : 'Noch keine Daten.' }}</p>
        } @else {
          @for (station of stations; track station.stationId) {
            <article class="charging-page__row" [class.charging-page__row--favorite]="station.favorite">
              <div class="charging-page__row-head">
                <span class="charging-page__dot" [attr.data-tone]="tone(station)"></span>
                <span class="charging-page__name">{{ station.name }}</span>
                <span class="charging-page__meta">
                  {{ station.free }}/{{ station.total }} frei · {{ power(station.maxPowerKw) }} · {{ distance(station.distanceMeters) }}
                </span>
                <button
                  type="button"
                  class="charging-page__star-btn"
                  [attr.aria-pressed]="station.favorite"
                  [attr.aria-label]="station.favorite ? 'Favorit entfernen' : 'Als Favorit merken'"
                  [disabled]="busyStationId === station.stationId"
                  (click)="toggleFavorite(station)">
                  {{ station.favorite ? '★' : '☆' }}
                </button>
              </div>
              @if (station.address) {
                <p class="charging-page__address">{{ station.address }}</p>
              }
              @if (station.chargePoints; as points) {
                <ul class="charging-page__points">
                  @for (point of points; track point.chargePointId) {
                    <li class="charging-page__point" [attr.data-status]="point.status">
                      <span>{{ power(point.maxPowerKw) }}</span>
                      <span>{{ pointLabel(point) }}</span>
                      @if (point.status === 'OCCUPIED') { <span class="charging-page__since">{{ duration(point) }}</span> }
                    </li>
                  }
                </ul>
              }
            </article>
          }
        }
      </div>
    </div>
  }
</div>
```

- [ ] **Step 5: SCSS schreiben**

```scss
.charging-page {
  padding: 1.5rem;
  max-width: 1200px;
  margin: 0 auto;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    flex-wrap: wrap;
  }

  &__actions { display: flex; align-items: center; gap: 0.75rem; }
  &__stand { color: #64748b; font-size: 0.9rem; }

  &__error {
    background: #fef2f2;
    border: 1px solid #fecaca;
    color: #b91c1c;
    border-radius: 8px;
    padding: 0.75rem 1rem;
  }

  &__unconfigured {
    padding: 0.75rem 1rem;
    border-radius: 8px;
    background: #fef3c7;
    color: #7c2d12;
  }

  &__body {
    display: grid;
    grid-template-columns: 3fr 2fr;
    gap: 1rem;

    @media (max-width: 900px) { grid-template-columns: 1fr; }
  }

  &__map {
    height: 520px;
    border-radius: 12px;
    overflow: hidden;

    @media (max-width: 900px) { height: 340px; }
  }

  &__list { display: flex; flex-direction: column; gap: 0.5rem; }
  &__hint { color: #64748b; text-align: center; }

  &__row {
    padding: 0.6rem 0.8rem;
    border-radius: 10px;
    border: 1px solid rgba(0, 0, 0, 0.08);
    background: #fff;

    &--favorite { background: #fffbeb; border-color: #fde68a; }
  }

  &__row-head { display: flex; align-items: center; gap: 0.6rem; }

  &__dot {
    width: 10px; height: 10px; border-radius: 50%; flex: none; background: #94a3b8;
    &[data-tone='free'] { background: #16a34a; }
    &[data-tone='busy'] { background: #dc2626; }
  }

  &__name { flex: 1; font-weight: 600; }
  &__meta { font-size: 0.85rem; color: #64748b; white-space: nowrap; }
  &__address { margin: 0.2rem 0 0 1.6rem; font-size: 0.85rem; color: #64748b; }

  &__star-btn {
    border: none;
    background: transparent;
    font-size: 1.4rem;
    line-height: 1;
    color: #f59e0b;
    cursor: pointer;
    padding: 0 0.2rem;

    &:disabled { opacity: 0.5; cursor: default; }
  }

  &__points { list-style: none; margin: 0.4rem 0 0; padding: 0 0 0 1.6rem; }

  &__point {
    display: flex; gap: 0.8rem; font-size: 0.9rem;
    &[data-status='FREE'] span:nth-child(2) { color: #16a34a; }
    &[data-status='OCCUPIED'] span:nth-child(2) { color: #dc2626; }
  }

  &__since { margin-left: auto; color: #b91c1c; }
}

:host ::ng-deep {
  .charging-marker {
    display: flex; align-items: center; justify-content: center;
    width: 30px; height: 30px; border-radius: 50%;
    color: #fff; font-weight: 700; font-size: 13px;
    background: #94a3b8;
    box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.9);

    &--free { background: #16a34a; }
    &--busy { background: #dc2626; }
    &--favorite::after { content: '★'; position: absolute; top: -10px; right: -8px; color: #f59e0b; font-size: 14px; }
  }

  .charging-home {
    width: 14px; height: 14px; border-radius: 50%;
    background: #0284c7;
    box-shadow: 0 0 0 6px rgba(2, 132, 199, 0.25);
  }
}
```

- [ ] **Step 6: Spec laufen lassen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/pages/charging/charging.component.spec.ts'`
Expected: `6 SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/charging
git commit -F - <<'EOF'
feat(charging): Website-Seite /charging mit Favorisieren am Listeneintrag

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 13: Admin-Seite `admin/charging`

**Files:**
- Create: `frontend/src/app/pages/admin-charging/admin-charging.component.ts`
- Create: `frontend/src/app/pages/admin-charging/admin-charging.component.html`
- Create: `frontend/src/app/pages/admin-charging/admin-charging.component.scss`
- Test: `frontend/src/app/pages/admin-charging/admin-charging.component.spec.ts`

- [ ] **Step 1: Failing Spec schreiben**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { AdminChargingComponent } from './admin-charging.component';
import { ChargingService } from '../../services/charging.service';
import { TractiveService } from '../../services/tractive.service';
import { ChargingSettings } from '../../models/charging.model';

describe('AdminChargingComponent', () => {
  let fixture: ComponentFixture<AdminChargingComponent>;
  let component: AdminChargingComponent;
  let chargingSpy: jasmine.SpyObj<ChargingService>;
  let tractiveSpy: jasmine.SpyObj<TractiveService>;

  const settings: ChargingSettings = { homeLatitude: 50, homeLongitude: 8, radiusKm: 10, minPowerKw: 50 };

  beforeEach(async () => {
    chargingSpy = jasmine.createSpyObj('ChargingService',
      ['getSettings', 'saveSettings', 'getStations', 'removeFavorite']);
    chargingSpy.getSettings.and.returnValue(of(settings));
    chargingSpy.getStations.and.returnValue(of({ configured: true, lastPolledAt: null, stations: [
      { stationId: 'fav', name: 'Lidl', lat: 50, lon: 8, distanceMeters: 100, total: 2, free: 1, favorite: true }
    ] }));
    tractiveSpy = jasmine.createSpyObj('TractiveService', ['getHomeSettings']);

    await TestBed.configureTestingModule({
      imports: [AdminChargingComponent],
      providers: [provideRouter([]),
        { provide: ChargingService, useValue: chargingSpy },
        { provide: TractiveService, useValue: tractiveSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(AdminChargingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('laedt die Einstellungen und zeigt das Formular', () => {
    expect(component.settings.radiusKm).toBe(10);
    expect((fixture.nativeElement as HTMLElement).querySelector('.admin-charging__fields')).not.toBeNull();
  });

  it('blendet das Formular aus, wenn das Laden scheitert', () => {
    chargingSpy.getSettings.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const fresh = TestBed.createComponent(AdminChargingComponent);
    fresh.detectChanges();

    expect(fresh.componentInstance.loadFailed()).toBeTrue();
    expect((fresh.nativeElement as HTMLElement).querySelector('.admin-charging__fields')).toBeNull();
    fresh.destroy();
  });

  it('uebernimmt das Hundetracker-Zuhause auf Knopfdruck', () => {
    tractiveSpy.getHomeSettings.and.returnValue(of({
      homeLatitude: 51.5, homeLongitude: 9.5, homeRadiusMeters: 100, homeArrivalRadiusMeters: 500,
      poweredOffAfterMinutes: 60, poweredOffMinBatteryPercent: 15, homeZoneName: 'Zuhause'
    }));

    component.adoptTractiveHome();

    expect(component.settings.homeLatitude).toBe(51.5);
    expect(component.settings.homeLongitude).toBe(9.5);
  });

  it('sperrt Speichern ausserhalb der Schranken', () => {
    component.settings.radiusKm = 80;
    expect(component.canSave).toBeFalse();
    component.settings.radiusKm = 20;
    component.settings.minPowerKw = 500;
    expect(component.canSave).toBeFalse();
    component.settings.minPowerKw = 50;
    expect(component.canSave).toBeTrue();
  });

  it('listet Favoriten und entfernt sie ueber den Service', () => {
    chargingSpy.removeFavorite.and.returnValue(of({ configured: true, lastPolledAt: null, stations: [] }));

    component.removeFavorite('fav');

    expect(chargingSpy.removeFavorite).toHaveBeenCalledOnceWith('fav');
    expect(component.favorites.length).toBe(0);
  });
});
```

- [ ] **Step 2: Spec laufen lassen, Fehlschlag bestätigen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-charging.component.spec.ts'`
Expected: Kompilierfehler.

- [ ] **Step 3: Komponente schreiben**

```typescript
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import { ChargingService } from '../../services/charging.service';
import { TractiveService } from '../../services/tractive.service';
import { ChargingSettings, ChargingStation } from '../../models/charging.model';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

const FALLBACK_CENTER: L.LatLngExpression = [51.1657, 10.4515];
const FALLBACK_ZOOM = 6;
const CONFIGURED_ZOOM = 12;

/**
 * Admin-Seite "Ladesaeulen": Zuhause (Leaflet-Klick), Radius, Mindestleistung, Favoritenliste.
 * Die Schranken 1-50 km / 0-400 kW stehen ZWEIMAL: verbindlich in ChargingSettingsService
 * (Backend), hier nur als Bedienhilfe - wer eine aendert, zieht die andere nach.
 */
@Component({
  selector: 'app-admin-charging',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-charging.component.html',
  styleUrl: './admin-charging.component.scss'
})
export class AdminChargingComponent implements OnInit, OnDestroy {
  private readonly chargingService = inject(ChargingService);
  private readonly tractiveService = inject(TractiveService);

  readonly radiusMin = 1;
  readonly radiusMax = 50;
  readonly powerMin = 0;
  readonly powerMax = 400;

  readonly loading = signal(true);
  readonly saving = signal(false);
  /** Bei Ladefehler bleibt das Formular verborgen - sonst ueberschriebe "Speichern" echte Werte mit Vorgaben. */
  readonly loadFailed = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);
  readonly favoriteError = signal<string | null>(null);

  settings: ChargingSettings = { homeLatitude: null, homeLongitude: null, radiusKm: 10, minPowerKw: 50 };
  favorites: ChargingStation[] = [];

  private map?: L.Map;
  private marker?: L.Marker;
  private circle?: L.Circle;

  ngOnInit(): void {
    this.chargingService.getSettings().subscribe({
      next: settings => {
        this.settings = settings;
        this.loading.set(false);
        setTimeout(() => this.initMap());
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set('Einstellungen konnten nicht geladen werden.');
      }
    });
    this.loadFavorites();
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.map = undefined;
  }

  get hasCoordinates(): boolean {
    return this.settings.homeLatitude != null && this.settings.homeLongitude != null;
  }

  get canSave(): boolean {
    const r = this.settings.radiusKm;
    const p = this.settings.minPowerKw;
    return Number.isFinite(r) && r >= this.radiusMin && r <= this.radiusMax
      && Number.isFinite(p) && p >= this.powerMin && p <= this.powerMax;
  }

  onValueChange(): void {
    this.successMessage.set(null);
    this.renderHome();
  }

  save(): void {
    this.saving.set(true);
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.chargingService.saveSettings(this.settings).subscribe({
      next: saved => {
        this.settings = saved;
        this.saving.set(false);
        this.successMessage.set('Gespeichert. Der Umkreis wird beim nächsten Abruf neu geladen (bis zu 5 Minuten).');
        this.renderHome();
      },
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

  /** Einmalige Vorbelegung aus der Hundetracker-Definition; danach eigenstaendige Werte. */
  adoptTractiveHome(): void {
    this.errorMessage.set(null);
    this.tractiveService.getHomeSettings().subscribe({
      next: home => {
        if (home.homeLatitude == null || home.homeLongitude == null) {
          this.errorMessage.set('Im Hundetracker ist kein Zuhause hinterlegt.');
          return;
        }
        this.settings.homeLatitude = home.homeLatitude;
        this.settings.homeLongitude = home.homeLongitude;
        this.successMessage.set(null);
        this.renderHome(true);
      },
      error: () => this.errorMessage.set('Hundetracker-Zuhause konnte nicht geladen werden.')
    });
  }

  removeFavorite(stationId: string): void {
    this.favoriteError.set(null);
    this.chargingService.removeFavorite(stationId).subscribe({
      next: response => { this.favorites = response.stations.filter(s => s.favorite); },
      error: (err: HttpErrorResponse) =>
        this.favoriteError.set(err.error?.message ?? 'Favorit konnte nicht entfernt werden.')
    });
  }

  private loadFavorites(): void {
    this.chargingService.getStations().subscribe({
      next: response => { this.favorites = response.stations.filter(s => s.favorite); },
      error: () => this.favoriteError.set('Favoriten konnten nicht geladen werden.')
    });
  }

  private initMap(): void {
    const container = document.getElementById('charging-home-map');
    if (!container || this.map) {
      return;
    }
    const center: L.LatLngExpression = this.hasCoordinates
      ? [this.settings.homeLatitude!, this.settings.homeLongitude!] : FALLBACK_CENTER;
    this.map = L.map(container).setView(center, this.hasCoordinates ? CONFIGURED_ZOOM : FALLBACK_ZOOM);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap', maxZoom: 19
    }).addTo(this.map);
    this.map.on('click', (event: L.LeafletMouseEvent) => {
      this.settings.homeLatitude = Number(event.latlng.lat.toFixed(6));
      this.settings.homeLongitude = Number(event.latlng.lng.toFixed(6));
      this.successMessage.set(null);
      this.renderHome();
    });
    this.renderHome();
  }

  private renderHome(recenter = false): void {
    if (!this.map) {
      return;
    }
    if (!this.hasCoordinates) {
      this.marker?.remove();
      this.circle?.remove();
      this.marker = undefined;
      this.circle = undefined;
      return;
    }
    const position: L.LatLngExpression = [this.settings.homeLatitude!, this.settings.homeLongitude!];
    const radiusMeters = this.settings.radiusKm * 1000;
    this.marker ? this.marker.setLatLng(position) : (this.marker = L.marker(position).addTo(this.map));
    if (this.circle) {
      this.circle.setLatLng(position).setRadius(radiusMeters);
    } else {
      this.circle = L.circle(position, { radius: radiusMeters, color: '#0284c7', weight: 2, fillOpacity: 0.08 })
        .addTo(this.map);
    }
    if (recenter) {
      this.map.setView(position, CONFIGURED_ZOOM);
    }
  }
}
```

- [ ] **Step 4: Template schreiben**

```html
<div class="admin-charging">
  <h1>Ladesäulen – Zuhause, Umkreis und Favoriten</h1>

  <p *ngIf="loading()">Wird geladen …</p>

  <p class="error" *ngIf="loadFailed()">
    {{ errorMessage() }} Das Formular bleibt ausgeblendet, damit nichts versehentlich
    überschrieben wird. Bitte die Seite neu laden.
  </p>

  <ng-container *ngIf="!loading() && !loadFailed()">
    <p class="warning" *ngIf="!hasCoordinates">
      Es ist kein Zuhause hinterlegt. Ohne Koordinaten gibt es keinen Umkreis, keine Karte und
      keinen Abruf. Klicke auf der Karte auf dein Haus oder übernimm das Hundetracker-Zuhause.
    </p>

    <div id="charging-home-map" class="map"></div>

    <div class="admin-charging__fields">
      <label>
        Breitengrad
        <input type="number" step="0.000001" name="latitude"
               [(ngModel)]="settings.homeLatitude" (ngModelChange)="onValueChange()">
      </label>
      <label>
        Längengrad
        <input type="number" step="0.000001" name="longitude"
               [(ngModel)]="settings.homeLongitude" (ngModelChange)="onValueChange()">
      </label>
      <label>
        Radius (km)
        <input type="number" [min]="radiusMin" [max]="radiusMax" step="1" name="radius"
               [(ngModel)]="settings.radiusKm" (ngModelChange)="onValueChange()">
        <span class="hint">Nur Säulen innerhalb dieses Kreises werden abgefragt (1–50 km).</span>
      </label>
      <label>
        Mindestleistung (kW)
        <input type="number" [min]="powerMin" [max]="powerMax" step="1" name="minPower"
               [(ngModel)]="settings.minPowerKw" (ngModelChange)="onValueChange()">
        <span class="hint">Säulen darunter erscheinen nicht (0 = alle, 50 = nur Schnelllader).</span>
      </label>
    </div>

    <div class="actions">
      <button type="button" (click)="save()" [disabled]="saving() || !canSave">
        {{ saving() ? 'Speichert …' : 'Speichern' }}
      </button>
      <button type="button" class="ghost" (click)="adoptTractiveHome()">
        Aus Hundetracker-Zuhause übernehmen
      </button>
      <button type="button" class="danger" (click)="clearCoordinates()" *ngIf="hasCoordinates">
        Koordinaten entfernen
      </button>
    </div>

    <p class="error" *ngIf="errorMessage()">{{ errorMessage() }}</p>
    <p class="success" *ngIf="successMessage()">{{ successMessage() }}</p>
  </ng-container>

  <h2>Favoriten</h2>
  <p class="hint">
    Favoriten werden jede Minute einzeln abgefragt und bekommen die Belegungsdauer je Ladepunkt.
    Hinzufügen geht auf der Seite „Ladesäulen" über den Stern.
  </p>
  <p class="error" *ngIf="favoriteError()">{{ favoriteError() }}</p>
  <ul class="admin-charging__favorites" *ngIf="favorites.length > 0; else noFavorites">
    <li *ngFor="let favorite of favorites">
      <span class="admin-charging__favorite-name">{{ favorite.name }}</span>
      <span class="hint" *ngIf="favorite.operator">{{ favorite.operator }}</span>
      <button type="button" class="danger" (click)="removeFavorite(favorite.stationId)">Entfernen</button>
    </li>
  </ul>
  <ng-template #noFavorites><p class="hint">Noch keine Favoriten.</p></ng-template>
</div>
```

- [ ] **Step 5: SCSS schreiben**

```scss
.admin-charging {
  padding: 1.5rem;
  max-width: 1000px;
  margin: 0 auto;

  &__fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 1rem;
    margin-bottom: 1rem;

    label { display: flex; flex-direction: column; gap: 0.25rem; font-weight: 600; }
  }

  &__favorites {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;

    li {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid rgba(0, 0, 0, 0.08);
      border-radius: 8px;
    }
  }

  &__favorite-name { flex: 1; font-weight: 600; }
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

.hint { font-weight: 400; font-size: 0.85rem; color: #64748b; }
.actions { display: flex; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 1rem; }
.error { color: #b91c1c; }
.success { color: #14532d; }
```

- [ ] **Step 6: Spec laufen lassen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-charging.component.spec.ts'`
Expected: `5 SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/admin-charging
git commit -F - <<'EOF'
feat(charging): Admin-Seite Zuhause/Radius/Mindestleistung/Favoriten

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 14: Verdrahtung — Tablet-Leiste, Routen, Header-Menü, Admin-Scope

**Files:**
- Modify: `frontend/src/app/shared/tablet-views.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`
- Modify: `frontend/src/styles.scss` (`$admin-scopes`)

- [ ] **Step 1: Siebten Tablet-Eintrag ergänzen** in `TABLET_VIEWS` nach dem Kameras-Eintrag:

```typescript
  { route: '/tablet/cameras', icon: 'videocam', label: 'Kameras' },
  { route: '/tablet/charging', icon: 'ev_station', label: 'Laden' }
```

- [ ] **Step 2: Routen ergänzen** in `app.routes.ts`:

Nach dem Block `tablet/cameras`:
```typescript
  {
    path: 'tablet/charging',
    loadComponent: () => import('./pages/tablet-charging/tablet-charging.component').then(m => m.TabletChargingComponent),
    canActivate: [authGuard],
    title: 'Laden Tablet - Household Manager'
  },
```

Nach dem Block `cameras`:
```typescript
  {
    path: 'charging',
    loadComponent: () => import('./pages/charging/charging.component').then(m => m.ChargingComponent),
    canActivate: [authGuard],
    title: 'Ladesäulen - Household Manager'
  },
```

Nach dem Block `admin/network-devices`:
```typescript
  {
    path: 'admin/charging',
    loadComponent: () => import('./pages/admin-charging/admin-charging.component').then(m => m.AdminChargingComponent),
    canActivate: [adminGuard],
    title: 'Ladesäulen-Einstellungen - Household Manager'
  },
```

- [ ] **Step 3: Header-Menü ergänzen** in `header.component.ts`:

Unter „Smart Home" nach `{ path: '/cameras', label: 'Kameras' },`:
```typescript
        { path: '/charging', label: 'Ladesäulen' },
```

Unter „Admin" nach `{ path: '/admin/network-devices', label: 'Netzwerk-Geräte', minRole: 'ADMIN' },`:
```typescript
        { path: '/admin/charging', label: 'Ladesäulen', minRole: 'ADMIN' },
```

- [ ] **Step 4: Admin-Scope eintragen** in `styles.scss` — die Zeile `$admin-scopes:` um `, .admin-charging` ergänzen:

```scss
$admin-scopes: '.admin-users, .admin-tokens, .audit-log, .admin-tractive, .admin-calendar-categories, .vision, .pets-page, .admin-presence, .admin-mode-quick-access, .admin-charging';
```

- [ ] **Step 5: Gesamten Frontend-Testlauf**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: nur die 3 vorbestehenden Fails (App/Hero). Gelegentliche Karma-Flake `SmartDeviceListComponent afterAll` bei Verdacht erneut laufen lassen. Zusätzlich `npx ng build --configuration production` — das `anyComponentStyle`-Budget wird nur vom Dashboard gerissen (bekannt); ein Fehler in einer *neuen* Datei wäre eine Regression.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/tablet-views.ts frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts frontend/src/styles.scss
git commit -F - <<'EOF'
feat(charging): Tablet-Leiste, Routen, Menue und Admin-Scope verdrahtet

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 15: Backend-Gesamtlauf und Start gegen echte Quelle

**Files:** keine neuen.

- [ ] **Step 1: Alle Backend-Tests**

Run: `cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test`
Expected: nur die vorbestehenden DB-Fails (`contextLoads`, `HealthControllerTest`). Jeder andere Fehler ist eine Regression — insbesondere `SecurityRulesTest` (Matcher-Reihenfolge) und ein Bean-Fehler in `ChargingPollingService` (SpEL `#{${charging.area-poll-seconds:300} * 1000}` muss parsen; schlägt es fehl, auf `fixedDelayString = "${charging.area-poll-interval-ms:300000}"` umstellen und die Properties entsprechend in Millisekunden umbenennen).

- [ ] **Step 2: Backend lokal starten und Umkreis-Poll beobachten**

Lokal mit `ZIGBEE_MQTT_CLIENT_ID=household-manager-zigbee-dev` (Dev-Falle) und `TELEGRAM_ENABLED=false` starten (IntelliJ-Run-Config). Dann:
1. Als Admin unter `/admin/charging` das Zuhause setzen (oder „Aus Hundetracker-Zuhause übernehmen"), Radius 10 km, Mindestleistung 50 kW, speichern.
2. Auf `/charging` „Jetzt aktualisieren" drücken. Erwartet: Marker auf der Karte, Liste nach Entfernung. Fehlt alles, im Log nach `EnBW antwortet HTTP` suchen (Key/Header-Problem) oder `nicht lesbar` (Feldnamen — Fixture vs. DTO abgleichen).
3. Eine Station favorisieren, Minute warten: die Zeile bekommt Ladepunkte; `GET /api/v1/entities?source=CHARGING` zeigt `sensor.charging_<id>_free`.
4. Bei einer belegten Säule: nach dem ersten Poll steht „seit mind. 0 min"; wird sie später frei und wieder belegt, steht „seit N min" ohne „mind.".

- [ ] **Step 3: Tablet-Ansicht prüfen**

`/tablet/charging` im Browser bei ~1280×800 öffnen: Karte links, Liste rechts, Leiste unten mit „Laden" als siebtem Eintrag (scrollt seitwärts). Zoomen, eine Minute warten: der Ausschnitt bleibt.

- [ ] **Step 4: Befund festhalten** — was real abwich (Feldnamen, Statustexte, Rate-Limit, Latenz), kommt in Task 16 in die Doku.

---

### Task 16: Dokumentation (CLAUDE.md, Spec-Abgleich, Memory)

**Files:**
- Modify: `CLAUDE.md` (neuer Abschnitt unter „Smart Device Integrations", nach „Netzwerk-Monitoring")
- Modify: `docs/superpowers/specs/2026-09-19-ladesaeulen-uebersicht-design.md` (Abweichungen nachziehen)
- Create: `C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\ladesaeulen-uebersicht.md` + Zeile in `MEMORY.md`

- [ ] **Step 1: CLAUDE.md-Abschnitt schreiben** (Inhalt an den Realbefund aus Task 15 anpassen):

```markdown
### Ladesäulen-Übersicht (Tesla)
- Modul `backend/src/main/java/com/household/manager/charging/`; Spec: `docs/superpowers/specs/2026-09-19-ladesaeulen-uebersicht-design.md`. Tablet-Ansicht `/tablet/charging` („Laden", siebter Eintrag in `TABLET_VIEWS`), Website `/charging` (Favorisieren per Stern, MEMBER), Admin `admin/charging` (Zuhause per Leaflet-Klick, Radius 1–50 km, Mindestleistung 0–400 kW, Favoritenliste)
- **Quelle ist das inoffizielle EnBW-mobility+-Backend** (`EnbwChargingClient` hinter `ChargingStationSource`, HTTP/1.1 erzwungen, Key `charging.enbw.api-key` = der öffentlich in der Web-App eingebettete, per `CHARGING_ENBW_API_KEY` nachziehbar). Es gibt keine offizielle Quelle mit Live-Belegung; ein Key- oder Formatwechsel legt die Ansicht lahm, sichtbar nur am alternden „Stand von" und an `unavailable`-Entitäten. Feldnamen sind gegen die aufgezeichneten Fixtures `backend/src/test/resources/charging/*.json` getestet — bei einem Formatwechsel zuerst `scripts/probe-enbw-charging.sh` neu laufen lassen
- **Zwei Poll-Pfade:** Umkreis alle 5 min (ein Request, Bounding Box, danach Haversine-Kreis + Mindestleistung in `ChargingAreaFilter`), Favoriten jede Minute (ein Detail-Request je Favorit, Fehler je Favorit isoliert, Rate-Limit bricht ab). Stand nur im Speicher (`ChargingSnapshot`), bleibt bei Fehlern erhalten
- **„Wie lange belegt" liefert keine API** — `ChargingOccupancyTracker` schreibt Übergänge je Ladepunkt nach `charging_point_occupancy`: frei→belegt setzt `occupied_since`; beim ersten Poll schon belegt ⇒ `occupied_since` NULL und die Anzeige sagt „seit mind." (`minimumDuration`); belegt→frei löscht; `UNKNOWN` fasst nichts an. Nur Favoriten haben Ladepunkte einzeln, der Umkreis nur Zähler
- **Zuhause ist eine eigene Einstellung** (`application_settings`, Kategorie `CHARGING`), nicht die Tractive-Home-Definition — die Admin-Seite bietet nur eine einmalige Übernahme. Sonst verschöbe ein Ändern des Hunde-Zuhauses still die Ladekarte. Lesen wirft nie; die Schranken stehen zweimal (Backend verbindlich, `admin-charging.component.ts` als Bedienhilfe)
- Entitäten `sensor.charging_<stationId>_free` (`EntitySource.CHARGING`, `ChargingEntityMapper.entityId` ist die einzige Id-Definition), State = freie Ladepunkte, Attribute `total`/`maxPowerKw`/`stationName`/`operator`/`occupiedSince`. **Kein `deviceClass`** (siehe Blink). `unavailable` mit erhaltenen Attributen; ein entfernter Favorit wird einmal `unavailable` gemeldet. Kein Trigger auf `value: "unavailable"` (tote-Trigger-Falle); Flow-Beispiel: `changed` von `0` auf `≥1` → `telegram-send`
- Security: `/v1/charging/settings` methodenloser ADMIN-Matcher **vor** der generischen GET-Regel; `POST /v1/charging/refresh` in der KIOSK-POST-Whitelist (Mindestabstand 15 s → 429); Favoriten MEMBER über `anyRequest`. `ChargingRateLimitException` erbt von `TooManyRequestsException`, `ChargingSourceException` → 502, **nie 401**
- Frontend: Tablet und Website teilen **keinen** Komponenten-Code, nur `ChargingService`, das Modell, `shared/charging-status.util.ts` (Farbe, Dauerformat, Sortierung) und `shared/charging-map.util.ts` (Marker). Kartencontainer immer im DOM (`ViewChild` + `ngAfterViewInit`); ein Refresh tauscht nur Marker und setzt den Ausschnitt nicht zurück; die Dauer läuft per Minuten-Tick zwischen zwei Abrufen weiter
- Grenzen v1: keine Preise, keine Historie, ein Zuhause, Dauer nur für Favoriten; OSM-Kacheln aus dem Internet
```

- [ ] **Step 2: Spec nachziehen** — jede Abweichung aus Task 15 (Feldnamen, Statustexte, Poll-Intervalle) in der Spec korrigieren; Abschnitt „Datenquelle" von „Annahme" auf „verifiziert am <Datum>" umstellen, oder die offene Abweichung benennen.

- [ ] **Step 3: Memory schreiben**

`ladesaeulen-uebersicht.md`:
```markdown
---
name: ladesaeulen-uebersicht
description: Ladesäulen-Übersicht (Tesla) — EnBW-Backend inoffiziell, Belegungsdauer selbst gerechnet; Stand von Realtest und Deploy
metadata:
  type: project
---

Ladesäulen-Übersicht gebaut am 2026-09-19 auf `feature/charging-overview` (Spec `docs/superpowers/specs/2026-09-19-ladesaeulen-uebersicht-design.md`). Quelle ist das inoffizielle EnBW-mobility+-Backend hinter `ChargingStationSource`; Belegungsdauer je Ladepunkt kommt aus eigenem Polling (`charging_point_occupancy`), „seit mind." bei unbekanntem Beginn.

**Why:** Der Nutzer will am Wandtablet sehen, ob die Lidl-/Kaufland-Säulen belegt sind und wie lange schon, um abzuschätzen, wann eine frei wird. Keine offizielle API bietet Live-Belegung.

**How to apply:** Bei „Karte leer" zuerst `scripts/probe-enbw-charging.sh` gegen das Backend laufen lassen — Key oder Feldnamen sind die ersten Verdächtigen, nicht der Code. Realtest-Stand: <hier eintragen: Feldnamen bestätigt? Rate-Limit beobachtet? PROD-Deploy?>. Siehe auch [[tractive-home-settings-db]] (gleiches Settings-Muster) und [[dashboard-style-encapsulation]].
```

Zeile in `MEMORY.md`:
```markdown
- [Ladesäulen-Übersicht](ladesaeulen-uebersicht.md) — EnBW inoffiziell hinter Interface, Dauer selbst gerechnet („seit mind."); Realtest-/Deploy-Stand in der Datei
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-09-19-ladesaeulen-uebersicht-design.md
git commit -F - <<'EOF'
docs(charging): CLAUDE.md-Abschnitt und Spec an Realbefund angeglichen

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
```

---

### Task 17: Abschluss

- [ ] **Step 1:** `superpowers:verification-before-completion` — Backend `mvn -q test` und Frontend-Testlauf noch einmal komplett, Ausgabe prüfen (Baseline-Fails, sonst grün).
- [ ] **Step 2:** `superpowers:requesting-code-review` für den Branch-Diff gegen `main`.
- [ ] **Step 3:** `superpowers:finishing-a-development-branch` — Merge nach `main` und Push nach Nutzerentscheidung; PROD-Deploy bleibt offen (Migration `20260919-0055` nie gegen echte DB gelaufen: Backup vorher).

---

## Plan-Selbstprüfung (durchgeführt beim Schreiben)

- **Spec-Abdeckung:** Datenquelle/Client (T1, T4), Settings (T3), Polling + Snapshot (T7, T8), Belegungsdauer (T6), Favoriten (T7), Entitäten (T8), API + Security (T9), Tablet (T11), Website (T12), Admin (T13), Verdrahtung (T14), Tests je Task, Doku (T16). Nicht in der Spec, bewusst ergänzt: `charging-map.util.ts` (T10) und `GET /v1/charging/favorites` (T9, für die Admin-Liste nicht nötig — sie liest `stations` — aber billig; kann entfallen).
- **Typkonsistenz:** `ChargingStation(stationId, name, operator, address, lat, lon, maxPowerKw, total, free)` überall in dieser Reihenfolge; `ChargePoint(chargePointId, status, maxPowerKw, connector)`; `ChargingSettings(homeLatitude, homeLongitude, radiusKm, minPowerKw)`; `ChargingSnapshot.updateArea/updateFavoriteDetails/areaStations/station/details/lastPolledAt`; `ChargingOccupancyTracker.record/occupancyFor/forgetStation`; Frontend `ChargingService.getStations/refresh/addFavorite/removeFavorite/getSettings/saveSettings`.
- **Offene Annahme:** EnBW-Feldnamen und Statustexte bis Task 1 unverifiziert; der Plan macht die Fixture zur Wahrheit.
