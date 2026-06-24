# Ausgaben-Tracking — Design / Spec

**Datum:** 2026-06-24
**Status:** Freigegeben (Brainstorming abgeschlossen)
**Bereich:** Neuer Funktionsbereich `finance` (Backend) + Frontend-Seiten

## 1. Ziel & Umfang

Erfassen, Auswerten und Optimieren der Ausgaben (und Einnahmen) eines Haushalts auf
Basis von Kontoauszügen der Bank. Eingangspunkt ist der Upload eines Bank-Exports.

Gewählte Ziele (alle in **einem** Spec, intern in klar getrennten Modulen umgesetzt):

- **Überblick / Kategorien** — Aufschlüsselung der Ausgaben nach Kategorien pro Monat.
- **Trends über Zeit** — Monatsvergleiche, Zeitreihen.
- **Fixkosten & Abos** — Erkennung wiederkehrender Zahlungen.
- **Budgets** — Gesamt- und Kategorie-Budgets mit Soll-Ist.

### Import-Format (entschieden)

**XML CAMT V8** = ISO 20022 `camt.053.001.08` (gebuchte Umsätze). Begründung:
strukturiert, bankunabhängig, reichhaltige Daten (Buchungs-/Wertstellungsdatum,
Soll/Haben, Betrag + Währung getrennt, Gegenkonto IBAN/Name, Verwendungszweck,
End-to-End-/Servicer-Referenz). Robuster und eindeutiger als die CSV-/MT940-Varianten.

## 2. Architektur

Bestehende Schichtung beibehalten: Controller → Service → Repository, DTOs für
API-Verträge, Liquibase für Schema. Konventionen wie im Projekt: Lombok,
`@Slf4j`, `BigDecimal` für Geldbeträge, REST unter `/api/v1/...`,
`@PrePersist`/`@PreUpdate`-Timestamps.

- **Backend-Paket:** `com.household.manager.finance` für CAMT-Parsing
  (analog zu `kasa`/`tapo`/`importer`). Controller/Service/DTO in den bestehenden
  Paketen. **Alle JPA-Repositories in `com.household.manager.repository`**
  (JpaConfig beschränkt das Scanning).
- **Frontend:** Standalone-Komponenten, separate HTML/SCSS, ECharts via `ngx-echarts`.

## 3. Datenmodell

Jede Tabelle erhält einen eigenen, datierten Liquibase-Changeset
(`20260624-00xx-...`). Betragskonvention: **vorzeichenbehaftet**
(negativ = Soll/Ausgabe, positiv = Haben/Einnahme).

### Entities

| Entity | Zweck | Kernfelder |
|---|---|---|
| `BankAccount` | Mehrere Konten | `name`, `iban`, `currency`, Timestamps |
| `Transaction` | Eine Buchung | `account_id` (FK), `bookingDate`, `valueDate`, `amount` (BigDecimal, vorzeichenbehaftet), `currency`, `counterpartyName`, `counterpartyIban`, `purpose`, `endToEndId`, `bankTxCode`, `category_id` (FK, nullable), `recurring_id` (FK, nullable), `manuallyCategorized` (boolean), `import_batch_id` (FK), `dedupHash` (unique), Timestamps |
| `Category` | Kategorie | `name`, `kind` (EXPENSE/INCOME/TRANSFER), `color`, `system` (boolean), `parent_id` (FK, nullable), Timestamps |
| `CategorizationRule` | Auto-Regel | `matchField` (COUNTERPARTY_NAME/COUNTERPARTY_IBAN/PURPOSE), `matchType` (CONTAINS/EQUALS/REGEX), `pattern`, `category_id` (FK), `priority` (int), `enabled` (boolean), Timestamps |
| `ImportBatch` | Import-Protokoll | `account_id` (FK), `filename`, `importedAt`, `importedCount`, `skippedDuplicates`, `failedCount`, `dateFrom`, `dateTo` |
| `RecurringPayment` | Fixkosten/Abo | `account_id` (FK), `counterpartyPattern`, `category_id` (FK, nullable), `expectedAmount`, `interval` (MONTHLY/QUARTERLY/YEARLY), `nextDueDate`, `confirmed` (boolean), Timestamps |
| `Budget` | Budget | `category_id` (FK, **nullable → null = Gesamtbudget**), `period` (MONTHLY), `amount`, `validFrom`, Timestamps |

### Designentscheidungen

- **Betrag vorzeichenbehaftet:** CAMT `CdtDbtInd` (CRDT/DBIT) wird beim Parsen ins
  Vorzeichen übersetzt. Einfachste Basis für Saldo/Auswertungen.
- **`dedupHash`** (unique constraint): bevorzugt aus CAMT-Referenz
  (`AcctSvcrRef`/`EndToEndId`), sonst Komposit-Hash aus
  `account + bookingDate + amount + counterpartyIban + purpose`.
  Garantiert idempotente Re-Uploads.
- **Budget „beides"** über ein Feld: `category_id = null` ⇒ Gesamtbudget,
  sonst Kategorie-Budget. Kein Doppelmodell.
- **Kategorie-Hierarchie** über `parent_id` (flach nutzbar, Unterkategorien möglich).

## 4. Import-Pipeline (CAMT V8)

Endpoint: `POST /api/v1/finance/import?accountId=…` (MultipartFile, analog zum
bestehenden CSV-Import).

```
CAMT-XML  ──►  Parser  ──►  Dedup-Filter  ──►  Auto-Kategorisierung  ──►  Speichern + ImportBatch
(camt.053)    (JAXB)      (dedupHash)         (Regeln nach priority)      (Summary zurück)
```

1. **Parsen** — `camt.053.001.08`, JAXB-Klassen aus offiziellem XSD
   (Maven `jaxb2-maven-plugin`, `generate-sources`). Mapping:
   - `Ntry/Amt` + `CdtDbtInd` → vorzeichenbehafteter Betrag
   - `BookgDt`/`ValDt` → `bookingDate`/`valueDate`
   - `NtryDtls/TxDtls/RltdPties` → `counterpartyName`/`counterpartyIban`
   - `RmtInf/Ustrd` → `purpose`; `Refs/EndToEndId`, `AcctSvcrRef` → Referenzen
   - Statement-IBAN gegen `accountId` plausibilisieren (Warnung bei Abweichung).
2. **Dedup-Filter** — `dedupHash` berechnen, vorhandene überspringen (gezählt).
3. **Auto-Kategorisierung** — aktive `CategorizationRule` nach `priority`;
   erste Übereinstimmung gewinnt. Kein Treffer ⇒ Kategorie leer.
4. **Speichern** — Transaktionen + ein `ImportBatch`. Rückgabe:
   `ImportSummaryResponse { importedCount, skippedDuplicates, failedCount, dateFrom, dateTo, uncategorizedCount }`.

### Fehlerbehandlung

- Leeres/kein File → 400.
- Ungültiges/nicht-CAMT-XML → 400 mit verständlicher Meldung (nicht 500).
- Fehler bei einzelner Buchung → Buchung überspringen, in Log + `failedCount`
  vermerken, Rest importieren (kein Komplettabbruch).

### Robustheit / Testbarkeit

`CamtStatementParser` als eigene Klasse mit reinem Input (Reader/InputStream) →
Liste von DTOs, ohne DB-Abhängigkeit. Isoliert mit Beispiel-CAMT-Dateien testbar.

## 5. Kategorisierung, Regeln & „Lernen"

### Vordefinierte Kategorien (Liquibase, `system=true`, vom Nutzer ergänz-/umbenennbar)

Ausgaben: Lebensmittel, Wohnen/Miete, Energie, Mobilität, Versicherungen,
Abos & Medien, Gesundheit, Freizeit, Shopping, Restaurant, Bargeld, Sonstiges.
Einnahmen: Gehalt, Erstattung. Farben vorbelegt.

### Manuelle Korrektur + Regel-Vorschlag (das „Lernen")

```
Nutzer ändert Kategorie einer Transaktion
        │
        ▼
PATCH /api/v1/finance/transactions/{id}/category   { categoryId }
        │  (setzt category, manuallyCategorized = true)
        ▼
Backend prüft: existiert bereits eine abdeckende Regel?
   nein ──► Antwort enthält Regel-VORSCHLAG:
            { suggestRule: true, field: COUNTERPARTY_NAME,
              pattern: "<normalisierter Gegenkonto-Name>", categoryId }
        │
        ▼
Frontend: „Künftig 'NETFLIX' immer als Abos & Medien? [Regel anlegen]"
        │  Nutzer bestätigt
        ▼
POST /api/v1/finance/rules   ──► Regel gespeichert
   + optional Backfill: bestehende UNkategorisierte Treffer nachkategorisieren
     (manuell kategorisierte werden nie überschrieben)
```

### Kernpunkte

- **Transparent & widerrufbar** — Regeln sind sichtbare Datensätze mit eigener
  Verwaltungsseite, kein Blackbox-ML.
- **Pattern-Vorschlag** aus normalisiertem Gegenkonto-Namen (Großschreibung,
  Entfernen von Buchungs-Suffixen wie Datum/Filialnummern), Default
  `matchType = CONTAINS`. Vor dem Anlegen anpassbar.
- **Konfliktregel:** `manuallyCategorized = true` ist unantastbar für Auto-Regeln;
  Regeln greifen nur auf leere/auto-gesetzte Kategorien.
- **Regelverwaltung:** Liste, Bearbeiten, Aktivieren/Deaktivieren, Priorität,
  „Regeln jetzt auf alle unkategorisierten anwenden".

## 6. Fixkosten-/Abo-Erkennung

Heuristik → Vorschlag → Bestätigung.

- On-Demand-Service (Button „Wiederkehrende suchen") + optional `@Scheduled` nach Import.
- Gruppiert Transaktionen nach normalisiertem Gegenkonto, prüft regelmäßige Abstände
  (monatlich/quartalsweise/jährlich ± Toleranz) und ähnliche Beträge (± Toleranz).
- Erzeugt `RecurringPayment`-Kandidaten mit `confirmed=false`. Nutzer bestätigt/verwirft.
  Bestätigte zeigen `nextDueDate` und werden Buchungen zugeordnet.

## 7. Budgets

Gesamt + pro Kategorie.

- `Budget`-Service berechnet je Monat: Ist-Ausgaben pro Kategorie vs. Limit sowie
  Gesamt-Ist vs. Gesamtlimit.
- Status-Ampel: grün < 80 %, gelb 80–100 %, rot > 100 %.

## 8. Analytics-API

Aggregation im Service (nicht im Controller).

- `GET /api/v1/finance/analytics/overview?month=&accountId=` →
  KPIs (Ausgaben, Einnahmen, Saldo, Budget-Auslastung), Kategorie-Aufschlüsselung,
  Budget-Status.
- `GET /api/v1/finance/analytics/trend?from=&to=&accountId=&categoryId=` →
  Zeitreihe für Monatsvergleich.

### Weitere CRUD-Endpunkte

`/finance/accounts`, `/finance/transactions` (Filter: Konto, Monat, Kategorie, Suche),
`/finance/categories`, `/finance/rules`, `/finance/recurring`, `/finance/budgets`.

## 9. Frontend

- **Übersichtsseite** mit **Layout-Umschalter A ↔ B** (Voreinstellung pro Nutzer in
  `ApplicationSetting` gespeichert — Pattern existiert bereits). Beide Layouts teilen
  dieselben Bausteine:
  - **Layout A:** KPI-Kacheln oben (Ausgaben/Einnahmen/Saldo/Budget), darunter
    Kategorie-Donut + Trend nebeneinander, unten Transaktionsliste.
  - **Layout B:** Kategorien-Sidebar mit Budget-Balken links, rechts Trend +
    Transaktionsliste.
- **Seiten:** Übersicht, Transaktionen (Liste + Inline-Kategorisierung +
  Regel-Vorschlag-Dialog), Kategorien, Regeln, Wiederkehrende, Budgets, Import-Dialog.
- **Services:** `BankAccountService`, `TransactionService`, `CategoryService`,
  `RuleService`, `AnalyticsService`, `RecurringService`, `BudgetService`.
- **Charts** via `ngx-echarts`: Kategorie-Donut, Trend-Linie, Budget-Balken.

## 10. Teststrategie

- **Backend:** `CamtStatementParser` mit echten Beispiel-CAMT-Dateien; Dedup-Logik;
  Regel-Matching/Priorität; Recurring-Heuristik; Budget-Berechnung;
  Analytics-Aggregation. AAA-Pattern, beschreibende Testnamen.
- **Frontend:** Service- und Komponenten-Tests (Karma/Jasmine).

## 11. Implementierungsreihenfolge (Module)

1. Datenmodell + Liquibase (Accounts, Transactions, Categories, ImportBatch).
2. CAMT-Parser + Import-Endpoint + Dedup.
3. Kategorien (Seed) + Regeln + Auto-Kategorisierung + Regel-Vorschlag.
4. Transaktions-Liste/Filter + manuelle Kategorisierung (Frontend).
5. Analytics-API + Übersichtsseite mit Layout-Umschalter.
6. Fixkosten-Erkennung.
7. Budgets.

## 12. Bewusst ausgeklammert (YAGNI)

- Echtes Machine-Learning für Kategorisierung (stattdessen regelbasiert mit Vorschlägen).
- Multi-User/Authentifizierung (Single-Household wie bestehendes Projekt).
- Andere Importformate (CSV-CAMT, MT940) — nur CAMT V8.
- Direkte Bank-API-/PSD2-Anbindung (nur Datei-Upload).
