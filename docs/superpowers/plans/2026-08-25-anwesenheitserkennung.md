# Anwesenheitserkennung per WLAN — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Anwesenheits-/Abwesenheitserfassung pro Person über TCP-Probes gegen die iPhone-IPs im WLAN, mit Entitäten für Flows, Admin-Seite und Dashboard-Kachel.

**Architecture:** Neues Backend-Modul `presence/` pollt alle 30 s die in `presence_device` gepflegten Handy-IPs. Jede TCP-Antwort (auch „Connection refused") zählt als anwesend; abwesend erst nach einer konfigurierbaren Karenzzeit (Default 10 Min., `application_settings` Kategorie `PRESENCE`). Pro Person entsteht `binary_sensor.presence_<userId>_home`, dazu das Aggregat `binary_sensor.presence_household`. Die Modus-Flows („Abwesend" automatisch) werden **beim Rollout via flow-mcp** angelegt, nicht im Code.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / Mockito; Angular 19 standalone / SCSS / Karma.

**Spec:** `docs/superpowers/specs/2026-08-25-anwesenheitserkennung-design.md`

---

## Wichtige Umgebungs-Hinweise (vor Task 1 lesen)

- **Backend-Builds brauchen JDK 21.** Vor jedem Maven-Aufruf (Bash-Syntax):
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
  ```
  Maven liegt unter `C:\Users\bened\apache-maven-3.9.11\bin\mvn` (auf dem PATH), es gibt **kein** `mvnw`. Immer aus `backend/` heraus laufen lassen.
- **Vorbestehende Test-Fails (KEINE Regressionen):** Backend: `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern lokal an „Access denied for user 'root'@'localhost'" (keine lokale Test-DB). Frontend-Baseline: genau 3 Fails (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`) plus gelegentliche `SmartDeviceListComponent`-Karma-Flake — bei Verdacht erneut laufen lassen.
- **Frontend-Tests:** `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`.
- **Alle JPA-Repositories MÜSSEN in `com.household.manager.repository` liegen** (JpaConfig schränkt das Scanning ein).
- Branch: `feature/presence-wifi` (in Task 1 angelegt).

---

### Task 1: Migration, Entity, Repository, EntitySource

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260825-0050-create-presence-device-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Include am Ende der Include-Liste ergänzen)
- Create: `backend/src/main/java/com/household/manager/model/entity/PresenceDevice.java`
- Create: `backend/src/main/java/com/household/manager/repository/PresenceDeviceRepository.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`

- [ ] **Step 1: Branch anlegen**

```bash
git checkout -b feature/presence-wifi
```

- [ ] **Step 2: Liquibase-Changeset schreiben**

Der FK auf `app_user` steht **inline im `createTable`** (ein einziges DDL — MariaDB committet jedes DDL implizit, getrennte Changesets wären hier unnötig, siehe Kommentar in `20260824-0049-create-network-tables.xml`). `ON DELETE CASCADE` räumt die Geräte eines gelöschten Nutzers ab.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260825-0050-create-presence-device" author="claude">
        <comment>Handys der Anwesenheitserkennung (TCP-Probe gegen feste WLAN-IPs).</comment>

        <createTable tableName="presence_device">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="user_id" type="BIGINT">
                <constraints nullable="false"
                             foreignKeyName="fk_presence_device_user"
                             references="app_user(id)"
                             deleteCascade="true"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="host" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="active" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <rollback>
            <dropTable tableName="presence_device"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

In `db.changelog-master.xml` ans Ende der Include-Liste (nach dem Network-Include):

```xml
    <!-- Anwesenheitserkennung (WLAN) -->
    <include file="db/changelog/changes/20260825-0050-create-presence-device-table.xml"/>
```

- [ ] **Step 3: Entity schreiben**

`PresenceDevice.java` — `userId` als schlichte Long-Spalte (kein `@ManyToOne`, der Poller braucht keine Navigation; Anzeigenamen löst er über `AppUserRepository` auf). Timestamps per `@PrePersist`/`@PreUpdate` wie `AppUser`:

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

import java.time.LocalDateTime;

/**
 * Ein Handy der Anwesenheitserkennung: gehoert einer Person (app_user) und wird
 * per TCP-Probe gegen seine feste WLAN-IP geprueft.
 */
@Entity
@Table(name = "presence_device")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

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

- [ ] **Step 4: Repository schreiben** (MUSS ins Paket `com.household.manager.repository`)

```java
package com.household.manager.repository;

import com.household.manager.model.entity.PresenceDevice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PresenceDeviceRepository extends JpaRepository<PresenceDevice, Long> {

    java.util.List<PresenceDevice> findAllByOrderByIdAsc();
}
```

- [ ] **Step 5: EntitySource erweitern**

In `EntitySource.java` nach dem `NETWORK`-Eintrag (Komma bei `NETWORK` ergänzen):

```java
    /** Internet-Konnektivitäts- und Latenzmessung (interner Poller, kein externes Quellsystem). */
    NETWORK,
    /** Anwesenheitserkennung pro Person (TCP-Probe gegen Handy-IPs im WLAN). */
    PRESENCE
```

- [ ] **Step 6: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile
```
Expected: BUILD SUCCESS (keine Ausgabe bei `-q` außer Warnungen).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/PresenceDevice.java backend/src/main/java/com/household/manager/repository/PresenceDeviceRepository.java backend/src/main/java/com/household/manager/entitystate/EntitySource.java
git commit -m "feat(presence): Tabelle presence_device, Entity und EntitySource PRESENCE"
```

---

### Task 2: Drei-Zustands-Probe (PresenceProbe)

Kern-Trick: **jede TCP-Antwort zählt** — auch „Connection refused" (RST beweist: der Host lebt). Der bestehende `TcpPortProbe` kann das nicht (refused und timeout sind für ihn beides „zu").

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/ProbeResult.java`
- Create: `backend/src/main/java/com/household/manager/presence/PresenceProbe.java`
- Create: `backend/src/main/java/com/household/manager/presence/SocketPresenceProbe.java`
- Test: `backend/src/test/java/com/household/manager/presence/SocketPresenceProbeTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.presence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SocketPresenceProbeTest {

    private final SocketPresenceProbe probe = new SocketPresenceProbe();

    @Test
    void offenerPortZaehltAlsAntwort() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            ProbeResult result = probe.probe("127.0.0.1",
                    List.of(server.getLocalPort()), Duration.ofSeconds(1));
            assertThat(result).isEqualTo(ProbeResult.RESPONDED);
        }
    }

    @Test
    void abgelehnteVerbindungZaehltAlsAntwort() throws IOException {
        // Port kurz belegen und wieder freigeben: connect dahin liefert "refused" (RST),
        // und genau das beweist, dass der Host lebt.
        int freedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            freedPort = server.getLocalPort();
        }
        ProbeResult result = probe.probe("127.0.0.1", List.of(freedPort), Duration.ofSeconds(1));
        assertThat(result).isEqualTo(ProbeResult.RESPONDED);
    }

    @Test
    void timeoutAufAllenPortsIstStille() {
        // 192.0.2.1 (TEST-NET-1) ist nicht geroutet -> Connect laeuft in den Timeout.
        ProbeResult result = probe.probe("192.0.2.1", List.of(80), Duration.ofMillis(200));
        assertThat(result).isEqualTo(ProbeResult.SILENT);
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SocketPresenceProbeTest
```
Expected: COMPILATION ERROR (ProbeResult/SocketPresenceProbe existieren nicht).

- [ ] **Step 3: Implementieren**

`ProbeResult.java`:

```java
package com.household.manager.presence;

/** Ergebnis einer Handy-Probe: hat der Host irgendwie geantwortet? */
public enum ProbeResult {
    /** Verbindung angenommen ODER abgelehnt (RST) — der Host lebt. */
    RESPONDED,
    /** Timeout auf allen Ports — keine Lebensaeusserung. */
    SILENT
}
```

`PresenceProbe.java`:

```java
package com.household.manager.presence;

import java.time.Duration;
import java.util.List;

/**
 * Prueft, ob ein Host im WLAN auf TCP ueberhaupt reagiert. Anders als
 * {@code TcpPortProbe} unterscheidet diese Probe "abgelehnt" von "still":
 * ein RST (Connection refused) beweist Anwesenheit genauso wie ein offener Port.
 */
public interface PresenceProbe {

    ProbeResult probe(String host, List<Integer> ports, Duration timeoutPerPort);
}
```

`SocketPresenceProbe.java`:

```java
package com.household.manager.presence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

/**
 * Socket-Implementierung: erste Antwort gewinnt. Ein {@link ConnectException}
 * (Connection refused) ist eine Antwort — nur Timeouts und Routing-Fehler
 * zaehlen als Stille.
 */
@Component
@Slf4j
public class SocketPresenceProbe implements PresenceProbe {

    @Override
    public ProbeResult probe(String host, List<Integer> ports, Duration timeoutPerPort) {
        for (int port : ports) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), (int) timeoutPerPort.toMillis());
                return ProbeResult.RESPONDED;
            } catch (ConnectException e) {
                // Aktive Ablehnung (RST): der Host lebt.
                return ProbeResult.RESPONDED;
            } catch (Exception e) {
                log.debug("Keine Antwort von {}:{}: {}", host, port, e.getMessage());
            }
        }
        return ProbeResult.SILENT;
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SocketPresenceProbeTest
```
Expected: 3 Tests PASS. (Sollte `timeoutAufAllenPortsIstStille` in dieser Netzumgebung wider Erwarten eine Antwort bekommen, den Test auf einen Kommentar mit Begründung reduzieren — nicht raten.)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence backend/src/test/java/com/household/manager/presence
git commit -m "feat(presence): Drei-Zustands-Probe - refused zaehlt als anwesend"
```

---

### Task 3: Karenzzeit-Einstellung (PresenceSettingsService)

Muster `TractiveHomeSettingsService`: Lesen wirft nie, unplausible Werte fallen auf den Default zurück.

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresenceSettingsService.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresenceSettingsServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceSettingsServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettings;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private PresenceSettingsService service;

    @Test
    void ohneEintragGiltDerDefault() {
        when(applicationSettings.getSettingsByCategory("PRESENCE")).thenReturn(Map.of());
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);
    }

    @Test
    void gespeicherterWertWirdGelesen() {
        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "25"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(25L);
    }

    @Test
    void unplausibleWerteFallenAufDenDefaultZurueck() {
        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "0"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);

        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "99999"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);

        when(applicationSettings.getSettingsByCategory("PRESENCE"))
                .thenReturn(Map.of("away_grace_minutes", "abc"));
        assertThat(service.getAwayGraceMinutes()).isEqualTo(10L);
    }

    @Test
    void speichernSchreibtWertUndAudit() {
        service.saveAwayGraceMinutes(15L);
        verify(applicationSettings).saveSettings("PRESENCE", Map.of("away_grace_minutes", "15"));
        verify(auditService).record(eq("presence.settings.update"), eq("away_grace_minutes=15"));
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceSettingsServiceTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Karenzzeit der Anwesenheitserkennung in {@code application_settings}
 * (Kategorie PRESENCE). Lesen wirft nie: der Poller laeuft alle 30 s, ein
 * Tippfehler in der Datenbank darf ihn nicht lahmlegen (Muster
 * {@code TractiveHomeSettingsService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceSettingsService {

    static final String CATEGORY = "PRESENCE";
    static final String KEY_AWAY_GRACE = "away_grace_minutes";
    static final long DEFAULT_AWAY_GRACE_MINUTES = 10;
    /** Mehr als 24 h Karenz ergibt keine Abwesenheitserkennung mehr. */
    static final long MAX_AWAY_GRACE_MINUTES = 1440;

    private final ApplicationSettingsService applicationSettings;
    private final AuditService auditService;

    public long getAwayGraceMinutes() {
        String raw = applicationSettings.getSettingsByCategory(CATEGORY).get(KEY_AWAY_GRACE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_AWAY_GRACE_MINUTES;
        }
        try {
            long value = Long.parseLong(raw);
            if (value < 1 || value > MAX_AWAY_GRACE_MINUTES) {
                log.warn("Unplausibler Wert '{}' fuer {}, nutze {}", raw, KEY_AWAY_GRACE,
                        DEFAULT_AWAY_GRACE_MINUTES);
                return DEFAULT_AWAY_GRACE_MINUTES;
            }
            return value;
        } catch (NumberFormatException ex) {
            log.warn("Unlesbarer Wert '{}' fuer {}, nutze {}", raw, KEY_AWAY_GRACE,
                    DEFAULT_AWAY_GRACE_MINUTES);
            return DEFAULT_AWAY_GRACE_MINUTES;
        }
    }

    public void saveAwayGraceMinutes(long minutes) {
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_AWAY_GRACE, String.valueOf(minutes)));
        auditService.record("presence.settings.update", KEY_AWAY_GRACE + "=" + minutes);
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceSettingsServiceTest
```
Expected: 4 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresenceSettingsService.java backend/src/test/java/com/household/manager/presence/PresenceSettingsServiceTest.java
git commit -m "feat(presence): Karenzzeit als Admin-Einstellung mit defensivem Lesen"
```

---

### Task 4: In-Memory-Monitor (PresenceMonitor)

Muster `NetworkDeviceStatusMonitor`, plus `startedAt` für die Anlauf-Karenz nach Neustarts.

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresenceMonitor.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresenceMonitorTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.presence;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceMonitorTest {

    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");
    private final PresenceMonitor monitor =
            new PresenceMonitor(Clock.fixed(START, ZoneId.of("Europe/Berlin")));

    @Test
    void merktSichDenStartzeitpunkt() {
        assertThat(monitor.startedAt()).isEqualTo(START);
    }

    @Test
    void lastSeenBleibtBeiStilleAufDemLetztenAntwortZeitpunktStehen() {
        Instant seen = START.plusSeconds(30);
        monitor.update(1L, true, seen);
        monitor.update(1L, false, START.plusSeconds(60));

        PresenceMonitor.DeviceProbeStatus status = monitor.statusOf(1L).orElseThrow();
        assertThat(status.lastSeenAt()).isEqualTo(seen);
        assertThat(status.lastCheckedAt()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    void nieGesehenesGeraetHatKeinLastSeen() {
        monitor.update(2L, false, START.plusSeconds(30));
        assertThat(monitor.statusOf(2L).orElseThrow().lastSeenAt()).isNull();
    }

    @Test
    void removeVergisstDenStatus() {
        monitor.update(3L, true, START.plusSeconds(30));
        monitor.remove(3L);
        assertThat(monitor.statusOf(3L)).isEmpty();
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceMonitorTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.presence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haelt lastSeen/lastChecked je Handy ausschliesslich im Speicher (Muster
 * {@code NetworkDeviceStatusMonitor}). Ueberlebt Neustarts bewusst nicht;
 * {@link #startedAt()} traegt deshalb die Anlauf-Karenz: bis dahin wird bei
 * Stille kein Zustand gemeldet statt "abwesend" zu raten.
 */
@Component
public class PresenceMonitor {

    public record DeviceProbeStatus(Instant lastSeenAt, Instant lastCheckedAt) {
    }

    private final Map<Long, DeviceProbeStatus> statuses = new ConcurrentHashMap<>();
    private final Instant startedAt;

    public PresenceMonitor(Clock clock) {
        this.startedAt = clock.instant();
    }

    public void update(Long deviceId, boolean responded, Instant now) {
        Instant previousLastSeenAt = Optional.ofNullable(statuses.get(deviceId))
                .map(DeviceProbeStatus::lastSeenAt)
                .orElse(null);
        Instant lastSeenAt = responded ? now : previousLastSeenAt;
        statuses.put(deviceId, new DeviceProbeStatus(lastSeenAt, now));
    }

    public Optional<DeviceProbeStatus> statusOf(Long deviceId) {
        return Optional.ofNullable(statuses.get(deviceId));
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void remove(Long deviceId) {
        statuses.remove(deviceId);
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceMonitorTest
```
Expected: 4 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresenceMonitor.java backend/src/test/java/com/household/manager/presence/PresenceMonitorTest.java
git commit -m "feat(presence): In-Memory-Monitor mit Startzeit fuer die Anlauf-Karenz"
```

---

### Task 5: Kernlogik (PresenceEvaluator)

**Die einzige Definition von „anwesend"** (Muster `TractiveHomeResolver`): Poller UND Status-API fragen dieselbe Klasse, damit Dashboard-Kachel und Flow-Trigger nie auseinanderlaufen.

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresenceEvaluator.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresenceEvaluatorTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresenceEvaluatorTest {

    private static final Instant START = Instant.parse("2026-08-25T10:00:00Z");

    @Mock
    private PresenceSettingsService settings;

    private PresenceMonitor monitor;
    private PresenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor(Clock.fixed(START, ZoneId.of("Europe/Berlin")));
        evaluator = new PresenceEvaluator(monitor, settings);
        lenient().when(settings.getAwayGraceMinutes()).thenReturn(10L);
    }

    private PresenceDevice device(long id, boolean active) {
        return PresenceDevice.builder().id(id).userId(5L).name("iPhone")
                .host("192.168.1.50").active(active).build();
    }

    @Test
    void antwortMachtSofortAnwesend() {
        Instant now = START.plusSeconds(600);
        monitor.update(1L, true, now);

        PresenceEvaluator.PersonPresence result = evaluator.evaluate(List.of(device(1, true)), now);

        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.PRESENT);
        assertThat(result.lastSeenAt()).isEqualTo(now);
    }

    @Test
    void stilleInnerhalbDerKarenzBleibtAnwesend() {
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plusSeconds(9 * 60);

        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.PRESENT);
    }

    @Test
    void stilleJenseitsDerKarenzIstAbwesend() {
        Instant seen = START.plusSeconds(60);
        monitor.update(1L, true, seen);
        Instant now = seen.plusSeconds(11 * 60);

        PresenceEvaluator.PersonPresence result = evaluator.evaluate(List.of(device(1, true)), now);
        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.AWAY);
        assertThat(result.lastSeenAt()).isEqualTo(seen);
    }

    @Test
    void nieGesehenWaehrendDerAnlaufKarenzIstUnbekannt() {
        // Kein Update seit Start: die Entitaet soll ihren DB-Wert behalten (nie raten).
        Instant now = START.plusSeconds(5 * 60);
        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.UNKNOWN);
    }

    @Test
    void nieGesehenNachDerAnlaufKarenzIstAbwesend() {
        Instant now = START.plusSeconds(11 * 60);
        assertThat(evaluator.evaluate(List.of(device(1, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void nurDeaktivierteGeraeteIstUnavailable() {
        assertThat(evaluator.evaluate(List.of(device(1, false)), START.plusSeconds(60)).state())
                .isEqualTo(PresenceEvaluator.PersonState.UNAVAILABLE);
    }

    @Test
    void lastSeenEinesDeaktiviertenGeraetsZaehltNicht() {
        Instant now = START.plusSeconds(20 * 60);
        monitor.update(1L, true, now);          // deaktiviertes Geraet, frisch gesehen
        monitor.update(2L, true, START.plusSeconds(60)); // aktives Geraet, lange still

        PresenceEvaluator.PersonPresence result =
                evaluator.evaluate(List.of(device(1, false), device(2, true)), now);
        assertThat(result.state()).isEqualTo(PresenceEvaluator.PersonState.AWAY);
    }

    @Test
    void zweitgeraetHaeltAnwesend() {
        Instant now = START.plusSeconds(20 * 60);
        monitor.update(1L, true, START.plusSeconds(60)); // lange still
        monitor.update(2L, true, now);                   // frisch

        assertThat(evaluator.evaluate(List.of(device(1, true), device(2, true)), now).state())
                .isEqualTo(PresenceEvaluator.PersonState.PRESENT);
    }

    @Test
    void aggregatRegeln() {
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.PRESENT, PresenceEvaluator.PersonState.AWAY)))
                .contains("on");
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.AWAY, PresenceEvaluator.PersonState.AWAY)))
                .contains("off");
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.UNAVAILABLE, PresenceEvaluator.PersonState.UNAVAILABLE)))
                .contains("unavailable");
        // Mischung ohne PRESENT: keine Aussage, nichts melden
        assertThat(evaluator.aggregateState(List.of(
                PresenceEvaluator.PersonState.AWAY, PresenceEvaluator.PersonState.UNKNOWN)))
                .isEmpty();
        assertThat(evaluator.aggregateState(List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceEvaluatorTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Die EINZIGE Definition von "anwesend" (Muster {@code TractiveHomeResolver}):
 * Poller und Status-API fragen dieselbe Klasse, damit Dashboard-Kachel und
 * Flow-Trigger nicht auseinanderlaufen koennen.
 *
 * <p>Regeln: Antwort eines aktiven Geraets => sofort anwesend. Abwesend erst,
 * wenn ALLE aktiven Geraete laenger als die Karenzzeit still sind. Nach einem
 * Neustart (lastSeen ist nur im Speicher) gilt die Karenz ab Startzeitpunkt:
 * bis dahin wird bei Stille UNKNOWN geliefert und der Aufrufer meldet nichts —
 * die Entitaet behaelt ihren letzten DB-Wert statt zu raten.
 */
@Component
@RequiredArgsConstructor
public class PresenceEvaluator {

    public enum PersonState { PRESENT, AWAY, UNAVAILABLE, UNKNOWN }

    public record PersonPresence(PersonState state, Instant lastSeenAt) {
    }

    private final PresenceMonitor monitor;
    private final PresenceSettingsService settings;

    public PersonPresence evaluate(List<PresenceDevice> devices, Instant now) {
        List<PresenceDevice> active = devices.stream().filter(PresenceDevice::isActive).toList();
        if (active.isEmpty()) {
            return new PersonPresence(PersonState.UNAVAILABLE, null);
        }

        Instant lastSeen = active.stream()
                .map(device -> monitor.statusOf(device.getId())
                        .map(PresenceMonitor.DeviceProbeStatus::lastSeenAt)
                        .orElse(null))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Duration grace = Duration.ofMinutes(settings.getAwayGraceMinutes());
        if (lastSeen != null) {
            PersonState state = Duration.between(lastSeen, now).compareTo(grace) <= 0
                    ? PersonState.PRESENT
                    : PersonState.AWAY;
            return new PersonPresence(state, lastSeen);
        }

        // Noch nie gesehen seit dem Start: Anlauf-Karenz — erst danach ist
        // Stille ein Beleg fuer Abwesenheit.
        if (Duration.between(monitor.startedAt(), now).compareTo(grace) < 0) {
            return new PersonPresence(PersonState.UNKNOWN, null);
        }
        return new PersonPresence(PersonState.AWAY, null);
    }

    /**
     * Aggregat "Jemand zu Hause": on sobald irgendwer anwesend ist; off nur,
     * wenn ALLE erfassten Personen abwesend sind; unavailable nur, wenn alle
     * blind sind. Jede Mischung ohne PRESENT ergibt keine Aussage — dann wird
     * bewusst nichts gemeldet.
     */
    public Optional<String> aggregateState(Collection<PersonState> states) {
        if (states.isEmpty()) {
            return Optional.empty();
        }
        if (states.contains(PersonState.PRESENT)) {
            return Optional.of("on");
        }
        if (states.stream().allMatch(state -> state == PersonState.AWAY)) {
            return Optional.of("off");
        }
        if (states.stream().allMatch(state -> state == PersonState.UNAVAILABLE)) {
            return Optional.of("unavailable");
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceEvaluatorTest
```
Expected: 9 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresenceEvaluator.java backend/src/test/java/com/household/manager/presence/PresenceEvaluatorTest.java
git commit -m "feat(presence): Kernlogik - Karenzzeit, Anlauf-Karenz, Aggregat"
```

---

### Task 6: Poller mit Entity-Meldung (PresencePollingService)

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresencePollingService.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresencePollingServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.presence;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresencePollingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Berlin"));

    @Mock
    private PresenceDeviceRepository deviceRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PresenceProbe probe;
    @Mock
    private PresenceEvaluator evaluator;
    @Mock
    private EntityStateService entityStateService;

    private PresenceMonitor monitor;
    private PresencePollingService service;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor();
        service = new PresencePollingService(deviceRepository, userRepository, probe,
                monitor, evaluator, entityStateService, CLOCK);
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(
                AppUser.builder().id(5L).username("benedikt").displayName("Benedikt")
                        .passwordHash("x").build()));
        lenient().when(evaluator.aggregateState(any())).thenReturn(Optional.empty());
    }

    private PresenceDevice device(long id, boolean active) {
        return PresenceDevice.builder().id(id).userId(5L).name("iPhone")
                .host("192.168.1.50").active(active).build();
    }

    @Test
    void meldetAnwesendeSofortMitAttributen() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.RESPONDED);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        EntityStateUpdate update = captor.getValue();
        assertThat(update.getEntityId()).isEqualTo("binary_sensor.presence_5_home");
        assertThat(update.getDomain()).isEqualTo(EntityDomain.BINARY_SENSOR);
        assertThat(update.getSource()).isEqualTo(EntitySource.PRESENCE);
        assertThat(update.getState()).isEqualTo("on");
        assertThat(update.getFriendlyName()).isEqualTo("Benedikt anwesend");
        assertThat(update.getAttributes()).containsEntry("deviceClass", "presence");
        assertThat(update.getAttributes()).containsEntry("personUserId", 5L);
        assertThat(update.getAttributes()).containsKey("lastSeenAt");
    }

    @Test
    void unknownMeldetNichts() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));

        service.poll();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void aggregatWirdGemeldet() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, true)));
        when(probe.probe(anyString(), anyList(), any())).thenReturn(ProbeResult.SILENT);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.AWAY, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.of("off"));

        service.poll();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, org.mockito.Mockito.times(2)).reportState(captor.capture());
        EntityStateUpdate household = captor.getAllValues().stream()
                .filter(u -> u.getEntityId().equals("binary_sensor.presence_household"))
                .findFirst().orElseThrow();
        assertThat(household.getState()).isEqualTo("off");
        assertThat(household.getFriendlyName()).isEqualTo("Jemand zu Hause");
    }

    @Test
    void deaktivierteGeraeteWerdenNichtGeprobt() {
        when(deviceRepository.findAll()).thenReturn(List.of(device(1, false)));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNAVAILABLE, null));

        service.poll();

        verify(probe, never()).probe(anyString(), anyList(), any());
    }

    @Test
    void dbFehlerUeberspringtDenZyklusOhneZuWerfen() {
        when(deviceRepository.findAll()).thenThrow(new RuntimeException("DB weg"));

        service.poll();

        verifyNoInteractions(entityStateService);
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresencePollingServiceTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.presence;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Probt alle 30 s die aktiven Handys und spiegelt das Ergebnis in den
 * Entity-State-Layer: eine Entitaet je Person plus das Aggregat "Jemand zu
 * Hause". Wirft nie; Fehler pro Geraet sind isoliert.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PresencePollingService {

    /**
     * 62078 (lockdownd) antwortet auf iPhones fast immer; 80/443 sind Fallbacks.
     * Auch ein "refused" auf jedem dieser Ports beweist Anwesenheit — die Liste
     * muss also nicht vollstaendig sein, nur eine Antwort provozieren.
     */
    static final List<Integer> PROBE_PORTS = List.of(62078, 80, 443);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    static final String HOUSEHOLD_REF = "household";
    static final String HOUSEHOLD_FRIENDLY_NAME = "Jemand zu Hause";

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceProbe probe;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final EntityStateService entityStateService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${presence.poll-interval-ms:30000}")
    public void poll() {
        List<PresenceDevice> devices;
        try {
            devices = deviceRepository.findAll();
        } catch (Exception e) {
            // Zustaende unveraendert lassen: lastSeen bleibt stehen, nichts wird
            // faelschlich "off".
            log.warn("Laden der Anwesenheits-Geraete fehlgeschlagen, Zyklus uebersprungen", e);
            return;
        }

        for (PresenceDevice device : devices) {
            if (!device.isActive()) {
                continue;
            }
            monitor.update(device.getId(), probeSafely(device), clock.instant());
        }

        try {
            evaluateAndReport(devices);
        } catch (Exception e) {
            log.warn("Auswertung der Anwesenheit fehlgeschlagen", e);
        }
    }

    private boolean probeSafely(PresenceDevice device) {
        try {
            return probe.probe(device.getHost(), PROBE_PORTS, PROBE_TIMEOUT) == ProbeResult.RESPONDED;
        } catch (Exception e) {
            log.debug("Probe fuer Geraet {} ({}) fehlgeschlagen: {}",
                    device.getId(), device.getHost(), e.getMessage());
            return false;
        }
    }

    private void evaluateAndReport(List<PresenceDevice> devices) {
        // TreeMap: stabile Reihenfolge der Meldungen (nach userId)
        Map<Long, List<PresenceDevice>> byUser = devices.stream()
                .collect(Collectors.groupingBy(PresenceDevice::getUserId, TreeMap::new,
                        Collectors.toList()));
        Instant now = clock.instant();

        List<PresenceEvaluator.PersonState> states = new ArrayList<>();
        byUser.forEach((userId, userDevices) -> {
            PresenceEvaluator.PersonPresence presence = evaluator.evaluate(userDevices, now);
            states.add(presence.state());
            if (presence.state() == PresenceEvaluator.PersonState.UNKNOWN) {
                // Anlauf-Karenz: kein Update, die Entitaet behaelt ihren DB-Wert.
                return;
            }
            reportPersonState(userId, presence);
        });

        evaluator.aggregateState(states).ifPresent(this::reportHouseholdState);
    }

    private void reportPersonState(Long userId, PresenceEvaluator.PersonPresence presence) {
        String state = PresenceEvaluator.entityState(presence.state());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("deviceClass", "presence");
        attributes.put("personUserId", userId);
        if (presence.lastSeenAt() != null) {
            // Schluessel fehlt statt null zu tragen (Muster Netzwerk-Monitoring)
            attributes.put("lastSeenAt",
                    LocalDateTime.ofInstant(presence.lastSeenAt(), clock.getZone()).toString());
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE,
                        String.valueOf(userId), "home"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.PRESENCE)
                .sourceRef(String.valueOf(userId))
                .friendlyName(displayNameOf(userId) + " anwesend")
                .state(state)
                .attributes(attributes)
                .build());
    }

    private void reportHouseholdState(String state) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.PRESENCE,
                        HOUSEHOLD_REF, null))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.PRESENCE)
                .sourceRef(HOUSEHOLD_REF)
                .friendlyName(HOUSEHOLD_FRIENDLY_NAME)
                .state(state)
                .attributes(Map.of("deviceClass", "presence"))
                .build());
    }

    private String displayNameOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getDisplayName)
                .orElse("Person " + userId);
    }
}
```

**Hinweis für den Umsetzer:** Sollte `EntityStateUpdate` keine Lombok-Getter wie `getEntityId()` haben (Builder-Pattern prüfen!), die Assertions im Test an die tatsächlichen Accessoren anpassen — `TabletPresenceService` zeigt die Builder-Feldnamen, die Accessoren zeigt die Klasse selbst.

**`PresenceEvaluator.entityState` muss `static` sein** (Anpassung aus dem Task-5-Review): Der Poller-Test mockt den `PresenceEvaluator`. Wäre `entityState` eine Instanzmethode, lieferte der Mock `null` und der Test prüfte am Ende eine gestubbte Abbildung statt der echten. Als statische Methode kann Mockito sie nicht abfangen — beide Konsumenten benutzen zwangsläufig die eine echte Definition. Die Methode ist eine reine Funktion ohne Abhängigkeiten, gehört also ohnehin nicht an die Instanz.

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresencePollingServiceTest
```
Expected: 5 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresencePollingService.java backend/src/test/java/com/household/manager/presence/PresencePollingServiceTest.java
git commit -m "feat(presence): Poller meldet Personen- und Haushalts-Entitaeten"
```

---

### Task 7: Geräte-CRUD (PresenceDeviceService + Dtos)

Muster `NetworkDeviceService` (Voll-PUT, fehlendes `active` = aktiv, Audit).

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresenceDtos.java`
- Create: `backend/src/main/java/com/household/manager/presence/PresenceDeviceService.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresenceDeviceServiceTest.java`

- [ ] **Step 1: Dtos schreiben** (reine Records, kein eigener Test)

```java
package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;

import java.time.LocalDateTime;
import java.util.List;

/** Request-/Response-Records der Anwesenheits-API. Alle Zeitstempel in Haushaltszeit. */
public final class PresenceDtos {

    private PresenceDtos() {
    }

    public record DeviceRequest(Long userId, String name, String host, Boolean active) {
    }

    public record DeviceAdminResponse(Long id, Long userId, String name, String host, boolean active) {
        public static DeviceAdminResponse from(PresenceDevice device) {
            return new DeviceAdminResponse(device.getId(), device.getUserId(), device.getName(),
                    device.getHost(), device.isActive());
        }
    }

    public record DeviceStatusResponse(Long id, String name, String host, boolean active,
                                        LocalDateTime lastSeenAt, LocalDateTime lastCheckedAt) {
    }

    public record PersonStatus(Long userId, String displayName, String state,
                                LocalDateTime lastSeenAt, List<DeviceStatusResponse> devices) {
    }

    public record StatusResponse(String householdState, List<PersonStatus> persons) {
    }

    public record SettingsDto(Long awayGraceMinutes) {
    }
}
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceDeviceServiceTest {

    @Mock
    private PresenceDeviceRepository repository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PresenceMonitor monitor;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private PresenceDeviceService service;

    private PresenceDtos.DeviceRequest request(Long userId, String name, String host) {
        return new PresenceDtos.DeviceRequest(userId, name, host, true);
    }

    @Test
    void createLegtGeraetAnUndAuditiert() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> {
            PresenceDevice device = inv.getArgument(0);
            device.setId(1L);
            return device;
        });

        PresenceDtos.DeviceAdminResponse response =
                service.create(request(5L, " iPhone Benedikt ", " 192.168.1.50 "));

        assertThat(response.name()).isEqualTo("iPhone Benedikt");
        assertThat(response.host()).isEqualTo("192.168.1.50");
        assertThat(response.userId()).isEqualTo(5L);
        verify(auditService).record(eq("presence.device.create"), anyString());
    }

    @Test
    void createLehntUnbekanntenBenutzerAb() {
        when(userRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(request(99L, "iPhone", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createLehntLeereFelderAb() {
        assertThatThrownBy(() -> service.create(request(5L, " ", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request(5L, "iPhone", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request(null, "iPhone", "192.168.1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fehlendesActiveGiltAlsAktiv() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PresenceDtos.DeviceAdminResponse response = service.create(
                new PresenceDtos.DeviceRequest(5L, "iPhone", "192.168.1.50", null));

        assertThat(response.active()).isTrue();
    }

    @Test
    void deleteEntferntGeraetMonitorEintragUndAuditiert() {
        PresenceDevice device = PresenceDevice.builder().id(7L).userId(5L)
                .name("iPhone").host("192.168.1.50").build();
        when(repository.findById(7L)).thenReturn(Optional.of(device));

        service.delete(7L);

        verify(repository).delete(device);
        verify(monitor).remove(7L);
        verify(auditService).record(eq("presence.device.delete"), anyString());
    }

    @Test
    void updateUnbekannterIdWirft404() {
        when(repository.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(42L, request(5L, "iPhone", "192.168.1.50")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
```

- [ ] **Step 3: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceDeviceServiceTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 4: Implementieren**

```java
package com.household.manager.presence;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pflegt die Handys der Anwesenheitserkennung (Stammdaten). Status/Auswertung
 * liegen in {@link PresenceEvaluator}/{@link PresenceStatusService}.
 */
@Service
@RequiredArgsConstructor
public class PresenceDeviceService {

    private final PresenceDeviceRepository repository;
    private final AppUserRepository userRepository;
    private final PresenceMonitor monitor;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<PresenceDtos.DeviceAdminResponse> list() {
        return repository.findAllByOrderByIdAsc().stream()
                .map(PresenceDtos.DeviceAdminResponse::from)
                .toList();
    }

    @Transactional
    public PresenceDtos.DeviceAdminResponse create(PresenceDtos.DeviceRequest request) {
        validate(request);
        PresenceDevice device = PresenceDevice.builder()
                .userId(request.userId())
                .name(request.name().trim())
                .host(request.host().trim())
                .active(activeOrDefault(request))
                .build();
        PresenceDevice saved = repository.save(device);
        auditService.record("presence.device.create", auditDetail(saved));
        return PresenceDtos.DeviceAdminResponse.from(saved);
    }

    @Transactional
    public PresenceDtos.DeviceAdminResponse update(Long id, PresenceDtos.DeviceRequest request) {
        PresenceDevice device = findOrThrow(id);
        validate(request);
        device.setUserId(request.userId());
        device.setName(request.name().trim());
        device.setHost(request.host().trim());
        device.setActive(activeOrDefault(request));
        PresenceDevice saved = repository.save(device);
        auditService.record("presence.device.update", auditDetail(saved));
        return PresenceDtos.DeviceAdminResponse.from(saved);
    }

    @Transactional
    public void delete(Long id) {
        PresenceDevice device = findOrThrow(id);
        repository.delete(device);
        monitor.remove(id);
        auditService.record("presence.device.delete", auditDetail(device));
    }

    private PresenceDevice findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PresenceDevice", "id", id));
    }

    private String auditDetail(PresenceDevice device) {
        return "%s (%s, userId=%d)".formatted(device.getName(), device.getHost(), device.getUserId());
    }

    /** Fehlendes Feld heisst "aktiv" — wie der Default in Entity und Spalte (Muster Netzwerk-Geraete). */
    private boolean activeOrDefault(PresenceDtos.DeviceRequest request) {
        return request.active() == null || request.active();
    }

    private void validate(PresenceDtos.DeviceRequest request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("Es ist keine Person ausgewaehlt.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Der Name darf nicht leer sein.");
        }
        if (request.host() == null || request.host().isBlank()) {
            throw new IllegalArgumentException("Die IP-Adresse darf nicht leer sein.");
        }
        if (!userRepository.existsById(request.userId())) {
            throw new IllegalArgumentException("Der Benutzer existiert nicht.");
        }
    }
}
```

- [ ] **Step 5: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceDeviceServiceTest
```
Expected: 6 Tests PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresenceDtos.java backend/src/main/java/com/household/manager/presence/PresenceDeviceService.java backend/src/test/java/com/household/manager/presence/PresenceDeviceServiceTest.java
git commit -m "feat(presence): Geraete-CRUD mit Audit und Personen-Validierung"
```

---

### Task 8: Status-API (PresenceStatusService)

Rechnet bei jedem Abruf **frisch** über den `PresenceEvaluator` (dieselbe Definition wie der Poller). Alle Zeitstempel als `LocalDateTime` in Haushaltszeit.

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresenceStatusService.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresenceStatusServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.presence;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresenceStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);

    @Mock
    private PresenceDeviceRepository deviceRepository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PresenceEvaluator evaluator;

    private PresenceMonitor monitor;
    private PresenceStatusService service;

    @BeforeEach
    void setUp() {
        monitor = new PresenceMonitor();
        service = new PresenceStatusService(deviceRepository, userRepository, monitor,
                evaluator, CLOCK);
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(
                AppUser.builder().id(5L).username("benedikt").displayName("Benedikt")
                        .passwordHash("x").build()));
    }

    @Test
    void liefertPersonenMitZustandGeraetenUndLokalzeit() {
        PresenceDevice device = PresenceDevice.builder().id(1L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(deviceRepository.findAll()).thenReturn(List.of(device));
        monitor.update(1L, true, NOW);
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.PRESENT, NOW));
        when(evaluator.aggregateState(any())).thenReturn(Optional.of("on"));

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.householdState()).isEqualTo("on");
        assertThat(response.persons()).hasSize(1);
        PresenceDtos.PersonStatus person = response.persons().get(0);
        assertThat(person.userId()).isEqualTo(5L);
        assertThat(person.displayName()).isEqualTo("Benedikt");
        assertThat(person.state()).isEqualTo("on");
        assertThat(person.lastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(person.devices()).hasSize(1);
        assertThat(person.devices().get(0).lastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
    }

    @Test
    void unknownWirdAlsUnknownAusgewiesen() {
        PresenceDevice device = PresenceDevice.builder().id(1L).userId(5L)
                .name("iPhone").host("192.168.1.50").active(true).build();
        when(deviceRepository.findAll()).thenReturn(List.of(device));
        when(evaluator.evaluate(anyList(), any())).thenReturn(
                new PresenceEvaluator.PersonPresence(PresenceEvaluator.PersonState.UNKNOWN, null));
        when(evaluator.aggregateState(any())).thenReturn(Optional.empty());

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.householdState()).isEqualTo("unknown");
        assertThat(response.persons().get(0).state()).isEqualTo("unknown");
        assertThat(response.persons().get(0).lastSeenAt()).isNull();
    }

    @Test
    void ohneGeraeteKeinePersonen() {
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(evaluator.aggregateState(any())).thenReturn(Optional.empty());

        PresenceDtos.StatusResponse response = service.getStatus();

        assertThat(response.persons()).isEmpty();
        assertThat(response.householdState()).isEqualTo("unknown");
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceStatusServiceTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implementieren**

```java
package com.household.manager.presence;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Baut die Status-Antwort fuer Admin-Seite und Dashboard-Kachel. Rechnet bei
 * jedem Abruf frisch ueber den {@link PresenceEvaluator} — dieselbe Definition
 * von "anwesend" wie der Poller, damit Kachel und Entitaet nie widersprechen.
 */
@Service
@RequiredArgsConstructor
public class PresenceStatusService {

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final Clock clock;

    public PresenceDtos.StatusResponse getStatus() {
        Map<Long, List<PresenceDevice>> byUser = deviceRepository.findAll().stream()
                .collect(Collectors.groupingBy(PresenceDevice::getUserId, TreeMap::new,
                        Collectors.toList()));
        Instant now = clock.instant();

        List<PresenceDtos.PersonStatus> persons = new ArrayList<>();
        List<PresenceEvaluator.PersonState> states = new ArrayList<>();
        byUser.forEach((userId, devices) -> {
            PresenceEvaluator.PersonPresence presence = evaluator.evaluate(devices, now);
            states.add(presence.state());
            persons.add(new PresenceDtos.PersonStatus(
                    userId,
                    displayNameOf(userId),
                    PresenceEvaluator.entityState(presence.state()),
                    toLocal(presence.lastSeenAt()),
                    devices.stream().map(this::deviceStatus).toList()));
        });

        String householdState = evaluator.aggregateState(states).orElse("unknown");
        return new PresenceDtos.StatusResponse(householdState, persons);
    }

    private PresenceDtos.DeviceStatusResponse deviceStatus(PresenceDevice device) {
        PresenceMonitor.DeviceProbeStatus status = monitor.statusOf(device.getId()).orElse(null);
        return new PresenceDtos.DeviceStatusResponse(
                device.getId(), device.getName(), device.getHost(), device.isActive(),
                status == null ? null : toLocal(status.lastSeenAt()),
                status == null ? null : toLocal(status.lastCheckedAt()));
    }

    private String displayNameOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getDisplayName)
                .orElse("Person " + userId);
    }

    private LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceStatusServiceTest
```
Expected: 3 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresenceStatusService.java backend/src/test/java/com/household/manager/presence/PresenceStatusServiceTest.java
git commit -m "feat(presence): Status-API rechnet frisch ueber den Evaluator"
```

---

### Task 9: Controller (PresenceController)

**Files:**
- Create: `backend/src/main/java/com/household/manager/presence/PresenceController.java`
- Test: `backend/src/test/java/com/household/manager/presence/PresenceControllerTest.java`

- [ ] **Step 1: Failing Test schreiben** (Muster `NetworkControllerTest`: standalone MockMvc + `GlobalExceptionHandler`)

```java
package com.household.manager.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PresenceControllerTest {

    @Mock
    private PresenceStatusService statusService;
    @Mock
    private PresenceDeviceService deviceService;
    @Mock
    private PresenceSettingsService settingsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PresenceController(statusService, deviceService, settingsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void statusLiefertDieAntwortDesServices() throws Exception {
        when(statusService.getStatus()).thenReturn(
                new PresenceDtos.StatusResponse("on", List.of()));

        mockMvc.perform(get("/v1/presence/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.householdState").value("on"));
    }

    @Test
    void settingsLesen() throws Exception {
        when(settingsService.getAwayGraceMinutes()).thenReturn(10L);

        mockMvc.perform(get("/v1/presence/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awayGraceMinutes").value(10));
    }

    @Test
    void settingsSchreibenValidiertUndSpeichert() throws Exception {
        when(settingsService.getAwayGraceMinutes()).thenReturn(15L);

        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awayGraceMinutes\": 15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awayGraceMinutes").value(15));
        verify(settingsService).saveAwayGraceMinutes(eq(15L));
    }

    @Test
    void unplausibleKarenzzeitWirdMit400Abgelehnt() throws Exception {
        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awayGraceMinutes\": 0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awayGraceMinutes\": 100000}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveAwayGraceMinutes(org.mockito.ArgumentMatchers.anyLong());
    }
}
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceControllerTest
```
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implementieren**

Validierung an der API-Grenze mit **derselben Grenze wie beim Lesen** (`MAX_AWAY_GRACE_MINUTES`) — sonst nimmt die API einen Wert mit 200 an, den der Service danach wortlos auf den Default zurückdreht (Muster `TractiveHomeSettingsController`).

```java
package com.household.manager.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Status-, Geraete- und Einstellungs-API der Anwesenheitserkennung. Bewusst
 * duenn — alle Logik steckt in den Services. Der Zugriff auf /settings ist
 * ueber die Matcher-Reihenfolge in {@code SecurityConfig} auf ADMIN
 * beschraenkt; /status bleibt KIOSK-lesbar (Dashboard-Kachel auf dem Tablet).
 */
@RestController
@RequestMapping("/v1/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceStatusService statusService;
    private final PresenceDeviceService deviceService;
    private final PresenceSettingsService settingsService;

    @GetMapping("/status")
    public ResponseEntity<PresenceDtos.StatusResponse> status() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    @GetMapping("/devices")
    public ResponseEntity<List<PresenceDtos.DeviceAdminResponse>> listDevices() {
        return ResponseEntity.ok(deviceService.list());
    }

    @PostMapping("/devices")
    public ResponseEntity<PresenceDtos.DeviceAdminResponse> createDevice(
            @RequestBody PresenceDtos.DeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deviceService.create(request));
    }

    @PutMapping("/devices/{id}")
    public ResponseEntity<PresenceDtos.DeviceAdminResponse> updateDevice(
            @PathVariable Long id, @RequestBody PresenceDtos.DeviceRequest request) {
        return ResponseEntity.ok(deviceService.update(id, request));
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<PresenceDtos.SettingsDto> getSettings() {
        return ResponseEntity.ok(new PresenceDtos.SettingsDto(settingsService.getAwayGraceMinutes()));
    }

    @PutMapping("/settings")
    public ResponseEntity<PresenceDtos.SettingsDto> updateSettings(
            @RequestBody PresenceDtos.SettingsDto request) {
        Long minutes = request.awayGraceMinutes();
        if (minutes == null || minutes < 1
                || minutes > PresenceSettingsService.MAX_AWAY_GRACE_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Karenzzeit muss zwischen 1 und "
                            + PresenceSettingsService.MAX_AWAY_GRACE_MINUTES + " Minuten liegen.");
        }
        settingsService.saveAwayGraceMinutes(minutes);
        return ResponseEntity.ok(new PresenceDtos.SettingsDto(settingsService.getAwayGraceMinutes()));
    }
}
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PresenceControllerTest
```
Expected: 4 Tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/presence/PresenceController.java backend/src/test/java/com/household/manager/presence/PresenceControllerTest.java
git commit -m "feat(presence): REST-API fuer Status, Geraete und Karenzzeit"
```

---

### Task 10: Security (SecurityConfig + SecurityRulesTest)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (ADMIN-Block ~Zeile 148–150, methodenspezifische Matcher ~Zeile 161–163)
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (neue Tests ans Ende, vor die schließende Klammer)

- [ ] **Step 1: Failing Tests schreiben** — in `SecurityRulesTest` ergänzen:

```java
    /**
     * Der Anwesenheits-Status braucht bewusst keine eigene Regel: das GET faellt auf die
     * generische Regel GET /v1/** -> KIOSK (Dashboard-Kachel auf dem Wandtablet). Kein
     * PresenceController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDenAnwesenheitsStatusLesen() throws Exception {
        mockMvc.perform(get("/v1/presence/status")).andExpect(status().isNotFound());
    }

    /**
     * Die Karenzzeit-Einstellung ist ADMIN-only, auch lesend — der Matcher ist methodenlos
     * und steht VOR der generischen GET-Regel (Muster tractive/home-settings).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieAnwesenheitsEinstellungenNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/presence/settings")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDarfDieAnwesenheitsEinstellungenLesen() throws Exception {
        mockMvc.perform(get("/v1/presence/settings")).andExpect(status().isNotFound());
    }

    /**
     * Anwesenheits-Geraete pflegen ist ADMIN-only. KIOSK und MEMBER muessen es je aus
     * eigenem Test belegen (Muster Netzwerk-Geraete).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinAnwesenheitsGeraetAnlegen() throws Exception {
        mockMvc.perform(post("/v1/presence/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinAnwesenheitsGeraetAnlegenAendernOderLoeschen() throws Exception {
        mockMvc.perform(post("/v1/presence/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/presence/devices/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/presence/devices/1").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnAnwesenheitsGeraetePflegenVorbei() throws Exception {
        // Kein PresenceController im Slice: 404 statt 403 belegt, dass die Regeln durchlaessen.
        mockMvc.perform(post("/v1/presence/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/v1/presence/devices/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/v1/presence/devices/1").with(csrf()))
                .andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest
```
Expected: Die neuen Tests FAILEN (`kioskDarfDieAnwesenheitsEinstellungenNichtLesen` bekommt 404 statt 403, die Geräte-Tests 404/200 statt 403); Bestandstests grün.

- [ ] **Step 3: SecurityConfig anpassen**

Im ADMIN-Block (der Matcher mit `/v1/tractive/home-settings`) den Settings-Pfad ergänzen — **methodenlos**, deckt GET und PUT ab:

```java
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**", "/v1/tractive/login", "/v1/tractive/logout",
                                "/v1/tractive/home-settings", "/v1/presence/settings").hasRole("ADMIN")
```

Direkt unter den drei `/v1/network/devices`-Zeilen die methodenspezifischen Geräte-Matcher ergänzen:

```java
                        // Anwesenheits-Geraete pflegen ist ADMIN, lesen darf jeder Angemeldete
                        // ueber die generische GET-Regel weiter unten (Muster Netzwerk-Geraete).
                        .requestMatchers(HttpMethod.POST, "/v1/presence/devices").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/presence/devices/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/presence/devices/*").hasRole("ADMIN")
```

- [ ] **Step 4: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest
```
Expected: alle Tests PASS.

- [ ] **Step 5: Gesamten Backend-Testlauf machen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test
```
Expected: nur die vorbestehenden DB-Fails (`contextLoads`, `HealthControllerTest`) — alles andere grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(presence): Security-Regeln - Settings ADMIN, Geraete-CRUD ADMIN, Status KIOSK"
```

---

### Task 11: Frontend-Modell und -Service

**Files:**
- Create: `frontend/src/app/models/presence.model.ts`
- Create: `frontend/src/app/services/presence.service.ts`

Kein eigener Spec (reine HTTP-Durchreiche, Muster `NetworkService` — bewusst **kein** `catchError`, Consumer lesen `error.error?.message`).

- [ ] **Step 1: Modell schreiben**

```typescript
/** Modelle der Anwesenheits-API (/api/v1/presence). Zeitstempel als ISO-LocalDateTime. */

export interface PresenceDeviceAdmin {
  id: number;
  userId: number;
  name: string;
  host: string;
  active: boolean;
}

export interface PresenceDeviceRequest {
  userId: number;
  name: string;
  host: string;
  active: boolean;
}

export interface PresenceDeviceStatus {
  id: number;
  name: string;
  host: string;
  active: boolean;
  lastSeenAt: string | null;
  lastCheckedAt: string | null;
}

export type PresencePersonState = 'on' | 'off' | 'unavailable' | 'unknown';

export interface PresencePersonStatus {
  userId: number;
  displayName: string;
  state: PresencePersonState;
  lastSeenAt: string | null;
  devices: PresenceDeviceStatus[];
}

export interface PresenceStatusResponse {
  householdState: string;
  persons: PresencePersonStatus[];
}

export interface PresenceSettings {
  awayGraceMinutes: number;
}
```

- [ ] **Step 2: Service schreiben**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  PresenceDeviceAdmin,
  PresenceDeviceRequest,
  PresenceSettings,
  PresenceStatusResponse
} from '../models/presence.model';

/** REST-Service für die Anwesenheitserkennung (Status, Geräte, Karenzzeit). */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/presence';

  getStatus(): Observable<PresenceStatusResponse> {
    return this.http.get<PresenceStatusResponse>(`${this.baseUrl}/status`);
  }

  getDevices(): Observable<PresenceDeviceAdmin[]> {
    return this.http.get<PresenceDeviceAdmin[]>(`${this.baseUrl}/devices`);
  }

  createDevice(request: PresenceDeviceRequest): Observable<PresenceDeviceAdmin> {
    return this.http.post<PresenceDeviceAdmin>(`${this.baseUrl}/devices`, request);
  }

  updateDevice(id: number, request: PresenceDeviceRequest): Observable<PresenceDeviceAdmin> {
    return this.http.put<PresenceDeviceAdmin>(`${this.baseUrl}/devices/${id}`, request);
  }

  deleteDevice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/devices/${id}`);
  }

  getSettings(): Observable<PresenceSettings> {
    return this.http.get<PresenceSettings>(`${this.baseUrl}/settings`);
  }

  updateSettings(settings: PresenceSettings): Observable<PresenceSettings> {
    return this.http.put<PresenceSettings>(`${this.baseUrl}/settings`, settings);
  }
}
```

- [ ] **Step 3: Kompilieren (Build reicht als Check)**

```bash
cd frontend && npx ng build --configuration development 2>&1 | tail -5
```
Expected: Build erfolgreich (Warnungen ok).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/presence.model.ts frontend/src/app/services/presence.service.ts
git commit -m "feat(presence): Frontend-Modell und REST-Service"
```

---

### Task 12: Admin-Seite „Anwesenheit"

Muster: `pages/admin-network-devices/` (**flache** Ablage `pages/admin-presence/`, NICHT `pages/admin/presence/`). Zusätzlich zur Geräteliste: Karenzzeit-Formular und Personen-Dropdown aus `HouseholdUserService` (`GET /v1/users`).

**Files:**
- Create: `frontend/src/app/pages/admin-presence/admin-presence.component.ts`
- Create: `frontend/src/app/pages/admin-presence/admin-presence.component.html`
- Create: `frontend/src/app/pages/admin-presence/admin-presence.component.scss`
- Test: `frontend/src/app/pages/admin-presence/admin-presence.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts` (nach dem `admin/network-devices`-Eintrag, ~Zeile 174)
- Modify: `frontend/src/app/components/header/header.component.ts` (~Zeile 77, nach „Netzwerk-Geräte")

- [ ] **Step 1: Failing Spec schreiben**

Vorbild für Setup und Fallen: `admin-network-devices.component.spec.ts` (`whenStable()` nach jedem `detectChanges()`, das ein `ngModel`-Formular neu befüllt; nach `httpMock.expectOne` immer ein echtes `expect(...)`).

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminPresenceComponent } from './admin-presence.component';

describe('AdminPresenceComponent', () => {
  let fixture: ComponentFixture<AdminPresenceComponent>;
  let component: AdminPresenceComponent;
  let httpMock: HttpTestingController;

  const USERS = [
    { id: 5, displayName: 'Benedikt', enabled: true },
    { id: 6, displayName: 'Partnerin', enabled: true }
  ];
  const DEVICES = [
    { id: 1, userId: 5, name: 'iPhone Benedikt', host: '192.168.1.50', active: true }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminPresenceComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(AdminPresenceComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function flushInitialRequests(): void {
    fixture.detectChanges();
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
    httpMock.expectOne('/api/v1/users').flush(USERS);
    httpMock.expectOne('/api/v1/presence/settings').flush({ awayGraceMinutes: 10 });
    fixture.detectChanges();
  }

  it('laedt Geraete, Personen und Karenzzeit beim Start', () => {
    flushInitialRequests();
    expect(component.devices().length).toBe(1);
    expect(component.users().length).toBe(2);
    expect(component.graceMinutes).toBe(10);
  });

  it('legt ein Geraet mit Person an', () => {
    flushInitialRequests();
    component.form = { id: null, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true };
    component.save();

    const request = httpMock.expectOne('/api/v1/presence/devices');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.userId).toBe(6);
    request.flush({ id: 2, userId: 6, name: 'iPhone Partnerin', host: '192.168.1.51', active: true });
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
  });

  it('sendet beim Aktiv-Toggle immer den kompletten Request', () => {
    flushInitialRequests();
    component.setActive(DEVICES[0], false);

    const request = httpMock.expectOne('/api/v1/presence/devices/1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      userId: 5, name: 'iPhone Benedikt', host: '192.168.1.50', active: false
    });
    request.flush({ ...DEVICES[0], active: false });
    httpMock.expectOne('/api/v1/presence/devices').flush(DEVICES);
  });

  it('speichert die Karenzzeit', () => {
    flushInitialRequests();
    component.graceMinutes = 15;
    component.saveSettings();

    const request = httpMock.expectOne('/api/v1/presence/settings');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ awayGraceMinutes: 15 });
    request.flush({ awayGraceMinutes: 15 });
  });

  it('lehnt eine leere Personenauswahl clientseitig ab', () => {
    flushInitialRequests();
    component.form = { id: null, userId: null, name: 'iPhone', host: '192.168.1.51', active: true };
    component.save();
    expect(component.errorMessage()).toBeTruthy();
    httpMock.expectNone('/api/v1/presence/devices');
  });
});
```

- [ ] **Step 2: Fehlschlag verifizieren**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-presence.component.spec.ts' 2>&1 | tail -20
```
Expected: Kompilierfehler (Komponente existiert nicht).

- [ ] **Step 3: Komponente implementieren**

`admin-presence.component.ts`:

```typescript
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { PresenceService } from '../../services/presence.service';
import { HouseholdUserService } from '../../services/household-user.service';
import { HouseholdUser } from '../../models/household-user.model';
import { PresenceDeviceAdmin, PresenceDeviceRequest } from '../../models/presence.model';

/**
 * Zustand des Anlege-/Bearbeiten-Formulars. Bewusst nicht `extends Request`:
 * die Personenauswahl ist im Formular `number | null` (kein Eintrag gewaehlt),
 * im Request dagegen Pflicht. Umwandlung an genau einer Stelle (toRequest).
 */
interface DeviceFormState {
  /** null = Anlegen, sonst die Id des bearbeiteten Geraets. */
  id: number | null;
  userId: number | null;
  name: string;
  host: string;
  active: boolean;
}

function emptyForm(): DeviceFormState {
  return { id: null, userId: null, name: '', host: '', active: true };
}

/**
 * Admin-Seite „Anwesenheit": Karenzzeit plus die Handys, die das Backend alle
 * 30 s per TCP probt. Muster/Interaktionsform: Admin-Seite „Netzwerk-Geräte".
 */
@Component({
  selector: 'app-admin-presence',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-presence.component.html',
  styleUrl: './admin-presence.component.scss'
})
export class AdminPresenceComponent implements OnInit {
  private readonly presenceApi = inject(PresenceService);
  private readonly userApi = inject(HouseholdUserService);

  readonly devices = signal<PresenceDeviceAdmin[]>([]);
  readonly users = signal<HouseholdUser[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly settingsMessage = signal<string | null>(null);
  readonly settingsSaving = signal(false);

  form: DeviceFormState = emptyForm();
  /** Karenzzeit-Formularwert; null solange noch nichts geladen ist. */
  graceMinutes: number | null = null;

  ngOnInit(): void {
    this.load();
    this.userApi.list().subscribe({
      next: users => this.users.set(users.filter(user => user.enabled)),
      error: () => this.errorMessage.set('Die Haushaltsmitglieder konnten nicht geladen werden.')
    });
    this.presenceApi.getSettings().subscribe({
      next: settings => (this.graceMinutes = settings.awayGraceMinutes),
      error: () => this.settingsMessage.set('Die Karenzzeit konnte nicht geladen werden.')
    });
  }

  load(afterLoad?: () => void): void {
    this.presenceApi.getDevices().subscribe({
      next: devices => {
        this.devices.set(devices);
        this.loadFailed.set(false);
        this.loading.set(false);
        afterLoad?.();
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set(this.messageFrom(error));
        afterLoad?.();
      }
    });
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  displayNameOf(userId: number): string {
    return this.users().find(user => user.id === userId)?.displayName ?? `Person ${userId}`;
  }

  startEdit(device: PresenceDeviceAdmin): void {
    this.errorMessage.set(null);
    this.form = {
      id: device.id,
      userId: device.userId,
      name: device.name,
      host: device.host,
      active: device.active
    };
  }

  resetForm(): void {
    this.form = emptyForm();
    this.errorMessage.set(null);
  }

  save(): void {
    if (this.form.userId === null) {
      this.errorMessage.set('Es ist keine Person ausgewählt.');
      return;
    }
    if (!this.form.name.trim()) {
      this.errorMessage.set('Der Name darf nicht leer sein.');
      return;
    }
    if (!this.form.host.trim()) {
      this.errorMessage.set('Die IP-Adresse darf nicht leer sein.');
      return;
    }
    const request = this.toRequest(this.form, this.form.userId);
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null
      ? this.presenceApi.createDevice(request)
      : this.presenceApi.updateDevice(id, request);
    call.subscribe({
      next: () => this.load(() => {
        this.saving.set(false);
        this.resetForm();
      }),
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(this.messageFrom(error));
      }
    });
  }

  /**
   * Sendet IMMER den kompletten Request: ein fehlendes `active` liest der
   * Server als „aktiv", ein Teil-PUT reaktivierte ein deaktiviertes Geraet
   * stillschweigend (Muster Netzwerk-Geräte).
   */
  setActive(device: PresenceDeviceAdmin, active: boolean): void {
    this.errorMessage.set(null);
    this.presenceApi.updateDevice(device.id, {
      userId: device.userId,
      name: device.name,
      host: device.host,
      active
    }).subscribe({
      next: () => {
        if (this.form.id === device.id) {
          this.form.active = active;
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  remove(device: PresenceDeviceAdmin): void {
    if (!confirm(`Gerät „${device.name}“ endgültig löschen?`)) {
      return;
    }
    this.errorMessage.set(null);
    this.presenceApi.deleteDevice(device.id).subscribe({
      next: () => {
        if (this.form.id === device.id) {
          this.resetForm();
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  saveSettings(): void {
    if (this.graceMinutes === null || this.graceMinutes < 1) {
      this.settingsMessage.set('Die Karenzzeit muss mindestens 1 Minute betragen.');
      return;
    }
    this.settingsSaving.set(true);
    this.settingsMessage.set(null);
    this.presenceApi.updateSettings({ awayGraceMinutes: this.graceMinutes }).subscribe({
      next: settings => {
        this.graceMinutes = settings.awayGraceMinutes;
        this.settingsSaving.set(false);
        this.settingsMessage.set('Gespeichert.');
      },
      error: (error: HttpErrorResponse) => {
        this.settingsSaving.set(false);
        this.settingsMessage.set(this.messageFrom(error));
      }
    });
  }

  private toRequest(state: DeviceFormState, userId: number): PresenceDeviceRequest {
    return {
      userId,
      name: state.name.trim(),
      host: state.host.trim(),
      active: state.active
    };
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}
```

`admin-presence.component.html` — Struktur an `admin-network-devices.component.html` ausrichten (deren Markup/Klassen 1:1 als Vorlage lesen und übernehmen); Inhalte:

```html
<div class="admin-presence">
  <h1>Anwesenheit</h1>
  <p class="admin-presence__hint">
    Erkannt wird über TCP-Antworten der Handys im WLAN. Voraussetzung: feste
    DHCP-Reservierung im Router und iOS-Einstellung „Private WLAN-Adresse: Fest"
    (nicht „Rotierend") für das Heim-WLAN.
  </p>

  <section class="admin-presence__settings">
    <h2>Karenzzeit</h2>
    <p>„Abwesend" gilt erst, wenn alle Geräte einer Person so lange still sind.</p>
    <form (ngSubmit)="saveSettings()">
      <label>
        Minuten
        <input type="number" name="graceMinutes" [(ngModel)]="graceMinutes" min="1" max="1440" />
      </label>
      <button type="submit" [disabled]="settingsSaving()">Speichern</button>
      <span class="admin-presence__message" *ngIf="settingsMessage() as message">{{ message }}</span>
    </form>
  </section>

  <section>
    <h2>Geräte</h2>
    <p class="admin-presence__error" *ngIf="errorMessage() as message">{{ message }}</p>

    <table *ngIf="!loading() && !loadFailed()">
      <thead>
        <tr><th>Person</th><th>Name</th><th>IP-Adresse</th><th>Aktiv</th><th></th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let device of devices()">
          <td>{{ displayNameOf(device.userId) }}</td>
          <td>{{ device.name }}</td>
          <td>{{ device.host }}</td>
          <td>
            <input type="checkbox" [checked]="device.active"
                   (change)="setActive(device, !device.active)" />
          </td>
          <td>
            <button type="button" (click)="startEdit(device)">Bearbeiten</button>
            <button type="button" (click)="remove(device)">Löschen</button>
          </td>
        </tr>
        <tr *ngIf="devices().length === 0">
          <td colspan="5">Noch keine Geräte angelegt.</td>
        </tr>
      </tbody>
    </table>

    <form class="admin-presence__form" (ngSubmit)="save()">
      <h3>{{ editing ? 'Gerät bearbeiten' : 'Gerät anlegen' }}</h3>
      <label>
        Person
        <select name="userId" [(ngModel)]="form.userId">
          <option [ngValue]="null" disabled>Bitte wählen…</option>
          <option *ngFor="let user of users()" [ngValue]="user.id">{{ user.displayName }}</option>
        </select>
      </label>
      <label>
        Name
        <input type="text" name="name" [(ngModel)]="form.name" placeholder="iPhone Benedikt" />
      </label>
      <label>
        IP-Adresse
        <input type="text" name="host" [(ngModel)]="form.host" placeholder="192.168.1.50" />
      </label>
      <label>
        <input type="checkbox" name="active" [(ngModel)]="form.active" /> Aktiv
      </label>
      <div class="admin-presence__actions">
        <button type="submit" [disabled]="saving()">{{ editing ? 'Speichern' : 'Anlegen' }}</button>
        <button type="button" *ngIf="editing" (click)="resetForm()">Abbrechen</button>
      </div>
    </form>
  </section>
</div>
```

`admin-presence.component.scss` — die Stile von `admin-network-devices.component.scss` als Vorlage nehmen (Klassennamen auf `admin-presence__…` umbenannt), keine neuen Design-Entscheidungen.

- [ ] **Step 4: Route und Navigation ergänzen**

`app.routes.ts`, nach dem `admin/network-devices`-Eintrag:

```typescript
  {
    path: 'admin/presence',
    loadComponent: () => import('./pages/admin-presence/admin-presence.component')
      .then(m => m.AdminPresenceComponent),
    canActivate: [adminGuard],
    title: 'Anwesenheit - Household Manager'
  },
```

`header.component.ts`, nach dem „Netzwerk-Geräte"-Eintrag (Komma an der Vorzeile beachten):

```typescript
        { path: '/admin/presence', label: 'Anwesenheit', minRole: 'ADMIN' }
```

- [ ] **Step 5: Spec laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/admin-presence.component.spec.ts' 2>&1 | tail -20
```
Expected: 5 Tests PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/admin-presence frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(presence): Admin-Seite Anwesenheit - Karenzzeit und Geraeteliste"
```

---

### Task 13: Dashboard-Kachel

Markup **direkt in `dashboard.component.html`** (lumina-Kapselung — eine Kind-Komponente würde lautlos ungestylt rendern). **Achtung SCSS-Budget:** `dashboard.component.scss` reißt bei zu vielen neuen Zeilen das `anyComponentStyle`-Budget (Build-ERROR ist Größenpolizei) — bestehende `lumina__`-Klassen wiederverwenden, nur minimale Ergänzungen.

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (nach der `lumina__petfood`-Kachel, ~Zeile 400)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (minimal)

- [ ] **Step 1: Komponente erweitern**

In `dashboard.component.ts`:

Imports ergänzen (bei den anderen Service-/Modell-Imports):

```typescript
import { PresenceService } from '../../services/presence.service';
import { PresencePersonStatus, PresenceStatusResponse } from '../../models/presence.model';
import { formatDate } from '@angular/common';
```

(`formatDate` nur ergänzen, falls nicht schon importiert.)

Bei den anderen `inject`-Feldern (~Zeile 113):

```typescript
  private readonly presenceService = inject(PresenceService);
```

Bei den Subscriptions (~Zeile 180):

```typescript
  private presenceSubscription?: Subscription;
```

Bei den Zustandsfeldern (~Zeile 375):

```typescript
  /** Anwesenheits-Status; null = Kachel wird nicht gerendert. */
  presence: PresenceStatusResponse | null = null;
```

Konstante bei den anderen Refresh-Konstanten:

```typescript
  /** Anwesenheits-Kachel: 30-s-Rhythmus wie der Backend-Poller. */
  private static readonly PRESENCE_REFRESH_MS = 30000;
```

In `ngOnInit()` (nach `this.startPetFoodRefresh();`):

```typescript
    this.startPresenceRefresh();
```

In `ngOnDestroy()` (bei den anderen unsubscribes):

```typescript
    this.presenceSubscription?.unsubscribe();
```

Methoden (nach `startPetFoodRefresh`, gleiches Muster — ein Fehler behält den letzten Stand):

```typescript
  /**
   * Anwesenheits-Kachel. Ein fehlgeschlagener Refresh behaelt den letzten Stand
   * (null = kein Update) statt die Kachel verschwinden zu lassen.
   */
  private startPresenceRefresh(): void {
    this.presenceSubscription = interval(DashboardComponent.PRESENCE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.presenceService.getStatus()
          .pipe(catchError(() => of<PresenceStatusResponse | null>(null))))
      )
      .subscribe(status => {
        if (status) {
          this.presence = status;
        }
      });
  }

  /** Nur Personen mit erfassten Geraeten; ohne sie bleibt die Kachel weg. */
  get presencePersons(): PresencePersonStatus[] {
    return this.presence?.persons ?? [];
  }

  presenceIcon(person: PresencePersonStatus): string {
    return person.state === 'on' ? 'home' : 'directions_walk';
  }

  presenceLabel(person: PresencePersonStatus): string {
    switch (person.state) {
      case 'on':
        return 'Zu Hause';
      case 'off':
        return person.lastSeenAt
          ? `Abwesend seit ${formatDate(person.lastSeenAt, 'HH:mm', 'de')}`
          : 'Abwesend';
      case 'unavailable':
        return 'Keine aktiven Geräte';
      default:
        return 'Unbekannt';
    }
  }
```

- [ ] **Step 2: Kachel-Markup einfügen**

In `dashboard.component.html` direkt **nach** der schließenden `</div>` der `lumina__petfood`-Kachel (~Zeile 400):

```html
    <div class="lumina-card lumina__presence" *ngIf="presencePersons.length > 0">
      <div class="lumina__secured-icon">
        <span class="material-symbols-outlined">home_pin</span>
      </div>
      <div class="lumina__pets-info">
        <h4 class="lumina__label lumina__label--secondary">Anwesenheit</h4>
        <p class="lumina__secured-detail" *ngFor="let person of presencePersons"
           [class.lumina__presence--away]="person.state !== 'on'">
          <span class="material-symbols-outlined">{{ presenceIcon(person) }}</span>
          {{ person.displayName }} • {{ presenceLabel(person) }}
        </p>
      </div>
    </div>
```

- [ ] **Step 3: Minimale SCSS-Ergänzung**

In `dashboard.component.scss`, bei den anderen Footer-Kachel-Stilen (z. B. nach den `lumina__pets`-Regeln; die Kachel erbt das Karten-Layout über `lumina-card`):

```scss
.lumina__presence--away {
  opacity: 0.55;
}
```

- [ ] **Step 4: Build + bestehende Dashboard-Tests**

```bash
cd frontend && npx ng build --configuration development 2>&1 | tail -5
```
Expected: Build erfolgreich. **Falls das `anyComponentStyle`-Budget als ERROR anschlägt:** die SCSS-Ergänzung ist eine Zeile — dann stammt der Überlauf aus dem Bestand; Ergänzung ggf. in eine bestehende Regel integrieren statt neue hinzuzufügen.

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -10
```
Expected: Baseline (3 vorbestehende Fails), keine neuen Fails.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(presence): Anwesenheits-Kachel im Dashboard-Footer"
```

---

### Task 14: Doku, Gesamtverifikation, Abschluss

**Files:**
- Modify: `CLAUDE.md` (neue Sektion nach „### Netzwerk-Monitoring")

- [ ] **Step 1: CLAUDE.md-Sektion schreiben** — nach dem Netzwerk-Monitoring-Block einfügen:

```markdown
### Anwesenheitserkennung (WLAN)
- Modul `backend/src/main/java/com/household/manager/presence/`; Spec: `docs/superpowers/specs/2026-08-25-anwesenheitserkennung-design.md`. Erste Quelle: iPhones im WLAN, TCP-Probe alle 30 s gegen feste IPs (`presence_device`, FK auf `app_user` mit `ON DELETE CASCADE`)
- **Jede TCP-Antwort zählt als anwesend, auch „Connection refused"** (RST beweist: der Host lebt) — deshalb eigene Drei-Zustands-Probe (`PresenceProbe`), NICHT der `TcpPortProbe` des network-Moduls (für den sind refused und timeout beides „zu"). Ports 62078 (iPhone lockdownd), 80, 443; erste Antwort gewinnt
- **`PresenceEvaluator` ist die einzige Definition von „anwesend"** (Muster `TractiveHomeResolver`): Poller und `GET /v1/presence/status` fragen dieselbe Klasse. Anwesend sofort mit der ersten Antwort; abwesend erst, wenn ALLE aktiven Geräte einer Person länger als die Karenzzeit still sind (`application_settings` Kategorie `PRESENCE`, `away_grace_minutes`, Default 10, defensives Lesen wirft nie)
- **Neustart-Verhalten:** `lastSeen` lebt nur im Speicher (`PresenceMonitor`, Muster `NetworkDeviceStatusMonitor` plus `startedAt`). Bis seit dem Start die Karenzzeit verstrichen ist, wird bei Stille KEIN Update gemeldet — die Entität behält ihren DB-Wert (nie raten). Person ohne Geräte-Zeilen: keine Entität; alle Geräte deaktiviert: `unavailable`
- Entitäten (`EntitySource.PRESENCE`): `binary_sensor.presence_<userId>_home` je Person (`deviceClass: presence`, Attribute `personUserId`, `lastSeenAt` — Schlüssel fehlt statt null) und Aggregat `binary_sensor.presence_household` („Jemand zu Hause"): `on` sobald irgendwer da ist, `off` nur wenn alle abwesend, `unavailable` nur wenn alle blind; jede Mischung ohne PRESENT meldet NICHTS
- **Modus-Automatik „Abwesend" sind Flows, kein Java** (beim Rollout via flow-mcp): Trigger `presence_household` → off ⇒ Modus an, → on ⇒ Modus aus. Ein Flow schaltet den Modus DIREKT — die Dashboard-Aktivierungs-Checks laufen dabei nicht (konsistent mit dem Bestand, bewusst so)
- Security: `/v1/presence/settings` ist ein methodenloser ADMIN-Matcher VOR der generischen GET-Regel (Muster tractive/home-settings); Geräte-CRUD drei methodenspezifische ADMIN-Matcher (Muster Netzwerk-Geräte); `GET /status` KIOSK über die generische Regel (Dashboard-Kachel auf dem Wandtablet). Audit: `presence.device.create/update/delete`, `presence.settings.update`
- Frontend: Admin-Seite „Anwesenheit" (`pages/admin-presence/`, Route `admin/presence`) mit Karenzzeit + Geräteliste (Personen-Dropdown aus `GET /v1/users`); Dashboard-Footer-Kachel direkt in `dashboard.component.html` (lumina-Kapselung), Refresh 30 s, Fehler behalten den letzten Stand
- **Rollout:** Deploy → DHCP-Reservierungen prüfen + iOS „Private WLAN-Adresse: Fest" (nicht „Rotierend", seit iOS 18) → Geräte auf der Admin-Seite erfassen → einige Tage `lastSeenAt` beobachten (nächtliche Aussetzer?), Karenzzeit ggf. nachziehen → ERST DANN die beiden Modus-Flows via flow-mcp anlegen. Wechselt ein iPhone die MAC (rotierende private Adresse), fällt die Person still auf „abwesend" — sichtbar nur am `lastSeenAt` der Admin-Seite
```

- [ ] **Step 2: Voller Backend-Testlauf**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test
```
Expected: nur die vorbestehenden DB-Fails (`contextLoads`, `HealthControllerTest`).

- [ ] **Step 3: Voller Frontend-Testlauf**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -10
```
Expected: Baseline (3 vorbestehende Fails, ggf. SmartDeviceList-Flake — bei Verdacht wiederholen), keine neuen Fails.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): Anwesenheitserkennung festhalten"
```

- [ ] **Step 5: Abschluss** — superpowers:finishing-a-development-branch verwenden (Merge nach `main` vorschlagen; PROD-Deploy und Rollout-Schritte laut Spec sind manuelle Folgearbeit).

---

## Nicht Teil dieses Plans (Rollout, manuell)

1. DHCP-Reservierungen im Router für beide iPhones; iOS-Einstellung „Private WLAN-Adresse: Fest" prüfen.
2. Geräte auf der Admin-Seite erfassen, Karenzzeit beobachten/nachziehen.
3. **Erst nach einigen Tagen Beobachtung:** die beiden Modus-Flows via flow-mcp anlegen (create → deploy → enable):
   - „Alle weg": Trigger `binary_sensor.presence_household` → `off`, Aktion Modus „Abwesend" einschalten.
   - „Jemand kommt": Trigger `binary_sensor.presence_household` → `on`, Aktion Modus „Abwesend" ausschalten.
