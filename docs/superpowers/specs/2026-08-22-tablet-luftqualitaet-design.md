# Tablet-Ansicht "Luftqualität"

Datum: 2026-08-22

## Ziel

Eine zweite Unteransicht des Wandtablets unter `/tablet/air-quality`, gebaut wie
`/tablet/temperatures`: alle Luftqualitätssensoren gleichzeitig, ohne Scrollen,
selbst aktualisierend. Quellen sind der Airrohr-Feinstaubsensor (draußen) und die
Amazon Smart Air Quality Monitore (drinnen).

## Abgrenzung

* Temperatur und Luftfeuchte der Amazon-Monitore bleiben der bestehenden
  Temperaturansicht vorbehalten. Dieselbe Größe an zwei Wandkacheln zu zeigen
  stiftet keinen Nutzen.
* Der UBA-Luftqualitätsindex (`/v1/air-quality/overview`, amtliche Messstation)
  ist nicht Teil dieser Ansicht — sie zeigt die eigene Sensorik.
* Die Website-Seite `/air-quality` bleibt unverändert.

## Datenquellen

| Quelle | Tabelle | Messgrößen |
|---|---|---|
| Airrohr | `airrohr_readings` | `sdsP2` = PM2.5, `sdsP1` = PM10 (µg/m³) |
| Amazon  | `alexa_air_quality_readings` | IAQ (0–100), PM2.5 (µg/m³), VOC (ppb), CO (ppm) |

Airrohr ist genau ein Gerät (`airrohr.url` in `application.properties`), es gibt
keine Gerätetabelle. Es bekommt deshalb die feste Sensor-ID `airrohr:local` und
den Namen "Draußen". Amazon-Geräte tragen ihre `applianceId` und ihren
`deviceName`.

## Backend

### Endpunkt

`GET /api/v1/air-quality/series?range=DAY|WEEK|MONTH` (Default `WEEK`), neue
Methode im vorhandenen `AirQualityController`.

Antwort: Liste von `AirQualitySensorSeries`

```json
[
  { "sensorId": "airrohr:local", "name": "Draußen", "source": "AIRROHR",
    "metrics": { "pm25": [{"time": "...", "value": 8.10}], "pm10": [...] } },
  { "sensorId": "alexa:<applianceId>", "name": "Wohnzimmer", "source": "ALEXA",
    "metrics": { "iaq": [...], "pm25": [...], "voc": [...], "co": [...] } }
]
```

Eine Map `metrics` statt fester Felder je Größe: die Quellen liefern disjunkte
Mengen (Airrohr kennt kein IAQ, Amazon kein PM10). Feste Felder wären für die
Mehrzahl der Kombinationen dauerhaft leer, und jede künftige Größe erzwänge eine
Vertragsänderung. Der Schlüssel ist der Metrik-Schlüssel (`pm25`, `pm10`, `iaq`,
`voc`, `co`); eine Größe ohne Werte fehlt in der Map, statt als leere Liste zu
erscheinen.

Ein Sensor ohne einen einzigen Messpunkt im Zeitraum wird ganz weggelassen — eine
Kachel, die nichts zeigen kann, ist auf einer Wandanzeige nur Fläche.

### Service

Neuer `AirQualitySeriesService` nach dem Muster von `TemperatureSeriesService`:

* pro Quelle eine private Methode, jede in `safe(...)` gekapselt — fällt Airrohr
  aus, kommen die Amazon-Kacheln trotzdem. Der Fehler wird geloggt.
* serverseitige Mittelung auf Buckets über den vorhandenen Downsampler.

### Wiederverwendung statt Kopie

Zwei bestehende Bausteine sind bereits quellen-agnostisch, tragen aber
Temperatur-Namen. Sie werden umbenannt statt kopiert, die Aufrufer werden
mitgezogen:

* `TemperatureRange` → `SeriesRange` (Tage + Bucketlänge; unverändert
  DAY=1/5min, WEEK=7/30min, MONTH=30/2h)
* `TemperatureSeriesDownsampler` → `SeriesDownsampler`

`TimeValue` wird unverändert wiederverwendet.

Die Umbenennung berührt `TemperatureController`, `TemperatureSeriesService` und
deren Tests. Sie ist bewusst Teil dieser Arbeit: zwei Klassen mit identischem
Inhalt und verschiedenen Namen laufen sonst auseinander, sobald jemand die
Bucketlängen anpasst.

### Repository

`AirrohrReadingRepository` braucht eine Zeitfensterabfrage
(`findByReadingTimeBetweenOrderByReadingTimeAsc`); die Alexa-Entsprechung
existiert bereits und wird von `TemperatureSeriesService` genutzt.

### Sicherheit

Lesend, unterhalb `/v1/**` — die generische `GET /v1/**`-Regel macht den Endpunkt
KIOSK-lesbar, das Wandtablet erreicht ihn ohne eigene Zeile in `SecurityConfig`.

## Frontend

### Seite

`pages/tablet-air-quality/` in `<app-tablet-shell heading="Luftqualität">`.
Route `tablet/air-quality` mit `authGuard`, Titel "Luftqualitaet - Household
Manager". Neuer Eintrag in `TABLET_VIEWS` (`icon: 'air'`, Label
"Luftqualität") — damit erscheint die Ansicht automatisch in der Leiste des
Dashboards und aller Tablet-Unterseiten.

Neuer `AirQualitySeriesService` (Frontend) und Modell `air-quality-series.model.ts`.

### Aus der Temperaturansicht unverändert übernommen

* Raster: 2 Spalten, ab 6 Sensoren 3, `grid-auto-rows: 1fr`
* durchgehende Flex-Höhenkette bis zum Chart-Element (`flex: 1` + `min-height: 0`
  auf jeder Stufe inklusive `:host`)
* Selbst-Refresh alle 5 Minuten; ein fehlgeschlagener Hintergrundabruf behält die
  letzten Werte, nur der Erstabruf meldet einen Fehler
* Zeitraum-Umschalter 24 h / 7 Tage / 30 Tage, Default 7 Tage, projiziert über
  `[shellActions]`

### Messgrößen-Umschalter: Einfachauswahl statt Mehrfachauswahl

Der einzige bewusste Unterschied zur Temperaturansicht. Dort sind zwei Größen
frei kombinierbar, hier gäbe es vier Einheiten (µg/m³, Score 0–100, ppb, ppm) —
vier Y-Achsen in einer Wandkachel sind unlesbar. Gewählt wird deshalb genau eine
Gruppe:

| Gruppe | Linien | Achse |
|---|---|---|
| **Feinstaub** (Default) | PM2.5 + PM10 | µg/m³ |
| IAQ | IAQ | 0–100 |
| VOC | VOC | ppb |
| CO | CO | ppm |

Jede Kachel hat damit immer genau eine Y-Achse. "Feinstaub" fasst die beiden
Größen zusammen, weil sie sich eine Einheit teilen und nur gemeinsam
aussagekräftig sind.

Hat eine Kachel zur gewählten Gruppe keine Werte (Airrohr bei IAQ/VOC/CO), zeigt
sie den Hinweis "Keine Werte" statt eines leeren Diagramms — dieselbe Behandlung
wie bei den Temperaturen. Ein Gruppenwechsel baut die Charts aus den gehaltenen
Rohserien neu, ohne neuen Abruf.

### Aktueller Wert in der Kachelüberschrift

Zusätzlich zum Temperatur-Vorbild steht neben dem Kachelnamen der jüngste Wert
der gewählten Gruppe (bei Feinstaub der PM2.5-Wert), z. B. "Draußen · 8 µg/m³".
Beim IAQ wird er mit der Farbstufe aus `iaqLevel` hinterlegt. Auf einer
Wandanzeige ist der Jetzt-Wert die eigentliche Information, der Verlauf der
Kontext. Fehlt der Wert, entfällt der Zusatz wortlos.

## Tests

**Backend** — `AirQualitySeriesServiceTest` gegen Fake-Repositories:
* Airrohr liefert PM2.5/PM10, Amazon liefert IAQ/PM2.5/VOC/CO
* eine werfende Quelle kippt die Gesamtantwort nicht
* ein Sensor ohne Messpunkte im Fenster erscheint nicht
* eine Größe ohne Werte fehlt in der Map
* Bucket-Mittelung greift (mehrere Rohpunkte je Bucket → ein Mittelwert)

`SecurityRulesTest` bekommt eine Zeile für die KIOSK-Lesbarkeit des Endpunkts.

**Frontend** — `tablet-air-quality.component.spec.ts` analog zur
Temperaturansicht:
* Chart-Höhe bei 600 px und 900 px Viewport (hält die Flex-Kette fest — genau
  dort ist die Temperaturansicht schon einmal auf Nullhöhe kollabiert)
* Gruppenwechsel baut die Charts ohne neuen HTTP-Abruf neu
* Kachel ohne Werte zur gewählten Gruppe zeigt den Hinweis
* ein fehlgeschlagener Hintergrund-Refresh behält die vorherigen Kacheln

## Bewusst nicht Teil davon

* Grenzwert-Einfärbung der Feinstaubkurven (WHO/EU-Schwellen). Wäre nützlich,
  ist aber eine eigene Entscheidung darüber, welcher Grenzwert gilt.
* Ein Dashboard-Kachel-Gegenstück. Die Luftqualität steht dort bereits.
* Retention für `airrohr_readings` / `alexa_air_quality_readings`. Beide Tabellen
  wachsen unbegrenzt; der neue Endpunkt begrenzt immerhin die Abfrage auf ein
  Zeitfenster, statt wie die Website-Seite alles zu laden.
