# Wiederkehrende Zahlungen: selbstständige Erkennung – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wiederkehrende Abbuchungen (vor allem Abos) werden nach jedem Import selbstständig erkannt, nach Händler und Betrag gruppiert, bei Preisänderungen fortgeschrieben und als „beendet“ markiert, wenn sie ausbleiben.

**Architecture:** Drei reine Bausteine im Paket `finance` (Händlerschlüssel, Rhythmus-Analyse, Reihen-Erkennung) liefern erkannte Reihen; `RecurringDetectionService` gleicht sie mit `recurring_payments` ab (fortschreiben statt duplizieren, Verwerfen als Flag). Der Import stößt die Erkennung nach seinem Commit an. Das Frontend zeigt Status und letzte Zahlung.

**Tech Stack:** Spring Boot 3.4 / Java 21 / JPA / Liquibase (MariaDB), JUnit 5 + Mockito; Angular 19 standalone, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-10-wiederkehrende-zahlungen-erkennung-design.md`

## Global Constraints

- Backend-Build mit JDK 21: `JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"`; der Default ist JDK 17. Lokal gibt es keine DB — Tests mit Spring-Kontext (`contextLoads`) sind rot by design; nur die genannten Testklassen laufen lassen.
- Alle JPA-Repositories liegen in `com.household.manager.repository`.
- Liquibase: **ein DDL je Changeset** (MariaDB-Autocommit), Dateien unter `backend/src/main/resources/db/changelog/changes/`, Einträge in `db.changelog-master.xml` hinter `20261110-0093-…`.
- Beträge von Abbuchungen sind in der DB negativ; `expectedAmount` bleibt negativ (Bestandsverhalten, Frontend zeigt ihn so).
- Zeitabhängiges über das `Clock`-Bean (Europe/Berlin), nie `LocalDate.now()` ohne Clock.
- Zahlen aus der Spec verbatim: Betragskette 5 %, Gruppenspanne ≤ 10 %, Doppeltermin ≤ 5 Tage, mindestens 3 Termine, Intervalle 30,4 / 91,3 / 365 Tage, Median-Toleranz 20 %, Abstands-Toleranz ±25 % von L oder 2L, Anteil ≥ 75 %, Zusammenführen/Anhängen bei Abstand ≤ 1,5 × L, beendet wenn heute > letzter Termin + 1,5 × L, Abgleichstoleranz 10 %, Rückblick 2 Jahre, Schlüssel max. 255 Zeichen.
- **Keine echten Kontodaten** in Testressourcen oder Commits; Testdaten sind synthetisch.
- Commit-Messages enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Arbeit auf einem Feature-Branch, nicht auf main.

## Review Focus

1. **Supermarkt mit zufällig gleichem Betrag:** dreimal 4,99 € beim Bäcker in unregelmäßigen Abständen darf keine Reihe ergeben → Test in Task 3 („irregular bakery“).
2. **Doppelbuchung am selben Tag** (zwei 25-€-Sparpläne an denselben Empfänger) darf weder den Betrag verdoppeln noch den Rhythmus brechen → Test in Task 2.
3. **Verworfener Eintrag nach Preiserhöhung** darf nicht neu als Kandidat erscheinen → Test in Task 4.
4. **Fehler in der Erkennung beim Import** darf den Import nicht kippen → Test in Task 5.
5. **Zwei parallele Verträge beim selben Händler** (Drillisch) dürfen nicht zusammengeführt werden, obwohl gleicher Händler und gleiches Intervall → Test in Task 3.

---

### Task 1: Händlerschlüssel

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/RecurringMerchantKey.java`
- Test: `backend/src/test/java/com/household/manager/finance/RecurringMerchantKeyTest.java`

**Interfaces:**
- Consumes: `PaymentIntermediary.detect/matchedToken`, `MerchantNameExtractor.extract(PaymentIntermediary, String)`, `CounterpartyNameNormalizer.normalize(String)`
- Produces: `@Component RecurringMerchantKey` mit `Optional<String> keyFor(String counterpartyName, String purpose)`

- [ ] **Step 1: Failing tests** (Konstruktor mit `new CounterpartyNameNormalizer(), new MerchantNameExtractor()`):
  - `paypalUsesMerchantFromPurpose`: `keyFor("PayPal Europe S.a.r.l. et Cie S.C.A", ". Spotify AB, Ihr Einkauf bei Spotify AB")` → `Optional.of("PAYPAL:" + <extract-Ergebnis für Spotify AB>)`; im Test den erwarteten Händler über `new MerchantNameExtractor().extract(PAYPAL, purpose).get()` bilden, nicht hart kodieren.
  - `paypalWithoutMerchantIsSkipped`: Zweck `". , Ihr Einkauf bei "` → `Optional.empty()`.
  - `plainCounterpartyIsNormalized`: `keyFor("DRILLISCH ONLINE GMBH", "C8645003 U8377 Mobilfunk")` → `"DRILLISCH ONLINE GMBH"`; `keyFor("ANTHROPIC. CLAUDE SUB//ANTHROPIC.COM/US/3", null)` → `"ANTHROPIC. CLAUDE SUB"`.
  - `blankCounterpartyIsSkipped`: `keyFor(null, null)` und `keyFor("   ", "x")` → empty.
  - `keyIsCappedAt255`: Empfänger aus 300 × `"A"` → Schlüssel der Länge 255.
  - `whitespaceInMerchantCollapsed`: Händler `"Foo   Bar"` aus PayPal-Zweck → Schlüssel ohne Mehrfach-Leerzeichen.
- [ ] **Step 2:** `mvn test -Dtest=RecurringMerchantKeyTest` → FAIL (Klasse fehlt).
- [ ] **Step 3:** Implementieren: Intermediär erkannt ⇒ `matchedToken + ":" + merchant` (Großbuchstaben, `\s+` → ein Leerzeichen, trim), sonst `normalizer.normalize`; leer ⇒ empty; danach auf 255 kürzen.
- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(finance): Händlerschlüssel für wiederkehrende Zahlungen`.

### Task 2: Rhythmus-Analyse nach neuen Regeln

**Files:**
- Modify: `backend/src/main/java/com/household/manager/finance/RecurrenceAnalyzer.java`, `RecurrenceResult.java`
- Test: `backend/src/test/java/com/household/manager/finance/RecurrenceAnalyzerTest.java` (bestehende Tests anpassen, wo sie der neuen Regel widersprechen)

**Interfaces:**
- Produces: `Optional<RecurrenceResult> analyze(List<LocalDate> dates, List<BigDecimal> amounts)` (Listen parallel, Reihenfolge beliebig); `RecurrenceResult` bekommt zusätzlich `LocalDate lastSeenDate`. `expectedAmount` = Betrag der jüngsten Buchung (Vorzeichen unverändert). Neue statische Hilfe `public static long intervalDays(RecurrenceInterval)` liefert 30 / 91 / 365 für Folge-Tasks (gerundete Werte reichen für die 1,5-×-Regeln; die Klassifikation selbst nutzt 30,4 / 91,3 / 365).

- [ ] **Step 1: Failing tests:**
  - `bookingWithinFiveDaysJoinsTerm`: 2026-01-01, 02-01, 03-01, 03-04, 04-01, 05-01 (03-04 gehört zum Termin 03-01) → MONTHLY, `lastSeenDate` 2026-05-01, `nextDueDate` 2026-06-01.
  - `skippedMonthIsNoBreak`: 2026-01-01, 02-01, 04-01, 05-01, 06-01 (Abstand 59 ≈ 2L) → MONTHLY.
  - `duplicateWithinFiveDaysDoesNotDoubleAmount`: je Monat zwei Buchungen am selben Tag à −25,00 über 4 Monate → MONTHLY, `expectedAmount` −25,00.
  - `latestAmountWins`: Monatsbeträge −21,96, −21,96, −21,96, −22,50 → `expectedAmount` −22,50.
  - `toleratesQuarterOfGapsOff`: Monatstermine 2026-01-01 bis 08-01 plus eine Nachbuchung am 03-13 (> 5 Tage nach 03-01) → 9 Termine, 8 Abstände, davon 12 und 19 Tage daneben, 6/8 = 75 % → MONTHLY.
  - `rejectsIrregular`: Abstände 4, 20, 18, 7, 14, 7 → empty.
  - `quarterly`: 4 Termine im Abstand 91, 88, 94 → QUARTERLY.
  - `rejectsTooFewOccurrences` (bestehend) bleibt.
- [ ] **Step 2:** `mvn test -Dtest=RecurrenceAnalyzerTest` → FAIL.
- [ ] **Step 3:** Implementieren: Paare nach Datum sortieren; Termine bilden (Buchung ≤ 5 Tage nach dem Beginn des vorigen Termins gehört zu diesem; Termindatum = erstes Datum); < 3 Termine ⇒ empty; Median der Abstände; Intervall, dessen L der Median um höchstens 20 % verfehlt; Anteil der Abstände mit `|g−L| ≤ 0,25L` oder `|g−2L| ≤ 0,25L` ≥ 0,75; `lastSeenDate` = Datum des letzten Termins; nächste Fälligkeit wie bisher per `plusMonths/plusYears`.
- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(finance): toleranter Rhythmus für wiederkehrende Zahlungen`.

### Task 3: Reihen-Erkennung (Betragsgruppen, Zusammenführen, Anhängen)

**Files:**
- Create: `backend/src/main/java/com/household/manager/finance/RecurringSeriesDetector.java`, `RecurringBooking.java` (record), `DetectedSeries.java` (record)
- Test: `backend/src/test/java/com/household/manager/finance/RecurringSeriesDetectorTest.java`

**Interfaces:**
- Consumes: `RecurrenceAnalyzer` (Task 2)
- Produces:
  - `record RecurringBooking(LocalDate date, BigDecimal amount, String merchantKey, Long categoryId)` (amount negativ)
  - `record DetectedSeries(String merchantKey, RecurrenceInterval interval, BigDecimal expectedAmount, LocalDate lastSeenDate, LocalDate nextDueDate, Long categoryId, List<BigDecimal> amounts)` — `amounts` = alle Beträge der Reihe inkl. zusammengeführter/angehängter (für den Abgleich in Task 4)
  - `@Component RecurringSeriesDetector` mit `List<DetectedSeries> detect(List<RecurringBooking> bookings)`

- [ ] **Step 1: Failing tests** (synthetische Daten, Helfer `monthly(key, amount, firstDate, count)` im Test):
  - `splitsParallelContractsByAmount` (Drillisch): „DRILLISCH ONLINE GMBH“ 12× −4,99 am ~28. und 12× −7,99 am ~18. → zwei MONTHLY-Reihen, Beträge −4,99 und −7,99.
  - `paypalMerchantsAreSeparateSeries`: Schlüssel `PAYPAL:SPOTIFY AB` 10× −12,99 monatlich und `PAYPAL:TELEKOM DEUTSCHLAND` 12× −17,00 monatlich plus 30 unregelmäßige Einkäufe unter `PAYPAL:SHELL DEUTSCHLAND` (Beträge 6–20 €) → genau die zwei Reihen, keine für Shell.
  - `irregularBakeryIsNoSeries`: dreimal −4,99 an 2026-01-03, 01-20, 03-30 → keine Reihe.
  - `fluctuatingGroceriesAreNoSeries`: 12 monatliche Termine mit Beträgen zwischen −45 und −60 → keine Reihe (Spanne > 10 %).
  - `priceIncreaseIsMergedIntoOneSeries` (Giganetz): 5× −29,80 Jan–Mai, dann 6× −84,80 Jun–Nov → eine Reihe, `expectedAmount` −84,80, `amounts` enthält −29,80 und −84,80, `lastSeenDate` im November.
  - `freshPriceChangeIsAttached`: 6× −69,00 Mai–Okt, dann 1× −88,00 im November → eine Reihe mit `expectedAmount` −88,00 und `lastSeenDate` im November.
  - `categoryOfLatestBooking`: ältere Buchungen `categoryId` 3, jüngste 7 → Reihe mit 7.
- [ ] **Step 2:** `mvn test -Dtest=RecurringSeriesDetectorTest` → FAIL.
- [ ] **Step 3:** Implementieren, je Händlerschlüssel:
  1. Betragsgruppen: nach `|amount|` aufsteigend, neue Gruppe sobald ein Betrag > 1,05 × vorheriger; Gruppen mit Spanne `(max−min)/median > 0,10` verwerfen.
  2. Gruppen mit ≥ 3 Buchungen durch `RecurrenceAnalyzer`; Treffer sind Reihen, der Rest sind „kleine Gruppen“.
  3. Zusammenführen: Reihen gleichen Intervalls, zeitlich disjunkt, `ersterTermin(jünger) − lastSeen(älter) ≤ 1,5 × intervalDays` ⇒ eine Reihe (Betrag/lastSeen/nextDue der jüngeren, `amounts` vereinigt). Überlappende Reihen (Drillisch) bleiben getrennt.
  4. Anhängen: kleine Gruppe (1–2 Buchungen), deren erste Buchung nach `lastSeen` einer Reihe liegt und höchstens 1,5 × L danach; bei mehreren Kandidaten der mit dem nächstliegenden Betrag ⇒ Betrag, lastSeen und nextDue (lastSeen + Intervall) aus der Gruppe.
  5. `categoryId` = die der jüngsten Buchung der Reihe.
- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(finance): Reihen-Erkennung nach Händler und Betrag`.

### Task 4: Speichern, Abgleichen, Verwerfen, „beendet“

**Files:**
- Create: Changesets `20261110-0094-recurring-last-seen-date.xml` (addColumn `last_seen_date DATE NULL`), `20261110-0095-recurring-dismissed.xml` (addColumn `dismissed BOOLEAN NOT NULL DEFAULT FALSE`), `20261110-0096-recurring-delete-unconfirmed.xml` (`DELETE FROM recurring_payments WHERE confirmed = FALSE`, Rollback leer mit Kommentar)
- Modify: `db.changelog-master.xml`, `model/entity/RecurringPayment.java` (+ `lastSeenDate`, `dismissed`), `dto/RecurringPaymentResponse.java` (+ `LocalDate lastSeenDate`, `boolean ended`), `repository/RecurringPaymentRepository.java`, `service/RecurringDetectionService.java`, `controller/RecurringPaymentController.java`
- Create: `dto/RecurringDetectionResponse.java` (`int created`, `int updated`)
- Test: `backend/src/test/java/com/household/manager/service/RecurringDetectionServiceTest.java` (Mockito)

**Interfaces:**
- Consumes: `RecurringMerchantKey` (T1), `RecurringSeriesDetector`, `DetectedSeries`, `RecurringBooking` (T3), `RecurrenceAnalyzer.intervalDays` (T2), `Clock`
- Produces: `RecurringDetectionResponse detect(Long accountId)`; `list(Boolean confirmed)` ohne verworfene, sortiert aktiv vor beendet, dann `nextDueDate` aufsteigend (null zuletzt); `dismiss(Long id)` statt `delete`; `POST /v1/finance/recurring/detect` liefert `RecurringDetectionResponse`; `DELETE /{id}` ruft `dismiss`. Repository: `List<RecurringPayment> findByDismissedFalse()`, `findByConfirmedAndDismissedFalse(boolean)`; `findByAccountIdAndCounterpartyPatternAndInterval` entfällt.

- [ ] **Step 1: Failing tests** (Clock fix auf 2026-11-10 Europe/Berlin; Detector und MerchantKey gemockt oder echt — echt ist einfacher, Repository gemockt):
  - `createsNewCandidate`: keine Zeilen, eine erkannte Reihe → `save` mit `confirmed=false`, `dismissed=false`, `lastSeenDate` gesetzt; Antwort `created=1, updated=0`.
  - `updatesExistingWithinTenPercent`: gespeicherte Zeile (gleicher Schlüssel/Intervall, `expectedAmount` −29,80, confirmed) und Reihe mit `amounts` [−29,80, −84,80], aktuell −84,80 → dieselbe Zeile auf −84,80 aktualisiert, `confirmed` bleibt true; `created=0, updated=1`.
  - `dismissedStaysDismissedAfterPriceIncrease`: gespeicherte verworfene Zeile −29,80 → wird fortgeschrieben, bleibt `dismissed`, kein neuer Kandidat.
  - `parallelContractsMatchTheirOwnRow`: zwei Zeilen −4,99 und −7,99 desselben Schlüssels, zwei Reihen → jede Reihe aktualisiert ihre eigene Zeile (nächster Betrag gewinnt, jede Zeile höchstens einmal).
  - `listHidesDismissedAndMarksEnded`: Zeile mit `lastSeenDate` 2026-04-02, MONTHLY → `ended=true`; Zeile 2026-10-28 → `ended=false`; verworfene fehlt; Reihenfolge aktiv vor beendet.
  - `dismissSetsFlagInsteadOfDeleting`: `dismiss(id)` → `save` mit `dismissed=true`, kein `deleteById`.
  - `skipsIncomeAndKeyless`: positive Buchungen und Buchungen ohne Schlüssel erreichen den Detector nicht.
- [ ] **Step 2:** `mvn test -Dtest=RecurringDetectionServiceTest` → FAIL.
- [ ] **Step 3:** Implementieren: Buchungen der letzten 2 Jahre (Clock) laden, nur `amount < 0`, `RecurringBooking` mit `keyFor(counterpartyName, purpose)`; Detector; Abgleich gegen `findByAccountId` (inkl. verworfener) nach Spec-Regel (Schlüssel + Intervall gleich, `|stored − a| ≤ 0,10 × |a|` für ein `a` aus `amounts`, bei mehreren der geringste Abstand zu `expectedAmount`, jede Zeile höchstens einmal); `ended` = `lastSeenDate != null && today.isAfter(lastSeenDate.plusDays(round(1,5 × intervalDays)))`.
- [ ] **Step 4:** Test → PASS; zusätzlich `mvn test -Dtest='Recurr*Test,RecurringMerchantKeyTest'`.
- [ ] **Step 5:** Commit `feat(finance): wiederkehrende Zahlungen fortschreiben, verwerfen, beendet`.

### Task 5: Erkennung nach dem Import

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/StatementImportService.java`
- Test: `backend/src/test/java/com/household/manager/service/StatementImportServiceTest.java` (anlegen, falls nicht vorhanden; sonst ergänzen)

**Interfaces:**
- Consumes: `RecurringDetectionService.detect(Long)` (T4)

- [ ] **Step 1: Failing tests:**
  - `detectionRunsAfterCommit`: mit `TransactionSynchronizationManager.initSynchronization()` im Test, `importStatement(...)` aufrufen, danach die registrierten Synchronisationen `afterCommit()` auslösen → `detect(accountId)` genau einmal aufgerufen; vor `afterCommit` kein Aufruf.
  - `detectionFailureDoesNotBreakImport`: `detect` wirft `RuntimeException` → `afterCommit()` wirft nicht.
  - `withoutTransactionDetectionRunsImmediately`: ohne aktive Synchronisation wird `detect` direkt aufgerufen (Fehler ebenfalls geschluckt).
- [ ] **Step 2:** Test → FAIL.
- [ ] **Step 3:** Am Ende von `importStatement` (vor `return`) die Erkennung über `TransactionSynchronizationManager.registerSynchronization` in `afterCommit` anstoßen, bei inaktiver Synchronisation direkt; jeder Fehler wird mit `log.warn(…, e)` geschluckt. `RecurringDetectionService.detect` hat sein eigenes `@Transactional`, läuft also in neuer Transaktion.
- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(finance): wiederkehrende Zahlungen nach jedem Import erkennen`.

### Task 6: Oberfläche

**Files:**
- Modify: `frontend/src/app/models/finance.model.ts` (`RecurringPayment` + `lastSeenDate?: string; ended: boolean`; neues `RecurringDetectionResult { created: number; updated: number }`), `frontend/src/app/services/finance.service.ts` (`detectRecurring` liefert `RecurringDetectionResult`), `frontend/src/app/pages/finance-recurring/finance-recurring.component.{ts,html}`
- Create: `frontend/src/app/shared/recurring-view.util.ts` mit `merchantLabel(pattern: string): string` und `recurringStatusLabel(item: RecurringPayment): string`
- Test: `frontend/src/app/shared/recurring-view.util.spec.ts`, `finance-recurring.component.spec.ts` (anlegen oder ergänzen)

- [ ] **Step 1: Failing tests:**
  - `merchantLabel('PAYPAL:SPOTIFY AB')` → `'SPOTIFY AB (über PayPal)'`; `'AMAZON:FOO'`/`'AMZN:FOO'` → `'FOO (über Amazon)'`; `'DRILLISCH ONLINE GMBH'` unverändert.
  - `recurringStatusLabel`: ended → `'beendet'` (auch wenn confirmed); sonst `'bestätigt'` / `'Kandidat'`.
  - Komponente: nach `detect()` mit Antwort `{created: 2, updated: 5}` zeigt `infoMessage` `'2 neue, 5 aktualisierte Einträge.'`; Tabelle hat Spalte „Letzte Zahlung“; beendete Zeilen tragen die Klasse `ended`; „Bestätigen“ fehlt bei beendeten nicht (bestätigen bleibt möglich).
- [ ] **Step 2:** `npx ng test --watch=false --browsers=ChromeHeadless --include='**/recurring*.spec.ts' --include='**/finance-recurring/**/*.spec.ts'` → FAIL.
- [ ] **Step 3:** Implementieren; Reihenfolge der Liste kommt vom Backend (nicht im Frontend umsortieren); beendete Zeilen gedämpft (`.ended { opacity: .6 }` in der Komponenten-SCSS).
- [ ] **Step 4:** Test → PASS.
- [ ] **Step 5:** Commit `feat(finance): Status und letzte Zahlung bei wiederkehrenden Zahlungen`.

### Task 7: Dokumentation

**Files:**
- Modify: `CLAUDE.md` — neuer Abschnitt „Wiederkehrende Zahlungen (Erkennung)“ unter den Finanz-Regeln: Händlerschlüssel, Betragsgruppen (5 %/10 %), Rhythmusregeln, Zusammenführen/Anhängen, „beendet“ berechnet statt gespeichert, Verwerfen = Flag, Erkennung nach Commit des Imports, Changesets 0094–0096 nie gegen echte DB gelaufen (0096 löscht unbestätigte Kandidaten → Backup), bekannte Grenzen aus der Spec (Claude-Namenswechsel, Sparpläne als eine Reihe, Kursschwankung > 10 %).
- [ ] **Step 1:** Abschnitt schreiben.
- [ ] **Step 2:** Commit `docs: Erkennung wiederkehrender Zahlungen in CLAUDE.md`.
