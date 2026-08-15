# Toni-Futtervorrat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein `petfood`-Modul, das den MjamMjam-Dosenbestand für Toni führt: automatische Abzüge (7:00/16:00 je 0,5 Dose, Europe/Berlin, mit Nachholen über Neustarts), REST-API, Entity-Spiegelung für den Telegram-Warnflow, Angular-Seite mit Füllstandsanzeiger und Dashboard-Kachel.

**Architecture:** Zwei Tabellen (`pet_food_stock` als Ein-Zeilen-Bestand mit persistierter Instant-Hochwassermarke, `pet_food_transaction` als Journal). Ein minütlicher Scheduler ruft einen transaktionalen Service, der fällige Fütterungszeitpunkte zwischen Marke und jetzt berechnet (reine Logik in `FeedingSchedule`, separat testbar) und abbucht. Jede Bestandsänderung spiegelt `sensor.pet_food_toni_cans` in den Entity-State-Layer.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / Lombok, Angular 19 standalone.

**Spec:** `docs/superpowers/specs/2026-08-15-toni-futtervorrat-design.md`

---

## Wichtige Projektregeln (vor dem Start lesen)

- **Maven braucht JDK 21:** Vor jedem `mvn` in der Bash-Shell `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Default der Maschine ist JDK 17 und schlägt fehl). Aus `backend/` heraus arbeiten, es gibt kein `mvnw`.
- **Vorbestehende Test-Fails (KEINE Regressionen):** Backend: `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern lokal an fehlender Test-DB ("Access denied for user 'root'"). Frontend-Baseline: 3 Fails (`AppComponent` ×2, `HeroComponent`), gelegentliche `SmartDeviceListComponent`-Karma-Flake.
- **JPA-Repositories MÜSSEN in `com.household.manager.repository` liegen** (`JpaConfig` scannt nur dieses Paket). Entities liegen in `com.household.manager.model.entity`.
- Frontend-Tests headless: `npm test -- --watch=false --browsers=ChromeHeadless` (aus `frontend/`).
- Keine deutschen Umlaute in Java-Quelltext-Kommentaren des Backends? Doch — der Bestand nutzt teils `ue`-Schreibweise in Javadoc (siehe `CalendarReminderScheduler`). Neue Backend-Kommentare in der etablierten `ue/ae/oe`-Schreibweise halten, Frontend-Kommentare wie im Bestand.

## File Structure

**Backend (neu):**
- `backend/src/main/resources/db/changelog/changes/20260815-0046-create-pet-food-tables.xml` — Schema + Seed
- `backend/src/main/java/com/household/manager/model/entity/PetFoodStock.java` — Ein-Zeilen-Bestand
- `backend/src/main/java/com/household/manager/model/entity/PetFoodTransaction.java` — Journal
- `backend/src/main/java/com/household/manager/repository/PetFoodStockRepository.java`
- `backend/src/main/java/com/household/manager/repository/PetFoodTransactionRepository.java`
- `backend/src/main/java/com/household/manager/petfood/FeedingSchedule.java` — reine Fütterungszeitpunkt-Logik
- `backend/src/main/java/com/household/manager/petfood/PetFoodService.java` — Buchungen, Status, Entity-Spiegelung
- `backend/src/main/java/com/household/manager/petfood/PetFoodFeedingScheduler.java` — minütlicher Trigger
- `backend/src/main/java/com/household/manager/petfood/PetFoodController.java` — REST
- `backend/src/main/java/com/household/manager/petfood/PetFoodDtos.java` — Request/Response-Records

**Backend (ändern):**
- `backend/src/main/resources/db/changelog/db.changelog-master.xml` — Include ergänzen
- `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` — Konstante `PET_FOOD`
- `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` — Rollenmatrix-Tests

**Backend-Tests (neu):**
- `backend/src/test/java/com/household/manager/petfood/FeedingScheduleTest.java`
- `backend/src/test/java/com/household/manager/petfood/PetFoodServiceTest.java`

**Frontend (neu):**
- `frontend/src/app/models/pet-food.model.ts`
- `frontend/src/app/services/pet-food.service.ts`
- `frontend/src/app/pages/pet-food/pet-food.component.ts|html|scss`
- `frontend/src/app/pages/pet-food/pet-food.component.spec.ts`

**Frontend (ändern):**
- `frontend/src/app/app.routes.ts` — Route `/pet-food`
- `frontend/src/app/components/header/header.component.ts` — Navi-Eintrag
- `frontend/src/app/pages/dashboard/dashboard.component.ts|html|scss` — Footer-Kachel

**Keine Änderung an `SecurityConfig`:** `GET /v1/pet-food/**` fällt unter die generische `GET /v1/**`-KIOSK-Regel, alle Schreibpfade unter `anyRequest().hasRole("MEMBER")`. Genau das sichern die neuen `SecurityRulesTest`-Fälle ab.

---

### Task 1: Liquibase-Changeset + Master-Include

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260815-0046-create-pet-food-tables.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Changeset anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260815-0046-create-pet-food-stock" author="claude">
        <comment>Toni-Futtervorrat: Ein-Zeilen-Bestand mit persistierter Abzugs-Hochwassermarke</comment>
        <createTable tableName="pet_food_stock">
            <column name="id" type="BIGINT">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="cans_remaining" type="DECIMAL(6,1)">
                <constraints nullable="false"/>
            </column>
            <column name="target_cans" type="DECIMAL(6,1)">
                <constraints nullable="false"/>
            </column>
            <column name="deduction_marker" type="DATETIME"/>
            <column name="updated_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

    <changeSet id="20260815-0046-create-pet-food-transaction" author="claude">
        <comment>Toni-Futtervorrat: Buchungsjournal (Fuetterung/Einkauf/Korrektur)</comment>
        <createTable tableName="pet_food_transaction">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="occurred_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
            <column name="type" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="amount" type="DECIMAL(6,1)">
                <constraints nullable="false"/>
            </column>
            <column name="cans_after" type="DECIMAL(6,1)">
                <constraints nullable="false"/>
            </column>
            <column name="note" type="VARCHAR(255)"/>
            <column name="created_at" type="DATETIME">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <createIndex tableName="pet_food_transaction" indexName="idx_pet_food_tx_occurred_at">
            <column name="occurred_at"/>
        </createIndex>
    </changeSet>

    <changeSet id="20260815-0046-seed-pet-food-stock" author="claude">
        <comment>Startbestand 0, Ziel 48, Marke NULL (der erste Scheduler-Lauf setzt sie ohne Abzug)</comment>
        <insert tableName="pet_food_stock">
            <column name="id" valueNumeric="1"/>
            <column name="cans_remaining" valueNumeric="0"/>
            <column name="target_cans" valueNumeric="48"/>
            <column name="updated_at" valueComputed="NOW()"/>
        </insert>
    </changeSet>

</databaseChangeLog>
```

Hinweis: mehrere Changesets wie bei `20260727-0044` — MariaDB committet DDL implizit, ein gebündeltes Changeset wäre nach einem Teilfehler dauerhaft nicht wiederholbar.

- [ ] **Step 2: Include im Master ergänzen**

In `db.changelog-master.xml` vor dem schließenden `</databaseChangeLog>` (nach dem `20260727-0045`-Include):

```xml
    <!-- Toni-Futtervorrat -->
    <include file="db/changelog/changes/20260815-0046-create-pet-food-tables.xml"/>
```

- [ ] **Step 3: Backend kompilieren (validiert die XML nicht gegen die DB, faengt aber Tippfehler im Pfad beim Test-Start)**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile`
Expected: `BUILD SUCCESS` (keine Ausgabe bei `-q` außer Warnungen)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog
git commit -m "feat(backend): Liquibase-Schema fuer den Toni-Futtervorrat"
```

---

### Task 2: JPA-Entities + Repositories

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/PetFoodStock.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/PetFoodTransaction.java`
- Create: `backend/src/main/java/com/household/manager/repository/PetFoodStockRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/PetFoodTransactionRepository.java`

Repositories MÜSSEN ins Paket `com.household.manager.repository` (JpaConfig scannt nur dort).

- [ ] **Step 1: `PetFoodStock` anlegen**

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Der Toni-Futtervorrat als Ein-Zeilen-Tabelle (id fest 1, per Liquibase geseedet).
 * {@link #deductionMarker} ist die Hochwassermarke der automatischen Abzuege als
 * Instant: bis zu diesem Zeitpunkt sind alle Fuetterungen (7:00/16:00) verbucht.
 * NULL bedeutet Erstinbetriebnahme — der erste Lauf setzt die Marke ohne Abzug,
 * sonst wuerde ab Epochenbeginn nachgeholt.
 */
@Entity
@Table(name = "pet_food_stock")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetFoodStock {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "cans_remaining", nullable = false, precision = 6, scale = 1)
    private BigDecimal cansRemaining;

    @Column(name = "target_cans", nullable = false, precision = 6, scale = 1)
    private BigDecimal targetCans;

    @Column(name = "deduction_marker")
    private Instant deductionMarker;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: `PetFoodTransaction` anlegen**

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Eine Bewegung im Futtervorrat. {@link #amount} ist die tatsaechlich wirksame,
 * vorzeichenbehaftete Bestandsaenderung (Fuetterung negativ, Einkauf positiv,
 * Korrektur als Differenz); {@link #occurredAt} ist der fachliche Zeitpunkt —
 * bei nachgeholten Fuetterungen der Fuetterungszeitpunkt, nicht die Laufzeit
 * des Schedulers.
 */
@Entity
@Table(name = "pet_food_transaction")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetFoodTransaction {

    public enum Type { FEEDING, PURCHASE, CORRECTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, precision = 6, scale = 1)
    private BigDecimal amount;

    @Column(name = "cans_after", nullable = false, precision = 6, scale = 1)
    private BigDecimal cansAfter;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Repositories anlegen**

`backend/src/main/java/com/household/manager/repository/PetFoodStockRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.PetFoodStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PetFoodStockRepository extends JpaRepository<PetFoodStock, Long> {
}
```

`backend/src/main/java/com/household/manager/repository/PetFoodTransactionRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.PetFoodTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PetFoodTransactionRepository extends JpaRepository<PetFoodTransaction, Long> {

    List<PetFoodTransaction> findByOrderByOccurredAtDescIdDesc(Pageable pageable);
}
```

- [ ] **Step 4: Kompilieren**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/PetFoodStock.java backend/src/main/java/com/household/manager/model/entity/PetFoodTransaction.java backend/src/main/java/com/household/manager/repository/PetFoodStockRepository.java backend/src/main/java/com/household/manager/repository/PetFoodTransactionRepository.java
git commit -m "feat(backend): Entities und Repositories fuer den Futtervorrat"
```

---

### Task 3: `FeedingSchedule` — Fütterungszeitpunkte zwischen zwei Instants (TDD)

**Files:**
- Test: `backend/src/test/java/com/household/manager/petfood/FeedingScheduleTest.java`
- Create: `backend/src/main/java/com/household/manager/petfood/FeedingSchedule.java`

Reine, zustandslose Logik — deshalb zuerst und separat getestet. Die Marke ist ein Instant (Zeitumstellungs-Lektion aus `CalendarReminderScheduler`); Wandzeiten 7:00/16:00 werden je Datum frisch über die Zone aufgelöst.

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.petfood;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedingScheduleTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @Test
    void leeresFensterLiefertNichts() {
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 15, 8, 0), at(2026, 8, 15, 15, 0), BERLIN);
        assertThat(due).isEmpty();
    }

    @Test
    void einMinutenFensterUmSiebenLiefertGenauDieMorgenfuetterung() {
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 15, 6, 59), at(2026, 8, 15, 7, 0), BERLIN);
        assertThat(due).containsExactly(at(2026, 8, 15, 7, 0));
    }

    @Test
    void untergrenzeIstExklusiv() {
        // Marke exakt auf 7:00: diese Fuetterung ist schon verbucht.
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 15, 7, 0), at(2026, 8, 15, 7, 30), BERLIN);
        assertThat(due).isEmpty();
    }

    @Test
    void mehrtagesFensterHoltAlleFuetterungenNach() {
        // 25 Stunden Stillstand ueber Nacht: 16:00 (Tag 1), 7:00 und 16:00 (Tag 2).
        List<Instant> due = FeedingSchedule.between(
                at(2026, 8, 14, 15, 0), at(2026, 8, 15, 16, 0), BERLIN);
        assertThat(due).containsExactly(
                at(2026, 8, 14, 16, 0),
                at(2026, 8, 15, 7, 0),
                at(2026, 8, 15, 16, 0));
    }

    @Test
    void zeitumstellungOktoberLiefertProTagWeiterGenauZweiFuetterungen() {
        // 2026-10-25: Ende der Sommerzeit in Europa (03:00 -> 02:00). Das Fenster
        // ueberspannt den Rueckstellmoment; 7:00/16:00 muessen trotzdem genau einmal kommen.
        List<Instant> due = FeedingSchedule.between(
                at(2026, 10, 24, 20, 0), at(2026, 10, 25, 20, 0), BERLIN);
        assertThat(due).containsExactly(
                at(2026, 10, 25, 7, 0),
                at(2026, 10, 25, 16, 0));
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FeedingScheduleTest`
Expected: COMPILATION ERROR (`FeedingSchedule` existiert nicht)

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.petfood;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Berechnet die Fuetterungszeitpunkte (taeglich 7:00 und 16:00 Wandzeit) in einem
 * Instant-Fenster. Instants statt Wandzeit, weil die Berliner Wandzeit bei der
 * Oktober-Zeitumstellung nicht monoton ist (siehe CalendarReminderScheduler);
 * 7:00 und 16:00 existieren an jedem Berliner Tag genau einmal, die Aufloesung
 * per atZone ist damit eindeutig.
 */
final class FeedingSchedule {

    static final List<LocalTime> FEEDING_TIMES = List.of(LocalTime.of(7, 0), LocalTime.of(16, 0));

    private FeedingSchedule() {
    }

    /** Alle Fuetterungszeitpunkte in (sinceExclusive, untilInclusive], aufsteigend. */
    static List<Instant> between(Instant sinceExclusive, Instant untilInclusive, ZoneId zone) {
        List<Instant> due = new ArrayList<>();
        LocalDate day = sinceExclusive.atZone(zone).toLocalDate();
        LocalDate lastDay = untilInclusive.atZone(zone).toLocalDate();
        while (!day.isAfter(lastDay)) {
            for (LocalTime time : FEEDING_TIMES) {
                Instant feeding = day.atTime(time).atZone(zone).toInstant();
                if (feeding.isAfter(sinceExclusive) && !feeding.isAfter(untilInclusive)) {
                    due.add(feeding);
                }
            }
            day = day.plusDays(1);
        }
        return due;
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss bestehen**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FeedingScheduleTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/petfood/FeedingSchedule.java backend/src/test/java/com/household/manager/petfood/FeedingScheduleTest.java
git commit -m "feat(backend): Fuetterungszeitpunkt-Berechnung mit Instant-Fenster"
```

---

### Task 4: `EntitySource.PET_FOOD` + DTOs

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Create: `backend/src/main/java/com/household/manager/petfood/PetFoodDtos.java`

- [ ] **Step 1: Enum-Konstante ergänzen**

In `EntitySource.java` vor `MANUAL` einfügen:

```java
    /** Toni-Futtervorrat (interner Bestand, sensor.pet_food_toni_cans). */
    PET_FOOD,
```

- [ ] **Step 2: DTOs anlegen**

```java
package com.household.manager.petfood;

import com.household.manager.model.entity.PetFoodTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Request-/Response-Records der Futtervorrats-API. */
public final class PetFoodDtos {

    private PetFoodDtos() {
    }

    public record StatusResponse(
            BigDecimal cansRemaining,
            BigDecimal targetCans,
            int percent,
            int daysRemaining
    ) {
    }

    public record TransactionResponse(
            LocalDateTime occurredAt,
            PetFoodTransaction.Type type,
            BigDecimal amount,
            BigDecimal cansAfter,
            String note
    ) {
        public static TransactionResponse from(PetFoodTransaction tx) {
            return new TransactionResponse(tx.getOccurredAt(), tx.getType(),
                    tx.getAmount(), tx.getCansAfter(), tx.getNote());
        }
    }

    public record PurchaseRequest(BigDecimal cans, String note) {
    }

    public record CorrectionRequest(BigDecimal cansRemaining, String note) {
    }

    public record TargetRequest(BigDecimal targetCans) {
    }
}
```

- [ ] **Step 3: Kompilieren**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java backend/src/main/java/com/household/manager/petfood/PetFoodDtos.java
git commit -m "feat(backend): EntitySource PET_FOOD und Futtervorrats-DTOs"
```

---

### Task 5: `PetFoodService` (TDD)

**Files:**
- Test: `backend/src/test/java/com/household/manager/petfood/PetFoodServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/petfood/PetFoodService.java`

Kernstück: transaktionale Buchungen, Klemmen auf 0, Validierung (0,5-Schritte), Entity-Spiegelung, Audit. BigDecimal überall — ein `NaN` ist damit strukturell unmöglich (Jackson lehnt Nicht-Zahlen für BigDecimal mit 400 ab), die `isFinite`-Falle aus der Spec betrifft nur double-Pfade und entfällt hier bewusst.

**Bewusster Trade-off (im Code dokumentieren):** Die Entity-Spiegelung läuft am Ende der `@Transactional`-Methode, also vor dem Commit. Schlägt der Commit danach fehl, zeigt der Sensor bis zur nächsten Änderung (max. wenige Stunden) einen zu neuen Wert — akzeptiert, weil `EntityStateService.reportState` nie wirft und der Fall praktisch nicht auftritt.

- [ ] **Step 1: Failing Tests schreiben**

```java
package com.household.manager.petfood;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.PetFoodStock;
import com.household.manager.model.entity.PetFoodTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PetFoodServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private com.household.manager.repository.PetFoodStockRepository stockRepository;
    @Mock
    private com.household.manager.repository.PetFoodTransactionRepository transactionRepository;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private AuditService auditService;
    @Captor
    private ArgumentCaptor<PetFoodTransaction> txCaptor;
    @Captor
    private ArgumentCaptor<EntityStateUpdate> updateCaptor;

    private PetFoodService service;
    private PetFoodStock stock;

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, BERLIN).toInstant();
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(at(2026, 8, 15, 16, 30), BERLIN);
        service = new PetFoodService(stockRepository, transactionRepository,
                entityStateService, auditService, clock);
        stock = PetFoodStock.builder()
                .id(PetFoodStock.SINGLETON_ID)
                .cansRemaining(new BigDecimal("10.0"))
                .targetCans(new BigDecimal("48.0"))
                .deductionMarker(at(2026, 8, 15, 16, 5))
                .build();
        // lenient: die reinen Validierungstests (0,5-Raster, null) werfen vor dem
        // ersten Repository-Zugriff — ein strikter Stub wuerde dort als unnoetig gelten.
        lenient().when(stockRepository.findById(PetFoodStock.SINGLETON_ID))
                .thenReturn(Optional.of(stock));
    }

    @Test
    void nullMarkeSetztMarkeOhneAbzug() {
        stock.setDeductionMarker(null);

        service.applyDueFeedings();

        assertThat(stock.getDeductionMarker()).isEqualTo(at(2026, 8, 15, 16, 30));
        assertThat(stock.getCansRemaining()).isEqualByComparingTo("10.0");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void holtVerpassteFuetterungenNach() {
        // Marke 25,5 h zurueck: 16:00 (14.8.), 7:00 und 16:00 (15.8.) sind faellig.
        stock.setDeductionMarker(at(2026, 8, 14, 15, 0));

        service.applyDueFeedings();

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("8.5");
        assertThat(stock.getDeductionMarker()).isEqualTo(at(2026, 8, 15, 16, 30));
        verify(transactionRepository, org.mockito.Mockito.times(3)).save(txCaptor.capture());
        assertThat(txCaptor.getAllValues())
                .allSatisfy(tx -> {
                    assertThat(tx.getType()).isEqualTo(PetFoodTransaction.Type.FEEDING);
                    assertThat(tx.getAmount()).isEqualByComparingTo("-0.5");
                });
        // occurred_at ist der Fuetterungszeitpunkt, nicht die Scheduler-Laufzeit.
        assertThat(txCaptor.getAllValues().get(0).getOccurredAt())
                .isEqualTo(ZonedDateTime.ofInstant(at(2026, 8, 14, 16, 0), BERLIN).toLocalDateTime());
    }

    @Test
    void bestandKlemmtBeiNull() {
        stock.setCansRemaining(new BigDecimal("0.3"));
        stock.setDeductionMarker(at(2026, 8, 15, 15, 0)); // genau eine Fuetterung faellig (16:00)

        service.applyDueFeedings();

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("0.0");
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("-0.3");
    }

    @Test
    void keineFaelligeFuetterungRuecktNurDieMarkeVor() {
        service.applyDueFeedings();

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("10.0");
        verify(transactionRepository, never()).save(any());
        // Auch ohne Abzug wird gespeichert (Marke) — aber kein Entity-Update noetig.
        verify(stockRepository).save(stock);
    }

    @Test
    void fuetterungSpiegeltDieEntitaet() {
        stock.setDeductionMarker(at(2026, 8, 15, 15, 0));

        service.applyDueFeedings();

        verify(entityStateService).reportState(updateCaptor.capture());
        EntityStateUpdate update = updateCaptor.getValue();
        assertThat(update.entityId()).isEqualTo("sensor.pet_food_toni_cans");
        assertThat(update.state()).isEqualTo("9.5");
    }

    @Test
    void einkaufBuchtZuUndSchreibtJournal() {
        service.recordPurchase(new BigDecimal("24"), "Karton");

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("34.0");
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(PetFoodTransaction.Type.PURCHASE);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("24");
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("petfood.purchase"), any());
    }

    @Test
    void einkaufLehntNichtHalbeSchritteAb() {
        assertThatThrownBy(() -> service.recordPurchase(new BigDecimal("0.3"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void einkaufLehntNullUndNegativAb() {
        assertThatThrownBy(() -> service.recordPurchase(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordPurchase(new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void korrekturSetztAbsolutUndJournalisiertDieDifferenz() {
        service.correctStock(new BigDecimal("8.5"), "gezaehlt");

        assertThat(stock.getCansRemaining()).isEqualByComparingTo("8.5");
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(PetFoodTransaction.Type.CORRECTION);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("-1.5");
    }

    @Test
    void korrekturOhneAenderungSchreibtKeinJournal() {
        service.correctStock(new BigDecimal("10.0"), null);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void statusRechnetProzentUndReichweite() {
        PetFoodDtos.StatusResponse status = service.getStatus();

        assertThat(status.cansRemaining()).isEqualByComparingTo("10.0");
        assertThat(status.percent()).isEqualTo(21); // 10/48 gerundet
        assertThat(status.daysRemaining()).isEqualTo(10);
    }

    @Test
    void zielbestandLehntNullUndNichtPositivesAb() {
        assertThatThrownBy(() -> service.updateTarget(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateTarget(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PetFoodServiceTest`
Expected: COMPILATION ERROR (`PetFoodService` existiert nicht)

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.petfood;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.PetFoodStock;
import com.household.manager.model.entity.PetFoodTransaction;
import com.household.manager.repository.PetFoodStockRepository;
import com.household.manager.repository.PetFoodTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuehrt den Toni-Futtervorrat: automatische Fuetterungsabzuege, Einkaeufe,
 * Korrekturen, Zielbestand. Jede Bestandsaenderung spiegelt
 * {@code sensor.pet_food_toni_cans} in den Entity-State-Layer (Warnflow-Trigger).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PetFoodService {

    static final String ENTITY_ID = "sensor.pet_food_toni_cans";
    private static final BigDecimal HALF_CAN = new BigDecimal("0.5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_TRANSACTIONS = 200;

    private final PetFoodStockRepository stockRepository;
    private final PetFoodTransactionRepository transactionRepository;
    private final EntityStateService entityStateService;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PetFoodDtos.StatusResponse getStatus() {
        return toStatus(loadStock());
    }

    @Transactional(readOnly = true)
    public List<PetFoodDtos.TransactionResponse> getTransactions(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_TRANSACTIONS));
        return transactionRepository.findByOrderByOccurredAtDescIdDesc(PageRequest.of(0, capped))
                .stream().map(PetFoodDtos.TransactionResponse::from).toList();
    }

    /**
     * Verbucht alle Fuetterungen zwischen Marke und jetzt. Abzug, Journal und Marke
     * laufen in EINER Transaktion; schlaegt sie fehl, bleibt die Marke stehen und der
     * naechste Lauf holt nach (idempotent). Die Entity-Spiegelung am Methodenende
     * passiert vor dem Commit — ein danach scheiternder Commit liesse den Sensor bis
     * zur naechsten Aenderung zu neu aussehen; akzeptiert, weil reportState nie wirft
     * und der Fall praktisch nicht auftritt.
     */
    @Transactional
    public void applyDueFeedings() {
        PetFoodStock stock = loadStock();
        Instant now = clock.instant();
        Instant marker = stock.getDeductionMarker();
        if (marker == null) {
            // Erstinbetriebnahme: Marke setzen, nichts abziehen — sonst wuerde ab
            // Epochenbeginn nachgeholt.
            stock.setDeductionMarker(now);
            stockRepository.save(stock);
            return;
        }
        List<Instant> due = FeedingSchedule.between(marker, now, clock.getZone());
        boolean changed = false;
        for (Instant feeding : due) {
            BigDecimal deduction = stock.getCansRemaining().min(HALF_CAN);
            if (deduction.signum() > 0) {
                stock.setCansRemaining(stock.getCansRemaining().subtract(deduction));
                changed = true;
            }
            transactionRepository.save(PetFoodTransaction.builder()
                    .occurredAt(LocalDateTime.ofInstant(feeding, clock.getZone()))
                    .type(PetFoodTransaction.Type.FEEDING)
                    .amount(deduction.negate())
                    .cansAfter(stock.getCansRemaining())
                    .build());
        }
        stock.setDeductionMarker(now);
        stockRepository.save(stock);
        if (changed) {
            log.info("Futtervorrat: {} Fuetterung(en) verbucht, Bestand {}",
                    due.size(), stock.getCansRemaining());
            mirrorEntity(stock);
        }
    }

    @Transactional
    public PetFoodDtos.StatusResponse recordPurchase(BigDecimal cans, String note) {
        requireHalfSteps(cans, "cans");
        if (cans.signum() <= 0) {
            throw new IllegalArgumentException("Die Dosenzahl muss groesser als 0 sein.");
        }
        PetFoodStock stock = loadStock();
        stock.setCansRemaining(stock.getCansRemaining().add(cans));
        transactionRepository.save(PetFoodTransaction.builder()
                .occurredAt(LocalDateTime.now(clock))
                .type(PetFoodTransaction.Type.PURCHASE)
                .amount(cans)
                .cansAfter(stock.getCansRemaining())
                .note(note)
                .build());
        stockRepository.save(stock);
        auditService.record("petfood.purchase", cans + " Dosen zugebucht");
        mirrorEntity(stock);
        return toStatus(stock);
    }

    @Transactional
    public PetFoodDtos.StatusResponse correctStock(BigDecimal cansRemaining, String note) {
        requireHalfSteps(cansRemaining, "cansRemaining");
        if (cansRemaining.signum() < 0) {
            throw new IllegalArgumentException("Der Bestand kann nicht negativ sein.");
        }
        PetFoodStock stock = loadStock();
        BigDecimal diff = cansRemaining.subtract(stock.getCansRemaining());
        if (diff.signum() == 0) {
            return toStatus(stock);
        }
        stock.setCansRemaining(cansRemaining);
        transactionRepository.save(PetFoodTransaction.builder()
                .occurredAt(LocalDateTime.now(clock))
                .type(PetFoodTransaction.Type.CORRECTION)
                .amount(diff)
                .cansAfter(stock.getCansRemaining())
                .note(note)
                .build());
        stockRepository.save(stock);
        auditService.record("petfood.correction", "Bestand korrigiert auf " + cansRemaining);
        mirrorEntity(stock);
        return toStatus(stock);
    }

    @Transactional
    public PetFoodDtos.StatusResponse updateTarget(BigDecimal targetCans) {
        requireHalfSteps(targetCans, "targetCans");
        if (targetCans.signum() <= 0) {
            throw new IllegalArgumentException("Der Zielbestand muss groesser als 0 sein.");
        }
        PetFoodStock stock = loadStock();
        stock.setTargetCans(targetCans);
        stockRepository.save(stock);
        auditService.record("petfood.target.update", "Zielbestand auf " + targetCans + " gesetzt");
        mirrorEntity(stock);
        return toStatus(stock);
    }

    private PetFoodStock loadStock() {
        return stockRepository.findById(PetFoodStock.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "pet_food_stock ist nicht geseedet — Liquibase-Migration fehlt."));
    }

    /**
     * BigDecimal statt double macht die NaN-Falle strukturell unmoeglich (Jackson
     * lehnt Nicht-Zahlen fuer BigDecimal mit 400 ab); zu pruefen bleiben nur
     * null und das 0,5-Raster.
     */
    private static void requireHalfSteps(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Feld '" + field + "' fehlt.");
        }
        if (value.remainder(HALF_CAN).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(
                    "Feld '" + field + "' muss ein Vielfaches von 0,5 sein.");
        }
    }

    private PetFoodDtos.StatusResponse toStatus(PetFoodStock stock) {
        int percent = stock.getCansRemaining()
                .multiply(HUNDRED)
                .divide(stock.getTargetCans(), 0, RoundingMode.HALF_UP)
                .intValue();
        int daysRemaining = stock.getCansRemaining().setScale(0, RoundingMode.FLOOR).intValue();
        return new PetFoodDtos.StatusResponse(
                stock.getCansRemaining(), stock.getTargetCans(), percent, daysRemaining);
    }

    private void mirrorEntity(PetFoodStock stock) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("targetCans", stock.getTargetCans());
        attributes.put("percent", toStatus(stock).percent());
        attributes.put("unit", "Dosen");
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.PET_FOOD)
                .sourceRef("toni")
                .friendlyName("Toni-Futtervorrat")
                .state(stock.getCansRemaining().stripTrailingZeros().toPlainString())
                .attributes(attributes)
                .build());
    }
}
```

**Achtung, Test-Detail:** `fuetterungSpiegeltDieEntitaet` erwartet State `"9.5"` — `stripTrailingZeros().toPlainString()` macht aus `9.5` `9.5`, aus `10.0` aber `10` — genau richtig für numerische Flow-Vergleiche (StateComparator parst beides).

- [ ] **Step 4: Test laufen lassen — muss bestehen**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=PetFoodServiceTest`
Expected: `Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/petfood/PetFoodService.java backend/src/test/java/com/household/manager/petfood/PetFoodServiceTest.java
git commit -m "feat(backend): PetFoodService mit Fuetterungsabzug, Journal und Entity-Spiegelung"
```

---

### Task 6: Scheduler + Controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/petfood/PetFoodFeedingScheduler.java`
- Create: `backend/src/main/java/com/household/manager/petfood/PetFoodController.java`

- [ ] **Step 1: Scheduler anlegen**

```java
package com.household.manager.petfood;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stoesst minuetlich die Verbuchung faelliger Fuetterungen an. Die eigentliche
 * Logik (Marke, Nachholen, Transaktion) liegt im PetFoodService; die
 * Scheduled-Methode wirft nie (Muster der uebrigen Poller). fixedDelay statt
 * fixedRate: Laeufe ueberlappen sich nie, die Marke braucht keine Synchronisation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PetFoodFeedingScheduler {

    private final PetFoodService petFoodService;

    @Scheduled(fixedDelayString = "${petfood.feeding.check-interval-ms:60000}")
    public void checkDueFeedings() {
        try {
            petFoodService.applyDueFeedings();
        } catch (Exception ex) {
            log.error("Fuetterungsabzug fehlgeschlagen — naechster Lauf holt nach", ex);
        }
    }
}
```

- [ ] **Step 2: Controller anlegen**

```java
package com.household.manager.petfood;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Futtervorrats-API. Lesen faellt unter die generische GET-KIOSK-Regel
 * (Wandtablet sieht die Kachel), alle Schreibpfade unter anyRequest -> MEMBER;
 * eine eigene Security-Regel gibt es bewusst nicht (SecurityRulesTest haelt
 * beide Richtungen fest).
 */
@RestController
@RequestMapping("/v1/pet-food")
@RequiredArgsConstructor
public class PetFoodController {

    private final PetFoodService petFoodService;

    @GetMapping
    public PetFoodDtos.StatusResponse getStatus() {
        return petFoodService.getStatus();
    }

    @GetMapping("/transactions")
    public List<PetFoodDtos.TransactionResponse> getTransactions(
            @RequestParam(defaultValue = "50") int limit) {
        return petFoodService.getTransactions(limit);
    }

    @PostMapping("/purchases")
    public PetFoodDtos.StatusResponse recordPurchase(@RequestBody PetFoodDtos.PurchaseRequest request) {
        return petFoodService.recordPurchase(request.cans(), request.note());
    }

    @PostMapping("/corrections")
    public PetFoodDtos.StatusResponse correctStock(@RequestBody PetFoodDtos.CorrectionRequest request) {
        return petFoodService.correctStock(request.cansRemaining(), request.note());
    }

    @PutMapping("/target")
    public PetFoodDtos.StatusResponse updateTarget(@RequestBody PetFoodDtos.TargetRequest request) {
        return petFoodService.updateTarget(request.targetCans());
    }
}
```

- [ ] **Step 3: Kompilieren + alle petfood-Tests**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="FeedingScheduleTest,PetFoodServiceTest"`
Expected: `Tests run: 17, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/petfood/PetFoodFeedingScheduler.java backend/src/main/java/com/household/manager/petfood/PetFoodController.java
git commit -m "feat(backend): Fuetterungs-Scheduler und Futtervorrats-API"
```

---

### Task 7: SecurityRulesTest erweitern

**Files:**
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Controller in den Slice aufnehmen**

In der `@WebMvcTest(controllers = {...})`-Liste `PetFoodController.class` ergänzen (Import `com.household.manager.petfood.PetFoodController`), und als Mock dazu:

```java
    @MockitoBean
    private com.household.manager.petfood.PetFoodService petFoodService;
```

- [ ] **Step 2: Testfälle ergänzen (ans Ende der Klasse)**

```java
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfFuttervorratLesen() throws Exception {
        mockMvc.perform(get("/v1/pet-food")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinenEinkaufBuchen() throws Exception {
        mockMvc.perform(post("/v1/pet-food/purchases").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cans\": 24}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfEinkaufBuchen() throws Exception {
        mockMvc.perform(post("/v1/pet-food/purchases").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cans\": 24}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfZielbestandAendern() throws Exception {
        mockMvc.perform(put("/v1/pet-food/target").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetCans\": 60}"))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 3: Test laufen lassen**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest`
Expected: alle Tests grün (bestehende + 4 neue)

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "test(backend): Rollenmatrix fuer die Futtervorrats-API"
```

---

### Task 8: Frontend-Model + Service

**Files:**
- Create: `frontend/src/app/models/pet-food.model.ts`
- Create: `frontend/src/app/services/pet-food.service.ts`

- [ ] **Step 1: Model anlegen**

```typescript
/** Status und Journal des Toni-Futtervorrats. */
export interface PetFoodStatus {
  cansRemaining: number;
  targetCans: number;
  percent: number;
  daysRemaining: number;
}

export type PetFoodTransactionType = 'FEEDING' | 'PURCHASE' | 'CORRECTION';

export interface PetFoodTransaction {
  occurredAt: string;
  type: PetFoodTransactionType;
  amount: number;
  cansAfter: number;
  note: string | null;
}
```

- [ ] **Step 2: Service anlegen (Muster `WasteCollectionService`)**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PetFoodStatus, PetFoodTransaction } from '../models/pet-food.model';

/** Service fuer die Futtervorrats-API. */
@Injectable({ providedIn: 'root' })
export class PetFoodService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/pet-food';

  getStatus(): Observable<PetFoodStatus> {
    return this.http.get<PetFoodStatus>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  getTransactions(limit = 50): Observable<PetFoodTransaction[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<PetFoodTransaction[]>(`${this.baseUrl}/transactions`, { params })
      .pipe(catchError(this.handleError));
  }

  recordPurchase(cans: number, note?: string): Observable<PetFoodStatus> {
    return this.http.post<PetFoodStatus>(`${this.baseUrl}/purchases`, { cans, note: note || null })
      .pipe(catchError(this.handleError));
  }

  correctStock(cansRemaining: number, note?: string): Observable<PetFoodStatus> {
    return this.http.post<PetFoodStatus>(`${this.baseUrl}/corrections`,
      { cansRemaining, note: note || null })
      .pipe(catchError(this.handleError));
  }

  updateTarget(targetCans: number): Observable<PetFoodStatus> {
    return this.http.put<PetFoodStatus>(`${this.baseUrl}/target`, { targetCans })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Futtervorrat-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Futtervorrat-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/models/pet-food.model.ts frontend/src/app/services/pet-food.service.ts
git commit -m "feat(frontend): Model und Service fuer den Futtervorrat"
```

---

### Task 9: Seite „Futtervorrat" + Route + Navi (TDD für die Anzeige-Logik)

**Files:**
- Test: `frontend/src/app/pages/pet-food/pet-food.component.spec.ts`
- Create: `frontend/src/app/pages/pet-food/pet-food.component.ts`
- Create: `frontend/src/app/pages/pet-food/pet-food.component.html`
- Create: `frontend/src/app/pages/pet-food/pet-food.component.scss`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`

- [ ] **Step 1: Spec schreiben**

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { PetFoodComponent } from './pet-food.component';

// main.ts registriert die de-Locale nur fuer die App — Karma laedt main.ts nicht,
// ohne diese Zeile wirft die number-Pipe mit explizitem 'de' im Test.
registerLocaleData(localeDe);

describe('PetFoodComponent', () => {
  let fixture: ComponentFixture<PetFoodComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PetFoodComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(PetFoodComponent);
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  function flushInitialRequests(status = {
    cansRemaining: 12.5, targetCans: 48, percent: 26, daysRemaining: 12
  }): void {
    httpMock.expectOne('/api/v1/pet-food').flush(status);
    httpMock.expectOne(r => r.url === '/api/v1/pet-food/transactions').flush([]);
    fixture.detectChanges();
  }

  it('zeigt Bestand, Prozent und Reichweite an', () => {
    flushInitialRequests();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('12,5');
    expect(text).toContain('48');
    expect(text).toContain('26');
    expect(text).toContain('12');
  });

  it('Farblogik: gruen, gelb unter 25 %, rot unter der Warnschwelle von 7 Dosen', () => {
    flushInitialRequests();
    const component = fixture.componentInstance;
    expect(component.fillTone({ cansRemaining: 30, targetCans: 48, percent: 63, daysRemaining: 30 }))
      .toBe('ok');
    expect(component.fillTone({ cansRemaining: 10, targetCans: 48, percent: 21, daysRemaining: 10 }))
      .toBe('warn');
    expect(component.fillTone({ cansRemaining: 6.5, targetCans: 48, percent: 14, daysRemaining: 6 }))
      .toBe('critical');
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL (Komponente existiert nicht) — Baseline-Fails (3×) ignorieren

- [ ] **Step 3: Komponente implementieren**

`pet-food.component.ts`:

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PetFoodService } from '../../services/pet-food.service';
import { PetFoodStatus, PetFoodTransaction } from '../../models/pet-food.model';

/**
 * Seite "Futtervorrat": Fuellstand des MjamMjam-Dosenlagers fuer Toni,
 * Zubuchen/Korrigieren/Zielbestand und das Buchungsjournal. Die Warnschwelle
 * 7 Dosen entspricht dem Telegram-Flow auf sensor.pet_food_toni_cans.
 */
@Component({
  selector: 'app-pet-food',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pet-food.component.html',
  styleUrl: './pet-food.component.scss'
})
export class PetFoodComponent implements OnInit {
  private readonly petFoodService = inject(PetFoodService);

  status: PetFoodStatus | null = null;
  transactions: PetFoodTransaction[] = [];
  loading = true;
  error: string | null = null;

  purchaseCans: number | null = null;
  purchaseNote = '';
  correctionCans: number | null = null;
  correctionNote = '';
  targetCans: number | null = null;
  saving = false;

  /** Muss der Flow-Schwelle "sensor.pet_food_toni_cans < 7" entsprechen. */
  readonly criticalCans = 7;

  ngOnInit(): void {
    this.load();
  }

  fillTone(status: PetFoodStatus): 'ok' | 'warn' | 'critical' {
    if (status.cansRemaining < this.criticalCans) {
      return 'critical';
    }
    return status.percent < 25 ? 'warn' : 'ok';
  }

  barWidth(status: PetFoodStatus): number {
    return Math.max(0, Math.min(100, status.percent));
  }

  typeLabel(type: PetFoodTransaction['type']): string {
    switch (type) {
      case 'FEEDING': return 'Fütterung';
      case 'PURCHASE': return 'Einkauf';
      default: return 'Korrektur';
    }
  }

  submitPurchase(): void {
    if (this.purchaseCans == null || this.purchaseCans <= 0) {
      return;
    }
    this.mutate(this.petFoodService.recordPurchase(this.purchaseCans, this.purchaseNote),
      () => { this.purchaseCans = null; this.purchaseNote = ''; });
  }

  submitCorrection(): void {
    if (this.correctionCans == null || this.correctionCans < 0) {
      return;
    }
    this.mutate(this.petFoodService.correctStock(this.correctionCans, this.correctionNote),
      () => { this.correctionCans = null; this.correctionNote = ''; });
  }

  submitTarget(): void {
    if (this.targetCans == null || this.targetCans <= 0) {
      return;
    }
    this.mutate(this.petFoodService.updateTarget(this.targetCans), () => {});
  }

  private mutate(request: ReturnType<PetFoodService['updateTarget']>, onSuccess: () => void): void {
    this.saving = true;
    this.error = null;
    request.subscribe({
      next: status => {
        this.saving = false;
        this.status = status;
        this.targetCans = status.targetCans;
        onSuccess();
        this.loadTransactions();
      },
      error: (err: Error) => {
        this.saving = false;
        this.error = err.message;
      }
    });
  }

  private load(): void {
    this.loading = true;
    this.petFoodService.getStatus().subscribe({
      next: status => {
        this.loading = false;
        this.status = status;
        this.targetCans = status.targetCans;
      },
      error: (err: Error) => {
        this.loading = false;
        this.error = err.message;
      }
    });
    this.loadTransactions();
  }

  private loadTransactions(): void {
    this.petFoodService.getTransactions().subscribe({
      next: transactions => (this.transactions = transactions),
      error: () => { /* Journalfehler blockiert die Seite nicht */ }
    });
  }
}
```

`pet-food.component.html`:

```html
<div class="pet-food">
  <header class="pet-food__header">
    <h1>Futtervorrat</h1>
    <p class="pet-food__subtitle">MjamMjam-Dosen für Toni — 1 Dose pro Tag (7:00 und 16:00 je eine Hälfte)</p>
  </header>

  <p *ngIf="loading" class="pet-food__hint">Lädt…</p>
  <p *ngIf="error" class="pet-food__error">{{ error }}</p>

  <ng-container *ngIf="status as s">
    <section class="pet-food__gauge" [attr.data-tone]="fillTone(s)">
      <div class="pet-food__bar-track">
        <div class="pet-food__bar-fill" [style.width.%]="barWidth(s)"></div>
      </div>
      <div class="pet-food__figures">
        <span class="pet-food__cans">{{ s.cansRemaining | number:'1.0-1':'de' }} von {{ s.targetCans | number:'1.0-1':'de' }} Dosen</span>
        <span class="pet-food__percent">{{ s.percent }} %</span>
      </div>
      <p class="pet-food__range">Reicht noch etwa {{ s.daysRemaining }} Tage</p>
    </section>

    <section class="pet-food__actions">
      <form class="pet-food__form" (ngSubmit)="submitPurchase()">
        <h2>Einkauf zubuchen</h2>
        <label>Dosen
          <input type="number" name="purchaseCans" [(ngModel)]="purchaseCans" min="0.5" step="0.5" required>
        </label>
        <label>Notiz (optional)
          <input type="text" name="purchaseNote" [(ngModel)]="purchaseNote" maxlength="255">
        </label>
        <button type="submit" [disabled]="saving || !purchaseCans">Zubuchen</button>
      </form>

      <form class="pet-food__form" (ngSubmit)="submitCorrection()">
        <h2>Bestand korrigieren</h2>
        <label>Gezählter Bestand
          <input type="number" name="correctionCans" [(ngModel)]="correctionCans" min="0" step="0.5" required>
        </label>
        <label>Notiz (optional)
          <input type="text" name="correctionNote" [(ngModel)]="correctionNote" maxlength="255">
        </label>
        <button type="submit" [disabled]="saving || correctionCans == null">Korrigieren</button>
      </form>

      <form class="pet-food__form" (ngSubmit)="submitTarget()">
        <h2>Zielbestand</h2>
        <label>Dosen (= 100 %)
          <input type="number" name="targetCans" [(ngModel)]="targetCans" min="0.5" step="0.5" required>
        </label>
        <button type="submit" [disabled]="saving || !targetCans">Speichern</button>
      </form>
    </section>
  </ng-container>

  <section class="pet-food__journal" *ngIf="transactions.length > 0">
    <h2>Historie</h2>
    <table>
      <thead>
        <tr><th>Zeitpunkt</th><th>Typ</th><th>Änderung</th><th>Bestand danach</th><th>Notiz</th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let tx of transactions">
          <td>{{ tx.occurredAt | date:'dd.MM.yyyy HH:mm' }}</td>
          <td>{{ typeLabel(tx.type) }}</td>
          <td [class.pet-food__amount--minus]="tx.amount < 0">{{ tx.amount > 0 ? '+' : '' }}{{ tx.amount | number:'1.0-1':'de' }}</td>
          <td>{{ tx.cansAfter | number:'1.0-1':'de' }}</td>
          <td>{{ tx.note }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</div>
```

`pet-food.component.scss` (an die Formular-/Karten-Optik der übrigen Seiten angelehnt, kompakt halten):

```scss
.pet-food {
  max-width: 900px;
  margin: 0 auto;
  padding: 1.5rem;

  &__subtitle { color: rgba(255, 255, 255, 0.6); margin-top: 0.25rem; }
  &__hint { opacity: 0.7; }
  &__error { color: #ef5350; }

  &__gauge {
    background: rgba(255, 255, 255, 0.05);
    border-radius: 12px;
    padding: 1.25rem;
    margin: 1.5rem 0;

    &[data-tone='ok'] .pet-food__bar-fill { background: #66bb6a; }
    &[data-tone='warn'] .pet-food__bar-fill { background: #ffca28; }
    &[data-tone='critical'] .pet-food__bar-fill { background: #ef5350; }
  }

  &__bar-track {
    height: 14px;
    border-radius: 7px;
    background: rgba(255, 255, 255, 0.12);
    overflow: hidden;
  }

  &__bar-fill { height: 100%; border-radius: 7px; transition: width 0.4s ease; }

  &__figures {
    display: flex;
    justify-content: space-between;
    margin-top: 0.6rem;
    font-weight: 600;
  }

  &__range { margin-top: 0.25rem; opacity: 0.75; }

  &__actions {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 1rem;
  }

  &__form {
    background: rgba(255, 255, 255, 0.05);
    border-radius: 12px;
    padding: 1rem;
    display: flex;
    flex-direction: column;
    gap: 0.6rem;

    h2 { font-size: 1rem; margin: 0; }
    label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.85rem; }
    input { padding: 0.45rem; border-radius: 6px; border: 1px solid rgba(255, 255, 255, 0.2); background: rgba(0, 0, 0, 0.2); color: inherit; }
    button { align-self: flex-start; padding: 0.5rem 1rem; border-radius: 8px; border: none; cursor: pointer; }
    button:disabled { opacity: 0.5; cursor: default; }
  }

  &__journal {
    margin-top: 2rem;

    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 0.5rem 0.75rem; border-bottom: 1px solid rgba(255, 255, 255, 0.08); }
  }

  &__amount--minus { color: #ef9a9a; }
}
```

**Hinweis:** Die bestehenden Seiten stylen unterschiedlich (kein globales Formular-Framework). Vor dem Feinschliff eine Bestandsseite (z. B. `pages/waste-collection/`) ansehen und Farben/Abstände angleichen; obiges SCSS ist die Basis.

- [ ] **Step 4: Route ergänzen**

In `frontend/src/app/app.routes.ts` nach dem `pets`-Eintrag (Zeile ~221):

```typescript
  {
    path: 'pet-food',
    loadComponent: () => import('./pages/pet-food/pet-food.component').then(m => m.PetFoodComponent),
    canActivate: [authGuard],
    title: 'Futtervorrat - Household Manager'
  },
```

- [ ] **Step 5: Navi-Eintrag ergänzen**

In `frontend/src/app/components/header/header.component.ts` in der `Smart Home`-Gruppe nach `Hundetracker`:

```typescript
        { path: '/pet-food', label: 'Futtervorrat' },
```

- [ ] **Step 6: Tests laufen lassen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: neue Specs grün; nur die 3 Baseline-Fails (`AppComponent` ×2, `HeroComponent`)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/pet-food frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(frontend): Seite Futtervorrat mit Fuellstand, Buchungen und Journal"
```

---

### Task 10: Dashboard-Kachel

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Footer, nach der Haustier-Kachel ~Zeile 382)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (minimal — SCSS-Budget!)

- [ ] **Step 1: Daten laden**

In `dashboard.component.ts`:
- Imports ergänzen: `import { PetFoodService } from '../../services/pet-food.service';` und `import { PetFoodStatus } from '../../models/pet-food.model';` (Router wird bereits importiert oder via `inject(Router)` ergänzen — vorhandene Imports prüfen).
- Felder + Injektion (bei den übrigen Services):

```typescript
  private readonly petFoodService = inject(PetFoodService);
  petFood: PetFoodStatus | null = null;
```

- In `ngOnInit()` (bei den übrigen Loads):

```typescript
    this.petFoodService.getStatus().subscribe({
      next: status => (this.petFood = status),
      error: () => { /* keine Kachel ist besser als eine geratene */ }
    });
```

- Methoden (bei den Pet-Helpern):

```typescript
  petFoodTone(status: PetFoodStatus): 'ok' | 'warn' | 'critical' {
    if (status.cansRemaining < 7) {
      return 'critical';
    }
    return status.percent < 25 ? 'warn' : 'ok';
  }

  openPetFoodPage(): void {
    this.router.navigate(['/pet-food']);
  }
```

Falls `dashboard.component.ts` noch keinen `Router` injiziert: `private readonly router = inject(Router);` mit Import aus `@angular/router` ergänzen; hat die Komponente bereits einen, den vorhandenen nutzen.

- [ ] **Step 2: Kachel-Markup einfügen**

In `dashboard.component.html` direkt nach der schließenden `</div>` der Haustier-Kachel (nach ~Zeile 382), vor `<div class="lumina__modes-area">`:

```html
    <div
      class="lumina-card lumina__petfood"
      *ngIf="petFood as food"
      role="button"
      tabindex="0"
      [attr.data-tone]="petFoodTone(food)"
      (click)="openPetFoodPage()"
      (keydown.enter)="openPetFoodPage()"
      aria-label="Futtervorrat öffnen"
    >
      <div class="lumina__secured-icon">
        <span class="material-symbols-outlined">pet_supplies</span>
      </div>
      <div class="lumina__lock-info">
        <h4 class="lumina__label lumina__label--secondary">Toni-Futter</h4>
        <p class="lumina__secured-detail">
          {{ food.cansRemaining | number:'1.0-1':'de' }} Dosen • {{ food.percent }} % • ~{{ food.daysRemaining }} Tage
        </p>
        <div class="lumina__petfood-track">
          <div class="lumina__petfood-fill" [style.width.%]="food.percent > 100 ? 100 : food.percent"></div>
        </div>
      </div>
    </div>
```

- [ ] **Step 3: Minimales SCSS ergänzen**

In `dashboard.component.scss` (bei den Footer-Styles; bewusst klein, die Datei reißt das `anyComponentStyle`-Budget bereits):

```scss
.lumina__petfood {
  display: flex;
  align-items: center;
  gap: 16px;
  cursor: pointer;

  &[data-tone='ok'] .lumina__petfood-fill { background: #66bb6a; }
  &[data-tone='warn'] .lumina__petfood-fill { background: #ffca28; }
  &[data-tone='critical'] .lumina__petfood-fill { background: #ef5350; }
}

.lumina__petfood-track {
  margin-top: 6px;
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.12);
  overflow: hidden;
}

.lumina__petfood-fill { height: 100%; border-radius: 3px; }
```

- [ ] **Step 4: Build prüfen (Budget!)**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -n 20`
Expected: Build läuft durch. Der bekannte `anyComponentStyle`-Budget-ERROR für `dashboard.component.scss` ist Größenpolizei — er darf nicht NEU auftreten bzw. nicht wachsen, falls er vorher schon da war; im Zweifel SCSS weiter eindampfen (z. B. `lumina__secured`-Klassen wiederverwenden statt neuer).

- [ ] **Step 5: Frontend-Tests laufen lassen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: nur die 3 Baseline-Fails

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(frontend): Futtervorrat-Kachel im Dashboard-Footer"
```

---

### Task 11: Gesamtverifikation

- [ ] **Step 1: Alle Backend-Tests**

Run (Bash): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test`
Expected: nur die 2 bekannten DB-Fails (`HouseholdManagerApplicationTests`, `HealthControllerTest`) — alles andere grün

- [ ] **Step 2: Alle Frontend-Tests**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: nur die 3 Baseline-Fails

- [ ] **Step 3: CLAUDE.md um das Modul ergänzen**

In `CLAUDE.md` unter „Current Features" bzw. als eigener Abschnitt bei den Integrationen einen kurzen Block „Toni-Futtervorrat" ergänzen: Tabellen, Scheduler-Verhalten (Instant-Marke, Nachholen, Klemmen auf 0), Entität `sensor.pet_food_toni_cans`, Rollen (Lesen KIOSK, Schreiben MEMBER), Warnflow-Schwelle 7 = `criticalCans` im Frontend (beide Stellen beim Ändern nachziehen!).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Toni-Futtervorrat in CLAUDE.md dokumentieren"
```

---

## Rollout (nach dem Merge/Deploy, manuell)

1. Backend + Frontend deployen (Liquibase legt Tabellen + Seed an; der erste Scheduler-Lauf setzt die Marke ohne Abzug).
2. Auf der Seite „Futtervorrat" den realen Bestand per Korrektur erfassen.
3. Telegram-Warnflow via flow-mcp anlegen (erst JETZT — vorher existiert die Entität nicht):
   - `flow_node_types` + `flow_list_entities` (Entität `sensor.pet_food_toni_cans` verifizieren)
   - `flow_create`: Trigger `entity-state-trigger` auf `sensor.pet_food_toni_cans`, `operator: "<"`, `value: "7"` → `telegram-send` („Toni-Futter geht zur Neige: nur noch X Dosen." — Platzhalter gemäß Node-Katalog)
   - `flow_deploy` → `flow_set_enabled`
   - Wichtig: KEIN Trigger auf `value: "unavailable"` (feuert nie, dokumentierte Falle) — hier irrelevant, die Entität wird nie `unavailable`.
4. Nach dem ersten 16:00-Abzug im Journal prüfen, dass genau eine Fütterung verbucht wurde (nicht zwei — Doppel-Deployment-Check).
