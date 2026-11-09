# Finanz-Regeln mit UND-Bedingungen – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kategorisierungs-Regeln bekommen 1–5 UND-Bedingungen, der Vorschlag nach einer Handzuordnung erkennt PayPal/Amazon, und jede Regeländerung bewertet die nicht von Hand eingeordneten Buchungen nach einer Vorschau neu.

**Architecture:** Bedingungen liegen in einer eigenen Tabelle (`categorization_rule_condition`), die Regel hält sie als geordnete Kind-Liste. Eine einzige Ordnung (`RuleOrdering`) und ein einziger Auswerter (`RuleMatcher`) werden von Import, Neubewertung, Vorschau und Vorschlag geteilt. Neubewertung rechnet auf einem hypothetischen Regelsatz im Speicher (Vorschau) bzw. auf dem gespeicherten (Anwenden). Im Frontend ein gemeinsamer Bedingungs-Editor und ein Vorschau-Dialog, eingebaut in Regel- und Buchungsseite.

**Tech Stack:** Spring Boot 3.4 / Java 21 / JPA / Liquibase (MariaDB), JUnit 5 + Mockito; Angular 19 standalone, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-09-finanz-regeln-und-bedingungen-design.md`

## Global Constraints

- Eine Regel hat **1 bis 5** Bedingungen; alle müssen zutreffen (UND).
- Bedingung = `field` (`COUNTERPARTY_NAME` | `COUNTERPARTY_IBAN` | `PURPOSE`) + `matchType` (`CONTAINS` | `EQUALS` | `REGEX`) + `pattern` (nicht leer, max. 255 Zeichen; Regex muss kompilieren). Einzelauswertung exakt wie der heutige `RuleMatcher` (case-insensitive; Regex Vollabgleich mit `CASE_INSENSITIVE | DOTALL`; ungültige Regex/`null`-Feld = passt nicht).
- Ordnung der aktiven Regeln: Anzahl Bedingungen **absteigend**, dann `priority` **aufsteigend**, dann `id` **aufsteigend** (`null`-Id zuletzt). Erste passende gewinnt.
- Neubewertung betrifft nur `manuallyCategorized = false`; Handzuordnungen werden nie angefasst.
- Vorschau-Liste höchstens **200** Zeilen, neueste Buchung zuerst; Gesamtzahl immer vollständig.
- Zahlungsdienstleister fest im Code: PayPal (Token `PAYPAL`), Amazon (Tokens `AMAZON`, `AMZN`).
- Ungültige Eingaben ⇒ `IllegalArgumentException` ⇒ 400 (vorhandener `@ExceptionHandler` im Controller). Nie 401.
- Liquibase: **ein DDL je Changeset** (MariaDB-DDL-Autocommit). Changesets lokal nie gegen echte DB – Backup vor dem Deploy.
- Security unverändert: `/v1/finance/**` ist MEMBER, keine neue Zeile.
- UI-Texte deutsch: Empfänger, IBAN, Verwendungszweck; enthält, ist gleich, Regex.
- Backend-Tests: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` und aus `backend/` `mvn test -Dtest=…`. `contextLoads`/`HealthControllerTest` scheitern lokal immer (keine DB).
- Frontend-Tests aus `frontend/`: `npm test -- --watch=false --browsers=ChromeHeadless`; Baseline 3 vorbestehende Fails (App/Hero).

## Review Focus

1. **Regel ohne Bedingungen in der DB** (Migration lief halb, Handeintrag): darf nicht als „passt immer“ gelten. Test in Task 1: `ruleWithoutConditionsNeverMatches`.
2. **Löschen einer Regel** lässt von ihr eingeordnete Buchungen auf „Unkategorisiert“ fallen (oder auf die nächste passende Regel) – muss in der Vorschau als Änderung erscheinen. Test in Task 2: `previewDeleteShowsTransactionsLosingTheirCategory`.
3. **Vorschlag mit leerem Zweck-Muster** (Amazon) darf nicht speicherbar sein, auch nicht durch Doppelklick. Tests in Task 4 (`canSaveConditions`) und Task 2/1 (Validator lehnt leeres Muster ab).
4. **Deaktivierte Regeln** zählen weder in Vorschau noch Anwenden, auch nicht, wenn die geplante Änderung sie nur editiert (enabled bleibt false). Test in Task 2: `previewIgnoresDisabledRulesIncludingTheEditedOne`.
5. **PayPal-Zweck ohne „Ihr Einkauf bei“** (z. B. nur Referenznummer) ⇒ Vorschlag mit leerer Zweck-Bedingung statt Müll-Muster. Test in Task 3: `paypalWithoutMerchantPhraseLeavesPurposePatternEmpty`.

---

### Task 1: Bedingungen im Datenmodell, Auswertung und Ordnung

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261109-0088-create-categorization-rule-condition.xml` (createTable mit FK `rule_id` → `categorization_rules.id` `ON DELETE CASCADE`)
- Create: `…/20261109-0089-migrate-categorization-rule-conditions.xml` (`<sql>INSERT INTO categorization_rule_condition (rule_id, position, match_field, match_type, pattern) SELECT id, 0, match_field, match_type, pattern FROM categorization_rules</sql>`)
- Create: `…/20261109-0090-drop-rule-match-field.xml`, `…-0091-drop-rule-match-type.xml`, `…-0092-drop-rule-pattern.xml` (je ein `dropColumn`)
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (fünf Includes nach 0087)
- Create: `backend/src/main/java/com/household/manager/model/entity/CategorizationRuleCondition.java`
- Modify: `…/model/entity/CategorizationRule.java` (Felder `matchField`/`matchType`/`pattern` entfernen)
- Modify: `…/finance/RuleMatcher.java`
- Create: `…/finance/RuleOrdering.java`
- Create: `…/finance/RuleConditionsValidator.java`
- Create: `…/dto/RuleConditionDto.java`
- Modify: `…/dto/CategorizationRuleRequest.java`, `…/dto/CategorizationRuleResponse.java`, `…/dto/RuleSuggestion.java`
- Modify: `…/service/CategorizationService.java`, `…/service/CategorizationRuleService.java`
- Test: `backend/src/test/java/com/household/manager/finance/RuleMatcherTest.java`, `…/finance/RuleOrderingTest.java`, `…/finance/RuleConditionsValidatorTest.java`, `…/service/CategorizationServiceTest.java`

**Interfaces:**
- Produces:
  - Entity `CategorizationRuleCondition` (Tabelle `categorization_rule_condition`: `id BIGINT PK auto`, `rule_id BIGINT NOT NULL`, `position INT NOT NULL`, `match_field VARCHAR(30)`, `match_type VARCHAR(20)`, `pattern VARCHAR(255)`, alle NOT NULL). Felder `id`, `position`, `matchField: RuleMatchField`, `matchType: RuleMatchType`, `pattern`. Lombok `@Builder` usw. wie `CategorizationRule`.
  - `CategorizationRule.conditions: List<CategorizationRuleCondition>` – `@OneToMany(cascade = ALL, orphanRemoval = true, fetch = EAGER) @JoinColumn(name = "rule_id", nullable = false) @OrderBy("position ASC") @Builder.Default = new ArrayList<>()`.
  - `RuleMatcher.matches(CategorizationRule rule, Transaction tx): boolean` (alle Bedingungen; leere Liste ⇒ `false`) und `RuleMatcher.matches(CategorizationRuleCondition c, Transaction tx): boolean`.
  - `RuleOrdering.COMPARATOR: Comparator<CategorizationRule>` und `static List<CategorizationRule> sort(Collection<CategorizationRule> rules)` (neue Liste).
  - `RuleConditionsValidator.validate(List<RuleConditionDto> conditions): void` – wirft `IllegalArgumentException` mit deutschem Text bei `null`/leer, > 5, fehlendem Feld/Typ, leerem (trim) Muster, > 255 Zeichen, nicht kompilierender Regex.
  - `RuleConditionDto { RuleMatchField field; RuleMatchType matchType; String pattern; }` (`@Data @Builder @NoArgsConstructor @AllArgsConstructor`).
  - `CategorizationRuleRequest`: `@NotNull List<RuleConditionDto> conditions; @NotNull Long categoryId; Integer priority; Boolean enabled; boolean applyToExisting;` (alte Felder entfallen).
  - `CategorizationRuleResponse`: `id, conditions: List<RuleConditionDto>, categoryId, priority, enabled, appliedToExistingCount`.
  - `RuleSuggestion { List<RuleConditionDto> conditions; Long categoryId; String hint; }` (`hint` nullable).
  - `CategorizationService.loadActiveRules()` liefert die **nach `RuleOrdering` sortierte** Liste aktiver Regeln.
  - `CategorizationRuleService.toEntityConditions(List<RuleConditionDto>): List<CategorizationRuleCondition>` (package-private static, setzt `position` = Index) – Task 2 baut damit hypothetische Regeln.

- [ ] **Step 1: Tests anpassen/schreiben (failing)**
  - `RuleMatcherTest`: Hilfsmethode baut Regeln jetzt über Bedingungen; bestehende fünf Tests bleiben inhaltlich. Neu:
    - `allConditionsMustMatch`: Regel [Name CONTAINS `paypal`, Zweck CONTAINS `shell deutschland`]; `tx("PayPal Europe S.a.r.l. et Cie S.C.A", null, "1053795742551/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH")` ⇒ true; dieselbe Regel gegen Zweck `"… Ihr Einkauf bei Spotify AB"` ⇒ false.
    - `ruleWithoutConditionsNeverMatches` ⇒ false.
  - `RuleOrderingTest.moreConditionsBeforePriorityThenId`: A (1 Bed., prio 10, id 1), B (2 Bed., prio 100, id 2), C (2 Bed., prio 50, id 3), D (2 Bed., prio 50, id 4) ⇒ `[C, D, B, A]`.
  - `RuleConditionsValidatorTest`: je ein Test für leer, 6 Bedingungen, Muster `"  "`, Regex `"("`, 256 Zeichen ⇒ `IllegalArgumentException`; 5 gültige ⇒ kein Fehler.
  - `CategorizationServiceTest`: Hilfsmethode auf Bedingungen umstellen; `suggestRuleUsesNormalizedCounterpartyName` prüft jetzt `conditions` = genau eine Bedingung `COUNTERPARTY_NAME`/`CONTAINS`/normalisierter Name. Neu `loadActiveRulesIsSortedBySpecificity`: Repository-Mock liefert [1 Bed. prio 10, 2 Bed. prio 100] ⇒ Ergebnis beginnt mit der 2-Bedingungs-Regel.

- [ ] **Step 2: Tests laufen lassen – Kompilier- bzw. Testfehler erwartet**
  Run: `mvn test -Dtest='RuleMatcherTest,RuleOrderingTest,RuleConditionsValidatorTest,CategorizationServiceTest'`
  Expected: FAIL (Kompilierfehler: `CategorizationRuleCondition`, `RuleOrdering`, … fehlen)

- [ ] **Step 3: Changesets, Entity, Matcher, Ordnung, Validator, DTOs implementieren**
  - `CategorizationRuleRepository.findByEnabledTrueOrderByPriorityAsc` bleibt; `loadActiveRules` sortiert zusätzlich per `RuleOrdering.sort`. Auch `CategorizationRuleService.applyAllToUncategorized` (wird in Task 2 ersetzt) und `suggestRule` nutzen die sortierte Liste.
  - `CategorizationRuleService.create/update` rufen zuerst `RuleConditionsValidator.validate`, ersetzen dann `rule.getConditions()` per `clear()` + `addAll(toEntityConditions(...))` (orphanRemoval).
  - `suggestRule` liefert vorerst genau eine Bedingung wie bisher (Task 3 erweitert).
  - Rollback der Changesets: 0088 `dropTable`; 0089 `<sql>DELETE FROM categorization_rule_condition</sql>`; 0090–0092 `addColumn` mit dem alten Typ, nullable.

- [ ] **Step 4: Tests grün**
  Run: `mvn test -Dtest='RuleMatcherTest,RuleOrderingTest,RuleConditionsValidatorTest,CategorizationServiceTest,StatementImport*'`
  Expected: PASS. Danach `mvn test` komplett: nur die bekannten DB-Fails.

- [ ] **Step 5: Commit**
  `git add` der genannten Dateien; Nachricht `feat(finance): Regeln mit mehreren UND-Bedingungen`.

---

### Task 2: Neubewertung mit Vorschau

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/RuleReevaluationService.java`
- Create: `…/dto/RuleChangeRequest.java`, `…/dto/RuleReevaluationPreview.java`, `…/dto/RuleReevaluationRow.java`
- Create: `…/finance/PaymentIntermediary.java` (nur Erkennung + Warnung; Task 3 ergänzt Extraktion)
- Modify: `…/repository/TransactionRepository.java` (`List<Transaction> findByManuallyCategorizedFalse()`)
- Modify: `…/service/CategorizationRuleService.java`, `…/controller/CategorizationRuleController.java`
- Test: `backend/src/test/java/com/household/manager/service/RuleReevaluationServiceTest.java`, `…/finance/PaymentIntermediaryTest.java`

**Interfaces:**
- Consumes: Task 1 (`RuleMatcher`, `RuleOrdering`, `RuleConditionsValidator`, `CategorizationRuleService.toEntityConditions`, `RuleConditionDto`, `CategorizationRuleRequest`).
- Produces:
  - `RuleChangeRequest { RuleChangeType type; Long ruleId; CategorizationRuleRequest rule; }`, `enum RuleChangeType { CREATE, UPDATE, DELETE, REAPPLY }`. `CREATE` braucht `rule`; `UPDATE` braucht `ruleId` + `rule`; `DELETE` braucht `ruleId`; `REAPPLY` nichts. Unbekannte `ruleId` ⇒ `IllegalArgumentException`.
  - `RuleReevaluationRow { Long transactionId; LocalDate bookingDate; String counterpartyName; String purpose; BigDecimal amount; Long oldCategoryId; Long newCategoryId; }`
  - `RuleReevaluationPreview { int totalChanges; List<RuleReevaluationRow> rows; String warning; }` (`warning` nullable).
  - `RuleReevaluationService.preview(RuleChangeRequest change): RuleReevaluationPreview` (`@Transactional(readOnly = true)`, schreibt nichts).
  - `RuleReevaluationService.applyAll(): int` (`@Transactional`, Anzahl geänderter Buchungen; nutzt gespeicherten aktiven Regelsatz).
  - `PaymentIntermediary` (enum `PAYPAL("PayPal", "PAYPAL")`, `AMAZON("Amazon", "AMAZON", "AMZN")`) mit `static Optional<PaymentIntermediary> detect(String counterpartyName)` (Token-Treffer, case-insensitive), `matchedToken(String counterpartyName): String`, `displayName()`, und `static String broadRuleWarning(List<RuleConditionDto> conditions)` – liefert `"Diese Regel trifft alle <displayName>-Zahlungen."`, wenn **alle** Bedingungen `COUNTERPARTY_NAME` sind und mindestens ein Muster einen Token enthält, sonst `null`.
  - HTTP:
    - `POST /v1/finance/rules/preview` (Body `RuleChangeRequest`) ⇒ 200 `RuleReevaluationPreview`.
    - `POST /v1/finance/rules` und `PUT /v1/finance/rules/{id}`: bei `applyToExisting = true` nach dem Speichern `applyAll()`; `appliedToExistingCount` = Ergebnis.
    - `DELETE /v1/finance/rules/{id}?apply=true|false` (Default `false`) ⇒ 200 `{"applied": n}` (statt 204).
    - `POST /v1/finance/rules/apply` ⇒ `{"applied": applyAll()}` (jetzt alle nicht von Hand eingeordneten, nicht nur unkategorisierte). `applyAllToUncategorized` und `applyRuleToUncategorized` entfallen.

- [ ] **Step 1: Failing tests** (`RuleReevaluationServiceTest`, Repositories gemockt, echter `RuleMatcher`)
  - `previewCreateMovesPaypalShellToNewCategory`: bestehende Regel [Name CONTAINS `paypal`] ⇒ Kategorie 7; Buchungen: Shell-PayPal (Kat. 7, regelbasiert), Spotify-PayPal (Kat. 7), Shell-PayPal von Hand (Kat. 3, `manuallyCategorized=true`, darf vom Repo-Mock gar nicht kommen – Mock liefert nur `findByManuallyCategorizedFalse`). CREATE [Name CONTAINS `paypal`, Zweck CONTAINS `shell deutschland`] ⇒ Kat. 9. Erwartet: `totalChanges == 1`, Zeile Shell 7 → 9; `verify(transactionRepository, never()).save(any())`.
  - `previewDeleteShowsTransactionsLosingTheirCategory`: einzige Regel ⇒ Kat. 7, DELETE ⇒ Zeile 7 → `null`.
  - `previewIgnoresDisabledRulesIncludingTheEditedOne`: UPDATE einer Regel mit `enabled=false` im Request ⇒ ihre Buchungen fallen weg (7 → null).
  - `previewCapsRowsAt200NewestFirst`: 250 betroffene Buchungen ⇒ `totalChanges 250`, `rows.size() 200`, erste Zeile hat das jüngste `bookingDate`.
  - `previewCarriesBroadRuleWarning`: CREATE [Name CONTAINS `PAYPAL`] ⇒ `warning == "Diese Regel trifft alle PayPal-Zahlungen."`; mit zusätzlicher Zweck-Bedingung ⇒ `null`.
  - `previewRejectsInvalidChange`: CREATE mit leerem Muster ⇒ `IllegalArgumentException`; UPDATE mit unbekannter Id ⇒ `IllegalArgumentException`.
  - `applyAllSavesOnlyChangedTransactions`: 3 Kandidaten, 1 ändert sich ⇒ Rückgabe 1, `save` genau einmal.
  - `PaymentIntermediaryTest`: `detect("PayPal Europe S.a.r.l. et Cie S.C.A")` ⇒ PAYPAL; `detect("AMZN Mktp DE")` ⇒ AMAZON, `matchedToken` ⇒ `AMZN`; `detect("REWE")` ⇒ leer; `detect(null)` ⇒ leer.

- [ ] **Step 2: Run** `mvn test -Dtest='RuleReevaluationServiceTest,PaymentIntermediaryTest'` – Expected FAIL (Klassen fehlen).

- [ ] **Step 3: Implementieren**
  Hypothetischer Regelsatz: alle Regeln aus `ruleRepository.findAll()` als **detached Kopien** (neue `CategorizationRule`-Objekte mit kopierten Bedingungen – die Entities aus dem Persistence-Context nie verändern, sonst flusht JPA die Vorschau), Änderung anwenden (CREATE: neue Kopie mit `id = null`; UPDATE: Kopie ersetzen; DELETE: entfernen), dann `enabled` filtern und `RuleOrdering.sort`. Für jede Buchung aus `findByManuallyCategorizedFalse()` die erste passende Regel bestimmen (`CategorizationService.findCategory` wiederverwenden); Änderung = `!Objects.equals(alt, neu)`. Default-Werte wie beim Anlegen: `priority` 100, `enabled` true.

- [ ] **Step 4: Run** dieselben Tests plus `CategorizationServiceTest` ⇒ PASS.

- [ ] **Step 5: Commit** `feat(finance): Regeländerungen neu bewerten mit Vorschau`.

---

### Task 3: Vorschlag mit Zahlungsdienstleister-Erkennung

**Files:**
- Modify: `backend/src/main/java/com/household/manager/finance/PaymentIntermediary.java`
- Create: `…/finance/MerchantNameExtractor.java`
- Modify: `…/service/CategorizationService.java` (Konstruktor bekommt `MerchantNameExtractor`)
- Test: `backend/src/test/java/com/household/manager/finance/MerchantNameExtractorTest.java`, `…/service/CategorizationServiceTest.java`

**Interfaces:**
- Consumes: `PaymentIntermediary.detect/matchedToken/displayName` (Task 2), `RuleSuggestion` (Task 1).
- Produces:
  - `MerchantNameExtractor.extract(PaymentIntermediary intermediary, String purpose): Optional<String>` (`@Component`). PAYPAL: erster Treffer von `(?i)Ihr Einkauf bei\s+([^,\r\n]+)`, getrimmt, nachgestellte Rechtsformen wiederholt entfernt (case-insensitive, jeweils mit führendem Leerraum, optional Endpunkt: `GmbH & Co. KG`, `GmbH`, `AG`, `SE`, `KG`, `OHG`, `GbR`, `UG`, `UG (haftungsbeschränkt)`, `e.K.`, `Ltd`, `Inc`, `S.a.r.l.`, `S.A.`, `B.V.`), Ergebnis uppercase; leer ⇒ `Optional.empty()`. AMAZON: immer `Optional.empty()` (Format unbelegt).
  - `CategorizationService.suggestRule(Transaction tx, Long categoryId): RuleSuggestion` – Regeln:
    - Unterdrückung unverändert (aktiver, sortierter Regelsatz ordnet schon `categoryId` zu ⇒ `null`).
    - Dienstleister erkannt ⇒ zwei Bedingungen: `COUNTERPARTY_NAME`/`CONTAINS`/`matchedToken`, `PURPOSE`/`CONTAINS`/Händler oder `""`. Ist der Händler leer: `hint = "Bitte den Händler im Verwendungszweck ergänzen – sonst trifft die Regel alle <displayName>-Zahlungen."`
    - Sonst unverändert eine Bedingung (normalisierter Name; leer ⇒ `null`).

- [ ] **Step 1: Failing tests**
  - `MerchantNameExtractorTest.extractsShellFromRealPaypalPurpose`: Zweck `"1053795742551/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH"` ⇒ `"SHELL DEUTSCHLAND"`; ebenso `"1053839646834/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH"`.
  - `stripsStackedLegalForms`: `"Ihr Einkauf bei Muster GmbH & Co. KG"` ⇒ `"MUSTER"`.
  - `paypalWithoutMerchantPhraseReturnsEmpty`: `"1053795742551/. Referenz 4711"` ⇒ empty; `null` ⇒ empty.
  - `amazonAlwaysEmpty`.
  - `CategorizationServiceTest`:
    - `paypalSuggestionHasCounterpartyAndPurposeCondition`: Empfänger `"PayPal Europe S.a.r.l. et Cie S.C.A"`, realer Shell-Zweck ⇒ Bedingungen `[NAME CONTAINS "PAYPAL", PURPOSE CONTAINS "SHELL DEUTSCHLAND"]`, `hint == null`.
    - `paypalWithoutMerchantPhraseLeavesPurposePatternEmpty` ⇒ zweite Bedingung Muster `""`, `hint` beginnt mit `"Bitte den Händler"`.
    - `amzSuggestionUsesMatchedToken`: Empfänger `"AMZN Mktp DE"` ⇒ erste Bedingung Muster `"AMZN"`.

- [ ] **Step 2: Run** `mvn test -Dtest='MerchantNameExtractorTest,CategorizationServiceTest'` – FAIL.
- [ ] **Step 3: Implementieren** (Signaturen oben).
- [ ] **Step 4: Run** – PASS; danach `mvn test` komplett (nur bekannte DB-Fails).
- [ ] **Step 5: Commit** `feat(finance): Regelvorschlag erkennt PayPal und Amazon`.

---

### Task 4: Frontend-Modell, Service und Regel-Hilfsfunktionen

**Files:**
- Modify: `frontend/src/app/models/finance.model.ts`
- Modify: `frontend/src/app/services/finance.service.ts`
- Create: `frontend/src/app/shared/finance-rule.util.ts`
- Test: `frontend/src/app/shared/finance-rule.util.spec.ts`

**Interfaces:**
- Consumes: HTTP-Formen aus Task 1–3.
- Produces:
  - Typen: `RuleCondition { field: RuleMatchField; matchType: RuleMatchType; pattern: string }`; `CategorizationRule { id; conditions: RuleCondition[]; categoryId; priority; enabled; appliedToExistingCount }`; `CategorizationRuleRequest { conditions; categoryId; priority?; enabled?; applyToExisting? }`; `RuleSuggestion { conditions; categoryId; hint?: string }`; `RuleChangeType = 'CREATE' | 'UPDATE' | 'DELETE' | 'REAPPLY'`; `RuleChangeRequest { type; ruleId?; rule? }`; `RuleReevaluationRow { transactionId; bookingDate: string; counterpartyName?; purpose?; amount: number; oldCategoryId?: number | null; newCategoryId?: number | null }`; `RuleReevaluationPreview { totalChanges; rows; warning?: string | null }`.
  - `FinanceService.previewRuleChange(change: RuleChangeRequest): Observable<RuleReevaluationPreview>`; `deleteRule(id: number, apply: boolean): Observable<{ applied: number }>`; `applyRules()` unverändert.
  - `finance-rule.util.ts`: `MAX_RULE_CONDITIONS = 5`; `FIELD_LABELS: Record<RuleMatchField, string>` (`Empfänger`, `IBAN`, `Verwendungszweck`); `MATCH_TYPE_LABELS` (`enthält`, `ist gleich`, `Regex`); `describeConditions(c: RuleCondition[]): string`; `canSaveConditions(c: RuleCondition[]): boolean`; `newCondition(): RuleCondition` (Default `PURPOSE`/`CONTAINS`/`''`).

- [ ] **Step 1: Failing spec** `finance-rule.util.spec.ts`
  - `describeConditions([{COUNTERPARTY_NAME, CONTAINS, 'PAYPAL'}, {PURPOSE, CONTAINS, 'SHELL DEUTSCHLAND'}])` ⇒ `'Empfänger enthält „PAYPAL“ und Verwendungszweck enthält „SHELL DEUTSCHLAND“'`.
  - `canSaveConditions`: `[]` ⇒ false; eine Bedingung mit `'  '` ⇒ false; 6 gültige ⇒ false; 1–5 gültige ⇒ true.
- [ ] **Step 2: Run** `npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/finance-rule.util.spec.ts` – FAIL.
- [ ] **Step 3: Implementieren**; die beiden Seiten kompilieren vorerst durch minimale Anpassung (Regelseite liest `describeConditions(r.conditions)`, Formular baut `conditions: [{field, matchType, pattern}]`; Vorschlags-Banner nutzt `s.conditions`). Volle UI folgt in Task 5/6.
- [ ] **Step 4: Run** derselbe Befehl ⇒ PASS; `npx ng build` ohne Fehler.
- [ ] **Step 5: Commit** `feat(finance): Frontend-Modell für Regelbedingungen`.

---

### Task 5: Bedingungs-Editor und Vorschau-Dialog (Komponenten)

**Files:**
- Create: `frontend/src/app/components/finance-rule-editor/finance-rule-editor.component.{ts,html,scss,spec.ts}`
- Create: `frontend/src/app/components/finance-rule-preview/finance-rule-preview.component.{ts,html,scss,spec.ts}`

**Interfaces:**
- Consumes: Task 4 (Typen, `finance-rule.util.ts`).
- Produces:
  - `<app-finance-rule-editor>`: Inputs `conditions: RuleCondition[]` (wird beim Setzen kopiert), `categoryId: number`, `priority: number | null`, `categories: Category[]`, `hint: string | null`, `submitLabel: string` (Default `'Weiter zur Vorschau'`). Outputs `submitted: EventEmitter<CategorizationRuleRequest>` (ohne `applyToExisting`), `cancelled: EventEmitter<void>`. Zeilen mit Feld-/Vergleichs-Select (Labels aus Util), Musterfeld, „Entfernen“ (nur wenn > 1 Zeile); „Bedingung hinzufügen“ deaktiviert ab 5; Submit deaktiviert, solange `!canSaveConditions` oder keine Kategorie; leeres Muster bekommt Klasse `is-empty`. `hint` steht über den Zeilen.
  - `<app-finance-rule-preview>`: Inputs `preview: RuleReevaluationPreview`, `categories: Category[]`, `busy: boolean`, `allowSaveOnly: boolean` (Default true), `applyLabel: string` (Default `'Speichern und anwenden'`). Outputs `applyAndSave`, `saveOnly`, `cancelled` (alle `EventEmitter<void>`). Zeigt Warnung (falls gesetzt), „N Buchungen wechseln ihre Kategorie“ (bei 0: „Keine bestehende Buchung ändert sich.“), Tabelle Datum · Empfänger · Verwendungszweck · Betrag · alt → neu (`null` = „Unkategorisiert“), bei `totalChanges > rows.length` den Satz „Es werden die neuesten 200 von N gezeigt.“ Knöpfe `applyLabel`, „Nur speichern“ (wenn `allowSaveOnly`), „Abbrechen“; alle gesperrt solange `busy`.
  - Stile nur für Layout; Knöpfe/Felder erben den globalen `$finance-scopes`-Stil, weil beide Komponenten **innerhalb** von `.manage` bzw. `.transactions` gerendert werden.

- [ ] **Step 1: Failing specs**
  - Editor: `addButtonDisabledAtFiveConditions`; `submitDisabledWhilePatternEmpty` (Bedingungen aus einem Vorschlag mit leerer Zweck-Bedingung ⇒ Submit-Knopf `disabled`); `submitEmitsRequestWithEditedPattern` (Muster tippen ⇒ `submitted` mit getrimmtem Muster); `removingLastConditionIsNotPossible`; `inputConditionsAreNotMutated` (Eingabe-Array nach Tippen unverändert).
  - Preview: `showsOldAndNewCategoryNames` (`null` ⇒ „Unkategorisiert“); `showsCapNoticeWhenTruncated`; `showsWarning`; `buttonsDisabledWhileBusy`; `hidesSaveOnlyWhenNotAllowed`.
- [ ] **Step 2: Run** `npm test -- --watch=false --browsers=ChromeHeadless --include='src/app/components/finance-rule-*/**/*.spec.ts'` – FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: Run** – PASS.
- [ ] **Step 5: Commit** `feat(finance): Bedingungs-Editor und Vorschau-Dialog`.

---

### Task 6: Regel- und Buchungsseite verdrahten, Doku

**Files:**
- Modify: `frontend/src/app/pages/finance-rules/finance-rules.component.{ts,html,scss}`
- Modify: `frontend/src/app/pages/finance-transactions/finance-transactions.component.{ts,html,scss}`
- Create: `frontend/src/app/pages/finance-rules/finance-rules.component.spec.ts`
- Modify: `CLAUDE.md` (neuer Abschnitt unter „Smart Device Integrations“-Ende bzw. bei den Finanz-Themen: „Finanz-Regeln mit UND-Bedingungen“, Inhalt siehe Step 3)

**Interfaces:**
- Consumes: Task 4 (`FinanceService.previewRuleChange`, `deleteRule(id, apply)`, `createRule`, `updateRule`, `applyRules`), Task 5 (beide Komponenten).
- Produces: keine neuen Schnittstellen.

Ablauf (beide Seiten gleich): Editor `submitted(req)` ⇒ `previewRuleChange({type, ruleId?, rule: req})` ⇒ Dialog. „Speichern und anwenden“ ⇒ create/update mit `applyToExisting: true`; „Nur speichern“ ⇒ mit `false`; danach Info „Regel gespeichert, N Buchungen neu zugeordnet.“ und Neuladen (Buchungsseite: `load()`, Vorschlag schließen). Während eines Requests `busy = true`; ein zweiter Klick löst nichts aus.

Regel-Seite:
- Tabelle: Bedingungen (`describeConditions`), Kategorie, Prio, Aktiv, „Bearbeiten“, „Löschen“.
- „Neue Regel“ öffnet den Editor mit einer `newCondition()`; „Bearbeiten“ öffnet ihn vorbelegt (Typ UPDATE).
- An/Aus ⇒ Vorschau mit Typ UPDATE (enabled umgedreht), dann wie oben.
- Löschen ⇒ Vorschau Typ DELETE mit `applyLabel = 'Löschen und anwenden'` (⇒ `deleteRule(id, true)`), „Nur speichern“ ⇒ `deleteRule(id, false)`.
- Knopf „Alle Regeln neu anwenden“ ⇒ Vorschau Typ REAPPLY, `allowSaveOnly = false`, Bestätigen ⇒ `applyRules()`.

Buchungsseite:
- Statt Banner: wenn `pendingSuggestion`, der Editor mit `conditions`/`categoryId`/`hint` aus dem Vorschlag, Überschrift „Regel für künftige Buchungen?“, `cancelled` = „Nein danke“ (schließt).

- [ ] **Step 1: Failing spec** `finance-rules.component.spec.ts` (FinanceService als Jasmine-Spy):
  - `deleteGoesThroughPreview`: Klick „Löschen“ ⇒ `previewRuleChange` mit `{type: 'DELETE', ruleId}`; `deleteRule` erst nach Klick „Löschen und anwenden“ mit `apply = true`.
  - `toggleSendsUpdateWithFlippedEnabled`: Vorschau-Request hat `type 'UPDATE'`, `rule.enabled` invertiert, Bedingungen unverändert.
  - `reapplyHasNoSaveOnly`: Dialog nach „Alle Regeln neu anwenden“ zeigt keinen „Nur speichern“-Knopf.
  - `secondConfirmClickIsIgnoredWhileBusy`: `createRule` liefert ein nicht abgeschlossenes `Subject` ⇒ zweiter Klick ruft `createRule` nicht erneut.
- [ ] **Step 2: Run** `npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/finance-rules/finance-rules.component.spec.ts` – FAIL.
- [ ] **Step 3: Implementieren**; CLAUDE.md-Abschnitt mit: Bedingungstabelle + Migration 0088–0092 (nie gegen echte DB, Backup), einzige Ordnung `RuleOrdering` (mehr Bedingungen vor Prio vor Id), Neubewertung nur `manuallyCategorized=false` inkl. bereits regelbasiert eingeordneter, Vorschau schreibt nichts und rechnet auf detached Kopien, `PaymentIntermediary`/`MerchantNameExtractor` als einzige Definition (Amazon-Format unbelegt), Warnung bei reiner Dienstleister-Regel kommt aus der Vorschau, `RecurringPayment.categoryId` folgt der Neubewertung nicht, Kreditkarten-Sammelbuchungen nicht aufteilbar.
- [ ] **Step 4: Run** komplette Frontend-Suite ⇒ nur Baseline-Fails; `npx ng build` ok; Backend `mvn test` ⇒ nur bekannte DB-Fails. Manuelle Sichtprüfung im Browser entfällt mangels lokaler DB – im Abschluss so benennen.
- [ ] **Step 5: Commit** `feat(finance): Regel- und Buchungsseite mit Bedingungs-Editor und Vorschau`.
