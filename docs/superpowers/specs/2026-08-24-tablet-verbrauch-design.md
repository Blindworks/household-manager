# Tablet-Ansicht „Verbrauch" (Strom, Gas, Wasser)

**Datum:** 2026-08-24
**Status:** Entwurf abgestimmt, Umsetzung offen

## Ziel

Eine vierte Tablet-Unteransicht unter `/tablet/consumption`, die den Haushaltsverbrauch
von Strom, Gas und Wasser als Balkendiagramme zeigt — analog zu `/tablet/temperatures`
und `/tablet/air-quality`, aber auf einer grundlegend anderen Datenlage.

## Datenlage

Zählerstände werden **wöchentlich von Hand** erfasst (freitags; verpasste Freitage
erzeugt das Backend als Schätzwerte mit `estimated: true`). Jede Ablesung trägt ein
bereits berechnetes `consumption`-Feld — den Verbrauch seit der Vorablesung.

Daraus folgt: Es gibt keine hochauflösende Zeitreihe wie bei Temperatur oder
Luftqualität. Die kleinste sinnvolle Einheit ist **eine Ablesewoche**.

## Backend

### Endpunkt

```
GET /api/v1/meter-readings/series?resolution=WEEK|MONTH&range=<ConsumptionRange>
```

Neuer `MeterConsumptionSeriesService` im Paket `service/` (Muster
`TemperatureSeriesService`), neuer Endpunkt im vorhandenen `MeterReadingController`.
Keine Schemaänderung, keine Liquibase-Migration.

### Eigenes Range-Enum

Neu: `ConsumptionRange` mit `WEEKS_8`, `WEEKS_26`, `WEEKS_52`, `MONTHS_6`,
`MONTHS_12`, `MONTHS_24`.

**Warum nicht `SeriesRange`:** Das bestehende `SeriesRange` (DAY/WEEK/MONTH)
beschreibt bei Temperatur und Luftqualität *Fenster von Tagen* und kann Wochen- oder
Monatszahlen nicht ausdrücken. Es bedient bereits zwei Serien-Services; es an eine
dritte, andersartige Bedeutung anzupassen wäre der teurere und riskantere Weg.
`SeriesRange` und `SeriesDownsampler` bleiben unangetastet.

### Antwortform

```json
[
  {
    "meterType": "ELECTRICITY",
    "unit": "kWh",
    "points": [
      { "periodStart": "2026-08-14", "label": "KW 33", "consumption": 38.2, "estimated": false }
    ]
  }
]
```

`unit` kommt aus derselben Zuordnung wie im Frontend (`kWh` für Strom, `m³` für Gas
und Wasser).

### Aggregation

- Der Service **summiert nur** das vorhandene `consumption` je Ablesung. Er rechnet
  keine Zählerstandsdifferenzen neu — die Verbrauchsberechnung bleibt an genau einer
  Stelle im bestehenden `MeterReadingService`.
- Bei `resolution=WEEK` ist ein Punkt eine Ablesung.
- Bei `resolution=MONTH` gehört eine Woche in den Monat ihres **Ablesedatums**. Eine
  Woche, die über den Monatswechsel reicht (z. B. 29.09.–06.10.), zählt also
  vollständig in den Oktober. Bewusst gewählt gegen tagesgenaues Aufteilen: der
  Balken entspricht so weiterhin echten Ablesungen, und die Zahl deckt sich mit dem,
  was in der Ablesungsliste steht. Preis: an Monatsgrenzen ist der Wert leicht unscharf.
- Ein Monatsbalken gilt als `estimated`, sobald **mindestens eine** beitragende
  Ablesung geschätzt war. Sonst verschwände eine geschätzte Woche in einem sonst
  echten Monat spurlos.

### Randfälle

- Die **allererste Ablesung** eines Typs hat kein `consumption` (kein Vorgänger) und
  fällt aus der Serie.
- Ein Typ **ohne jede Ablesung** fehlt in der Antwort — es wird keine leere Serie
  geliefert, damit das Frontend keine leeren Diagramme zeichnen muss.
- Ein Fehler bei einem Typ ist **isoliert** (Muster `TemperatureSeriesService.safe`):
  Wasser fällt nicht aus, weil Gas scheitert.

### Sicherheit

Der Pfad fällt unter die generische Regel `GET /v1/**` → KIOSK
(`SecurityConfig`, Zeile ~170). **Keine eigene Zeile** in `SecurityConfig` — das
Wandtablet liest den Endpunkt ohne Änderung. `SecurityRulesTest` hält beide
Richtungen fest: KIOSK darf lesen, `POST /v1/meter-readings` bleibt MEMBER.

## Frontend

### Struktur

- Neue Seite `pages/tablet-consumption/`
- Route `tablet/consumption` in `app.routes.ts` (lazy, wie die anderen Tablet-Routen)
- Eintrag in `TABLET_VIEWS` (`shared/tablet-views.ts`), Icon `electric_meter`,
  Label „Verbrauch" — dadurch erscheint die Ansicht automatisch in der Leiste des
  Dashboards **und** aller Tablet-Unterseiten, weil beide dieselbe Konstante lesen.
- Neuer `MeterConsumptionSeriesService` (`services/`) plus Modell
  (`models/meter-consumption-series.model.ts`).
- Der bestehende `MeterReadingService` bleibt unberührt; die Website-Seiten
  `/consumption-charts` und `/meter-readings` ändern sich **nicht**.

### Layout

Inhalt in `<app-tablet-shell heading="Verbrauch">`, drei Kacheln **nebeneinander**
(Strom, Gas, Wasser) in einem Raster mit `grid-auto-rows: 1fr`, ohne Scrollen.

52 Balken in einer Drittelspalte werden schmal (grob 4–6 px). Das ist der bewusst
akzeptierte Preis der Nebeneinander-Anordnung: als Verlaufsbild funktioniert es,
einzelne Wochen liest man dort nicht mehr ab.

### Umschalter (im `[shellActions]`-Slot der Shell)

Zwei gekoppelte Umschalter:

| Auflösung | Zeiträume            | Default    |
|-----------|----------------------|------------|
| Woche     | 8 / 26 / 52 Wochen   | 26 Wochen  |
| Monat     | 6 / 12 / 24 Monate   | 12 Monate  |

Ein Wechsel der Auflösung tauscht die Zeitraumknöpfe aus und wählt den **Default der
neuen Auflösung** — nicht den gleichen Index. Sonst landete man von „8 Wochen" bei
„6 Monaten" und die Ansicht spränge auf einen ganz anderen Maßstab, ohne dass das
angetippt wurde.

Jeder Wechsel kostet einen Abruf (die Aggregation liegt serverseitig). Der Zustand
wird wie bei den anderen Tablet-Ansichten **nicht** persistiert.

### Kachelkopf

- Links der Titel („Strom", „Gas", „Wasser")
- Rechts groß der **letzte Wert** mit Einheit
- Darunter klein der **Vergleich zur Vorperiode**: „+12 % ggü. Vorwoche" bzw.
  „ggü. Vormonat"
- Bei nur einem Datenpunkt entfällt der Vergleich wortlos, statt „+0 %" zu behaupten
- Der Vergleich wird bewusst **nicht eingefärbt**: ob mehr Verbrauch schlecht ist,
  hängt an Jahreszeit und Anlass — eine rote Zahl im Winter wäre eine Wertung, die
  diese Seite nicht treffen kann

### Balken

- Farbe je Typ aus `MeterTypeUtils.getColor` (Strom orange, Gas blau, Wasser grün) —
  die einzige Definition, geteilt mit den Website-Seiten
- Geschätzte Balken: dieselbe Farbe mit reduzierter Deckkraft plus gestricheltem Rand
- Unter dem Diagramm eine Legendenzeile „blass = Schätzwert", sonst rät man auf der Wand

### Aktualisierung

Selbst-Refresh alle 5 Minuten wie die anderen Tablet-Ansichten. Ein fehlgeschlagener
Hintergrundabruf behält die letzten Werte **stumm**; nur der Erstabruf meldet einen
Fehler.

## Höhenkette

`flex: 1` + `min-height: 0` müssen **lückenlos** von `.app-layout` (100vh) über das
`:host` der Seite, die Shell und das Raster bis zum Chart-Element durchlaufen. Fehlt
ein Glied, fällt alles darunter auf Inhaltshöhe zurück und die Diagramme schrumpfen
auf fast nichts — genau so bei der Temperaturansicht real passiert, weil deren
Host-Element die Regel zunächst nicht hatte.

## Tests

### Backend — `MeterConsumptionSeriesServiceTest`

- Wochen-Aggregation: eine Ablesung ergibt einen Punkt
- Monats-Aggregation inklusive der Woche über den Monatswechsel (zählt in den Monat
  des Ablesedatums)
- `estimated` einer Woche schlägt auf den Monatsbalken durch
- Erste Ablesung ohne `consumption` fällt aus der Serie
- Typ ohne Ablesungen fehlt in der Antwort
- Ein Fehler bei einem Typ kippt die anderen nicht

Dazu beide Security-Richtungen in `SecurityRulesTest`.

### Frontend — `tablet-consumption.component.spec.ts`

- Aufbau der Kacheln aus einer Serie
- Vorperiodenvergleich, inklusive „entfällt bei genau einem Punkt"
- Auflösungswechsel setzt den Zeitraum auf den Default der neuen Auflösung und löst
  **genau einen** Abruf aus. Dafür ein `Subject` statt `of(...)`: ein synchroner Stub
  verdeckt genau die Reihenfolge, um die es geht (dieselbe Falle wie bei `setWalkDays`
  in der Toni-Ansicht)
- Stiller Refresh-Fehlschlag behält die Werte
- **Höhenketten-Test:** misst die Chart-Höhe bei **900 und 1200 px** (nicht 600/900 wie
  bei Temperatur und Luftqualität): die Kacheln tragen über dem Graphen einen
  zweizeiligen Kopf und darunter die Schätzwert-Legende, bei 600 px bliebe dem Graphen
  strukturell kaum etwas, ohne dass an der Kette etwas kaputt wäre. Der Host wird dafür
  in ein **selbst erzeugtes `div`** umgehängt, nicht in `host.parentElement` — das
  Fixture hängt in Karma direkt im `<body>` neben Karmas Elementen und den Wurzelknoten
  schon gelaufener Suiten, ein Flex-`<body>` verteilte die Höhe je nach Suite-Reihenfolge
  anders (daran sind die Temperatur- und Luftqualitätstests sporadisch gescheitert)

### Baseline

Der Frontend-Testlauf hat bereits 3 vorbestehende Fehlschläge (App/Hero) plus einen
bekannten SmartDeviceList-Flake. Diese zählen nicht als Regression dieser Arbeit.

## Bewusst nicht Teil davon

- Keine Kosten-Umrechnung über die Utility-Prices (Gas m³→kWh wäre eine eigene
  Entscheidung)
- Kein Vorjahresvergleich
- Keine Retention und kein Aufräumjob für `meter_readings`
- Keine Änderung an den bestehenden Website-Seiten `/consumption-charts` und
  `/meter-readings`
