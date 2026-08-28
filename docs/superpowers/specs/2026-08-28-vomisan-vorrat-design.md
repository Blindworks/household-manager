# VomiSan-Tabletten im Toni-Vorrat

**Datum:** 2026-08-28
**Status:** Entwurf, vom Nutzer freigegeben

## Anlass

Toni bekommt täglich zwei VomiSan-Magentabletten zum Essen. Der Bestand soll
genauso geführt werden wie der Futtervorrat: automatischer Abzug, Einkauf
zubuchen, Bestand korrigieren, Warnung bei Knappheit.

Die Mechanik ist mit der bestehenden identisch — fester Verbrauch zu den zwei
Fütterungszeiten, Journal, Sensor-Entität, Zielbestand. Deshalb wird der
bestehende Ein-Zeilen-Futtervorrat zu einem generischen Vorrat verallgemeinert,
statt ein zweites Modul danebenzustellen. Ein dritter Vorrat (Zeckenmittel,
Gelenktabletten) kostet danach nur noch eine Datenbankzeile, keinen Code.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Architektur | Generischer Vorrat, eine Zeile je Artikel |
| Abzug | 1 Tablette je Fütterung (7:00 und 16:00), wie die halbe Dose Futter |
| Einheit | Ganze Tabletten, Raster 1 (Futter behält Raster 0,5) |
| Sichtbar auf | Seite `/pet-food`, Dashboard-Kachel, `/tablet/toni`, Telegram-Warnung |
| Warnschwelle | Reichweite unter 7 Tagen (statt einer Stückzahl je Einheit) |

## Datenmodell

`pet_food_stock` wird zu **`pet_supply`** umbenannt, mit einer Zeile je Artikel:

| Spalte | Futter | VomiSan |
|---|---|---|
| `supply_key` (unique) | `toni_cans` | `toni_vomisan` |
| `name` | Futtervorrat | VomiSan-Tabletten |
| `unit` | Dosen | Tabletten |
| `amount_remaining` | Bestand heute | 0 |
| `target_amount` | 48 | 60 |
| `per_feeding` | 0,5 | 1 |
| `step` | 0,5 | 1 |
| `deduction_marker` | Marke heute | NULL |
| `display_order` | 1 | 2 |

`pet_food_transaction` wird zu **`pet_supply_transaction`** und bekommt
`supply_id` (Fremdschlüssel auf `pet_supply`, `ON DELETE CASCADE`, NOT NULL);
die bestehenden Zeilen werden dem Futter zugeordnet. Index auf
`(supply_id, occurred_at)`. `cans_after` heißt `amount_after`.

### Drei Festlegungen, die nicht beliebig sind

- **Die Entity-Id des Futters bleibt buchstäblich gleich.** Sie wird als
  `sensor.pet_food_<supply_key>` abgeleitet; der Schlüssel `toni_cans` ergibt
  exakt das bestehende `sensor.pet_food_toni_cans`. VomiSan wird
  `sensor.pet_food_toni_vomisan`. Wer den Schlüssel eines Vorrats je ändert,
  ändert damit dessen Entity-Id — ein darauf gebauter Flow liefe still ins
  Leere (dieselbe Falle wie beim Umbenennen eines Blink-Sync-Moduls).
- **Das Attribut `targetCans` heißt künftig `targetAmount`**, dazu kommen
  `daysRemaining` und `perDay`. Für Tabletten wäre „Cans" schlicht falsch.
  Konsumenten gibt es nicht: der Futter-Warnflow wurde laut Rollout-Notiz nie
  angelegt.
- **Die Abzugsmarke bleibt je Vorrat eigen.** Das trägt die
  Erstinbetriebnahme: VomiSan startet mit `NULL`, der erste Scheduler-Lauf
  setzt nur die Marke und zieht nichts ab. Eine gemeinsame Marke würde den
  Tablettenvorrat rückwirkend ab dem Futter-Deploy leerbuchen.

### Migration

Neues Changelog `20260828-0047-generalize-pet-supplies.xml`, wie
`20260727-0044` in **einzelne Changesets** zerlegt: MariaDB committet jedes DDL
implizit, ein gebündeltes Changeset stünde nach einem Abbruch halb angewendet in
der Tabelle, aber nicht in `DATABASECHANGELOG`, und wäre dauerhaft nicht mehr
wiederholbar.

Reihenfolge: Tabellen umbenennen → Spalten umbenennen → neue Spalten anlegen →
Bestandszeile mit Schlüssel/Name/Einheit/Raster befüllen → `supply_id` an den
Transaktionen ergänzen und auf die Futterzeile setzen → Fremdschlüssel und
NOT-NULL nachziehen → VomiSan-Zeile seeden.

## Backend

Paket `petfood` → `petsupply`, `PetFoodService` → `PetSupplyService`.
`FeedingSchedule` bleibt unverändert — 7:00 und 16:00 gelten für beide Vorräte,
das ist gerade die gewollte Kopplung.

`applyDueFeedings()` läuft über alle Vorräte: je Vorrat eigene Marke, eigener
Abzug (`per_feeding`, am Bestand gekappt), eigene Journalzeile, eigene
Sensor-Spiegelung. Ein Fehler an einem Vorrat darf den anderen nicht mitreißen.
Die bestehenden Absicherungen gelten unverändert weiter, jetzt je Vorrat: Marke
sekundengenau abschneiden (MariaDB rundet DATETIME-Bruchsekunden und schöbe die
Marke sonst in die Zukunft), Uhr-Rücksprung überspringt den Lauf, Abzug plus
Journal plus Marke in einer Transaktion.

Die Rasterprüfung liest `step` aus dem Vorrat statt der bisherigen Konstante
0,5. Damit lehnt die API „2,5 Tabletten" mit 400 ab, „2,5 Dosen" aber
weiterhin nicht.

`daysRemaining` wird generisch `floor(Bestand / (per_feeding × 2))`. Für Futter
ergibt das rechnerisch exakt den heutigen Wert.

### API

Unter `/v1/pet-supplies`:

- `GET /` — alle Vorräte mit Bestand, Ziel, Prozent, Reichweite, Einheit, Raster
- `GET /{key}/transactions?limit` — Journal eines Vorrats (Kappung 1..200)
- `POST /{key}/purchases` — Einkauf zubuchen
- `POST /{key}/corrections` — absoluter Ist-Bestand, Journal = Differenz
- `PUT /{key}/target` — Zielbestand

Unbekannter Schlüssel ⇒ 404.

**Security bleibt unangetastet:** Lesen über die generische
`GET /v1/**`-KIOSK-Regel, Schreiben über `anyRequest` → MEMBER, weiterhin
bewusst **keine** eigene Zeile in `SecurityConfig`. `SecurityRulesTest` zieht nur
die Pfade nach und hält beide Richtungen fest.

Die Audit-Aktionen behalten ihre Namen (`petfood.purchase`,
`petfood.correction`, `petfood.target.update`) und tragen den Vorrat im
Detailtext — so bleibt die bestehende Audit-Historie homogen.

## Frontend

**Seite `/pet-food`** (Route bleibt, Überschrift wird „Toni-Vorräte"): eine
Karte je Vorrat, untereinander, gerendert aus der Liste — die Seite kennt die
beiden Artikel nicht namentlich. Jede Karte trägt Füllstandsbalken, Reichweite,
die beiden Buchungsformulare, den Zielbestand und darunter ihr eigenes Journal.
Die Eingabefelder übernehmen `step` und `unit` aus dem Vorrat.

**Dashboard:** zweite Footer-Kachel neben der Futter-Kachel mit demselben
Klick-Dialog; der Dialog bekommt den Vorrat als Parameter statt zweier Kopien.
Kachel- und Dialog-Markup bleiben **direkt in `dashboard.component.html`** — die
`lumina`-Styles sind in `dashboard.component.scss` gekapselt und würden in einer
Kind-Komponente lautlos nicht greifen.

**`/tablet/toni`:** die bestehende Futter-Kachel zeigt einen zweiten Balken für
die Tabletten. Das 2×2-Raster bleibt unangetastet — ein fünfter Kasten würde es
sprengen, und der Höhenketten-Test dieser Ansicht misst genau diese Aufteilung.

**Warnschwelle:** `pet-food-level.util.ts` wird zu `pet-supply-level.util.ts`
und färbt nach `daysRemaining < 7` statt nach einer Dosenzahl. Damit
verschwindet die hart kodierte 7 aus `dashboard.component.ts` und aus
`pet-food.component.ts`; die Zahl steht danach an **zwei** Stellen statt an
dreien: in dieser Util und im Telegram-Flow. Für den Futtervorrat ändert sich
nichts — 7 Dosen sind bei einer Dose pro Tag genau 7 Tage.

## Tests

Backend:

- Eigene Marke je Vorrat: ein neu angelegter Vorrat holt beim ersten Lauf nichts nach
- Raster: 2,5 Tabletten ⇒ 400, 2,5 Dosen ⇒ akzeptiert
- `daysRemaining` bei beiden Verbrauchsraten
- 404 bei unbekanntem Vorratsschlüssel
- Die Entity-Id des Futters ist unverändert `sensor.pet_food_toni_cans`
- `SecurityRulesTest`: neue Pfade, Lesen als KIOSK erlaubt, Schreiben verboten

Frontend:

- Bestehende Specs auf die Liste umgestellt
- Kachel-Färbung hängt an der Reichweite, nicht mehr an der Dosenzahl
- Der Höhenketten-Test von `/tablet/toni` bleibt unverändert

## Rollout

1. Deploy — die Migration überträgt Bestand, Marke und Journal des Futters
   unverändert und legt VomiSan mit 0 Tabletten an
2. Realen Tablettenbestand über „Bestand korrigieren" erfassen, Zielbestand bei
   Bedarf abweichend von 60 setzen
3. **Danach** den Telegram-Warnflow auf `sensor.pet_food_toni_vomisan` per
   flow-mcp anlegen (create → deploy → enable), Bedingung auf das Attribut
   `daysRemaining`. Kein Trigger auf `value: "unavailable"` — der könnte nie
   feuern (der Übergang nach `unavailable` ist engine-weit unterdrückt)

## Bewusste Grenzen

- **Der erste Scheduler-Lauf nach dem Deploy zieht für VomiSan nichts ab**, er
  setzt nur die Marke. Der Abzug beginnt mit der nächsten Fütterungszeit.
- **Die Reichweite ist eine reine Division.** Sie weiß nichts von Kuren oder
  Tierarztterminen: wird VomiSan zeitweise abgesetzt, läuft der Abzug weiter und
  muss per Korrektur geradegezogen werden. Ein Pausieren-Schalter ist bewusst
  nicht Teil dieser Ausbaustufe.
- **Die Dosis steht in der Datenbank, aber es gibt kein Pflege-UI dafür.**
  `per_feeding` und `step` sind über die API nicht änderbar; eine geänderte
  Tierarzt-Dosis erfordert heute ein Changeset. Das ist die erste Stelle zum
  Nachziehen, falls sich das ändert.
- **Kein Aufräumjob für `pet_supply_transaction`.** Bei zwei bis vier Zeilen pro
  Tag bleibt die Tabelle auch nach Jahren klein.
