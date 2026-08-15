# Design: Toni-Futtervorrat (MjamMjam-Dosenbestand)

**Datum:** 2026-08-15
**Status:** Vom Nutzer bestätigt

## Ziel

Ein kleines Modul, das den Vorrat an MjamMjam-Feuchtfutterdosen für Toni erfasst und verwaltet.
Toni bekommt pro Tag genau eine Dose: eine Hälfte um 7:00, die andere um 16:00 (lokale
Haushaltszeit, Europe/Berlin). Das Backend reduziert den Bestand automatisch zu diesen
Zeitpunkten; die GUI zeigt einen Füllstandsanzeiger und erlaubt das Zubuchen und Korrigieren.

## Geklärte Anforderungen

- **Nur Gesamtzahl**, keine Sortenverwaltung — für den Füllstand ist die Sorte irrelevant.
- **Kein Pause-Modus**: Toni wird immer zu Hause gefüttert; stures Abziehen reicht,
  Korrekturen decken Zählfehler ab.
- **Warnung bei niedrigem Bestand** per Telegram über die Flow-Engine (Entity-Spiegelung
  als Trigger), Schwelle ohne Redeploy im Flow änderbar.
- **GUI: Dashboard-Kachel + eigene Seite** (Füllstand auf einen Blick, Pflege auf der Seite).
- **Pflegbarer Zielbestand** als 100-%-Bezug des Füllstands (Default 48 Dosen), auf der
  Seite änderbar, ohne Redeploy.
- Ansatz A gewählt: eigenes Modul mit Buchungsjournal und persistierter Hochwassermarke
  (statt Minimalvariante ohne Historie oder Vorgriff auf das Phase-2-Inventar).

## Datenmodell (Liquibase, neues Changeset; Paket `petfood/`)

### `pet_food_stock` — genau eine Zeile

| Spalte | Typ | Bedeutung |
|---|---|---|
| `id` | PK | fest `1` |
| `cans_remaining` | `DECIMAL(6,1)` | aktueller Bestand in Dosen, 0,5-Schritte |
| `target_cans` | `DECIMAL(6,1)` | Zielbestand (= 100 % des Füllstands), Default 48 |
| `deduction_marker` | `DATETIME` (nullable) | Hochwassermarke der Abzüge als Instant/UTC |
| `updated_at` | `DATETIME` | letzter Schreibzugriff |

Die Zeile wird per Liquibase geseedet: Bestand 0, Ziel 48, Marke `NULL`.

### `pet_food_transaction` — das Journal

| Spalte | Typ | Bedeutung |
|---|---|---|
| `id` | PK | |
| `occurred_at` | `DATETIME` | fachlicher Zeitpunkt (bei Fütterungen der Fütterungszeitpunkt, nicht die Laufzeit des Schedulers) |
| `type` | `VARCHAR` | `FEEDING`, `PURCHASE`, `CORRECTION` |
| `amount` | `DECIMAL(6,1)` | vorzeichenbehaftete Bestandsänderung (tatsächlich wirksam) |
| `cans_after` | `DECIMAL(6,1)` | Bestand nach der Buchung |
| `note` | `VARCHAR` (nullable) | optionale Notiz |
| `created_at` | `DATETIME` | Schreibzeitpunkt |

## Fütterungs-Scheduler (`PetFoodFeedingScheduler`)

- `@Scheduled` minütlich; die Scheduled-Methode **wirft nie** (Muster der übrigen Poller).
- Zählt die Fütterungszeitpunkte (täglich 7:00 und 16:00 **Europe/Berlin**) zwischen der
  persistierten Marke (exklusiv) und jetzt (inklusiv), zieht je 0,5 Dosen ab und schreibt
  pro Fütterung einen `FEEDING`-Journaleintrag mit `occurred_at` = Fütterungszeitpunkt.
  Verpasste Fütterungen (Backend zur Fütterungszeit down/im Deploy) werden so nachgeholt.
- **Die Marke ist ein `Instant`, keine Wandzeit** — dieselbe Zeitumstellungs-Lektion wie
  beim `CalendarReminderScheduler`; die 7:00/16:00-Zeitpunkte werden über `ZonedDateTime`
  in Europe/Berlin aufgelöst. Anders als beim Kalender ist die Marke **persistiert**
  (`deduction_marker`), weil ein verpasster Abzug den Bestand dauerhaft verfälschen würde,
  statt nur eine Ansage zu verpassen.
- Marke `NULL` (Erstinbetriebnahme): Marke auf „jetzt" setzen, **nichts** abziehen — sonst
  würde ab Epochenbeginn nachgeholt.
- Der Bestand wird bei 0 **geklemmt** (nie negativ); `amount` im Journal hält den
  tatsächlich abgezogenen Betrag fest (ggf. weniger als 0,5 oder 0).

## REST-API (`/api/v1/pet-food`)

Controller dünn, Logik in `PetFoodService` (Controller → Service → Repository).

| Endpunkt | Zweck |
|---|---|
| `GET /v1/pet-food` | Bestand, Zielbestand, Prozent, Reichweite in Tagen (1 Dose/Tag) |
| `GET /v1/pet-food/transactions?limit=…` | Journal, neueste zuerst |
| `POST /v1/pet-food/purchases` `{cans, note?}` | Einkauf zubuchen |
| `POST /v1/pet-food/corrections` `{cansRemaining, note?}` | gezählten Ist-Bestand **absolut** setzen; das Journal hält die Differenz fest |
| `PUT /v1/pet-food/target` `{targetCans}` | Zielbestand ändern |

### Sicherheit

- Lesen über die generische `GET /v1/**`-Regel (das KIOSK-Wandtablet sieht die Kachel).
- Alle Schreibpfade **MEMBER**; bewusst **nicht** in der KIOSK-POST-Whitelist (das Tablet
  muss nichts zubuchen).
- `SecurityRulesTest` wird um beide Richtungen erweitert.
- Einkäufe, Korrekturen und Zieländerungen landen im Audit-Log (Muster Kalender);
  `AuditService.record` wirft nie.

## Entity-Spiegelung und Warnflow

- Nach jeder Bestandsänderung (Scheduler wie API) wird `sensor.pet_food_toni_cans` in den
  Entity-State-Layer gemeldet: State = Dosenzahl, Attribute `targetCans`/`percent`.
- Die Spiegelung sitzt als Hook mit try/catch um das Mapping (etabliertes Hook-Muster):
  ein Fehler dort darf die Buchung nicht brechen.
- Kein `unavailable`-Szenario — die Daten sind lokal, es gibt keine Anbindung, die
  ausfallen könnte.
- **Telegram-Warnflow** (Teil des Rollouts, kein Java-Code — Muster Zigbee-Warnung):
  via flow-mcp ein Flow „Toni-Futter geht zur Neige" mit Trigger
  `sensor.pet_food_toni_cans < 7` (≈ eine Woche Reichweite) → `telegram-send`.
  Der numerische Trigger feuert nur beim Übergang; der Bestand fällt monoton bis zum
  nächsten Einkauf — genau eine Meldung pro Unterschreitung, nach dem Zubuchen ist der
  Trigger automatisch wieder scharf. Der Flow wird erst **nach** dem Backend-Deploy
  angelegt (create → deploy → enable).

## Frontend

### Seite `pages/pet-food/` (Route `/pet-food`, Navi „Futtervorrat", alle Rollen)

- Großer Füllstandsanzeiger: Balken mit Prozent, „12,5 von 48 Dosen", **Reichweite in
  Tagen** („reicht noch ~12 Tage") — bei 1 Dose/Tag die eigentlich interessante Zahl.
- Farblogik: grün; unter ~25 % gelb; unter der Flow-Schwelle (7 Dosen) rot.
- Aktionen: „Einkauf zubuchen" (Anzahl + Notiz), „Bestand korrigieren" (gezählten
  Ist-Bestand eintragen), Zielbestand ändern.
- Journal-Tabelle: Zeitpunkt, Typ, Betrag, Bestand danach, Notiz.

### Dashboard-Kachel

- Im Footer neben Türschloss und Hund: Mini-Füllstand mit Prozent, Dosenzahl, Reichweite;
  Klick navigiert zur Seite.
- Das Markup steht **direkt in `dashboard.component.html`** — die `lumina`-Styles sind
  dort gekapselt und griffen in einer Kind-Komponente lautlos nicht (dokumentierte Falle).
- Das SCSS-Budget der Dashboard-Datei ist bekanntlich am Limit (Build-ERROR ist
  Größenpolizei) — die Kachel-Styles bleiben minimal.
- Angezeigt wird der Stand vom letzten `GET`; kein Live-Push — der Bestand ändert sich
  zweimal am Tag.

## Fehlerbehandlung

- Scheduler wirft nie; ein DB-Fehler beim Abzug lässt die Marke unverändert, der nächste
  Lauf holt nach (idempotent, weil die Marke erst nach erfolgreicher Buchung vorrückt;
  Abzug + Journal + Marke in **einer Transaktion**).
- Validierung: `cans` beim Einkauf > 0; `cansRemaining` bei Korrektur ≥ 0; `targetCans` > 0;
  alle Werte endlich (`isFinite`-Lektion); Werte, die kein Vielfaches von 0,5 sind,
  werden mit 400 abgelehnt (nicht stillschweigend gerundet).
- 400 mit Klartext bei Validierungsfehlern über den `GlobalExceptionHandler`.

## Tests

- `PetFoodService`/Scheduler: Nachholen mehrerer verpasster Fütterungen, `NULL`-Marke
  (kein Nachholen ab Epoche), Klemmen auf 0, Zeitumstellungs-Fenster (Instant-Marke),
  Korrektur-Differenzbildung.
- `SecurityRulesTest`: GET für KIOSK offen, Schreibpfade MEMBER, KIOSK-POST abgelehnt.
- Frontend: Komponententest der Seite (Rendering von Füllstand/Reichweite, Farblogik).

## Bewusst nicht enthalten (YAGNI)

- Keine Sortenverwaltung, kein Pause-Modus, kein generisches Inventar (Phase 2 bleibt
  unberührt), kein Live-Push, keine Retention fürs Journal (2 Zeilen/Tag — unkritisch).
