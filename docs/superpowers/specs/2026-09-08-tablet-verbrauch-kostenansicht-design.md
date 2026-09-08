# Kostenansicht in der Tablet-Verbrauchsübersicht

Datum: 2026-09-08
Status: Design abgestimmt, Umsetzung offen

## Ziel

In `/tablet/consumption` lässt sich **jede Kachel einzeln** zwischen „Verbrauch" und
„Kosten" umschalten. Der Kachelkopf (großer Wert, Vorperiodenvergleich) folgt dem Modus.
Die Kosten sind die **verbrauchsabhängigen Kosten** (Arbeitspreis × Menge), kein
Rechnungsbetrag: Grundgebühr, Netzentgelte und Steuern sind nicht Teil des Modells.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Wasser | Wasserpreise werden freigeschaltet (Validierung und DB-Check erweitert), Preis in €/m³ |
| Bepreisung | Je Ablesewoche der am **Ablesedatum** gültige Preis; Monatsbalken summieren die bepreisten Wochen |
| Fehlender Preis | Der Balken **entfällt** in der Kostenansicht (keine erfundene 0 €) |
| Monatsregel | Fehlt einer beitragenden Woche der Preis, entfällt der **ganze** Monatsbalken (eine Teilsumme sähe aus wie ein billiger Monat) |
| Gas | Preis ist €/kWh, der Zähler zählt m³. Umrechnungsfaktor „kWh je m³" ist pflegbar (`application_settings`), Default 10,0 |
| Kopf | Folgt dem Modus (Wert und Vergleich in €) |
| Persistenz | Modus wird **nicht** gespeichert; nach Neuladen steht jede Kachel auf Verbrauch (wie Zeitraum und Auflösung) |
| Umsetzung | Kosten werden im bestehenden Serien-Endpunkt mitgeliefert; das Umschalten ist rein lokal, ohne Nachladen |

## Backend

### Wasserpreise freischalten

- Neues Liquibase-Changeset ersetzt den Check `chk_utility_price_meter_type` durch
  `IN ('ELECTRICITY','GAS','WATER')` (Muster `20260206-0005`, `DROP` + `ADD`).
- `UtilityPriceService.validateMeterType` entfällt samt allen Aufrufen.

### Gas-Umrechnungsfaktor

- Neuer `UtilityPricingSettingsService` (Muster `PresenceSettingsService`):
  Kategorie `UTILITY_PRICING`, Schlüssel `gas_kwh_per_m3`, Default `10.0`.
- Lesen wirft nie: unlesbar oder außerhalb **5 … 15** ⇒ Default und Warnung im Log.
- Endpunkt `GET/PUT /v1/utility-prices/settings` (`{ gasKwhPerM3: number }`), beide ADMIN.
  Validierung an der API-Grenze mit `Double.isFinite` und Bereich 5 … 15, sonst 400.
- Security: `/v1/utility-prices/settings` als **methodenloser ADMIN-Matcher vor** der
  Zeile `GET /v1/utility-prices/** → KIOSK` (sonst gewänne die KIOSK-GET-Regel).
- Audit `utility-pricing.settings.update` mit altem und neuem Wert.

### Kostenberechnung im Serien-Service

- `ConsumptionPoint` bekommt `BigDecimal cost` (nullable), `MeterConsumptionSeries`
  bekommt `String currency` (konstant `"EUR"`).
- Neuer `MeterCostCalculator`: einzige Definition von „was kostet Menge X am Datum Y".
  - Lädt die Preise eines Typs **einmal pro Serie** (`findByMeterTypeOrderByValidFromDesc`)
    und ordnet in Java zu (`validFrom <= datum < validTo`, `validTo == null` = unbegrenzt).
    Keine Query je Woche (bis zu 104 Wochen bei 24 Monaten).
  - Gas: `m³ × gasKwhPerM3 × Preis`; Strom und Wasser: `Menge × Preis`.
  - Kein passender Preis ⇒ `null`.
- `MeterConsumptionSeriesService.points()` bepreist jede Ablesewoche direkt nach der
  Differenz (vor der Aggregation). `mergeGroup` summiert die Kosten; ist ein Mitglied
  `null`, ist die Summe `null`. Rundung auf 2 Nachkommastellen **nach** der Summe.
- Ein Fehler beim Preisladen eines Typs macht nur dessen Kosten `null` (Warnung im Log),
  die Verbrauchsserie kommt trotzdem.
- Der Serien-Endpunkt bleibt über die generische `GET /v1/**`-Regel KIOSK-lesbar.

## Frontend

### Modell

- `ConsumptionPoint.cost: number | null`, `MeterConsumptionSeries.currency: string`.

### Tablet-Ansicht (`pages/tablet-consumption/`)

- `ConsumptionTile` hält die Rohserie und `mode: 'consumption' | 'cost'`.
  Der Modus lebt in einer `Map<MeterType, Mode>` in der Komponente und überlebt damit
  den 5-Minuten-Refresh und einen Zeitraum-/Auflösungswechsel (beide bauen die Kacheln
  neu). Nach dem Neuladen der Seite steht alles auf Verbrauch.
- Kachelkopf: rechts neben dem Titel ein zweiteiliger Umschalter „Verbrauch | Kosten"
  im Stil der bestehenden `__btn`-Gruppe, kompakter. Großer Wert und Vergleich folgen
  dem Modus.
- `consumption-view.util.ts`:
  - `formatCost(value, currency)` → „12,40 €" (2 Nachkommastellen, deutsches Komma,
    `–` bei `null`).
  - `compareToPrevious` wird auf einen Wertselektor verallgemeinert, damit Verbrauch
    und Kosten dieselbe Logik teilen (Nachbarschaftsprüfung, Null-Vorperiode ⇒ `null`).
    Im Kostenmodus werden nur Punkte **mit** Preis betrachtet, die letzten beiden
    bepreisten; sind sie nicht benachbart, gilt wie bisher „ggü. letztem Wert".
- Diagramm im Kostenmodus: nur Punkte mit `cost !== null`, Y-Achse `{value} €`,
  Tooltip mit `formatCost`. Schätzwert-Darstellung (blass, gestrichelt) bleibt in
  beiden Modi gleich. Umschalten baut nur die Optionen dieser Kachel neu.
- Hinweise: sind im Kostenmodus Balken entfallen, steht unter dem Diagramm
  „N Perioden ohne hinterlegten Preis" (neben der Schätzwert-Legende). Sind **alle**
  Balken ohne Preis, steht statt des Diagramms „Kein Preis hinterlegt"; der Umschalter
  bleibt bedienbar.
- Höhenkette: der Umschalter kommt in den bestehenden Kachelkopf, die Flex-Kette
  bleibt unberührt. Der Höhenketten-Test (900/1200 px) muss danach weiterhin grün sein.

### Preisseite (`pages/utility-prices/`, `components/utility-price-form/`)

- `meterTypes` in Seite und Formular um `WATER` erweitern; Untertitel
  „Strom-, Gas- und Wasserpreise".
- Neues Feld „Gas: kWh je m³" mit Speichern-Knopf oberhalb der Tabellen, nur für ADMIN
  sichtbar (`GET/PUT /v1/utility-prices/settings`).
- `MeterTypeUtils.getPriceUnit` bleibt (Strom kWh, Gas kWh, Wasser m³) und ist damit
  erstmals stimmig zur Rechnung.

## Tests

**Backend**
- `MeterCostCalculatorTest`: Preis am Ablesedatum, Preiswechsel im Fenster, Gasfaktor,
  fehlender Preis ⇒ `null`, `validTo == null` unbegrenzt.
- `MeterConsumptionSeriesServiceTest` erweitert: Monatsregel bei teilweise fehlendem
  Preis, Rundung nach der Summe, Preisfehler kippt nur die Kosten.
- `UtilityPricingSettingsServiceTest`: Default, unlesbar, unplausibel.
- `SecurityRulesTest`: Settings-Pfad für KIOSK (GET und PUT) und MEMBER verboten.
- `UtilityPriceServiceTest`: Wasser wird angenommen.
- Controller-Test: 400 bei `NaN`, Bereichsverstoß.

**Frontend**
- `consumption-view.util.spec`: `formatCost`, Vergleich im Kostenmodus überspringt
  Punkte ohne Preis.
- `tablet-consumption.component.spec`: Umschalten ändert Kopfwert, Y-Achsen-Format und
  Balkenzahl; Modus überlebt Refresh und Zeitraumwechsel; „Kein Preis hinterlegt" bei
  leerer Kostenserie; Höhenkette bei 900/1200 px.
- Preisseite: Wasser in der Auswahl; Faktorfeld nur für ADMIN.

## Bewusste Grenzen

- Nur Arbeitspreis; Grundgebühr, Netzentgelte und Steuern fehlen. Der Wert ist
  „verbrauchsabhängige Kosten", kein Rechnungsbetrag.
- Der Gasfaktor gilt für die gesamte Historie. Ändert der Versorger den Brennwert,
  verschiebt sich rückwirkend die ganze Kurve. Ein zeitabhängiger Faktor wäre eine
  eigene Ausbaustufe.
- Ein Preiswechsel mitten in einer Ablesewoche zählt ganz zum Preis am Ablesedatum
  (analog zur Monatsregel „eine Ablesewoche zählt ganz in den Monat ihres Ablesedatums").
- Die Website-Seite `/meter-readings` bleibt unverändert; den Kostenmodus gibt es nur
  im Tablet.

## Rollout

Deploy (Changeset erweitert den Check) → auf der Preisseite Wasserpreis erfassen und
den Gasfaktor aus der letzten Gasrechnung eintragen (Brennwert × Zustandszahl) →
Kostenkurven gegen die Abrechnung plausibilisieren.
