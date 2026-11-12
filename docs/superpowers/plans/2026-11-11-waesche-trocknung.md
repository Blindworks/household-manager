# Wäschetrocknung im Keller – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Am Wandtablet fragen, ob Wäsche hängt, die Trockenzeit aus Temperatur/Feuchte im Keller schätzen und lernen, im Hub zeigen und „könnte trocken sein“ melden.

**Architecture:** Backend-Paket `laundry/` mit reinen Rechenklassen (Physik, Schätzung, Lernen), einer Zustandsmaschine je Waschgang (Tabelle `laundry_drying_session`), einem Ereignis-Listener für die Auslöser und einem minütlichen Takt. Der Zustand wird als `sensor.laundry_drying` gespiegelt; der Push läuft als Flow auf `maybe_dry`. Frontend: ein Service mit 30-s-Abruf, Hub-Karte im Dashboard und ein eigenständig gestylter Frage-Dialog im Tablet-Modus (Dashboard und Tablet-Shell).

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / MariaDB, Angular 19 standalone, Karma/Jasmine, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-11-11-waesche-trocknung-design.md`

## Global Constraints

- Kellerfühler: `sensor.zigbee_temperatur_aqara_keller_temperature`, `sensor.zigbee_temperatur_aqara_keller_humidity`; Steckdose `switch.meross_2112156531504590863548e1e9817420`; Helfer `input_boolean.manual_waschmaschine_fertig`, `input_boolean.manual_waschmaschine_laeuft` — als `laundry.*`-Properties mit diesen Defaults.
- Schwellen (einzige Definition in `LaundryRules`): Feuchteanstieg 3,0 pp binnen 3 h nach „fertig“; Frage verfällt nach 6 h; Kurvenkriterium ≤ 25 % des Höchstwerts, ≥ 60 min, frühestens 6 h nach Start, nur bei Höchstwert ≥ 3,0 pp; Fühler veraltet nach 60 min; „Noch nicht“ ⇒ erneut nach 2 h; Ende nach 72 h; Startwert K = 17,5 kPa·h; Lernen über die letzten 10 Waschgänge, schwache Proben nur bei < 3 bestätigten; Ruhezeit 22:00–07:00.
- Texte: Frage 1 „Wäsche aufgehängt?“ (Ja / Nein); Frage 2 „Ist die Wäsche trocken?“ (Ja / Noch nicht); Push-Titel „Wäsche“, Text „Die Wäsche im Keller könnte jetzt trocken sein.“; Hub „Wäsche trocknet“ / „Wäsche könnte trocken sein“; pausiert: „Schätzung pausiert – Kellerfühler meldet nichts“.
- Zeit nur über den `Clock`-Bean (Europe/Berlin), Ruhezeit über `common/TimeWindow`.
- JPA-Repositories in `com.household.manager.repository`; je DDL ein Changeset; kein `deviceClass` an `sensor.laundry_drying`.
- Fragen nur im Tablet-Modus (`ViewModeService.isTabletView()`), Hub-Karte in beiden Ansichten.
- Alle Antwort-POSTs müssen KIOSK dürfen; `@Scheduled`-Methoden werfen nie.

## Review Focus

1. Kellerfühler `unavailable` oder still während des Trocknens → keine Trockenleistung, kein „könnte trocken“, Karte „pausiert“ (Test in Task 3).
2. Doppelklick / zwei Geräte beantworten dieselbe Frage → zweite Antwort ändert nichts, 409 (Task 3 + 5).
3. Steckdose wird durch einen Flow ausgeschaltet (z. B. Nachtmodus) → keine Frage (Task 4).
4. Erneute Wäsche („läuft“ wieder an), während ein Waschgang noch trocknet → keine zweite Frage, laufender Waschgang bleibt (Task 4).
5. Hub-Karte antippen, nachdem der Waschgang anderswo beendet wurde → nichts passiert (frischer Abruf, Task 6).

---

### Task 1: Rechenkern (Physik, Schätzung, Lernen)

**Files:**
- Create: `backend/src/main/java/com/household/manager/laundry/DryingPhysics.java`, `LaundryRules.java`, `DryingCalibration.java`, `DryingSample.java`
- Test: `backend/src/test/java/com/household/manager/laundry/DryingPhysicsTest.java`, `DryingCalibrationTest.java`

**Interfaces:**
- Produces:
  - `DryingPhysics.vaporPressureDeficitKpa(double tempC, double relHumidityPercent): double` (Magnus: `es = 0.6112 * exp(17.62*T/(243.12+T))` kPa, `vpd = es * (1 - rh/100)`, nie < 0).
  - `DryingPhysics.remainingHours(double requiredLoad, double accumulatedLoad, double currentVpd): OptionalDouble` (leer bei `currentVpd <= 0`; 0 wenn erreicht).
  - `LaundryRules` – nur `public static final`-Konstanten der Global Constraints (`DEFAULT_REQUIRED_LOAD = 17.5`, `HUMIDITY_RISE_PP = 3.0`, `RISE_WINDOW = Duration.ofHours(3)`, `ASK_EXPIRY = Duration.ofHours(6)`, `CURVE_FRACTION = 0.25`, `CURVE_HOLD = Duration.ofMinutes(60)`, `CURVE_EARLIEST = Duration.ofHours(6)`, `CURVE_MIN_PEAK_PP = 3.0`, `SENSOR_STALE = Duration.ofMinutes(60)`, `REASK_AFTER = Duration.ofHours(2)`, `SESSION_TIMEOUT = Duration.ofHours(72)`, `LEARN_WINDOW = 10`, `MIN_CONFIRMED = 3`, `QUIET_HOURS = new TimeWindow(22:00, 07:00)`).
  - `record DryingSample(SampleKind kind, double load)`; `enum SampleKind { CONFIRMED, WEAK, LOWER_BOUND }`.
  - `DryingCalibration.requiredLoad(List<List<DryingSample>> lastSessionsNewestFirst): double`.

- [ ] **Step 1: Write failing tests**

```java
@Test void vpdBei16GradUnd60ProzentIstEtwa073() {
    assertEquals(0.73, DryingPhysics.vaporPressureDeficitKpa(16, 60), 0.01);
}
@Test void vpdBei100ProzentIstNull() { assertEquals(0.0, DryingPhysics.vaporPressureDeficitKpa(16, 100), 1e-9); }
@Test void restzeitOhneTrockenleistungIstLeer() { assertTrue(DryingPhysics.remainingHours(17.5, 3, 0).isEmpty()); }
@Test void restzeitRechnetLinear() { assertEquals(10.0, DryingPhysics.remainingHours(17.5, 10.2, 0.73).getAsDouble(), 0.1); }

@Test void ohneProbenGiltDerStartwert() { assertEquals(17.5, DryingCalibration.requiredLoad(List.of()), 1e-9); }
@Test void medianDerBestaetigten() {
    assertEquals(12.0, DryingCalibration.requiredLoad(List.of(
        List.of(confirmed(10)), List.of(confirmed(12)), List.of(confirmed(30)))), 1e-9);
}
@Test void schwacheProbenNurUnterDreiBestaetigten() {
    // 2 bestaetigte (10, 12) + schwach 20 -> Median 12; mit 3 bestaetigten zaehlt die schwache nicht
    assertEquals(12.0, DryingCalibration.requiredLoad(List.of(
        List.of(confirmed(10)), List.of(confirmed(12)), List.of(weak(20)))), 1e-9);
    assertEquals(11.0, DryingCalibration.requiredLoad(List.of(
        List.of(confirmed(10)), List.of(confirmed(12)), List.of(confirmed(11)), List.of(weak(40)))), 1e-9);
}
@Test void untergrenzeHebtNurAusWaschgaengenOhneBestaetigung() {
    // Untergrenze 25 im Waschgang ohne Bestaetigung gewinnt, 99 neben einer Bestaetigung zaehlt nicht
    assertEquals(25.0, DryingCalibration.requiredLoad(List.of(
        List.of(confirmed(10)), List.of(lowerBound(20), lowerBound(25)),
        List.of(lowerBound(99), confirmed(12)))), 1e-9);
}
@Test void nurDieLetztenZehnWaschgaenge() { /* 10 x confirmed(10), dann 5 x confirmed(50) dahinter -> 10.0 */ }
```

- [ ] **Step 2: Run** `mvn -q test -Dtest='DryingPhysicsTest,DryingCalibrationTest'` (JAVA_HOME = JDK 21) — Expected: FAIL (Klassen fehlen).
- [ ] **Step 3: Implement** die Klassen. Lernregel: nur die ersten `LEARN_WINDOW` Waschgänge; Basis = Median der CONFIRMED; bei < `MIN_CONFIRMED` CONFIRMED kommen WEAK dazu; je Waschgang ohne CONFIRMED die größte LOWER_BOUND; ist die größte davon > Basis, gilt sie; ohne CONFIRMED/WEAK und ohne Untergrenze `DEFAULT_REQUIRED_LOAD`; nur Untergrenzen ⇒ max(Startwert, größte Untergrenze).
- [ ] **Step 4: Run** dieselben Tests — Expected: PASS.
- [ ] **Step 5: Commit** `feat(laundry): Rechenkern fuer die Trocken-Vorhersage`

### Task 2: Waschgang speichern

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261111-0098-create-laundry-drying-session.xml`; Modify: `db.changelog-master.xml` (Include nach 0097)
- Create: `backend/src/main/java/com/household/manager/laundry/LaundryDryingSession.java` (JPA-Entity), `LaundrySessionStatus.java`, `LaundryTrigger.java`
- Create: `backend/src/main/java/com/household/manager/repository/LaundryDryingSessionRepository.java`
- Modify: `entitystate/EntitySource.java` (+ `LAUNDRY`)

**Interfaces:**
- Produces:
  - `enum LaundrySessionStatus { ASKING, DECLINED, DRYING, MAYBE_DRY, DONE, EXPIRED }` — „offen“ = ASKING, DRYING, MAYBE_DRY.
  - `enum LaundryTrigger { SWITCH_OFF, HUMIDITY_RISE }`
  - Entity-Felder (Spalten snake_case, alle Zeitpunkte `LocalDateTime`, Werte `Double`, nullbar außer id/status/trigger/askedAt): `id, status, trigger, askedAt, startedAt, baselineHumidity, peakExcess, accumulatedLoad, lastAccumulatedAt, curveLowSince, curveCriterionAt, curveCriterionLoad, maybeDryAt, dryQuestionOpen (boolean, default false), dryQuestionAt, lowerBoundLoad, confirmedLoad, weakLoad, endedAt`.
  - Repository: `Optional<LaundryDryingSession> findFirstByStatusInOrderByAskedAtDesc(Collection<LaundrySessionStatus>)`, `List<LaundryDryingSession> findTop10ByStatusInOrderByAskedAtDesc(Collection<LaundrySessionStatus>)` (für DONE/EXPIRED), `Optional<LaundryDryingSession> findFirstByOrderByAskedAtDesc()`.
  - `@Version`-Spalte `version BIGINT` für den Doppelklick-Schutz (Optimistic Locking).

- [ ] **Step 1:** Changeset (eine `createTable`, Kommentar „Wäschetrocknung: ein Waschgang je Zeile“).
- [ ] **Step 2:** Entity, Enums, Repository, `EntitySource.LAUNDRY`.
- [ ] **Step 3: Run** `mvn -q -DskipTests compile` — Expected: BUILD SUCCESS.
- [ ] **Step 4: Commit** `feat(laundry): Tabelle und Entity fuer Waschgaenge`

### Task 3: Zustandsmaschine und Takt

**Files:**
- Create: `backend/src/main/java/com/household/manager/laundry/LaundryProperties.java` (`@ConfigurationProperties("laundry")`: `enabled`, `humidityEntity`, `temperatureEntity`, `switchEntity`, `finishedHelper`, `runningHelper` mit den Defaults aus den Global Constraints)
- Create: `LaundryClimateReader.java`, `LaundryDryingService.java`, `LaundryStateView.java`, `LaundryEntityReporter.java`, `LaundryTickJob.java`
- Modify: `backend/src/main/resources/application.properties` (`laundry.enabled=true`)
- Test: `backend/src/test/java/com/household/manager/laundry/LaundryDryingServiceTest.java` (Mockito-Repository oder In-Memory-Fake, fester `Clock`)

**Interfaces:**
- Consumes: Task 1, Task 2; `EntityStateService.getByEntityId(String)`, `EntityStateService.reportState(EntityStateUpdate)`, `AuditService.record(String, String)`.
- Produces:
  - `record KellerClimate(double tempC, double humidity, LocalDateTime measuredAt)`; `LaundryClimateReader.current(): Optional<KellerClimate>` (leer bei fehlend/`unavailable`/nicht numerisch/älter als `SENSOR_STALE`; `measuredAt` = älterer der beiden `lastUpdated`).
  - `LaundryDryingService`:
    - `void ask(LaundryTrigger trigger, double baselineHumidity)` — nur wenn kein offener Waschgang und `!declinedForCurrentWash()`.
    - `void answerHung(boolean hung)` — ASKING → DRYING (`startedAt` = jetzt) bzw. DECLINED; sonst `IllegalStateException`.
    - `void answerDry(boolean dry)` — nur bei `dryQuestionOpen`; Ja ⇒ DONE, `confirmedLoad = accumulatedLoad`; Noch nicht ⇒ `lowerBoundLoad = max(...)`, `dryQuestionOpen=false`, `dryQuestionAt = jetzt + REASK_AFTER` (in Ruhezeit auf 07:00 verschoben).
    - `void markRemoved()` — DRYING/MAYBE_DRY ⇒ DONE; bei MAYBE_DRY zusätzlich `confirmedLoad = accumulatedLoad`.
    - `void onNewWash()` — merkt „neue Wäsche“: hebt die DECLINED-Sperre auf (Sperre = jüngster Waschgang DECLINED und seit dessen `askedAt` kein `onNewWash`; Zeitpunkt im Speicher genügt).
    - `void tick()` — siehe Step 3; wirft nie.
    - `LaundryStateView currentState()`.
  - `record LaundryStateView(String status /* idle|asking|drying|maybe_dry */, String prompt /* NONE|HUNG|DRY */, LocalDateTime startedAt, LocalDateTime predictedDryAt, boolean estimatePaused, double requiredLoad)`.
  - `LaundryEntityReporter.report(LaundryStateView)` → `sensor.laundry_drying`, State = `status`, Attribute `predictedDryAt` (fehlt ohne Wert), `startedAt`, `estimatePaused`; nur `reportState`, kein `deviceClass`.
  - `LaundryTickJob.run()` — `@Scheduled(fixedRate = 60_000)`, ruft `tick()`, fängt alles.
  - Audit: `laundry.answer.hung`, `laundry.answer.dry`, `laundry.removed` nach Erfolg.

- [ ] **Step 1: Write failing tests** (je ein Test, Namen = Verhalten):
  - `askLegtFrageAnUndMeldetAsking` (Entity-State `asking`, prompt `HUNG`)
  - `zweiteFrageWaehrendOffenemWaschgangWirdIgnoriert`
  - `neinSperrtBisZurNaechstenWaescheUndOnNewWashHebtDieSperreAuf`
  - `frageVerfaelltNachSechsStunden` (tick ⇒ EXPIRED, Status `idle`)
  - `jaStartetTrocknenMitGrundfeuchte`
  - `tickSummiertVpdMalZeit` (60 min bei 16 °C / 60 % ⇒ `accumulatedLoad ≈ 0.73`)
  - `veralteterFuehlerPausiertOhneZaehlen` (`estimatePaused = true`, Load unverändert, kein MAYBE_DRY)
  - `erreichtesKMeldetMaybeDryUndOeffnetFrage`
  - `kurvenkriteriumGreiftNachHoechstwertUndHaltezeit` (Höchstwert 8 pp, dann ≤ 2 pp für 60 min, ≥ 6 h nach Start ⇒ MAYBE_DRY, `curveCriterionAt/Load` gesetzt)
  - `kurvenkriteriumGreiftNichtBeiKleinemAnstieg` (Höchstwert 2 pp ⇒ nur K)
  - `maybeDryInDerRuhezeitWirdAufSiebenUhrVerschoben` (Kriterium um 23:10 ⇒ Status bleibt `drying` bis 07:00, dann `maybe_dry`)
  - `nochNichtFragtNachZweiStundenErneut`
  - `jaTrockenSpeichertBestaetigteProbe`
  - `abgenommenVorMaybeDryIstKeineProbe` / `abgenommenNachMaybeDryZaehltAlsBestaetigt`
  - `nachZweiundsiebzigStundenEndetDerWaschgangMitSchwacherProbe` (`weakLoad = curveCriterionLoad`)
  - `antwortOhnePassendeFrageWirft` (`IllegalStateException`)
  - `predictedDryAtNutztGelerntesK` (Repository liefert 3 DONE mit `confirmedLoad` 10/12/14 ⇒ K = 12)
- [ ] **Step 2: Run** `mvn -q test -Dtest=LaundryDryingServiceTest` — Expected: FAIL.
- [ ] **Step 3: Implement.** `tick()` je offenem Waschgang: ASKING älter als `ASK_EXPIRY` ⇒ EXPIRED. DRYING/MAYBE_DRY: Klima lesen; ohne Klima nur pausieren (`lastAccumulatedAt` = jetzt, damit die Lücke nicht nachgezählt wird). Sonst `accumulatedLoad += vpd * Stunden seit lastAccumulatedAt`; `excess = rh − baseline`, `peakExcess = max`; Kurve: `peakExcess ≥ CURVE_MIN_PEAK_PP && excess ≤ CURVE_FRACTION*peak` ⇒ `curveLowSince` setzen (sonst null), greift wenn `curveLowSince` ≥ `CURVE_HOLD` alt und `startedAt` ≥ `CURVE_EARLIEST` alt. K aus `DryingCalibration` über die letzten DONE/EXPIRED-Waschgänge (Proben: confirmedLoad ⇒ CONFIRMED, weakLoad ⇒ WEAK, lowerBoundLoad ⇒ LOWER_BOUND). DRYING ⇒ MAYBE_DRY nur außerhalb `QUIET_HOURS`, dann `maybeDryAt`, `dryQuestionOpen = true`. MAYBE_DRY mit `dryQuestionAt ≤ jetzt` und außerhalb Ruhezeit ⇒ `dryQuestionOpen = true`. Älter als `SESSION_TIMEOUT` ⇒ EXPIRED, `weakLoad = curveCriterionLoad`. Nach jeder Änderung speichern und `LaundryEntityReporter.report(currentState())`. Prompt: ASKING ⇒ HUNG; MAYBE_DRY mit `dryQuestionOpen` ⇒ DRY; sonst NONE. `predictedDryAt` = jetzt + `remainingHours` (gekappt auf `startedAt + SESSION_TIMEOUT`).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(laundry): Zustandsmaschine und Takt fuer Waschgaenge`

### Task 4: Auslöser

**Files:**
- Create: `backend/src/main/java/com/household/manager/laundry/LaundryTriggerListener.java`
- Test: `backend/src/test/java/com/household/manager/laundry/LaundryTriggerListenerTest.java`

**Interfaces:**
- Consumes: `EntityStateChangedEvent(entityId, oldState, newState, attributes, timestamp)`, `AuditActorContext.get()`, `LaundryClimateReader.current()`, Task-3-Service.
- Produces: `@EventListener void onStateChanged(EntityStateChangedEvent)` — wirft nie.

- [ ] **Step 1: Write failing tests:**
  - `steckdoseAusFragtMitAktuellerKellerfeuchte` (`on→off` auf `switchEntity` ⇒ `ask(SWITCH_OFF, 58.9)`)
  - `steckdoseAusDurchFlowFragtNicht` (`AuditActorContext` = `AuditActor.flow(14)` ⇒ kein `ask`)
  - `steckdoseAusOhneKlimaFragtNicht`
  - `fertigUndAnstiegUmDreiPunkteFragt` (`finishedHelper off→on` bei 58,9 %, dann Feuchte-Event 61,9 % binnen 3 h ⇒ `ask(HUMIDITY_RISE, 58.9)`)
  - `anstiegNachDreiStundenFragtNicht`
  - `anstiegUnterDreiPunktenFragtNicht`
  - `laeuftAnRuftOnNewWashUndVergisstFertig`
- [ ] **Step 2: Run** `mvn -q test -Dtest=LaundryTriggerListenerTest` — Expected: FAIL.
- [ ] **Step 3: Implement** mit festem `Clock`; „fertig“-Zeitpunkt und -Feuchte im Speicher; „durch Flow“ = Aktor-Name beginnt mit `FLOW:`; nur bei `laundry.enabled`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(laundry): Ausloeser fuer die Frage Waesche aufgehaengt`

### Task 5: API und Rechte

**Files:**
- Create: `backend/src/main/java/com/household/manager/laundry/LaundryController.java`, DTOs `LaundryAnswerRequest.java` (`boolean yes`)
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (KIOSK-POST-Whitelist Zeile ~265)
- Modify: `exception/GlobalExceptionHandler` nur falls `IllegalStateException` dort nicht schon 409/400 liefert — Antworten ohne passende Frage und `ObjectOptimisticLockingFailureException` ⇒ **409**
- Test: `LaundryControllerTest.java` (WebMvc), `security/SecurityRulesTest.java`

**Interfaces:**
- `GET /v1/laundry/state` → `LaundryStateView` (KIOSK über generische GET-Regel)
- `POST /v1/laundry/answers/hung` `{yes}`, `POST /v1/laundry/answers/dry` `{yes}`, `POST /v1/laundry/removed` → jeweils neuer `LaundryStateView`
- `GET /v1/laundry/sessions` → letzte 10 abgeschlossene Waschgänge (`askedAt`, `startedAt`, `endedAt`, `status`, `confirmedLoad`, `weakLoad`, `lowerBoundLoad`) plus `requiredLoad` — zur Kontrolle des Gelernten (Spec „über die API lesbar“); Test `sessionsLiefertGelerntenWert`

- [ ] **Step 1: Failing tests:** Controller: `stateLiefertAktuellenZustand`, `antwortOhneFrageGibt409`; Security: `kioskDarfWaescheFragenBeantworten` (alle drei POSTs nicht 403), `kioskDarfWaescheZustandLesen`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='LaundryControllerTest,SecurityRulesTest'` — Expected: FAIL.
- [ ] **Step 3:** Controller + Whitelist-Einträge `"/v1/laundry/answers/*", "/v1/laundry/removed"` mit Kommentar („Fragen am Wandtablet, Spec Wäschetrocknung“).
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(laundry): API fuer Zustand und Antworten`

### Task 6: Hub-Karte

**Files:**
- Create: `frontend/src/app/models/laundry.model.ts`, `frontend/src/app/services/laundry.service.ts`, `frontend/src/app/shared/laundry-insight.util.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/shared/hub-insight.model.ts` (`readonly tapAction?: 'laundry-removed'`), `pages/dashboard/dashboard.component.ts/.html` (+ spec)

**Interfaces:**
- `interface LaundryState { status: 'idle'|'asking'|'drying'|'maybe_dry'; prompt: 'NONE'|'HUNG'|'DRY'; startedAt: string|null; predictedDryAt: string|null; estimatePaused: boolean; requiredLoad: number }`
- `LaundryService`: `getState(): Observable<LaundryState>`, `answerHung(yes: boolean)`, `answerDry(yes: boolean)`, `markRemoved()` (alle `Observable<LaundryState>`).
- `buildLaundryInsight(state: LaundryState | null, now: Date): HubInsight | null`

- [ ] **Step 1: Failing tests** (`laundry-insight.util.spec.ts`): `keineKarteBeiIdleOderAsking`; `trocknetMitSchaetzung` (predicted morgen 14:05 ⇒ Text „Voraussichtlich trocken Morgen gegen 14 Uhr“ über `relativeDayLabel`, Titel „Wäsche trocknet“, `tapAction: 'laundry-removed'`, Ton `primary`); `pausiert` (Text „Schätzung pausiert – Kellerfühler meldet nichts“); `koennteTrockenSein` (Titel „Wäsche könnte trocken sein“, Text „Die Wäsche im Keller könnte jetzt trocken sein.“, Ton `tertiary`). Dashboard-Spec: `zeigtWaeschekarteImHub`, `antippenLaedtFrischUndMeldetAbgenommen`, `antippenNachAnderweitigemEndeTutNichts` (frischer Zustand `idle` ⇒ `markRemoved` nicht aufgerufen).
- [ ] **Step 2: Run** `npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/shared/laundry-insight.util.spec.ts' --include='src/app/pages/dashboard/dashboard.component.spec.ts'` — Expected: FAIL.
- [ ] **Step 3: Implement.** Dashboard: 30-s-Abruf (`interval` + `startWith(0)`, Fehler behalten den letzten Stand), `laundryInsight` in `rebuildInsights()` direkt nach den Maschinenkarten; Karte klickbar, wenn `tapAction` gesetzt (Template-Bedingungen um `|| !!item.tapAction` erweitern); `onInsight` ruft bei `tapAction` erst `getState()`, nur bei `drying`/`maybe_dry` `markRemoved()`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(laundry): Hub-Karte fuer trocknende Waesche`

### Task 7: Frage-Dialog am Wandtablet

**Files:**
- Create: `frontend/src/app/components/laundry-prompt/laundry-prompt.component.ts/.html/.scss/.spec.ts`
- Modify: `pages/dashboard/dashboard.component.html` und `components/tablet-shell/tablet-shell.component.html/.ts` (Komponente innerhalb des `.lumina`-Wurzelelements, nur bei `isTabletView()`)

**Interfaces:**
- `<app-laundry-prompt>` ohne Inputs; holt `LaundryService.getState()` selbst im 30-s-Takt (eigener Abruf; Fehler ⇒ Dialog bleibt wie er ist), zeigt bei `prompt === 'HUNG'` „Wäsche aufgehängt?“ [Ja] [Nein], bei `'DRY'` „Ist die Wäsche trocken?“ [Ja] [Noch nicht]; Knöpfe während der Anfrage gesperrt; Antwort übernimmt den zurückgelieferten Zustand; 409 ⇒ Zustand frisch laden. Farben/Knöpfe nur über lumina-Tokens (`--btn-*`), eigene Styles (Muster `components/dog-mode-screen/`).

- [ ] **Step 1: Failing tests:** `zeigtFrageAufgehaengt`, `jaSendetAnswerHungTrue`, `neinSendetAnswerHungFalse`, `zeigtFrageTrockenMitNochNicht`, `ohneFrageNichtsSichtbar`, `knoepfeGesperrtWaehrendAnfrage`, `konfliktLaedtZustandNeu`; Shell-/Dashboard-Spec: `promptNurImTabletModus`.
- [ ] **Step 2: Run** die Specs — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS; danach volle Frontend-Suite (Baseline: 3 vorbestehende Fails App/Hero laut Memory) und `mvn test` gegen die bekannte Backend-Baseline.
- [ ] **Step 5: Commit** `feat(laundry): Fragen zur Waesche am Wandtablet`

### Task 8: Doku und Rollout-Notiz

**Files:**
- Modify: `CLAUDE.md` (Feature-Liste + neuer Abschnitt „Wäschetrocknung im Keller“: Auslöser, Rechenregel, Lernregel, Ruhezeit, Meross-Einschränkung, Entität, Changeset 0098 nie gegen echte DB gelaufen, Push-Flow nach dem Deploy)

- [ ] **Step 1:** Abschnitt schreiben.
- [ ] **Step 2: Commit** `docs(laundry): Waeschetrocknung in CLAUDE.md`
- [ ] **Step 3 (nach dem PROD-Deploy, nicht Teil des Branches):** Flow „Push, wenn die Wäsche im Keller trocken sein könnte“ (Bereich „Haushaltsgeräte“): `entity-state-trigger` `sensor.laundry_drying == maybe_dry` → `push-send` Titel „Wäsche“, Text „Die Wäsche im Keller könnte jetzt trocken sein.“; create → deploy → enable.
