# VomiSan-Vorrat: Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Toni-Futtervorrat wird zu einem generischen Vorratsmodul mit einer Zeile je Artikel; VomiSan-Tabletten kommen als zweiter Vorrat dazu (1 Tablette je Fütterung, ganze Stück).

**Architecture:** Eine Tabelle `pet_supply` (aus `pet_food_stock` umbenannt) hält je Artikel Bestand, Ziel, Einheit, Verbrauch je Fütterung, Eingaberaster und eine **eigene** Abzugsmarke. `PetSupplyService` iteriert im minütlichen Scheduler über alle Vorräte. Die Entity-Id wird als `sensor.pet_food_<supply_key>` abgeleitet, wodurch die bestehende Futter-Id buchstäblich erhalten bleibt. Im Frontend rendern Seite, Dashboard-Kachel und Tablet-Ansicht aus einer Liste statt aus einem einzelnen Status.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / MariaDB; Angular 19 standalone / SCSS / Karma-Jasmine.

**Spec:** `docs/superpowers/specs/2026-08-28-vomisan-vorrat-design.md`

---

## Dateiübersicht

**Backend — neu:**
- `backend/src/main/resources/db/changelog/changes/20260828-0047-generalize-pet-supplies.xml`
- `backend/src/main/java/com/household/manager/model/entity/PetSupply.java`
- `backend/src/main/java/com/household/manager/model/entity/PetSupplyTransaction.java`
- `backend/src/main/java/com/household/manager/repository/PetSupplyRepository.java`
- `backend/src/main/java/com/household/manager/repository/PetSupplyTransactionRepository.java`
- `backend/src/main/java/com/household/manager/petsupply/{PetSupplyService,PetSupplyController,PetSupplyDtos,PetSupplyFeedingScheduler,FeedingSchedule}.java`
- `backend/src/test/java/com/household/manager/petsupply/PetSupplyServiceTest.java`

**Backend — gelöscht:** das Paket `petfood`, `PetFoodStock`, `PetFoodTransaction`, beide alten Repositories, `PetFoodServiceTest`.

**Backend — geändert:** `db/changelog/db.changelog-master.xml`, `security/SecurityRulesTest.java`.

**Frontend — neu:** `models/pet-supply.model.ts`, `services/pet-supply.service.ts`, `shared/pet-supply-level.util.ts` (+ Spec).

**Frontend — gelöscht:** `models/pet-food.model.ts`, `services/pet-food.service.ts`, `shared/pet-food-level.util.ts` (+ Spec).

**Frontend — geändert:** `pages/pet-food/*` (Komponente bleibt unter dieser Route), `pages/dashboard/dashboard.component.{ts,html}`, `pages/tablet-toni/tablet-toni.component.{ts,html,scss}`.

---

### Task 1: Datenmodell und Migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260828-0047-generalize-pet-supplies.xml`
- Create: `backend/src/main/java/com/household/manager/model/entity/PetSupply.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/PetSupplyTransaction.java`
- Create: `backend/src/main/java/com/household/manager/repository/PetSupplyRepository.java`
- Create: `backend/src/main/java/com/household/manager/repository/PetSupplyTransactionRepository.java`
- Delete: `.../entity/PetFoodStock.java`, `.../entity/PetFoodTransaction.java`, `.../repository/PetFoodStockRepository.java`, `.../repository/PetFoodTransactionRepository.java`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Changelog schreiben — je DDL ein eigenes Changeset**

MariaDB committet jedes DDL implizit; ein gebündeltes Changeset stünde nach einem Abbruch halb angewendet in der Tabelle, aber nicht in `DATABASECHANGELOG`. Reihenfolge:

1. `renameTable` `pet_food_stock` → `pet_supply`
2. `renameColumn` `cans_remaining` → `amount_remaining`, `target_cans` → `target_amount` (zwei Changesets, `columnDataType="DECIMAL(6,1)"`)
3. `addColumn` `supply_key VARCHAR(50)`, `name VARCHAR(100)`, `unit VARCHAR(20)`, `per_feeding DECIMAL(6,1)`, `step_size DECIMAL(6,1)`, `display_order INT` — alle zunächst nullable
4. `update` der Bestandszeile (`id=1`): `supply_key='toni_cans'`, `name='Futtervorrat'`, `unit='Dosen'`, `per_feeding=0.5`, `step_size=0.5`, `display_order=1`
5. `addNotNullConstraint` auf die fünf Pflichtspalten, dann `addUniqueConstraint` auf `supply_key`
6. `renameTable` `pet_food_transaction` → `pet_supply_transaction`, `renameColumn` `cans_after` → `amount_after`
7. `addColumn` `supply_id BIGINT` nullable → `update` alle Zeilen auf `1` → `addNotNullConstraint` → `addForeignKeyConstraint` mit `onDelete="CASCADE"` → `createIndex` `idx_pet_supply_tx_supply_occurred` auf `(supply_id, occurred_at)`
8. `insert` der VomiSan-Zeile: `id=2`, `amount_remaining=0`, `target_amount=60`, `supply_key='toni_vomisan'`, `name='VomiSan-Tabletten'`, `unit='Tabletten'`, `per_feeding=1`, `step_size=1`, `display_order=2`, `deduction_marker` bleibt NULL

Die Spalte heißt `step_size`, nicht `step` — `step` ist in MariaDB kein Schlüsselwort, aber `step_size` erspart Diskussionen mit künftigen Reserved Words und liest sich in SQL eindeutiger.

- [ ] **Step 2: Changelog in `db.changelog-master.xml` einhängen**

Neue `<include file="db/changelog/changes/20260828-0047-generalize-pet-supplies.xml"/>`-Zeile ans Ende, dem bestehenden Muster folgend.

- [ ] **Step 3: Entitäten schreiben**

`PetSupply` ersetzt `PetFoodStock`. Kein `SINGLETON_ID` mehr; Id ist `@GeneratedValue(IDENTITY)`. Felder: `supplyKey`, `name`, `unit`, `amountRemaining`, `targetAmount`, `perFeeding`, `stepSize`, `displayOrder`, `deductionMarker` (Instant), `updatedAt` mit `@PrePersist/@PreUpdate`.

Der Klassenkommentar von `PetFoodStock` zur Instant-Marke (Oktober-Zeitumstellung, MariaDB-Rundung von DATETIME-Bruchsekunden) wird **übernommen** — er beschreibt weiterhin geltende Fallen. Ergänzt um: die Marke ist je Vorrat eigen, damit ein neuer Vorrat nicht rückwirkend leergebucht wird.

`PetSupplyTransaction` ersetzt `PetFoodTransaction`: identisch, aber `amountAfter` statt `cansAfter` und ein `@ManyToOne(fetch = LAZY) @JoinColumn(name = "supply_id", nullable = false) private PetSupply supply;`.

- [ ] **Step 4: Repositories schreiben**

```java
public interface PetSupplyRepository extends JpaRepository<PetSupply, Long> {
    List<PetSupply> findAllByOrderByDisplayOrderAscIdAsc();
    Optional<PetSupply> findBySupplyKey(String supplyKey);
}

public interface PetSupplyTransactionRepository extends JpaRepository<PetSupplyTransaction, Long> {
    List<PetSupplyTransaction> findBySupplyOrderByOccurredAtDescIdDesc(PetSupply supply, Pageable pageable);
}
```

Beide liegen in `com.household.manager.repository` — `JpaConfig` schränkt das Scanning auf dieses Paket ein.

- [ ] **Step 5: Alte Entitäten und Repositories löschen**

- [ ] **Step 6: Kompilieren**

Run: `mvn -q -pl backend compile` (mit `JAVA_HOME` auf jdk-21).
Expected: Fehler nur noch im Paket `petfood`, das Task 2 ersetzt.

---

### Task 2: PetSupplyService

**Files:**
- Create: `backend/src/main/java/com/household/manager/petsupply/{FeedingSchedule,PetSupplyService,PetSupplyFeedingScheduler}.java`
- Delete: `backend/src/main/java/com/household/manager/petfood/*`
- Test: `backend/src/test/java/com/household/manager/petsupply/PetSupplyServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`PetSupplyServiceTest` (Mockito, `Clock.fixed`, Zone Europe/Berlin) mit diesen Fällen:

```java
@Test
void ersterLaufEinesNeuenVorratsZiehtNichtsAb() {
    // supply mit deductionMarker == null, Bestand 30
    // applyDueFeedings()
    // -> Marke gesetzt, Bestand unveraendert 30, keine Transaktion
}

@Test
void zwoelfStundenSpaeterZiehtJederVorratSeineEigeneMengeAb() {
    // Futter (perFeeding 0.5) und VomiSan (perFeeding 1), Marke 06:00, jetzt 17:00
    // -> zwei Fuetterungen (07:00, 16:00): Futter -1.0, VomiSan -2
}

@Test
void einFehlerAnEinemVorratStopptDieAnderenNicht() {
    // Repository wirft beim ersten Vorrat -> zweiter wird trotzdem verbucht
}

@Test
void tablettenRasterLehntHalbeStueckeAb() {
    // recordPurchase("toni_vomisan", 2.5) -> IllegalArgumentException
}

@Test
void dosenRasterErlaubtHalbeDosen() {
    // recordPurchase("toni_cans", 2.5) -> ok
}

@Test
void reichweiteRechnetMitDemVerbrauchDesVorrats() {
    // Futter 7.0 / (0.5*2) = 7 Tage; VomiSan 7 / (1*2) = 3 Tage
}

@Test
void entityIdDesFuttersBleibtUnveraendert() {
    // captor auf EntityStateUpdate -> "sensor.pet_food_toni_cans"
}

@Test
void unbekannterSchluesselWirftResourceNotFound() {
    // recordPurchase("gibtsnicht", 1) -> ResourceNotFoundException
}
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag prüfen**

Run: `mvn -pl backend test -Dtest=PetSupplyServiceTest`
Expected: Kompilierfehler bzw. FAIL — `PetSupplyService` existiert noch nicht.

- [ ] **Step 3: `FeedingSchedule` unverändert nach `petsupply` verschieben**

Die Klasse bleibt inhaltlich gleich (7:00/16:00, Instant-Fenster). Nur `package` ändert sich.

- [ ] **Step 4: `PetSupplyService` schreiben**

Kernpunkte gegenüber dem alten `PetFoodService`:

```java
@Transactional
public void applyDueFeedings() {
    Instant now = clock.instant();
    for (PetSupply supply : supplyRepository.findAllByOrderByDisplayOrderAscIdAsc()) {
        try {
            applyDueFeedings(supply, now);
        } catch (Exception ex) {
            // Ein kaputter Vorrat darf die anderen nicht mitreissen (Muster
            // TractivePollingService: Fehler je Objekt isoliert).
            log.error("Vorrat {}: Fuetterungsabzug fehlgeschlagen", supply.getSupplyKey(), ex);
        }
    }
}
```

`applyDueFeedings(supply, now)` enthält die bisherige Logik unverändert, aber je Vorrat: NULL-Marke ⇒ nur Marke setzen und spiegeln; `now.isBefore(marker)` ⇒ Lauf überspringen und warnen (Uhr-Rücksprung darf nichts doppelt abziehen); sonst je fälligem Zeitpunkt `supply.getPerFeeding()` abziehen, am Bestand gekappt, Journalzeile schreiben; Marke auf `now.truncatedTo(SECONDS)`.

Die Rasterprüfung wird zur Instanzmethode:

```java
private static void requireStep(BigDecimal value, BigDecimal step, String field) {
    if (value.remainder(step).compareTo(BigDecimal.ZERO) != 0) {
        throw new IllegalArgumentException(
                "Feld '" + field + "' muss ein Vielfaches von " + step.stripTrailingZeros().toPlainString() + " sein.");
    }
}
```

Reichweite und Spiegelung:

```java
private int daysRemaining(PetSupply supply) {
    BigDecimal perDay = perDay(supply);
    return perDay.signum() <= 0 ? 0
            : supply.getAmountRemaining().divide(perDay, 0, RoundingMode.FLOOR).intValue();
}

private static BigDecimal perDay(PetSupply supply) {
    return supply.getPerFeeding().multiply(BigDecimal.valueOf(FeedingSchedule.FEEDING_TIMES.size()));
}

static String entityId(PetSupply supply) {
    // Praefix bleibt pet_food: der Schluessel toni_cans ergibt exakt die
    // bestehende Id sensor.pet_food_toni_cans, ein Umbenennen wuerde jeden
    // darauf gebauten Flow still ins Leere laufen lassen.
    return "sensor.pet_food_" + supply.getSupplyKey();
}
```

Attribute der Spiegelung: `targetAmount`, `percent`, `unit`, `daysRemaining`, `perDay`. `sourceRef` = `supplyKey`, `friendlyName` = `name`.

Auflösung des Vorrats:

```java
private PetSupply requireSupply(String supplyKey) {
    return supplyRepository.findBySupplyKey(supplyKey).orElseThrow(
            () -> new ResourceNotFoundException("Unbekannter Vorrat: " + supplyKey));
}
```

Audit-Aktionen behalten ihre Namen und tragen den Vorrat im Detailtext, z. B.
`auditService.record("petfood.purchase", supply.getName() + ": " + amount + " " + supply.getUnit() + " zugebucht")`.

Der Klassenkommentar zum bewusst fehlenden `@Version` wird übernommen.

- [ ] **Step 5: Scheduler umbenennen**

`PetSupplyFeedingScheduler` ist der alte `PetFoodFeedingScheduler` mit neuem Service-Typ; `fixedDelayString` bleibt `${petfood.feeding.check-interval-ms:60000}`, damit eine gesetzte Umgebungsvariable nicht still wirkungslos wird.

- [ ] **Step 6: Altes Paket löschen, Tests laufen lassen**

Run: `mvn -pl backend test -Dtest=PetSupplyServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main backend/src/test
git commit -m "feat(petsupply): generischer Vorrat als Basis fuer Futter und Tabletten"
```

---

### Task 3: API und Security-Test

**Files:**
- Create: `backend/src/main/java/com/household/manager/petsupply/{PetSupplyController,PetSupplyDtos}.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java:337-375`

- [ ] **Step 1: DTOs schreiben**

```java
public record SupplyResponse(String key, String name, String unit,
        BigDecimal amountRemaining, BigDecimal targetAmount, BigDecimal step,
        BigDecimal perDay, int percent, int daysRemaining) {}

public record TransactionResponse(LocalDateTime occurredAt, PetSupplyTransaction.Type type,
        BigDecimal amount, BigDecimal amountAfter, String note) {}

public record PurchaseRequest(BigDecimal amount, String note) {}
public record CorrectionRequest(BigDecimal amountRemaining, String note) {}
public record TargetRequest(BigDecimal targetAmount) {}
```

- [ ] **Step 2: Controller schreiben**

`@RequestMapping("/v1/pet-supplies")`, Methoden `GET /`, `GET /{key}/transactions`, `POST /{key}/purchases`, `POST /{key}/corrections`, `PUT /{key}/target`. Die Schreibmethoden geben den aktualisierten `SupplyResponse` zurück. Klassenkommentar wie bisher: Lesen fällt unter die generische GET-KIOSK-Regel, Schreiben unter `anyRequest` → MEMBER, bewusst keine eigene Zeile in `SecurityConfig`.

- [ ] **Step 3: `SecurityRulesTest` auf die neuen Pfade ziehen**

Die fünf bestehenden Zeilen bekommen die neuen URLs und Bodies:
`get("/v1/pet-supplies")` als KIOSK ⇒ 200; `post("/v1/pet-supplies/toni_cans/purchases")` als KIOSK ⇒ 403, als MEMBER ⇒ 200; dasselbe für `corrections` und `put(".../target")`. Body-Felder heißen jetzt `amount`, `amountRemaining`, `targetAmount`.

- [ ] **Step 4: Tests laufen lassen**

Run: `mvn -pl backend test -Dtest=SecurityRulesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(petsupply): API unter /v1/pet-supplies mit Vorratsschluessel"
```

---

### Task 4: Frontend-Grundlagen (Model, Service, Util)

**Files:**
- Create: `frontend/src/app/models/pet-supply.model.ts`, `frontend/src/app/services/pet-supply.service.ts`, `frontend/src/app/shared/pet-supply-level.util.ts`
- Create: `frontend/src/app/shared/pet-supply-level.util.spec.ts`
- Delete: die drei `pet-food`-Pendants inkl. `pet-food-level.util.spec.ts`

- [ ] **Step 1: Failing Test für die Util schreiben**

```ts
describe('petSupplyTone', () => {
  it('warnt kritisch unter sieben Tagen Reichweite', () => {
    expect(petSupplyTone({ daysRemaining: 6, percent: 90 })).toBe('critical');
  });

  it('bleibt ok bei genau sieben Tagen', () => {
    expect(petSupplyTone({ daysRemaining: 7, percent: 90 })).toBe('ok');
  });

  it('warnt unter 25 Prozent trotz ausreichender Reichweite', () => {
    expect(petSupplyTone({ daysRemaining: 20, percent: 20 })).toBe('warn');
  });

  it('klemmt die Balkenbreite auf 0..100', () => {
    expect(petSupplyBarWidth(140)).toBe(100);
    expect(petSupplyBarWidth(-5)).toBe(0);
  });
});
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/pet-supply-level.util.spec.ts'`
Expected: FAIL — Modul nicht gefunden.

- [ ] **Step 3: Util schreiben**

```ts
export type PetSupplyTone = 'ok' | 'warn' | 'critical';

/**
 * Einzige Definition der Warnschwelle im Frontend. Sie ist bewusst eine
 * REICHWEITE, keine Stueckzahl: Dosen und Tabletten haben verschiedene
 * Tagesverbraeuche, eine gemeinsame Stueckzahl waere fuer einen der beiden
 * Vorraete falsch. Fuer den Futtervorrat ist das die bisherige Schwelle -
 * 7 Dosen sind bei einer Dose pro Tag genau 7 Tage.
 *
 * Der Telegram-Warnflow traegt dieselbe Zahl ein zweites Mal (Bedingung auf
 * das Attribut daysRemaining). Er lebt in der Flow-Engine und ist von hier
 * aus nicht erreichbar - wer diese Konstante aendert, muss ihn nachziehen.
 */
export const PET_SUPPLY_CRITICAL_DAYS = 7;
export const PET_SUPPLY_WARN_PERCENT = 25;

export function petSupplyTone(supply: { daysRemaining: number; percent: number }): PetSupplyTone {
  if (supply.daysRemaining < PET_SUPPLY_CRITICAL_DAYS) {
    return 'critical';
  }
  return supply.percent < PET_SUPPLY_WARN_PERCENT ? 'warn' : 'ok';
}

export function petSupplyBarWidth(percent: number): number {
  return Math.max(0, Math.min(100, percent));
}
```

- [ ] **Step 4: Model und Service schreiben**

```ts
export interface PetSupply {
  key: string;
  name: string;
  unit: string;
  amountRemaining: number;
  targetAmount: number;
  step: number;
  perDay: number;
  percent: number;
  daysRemaining: number;
}

export type PetSupplyTransactionType = 'FEEDING' | 'PURCHASE' | 'CORRECTION';

export interface PetSupplyTransaction {
  occurredAt: string;
  type: PetSupplyTransactionType;
  amount: number;
  amountAfter: number;
  note: string | null;
}
```

`PetSupplyService` mit `baseUrl = '/api/v1/pet-supplies'` und den Methoden `getSupplies()`, `getTransactions(key, limit = 50)`, `recordPurchase(key, amount, note?)`, `correctStock(key, amountRemaining, note?)`, `updateTarget(key, targetAmount)`. Fehlerbehandlung wie bisher (`catchError` → `Error` mit Servermeldung).

- [ ] **Step 5: Test laufen lassen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/pet-supply-level.util.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models frontend/src/app/services frontend/src/app/shared
git commit -m "feat(petsupply): Frontend-Model, Service und Reichweiten-Schwelle"
```

---

### Task 5: Seite /pet-food auf mehrere Vorräte

**Files:**
- Modify: `frontend/src/app/pages/pet-food/pet-food.component.{ts,html,scss,spec.ts}`

- [ ] **Step 1: Komponente auf eine Liste umstellen**

Statt `status`/`transactions` hält die Komponente `supplies: PetSupply[]` und `transactions: Record<string, PetSupplyTransaction[]>`, dazu je Vorrat ein Formularzustand:

```ts
interface SupplyForm {
  purchaseAmount: number | null;
  purchaseNote: string;
  correctionAmount: number | null;
  correctionNote: string;
  targetAmount: number | null;
}
```

`forms: Record<string, SupplyForm>` wird beim Laden aus der Liste aufgebaut. `submitPurchase(key)`, `submitCorrection(key)`, `submitTarget(key)` arbeiten gegen den Schlüssel; nach Erfolg wird der zurückgegebene Vorrat **in der Liste ersetzt** (nicht die ganze Liste neu geladen) und nur dessen Journal nachgezogen.

`fillTone(supply)` und `barWidth(supply)` delegieren an die Util. Der Fehlertext ist je Vorrat (`errors: Record<string, string | null>`), damit ein Fehler beim Futter die Tablettenkarte nicht mit einer fremden Meldung überzieht.

- [ ] **Step 2: Template auf `@for` über die Vorräte umbauen**

Überschrift „Toni-Vorräte", Untertitel nennt keine Artikel mehr. Je Vorrat eine `<section class="pet-food__supply">` mit Name als `<h2>`, Füllstandsbalken, Zahlen (`{{ s.amountRemaining }} von {{ s.targetAmount }} {{ s.unit }}`), Reichweite, den drei Formularen und der Historientabelle darunter.

Die Zahlenfelder übernehmen Raster und Einheit aus dem Vorrat:

```html
<input type="number" [name]="'purchase-' + s.key"
       [(ngModel)]="forms[s.key].purchaseAmount"
       [min]="s.step" [step]="s.step" required>
```

Die Korrektur nutzt `min="0"`, das Ziel `[min]="s.step"`.

- [ ] **Step 3: Spec anpassen**

Der bestehende Spec wird auf die Liste umgeschrieben: `getSupplies` liefert zwei Vorräte, der Test prüft, dass zwei Karten gerendert werden und das Tabletten-Eingabefeld `step="1"` trägt, das Futterfeld `step="0.5"`.

- [ ] **Step 4: Tests laufen lassen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/pet-food.component.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/pet-food
git commit -m "feat(petsupply): Seite zeigt eine Karte je Vorrat"
```

---

### Task 6: Dashboard-Kachel und -Dialog

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts:382-392,1505-1590`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html:402-425,949-1015`

- [ ] **Step 1: Zustand auf die Liste umstellen**

`petFood: PetFoodStatus | null` wird zu `petSupplies: PetSupply[] = []`. Der 10-Minuten-Refresh lädt die Liste; ein fehlgeschlagener Refresh behält wie bisher den letzten Stand, ein fehlgeschlagener Erstabruf lässt die Kacheln weg.

Der Dialog bekommt den Vorrat als Parameter: `petSupplyDialogKey: string | null`, dazu ein Getter `get petSupplyDialogSupply(): PetSupply | null`, der den Schlüssel **gegen die aktuelle Liste** auflöst — derselbe Grund wie bei `confirmToggle`: ein Hintergrund-Refresh bei offenem Dialog darf nicht auf einem veralteten Objekt buchen.

`openPetSupplyDialog(supply)`, `closePetSupplyDialog()`, `submitPetSupplyPurchase()`, `submitPetSupplyCorrection()` arbeiten über diesen Schlüssel.

- [ ] **Step 2: Kachel-Markup zu einer `*ngFor`-Schleife machen**

Die bestehende Kachel wird zu `<div class="lumina-card lumina__petfood" *ngFor="let supply of petSupplies" ...>` mit `[attr.data-tone]="petSupplyTone(supply)"`, Titel `{{ supply.name }}` und Detailzeile `{{ supply.amountRemaining }} {{ supply.unit }} • {{ supply.percent }} % • ~{{ supply.daysRemaining }} Tage`. Das Icon `pet_supplies` bleibt für beide.

Markup und Styles bleiben **in `dashboard.component.html`/`.scss`** — die `lumina`-Klassen sind dort gekapselt und griffen in einer Kind-Komponente lautlos nicht.

- [ ] **Step 3: Dialog auf den gewählten Vorrat umschreiben**

Ein Dialog statt zweier Kopien: Titel `{{ supply.name }} erfassen`, Eingabefelder mit `[min]="supply.step" [step]="supply.step"`, Einheit im Label. Der Link unten führt weiterhin auf `/pet-food`.

- [ ] **Step 4: Kompilieren und Dashboard-Spec laufen lassen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'`
Expected: PASS (Baseline-Fails der App/Hero-Specs sind davon unberührt).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(petsupply): Dashboard zeigt eine Kachel je Vorrat"
```

---

### Task 7: Tablet-Ansicht /tablet/toni

**Files:**
- Modify: `frontend/src/app/pages/tablet-toni/tablet-toni.component.{ts,html,scss}`

- [ ] **Step 1: Komponente auf die Liste umstellen**

`food: PetFoodStatus | null` wird zu `supplies: PetSupply[] = []`; `foodTone` wird zu `supplyTone(supply)`, `foodBarWidth` zu `supplyBarWidth(supply)`. Der Kartenton der ganzen Kachel richtet sich nach dem **schlechtesten** Vorrat, damit ein leerer Tablettenvorrat nicht hinter einem vollen Futterlager verschwindet:

```ts
get suppliesTone(): PetSupplyTone {
  if (this.supplies.some(s => petSupplyTone(s) === 'critical')) return 'critical';
  if (this.supplies.some(s => petSupplyTone(s) === 'warn')) return 'warn';
  return 'ok';
}
```

- [ ] **Step 2: Kachel um den zweiten Balken erweitern**

Die Kachel heißt „Vorräte" und rendert je Vorrat eine Zeile mit Zahl, Einheit, Balken und Reichweite. Das 2×2-Raster bleibt unangetastet — ein fünfter Kasten würde es sprengen, und der Höhenketten-Test misst genau diese Aufteilung.

- [ ] **Step 3: SCSS für zwei Zeilen in einer Kachel**

Die bestehende `--food`-Kachel bekommt einen inneren Flex-Container mit `gap`; Schriftgrößen der Zahl leicht reduziert, damit zwei Zeilen ohne Scrollen passen.

- [ ] **Step 4: Tests laufen lassen — besonders die Höhenkette**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/tablet-toni.component.spec.ts'`
Expected: PASS, inklusive des Höhenketten-Tests bei 900 und 1200 px.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/tablet-toni
git commit -m "feat(petsupply): Tablet-Kachel zeigt Futter und Tabletten"
```

---

### Task 8: Gesamtlauf und Dokumentation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Toni-Futtervorrat")

- [ ] **Step 1: Backend-Gesamtlauf**

Run: `mvn -pl backend test`
Expected: nur die bekannten Baseline-Fehlschläge (DB-abhängige Tests lokal).

- [ ] **Step 2: Frontend-Gesamtlauf**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: die drei bekannten Baseline-Fails (App/Hero), sonst grün.

- [ ] **Step 3: CLAUDE.md nachziehen**

Der Abschnitt „Toni-Futtervorrat" wird zu „Toni-Vorräte (Futter und Tabletten)": generisches Datenmodell, abgeleitete Entity-Id mit der bewahrten Futter-Id, eigene Marke je Vorrat, Raster je Vorrat, Warnschwelle als Reichweite (jetzt zwei statt drei Fundstellen), neue API-Pfade, Rollout-Reihenfolge.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(petsupply): CLAUDE.md auf den generischen Vorrat gezogen"
```
