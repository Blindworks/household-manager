# Design: Temperaturen-View unter „Umwelt"

**Datum:** 2026-07-14
**Status:** Freigegeben (Design)

## Ziel

Unter dem Menü **Umwelt** entsteht eine neue View **Temperaturen**. Sie zeigt alle
Temperatur-Entitäten des Haushalts — je Sensor eine eigene Kachel mit einem Graph über
Temperatur **und** Luftfeuchtigkeit.

## Ausgangslage

- `entity_states` spiegelt nur den **aktuellen** Zustand einer Entität; eine generische
  Historie existiert nicht ("Historie liegt in den Fachtabellen der Integrationen").
- Temperatur-/Feuchte-Zeitreihen liegen in drei getrennten Fachtabellen:
  - **Zigbee** (`zigbee_measurement`): typisierte `TEMPERATURE`/`HUMIDITY`-Messwerte pro
    Gerät, bereits per Range-Query abfragbar
    (`findByDeviceIdAndMeasurementTypeAndMeasuredAtBetweenOrderByMeasuredAtAsc`).
  - **Weather** (`weather_readings`): Außentemperatur (`temperature`) und Feuchte
    (`humidity`), Zeitstempel `readingTime`.
  - **Alexa** (`alexa_air_quality_readings`): Innentemperatur/-feuchte des Amazon-Monitors,
    Identität über stabile `applianceId`, Zeitstempel `readingTime`.

## Ansatz

Serverseitige Aggregation in **einem** neuen Endpoint. Das hält das Frontend einfach
(ein Service, ein Call) und kapselt die quellenspezifische Logik im Backend.

**Verworfene Alternativen:**
- *Frontend aggregiert die Einzel-Endpoints:* quellenspezifische Logik leckt ins Frontend,
  viele HTTP-Calls (Zigbee zusätzlich pro Gerät). Abgelehnt.
- *Generische Entity-History-Tabelle:* großer Umbau mit Liquibase-Migration, rückwirkend
  keine Daten. Über-Engineering für diesen Bedarf (YAGNI). Abgelehnt.

## Backend

### Endpoint
`GET /api/v1/temperatures?range=DAY|WEEK|MONTH`
- `range` optional, Standard `WEEK`.
- `TemperatureController` bleibt dünn und delegiert an den Service.

### Service
`TemperatureSeriesService` — der Kern:
- Löst `range` in ein `from`/`to`-Zeitfenster auf (`DAY`=24h, `WEEK`=7 Tage, `MONTH`=30 Tage).
- Fragt die drei Repositories ab und normalisiert in ein gemeinsames DTO.
- **Resilient:** Fällt eine Quelle aus (Exception), wird sie geloggt und übersprungen,
  statt die gesamte Antwort zu killen (analog zum Hook-Muster der Spiegel-Schicht).

### Quellen-Mapping
- **Zigbee:** Geräte ermitteln, die im Zeitfenster `TEMPERATURE`-Messwerte liefern; je Gerät
  eine Serie. Luftfeuchtigkeit wird pro Gerät gepaart (falls das Gerät auch `HUMIDITY`
  liefert). `sensorId = "zigbee:<deviceId>"`, `name = friendlyName`.
- **Weather:** eine Serie „Außen". `sensorId = "weather:outdoor"`.
- **Alexa:** je `applianceId` eine Serie. `sensorId = "alexa:<applianceId>"`,
  `name = deviceName`.

### DTOs
```
TemperatureSensorSeries {
  sensorId: String,          // stabile ID, quellenpräfixiert
  name: String,              // Anzeigename
  source: String,            // ZIGBEE | WEATHER | ALEXA
  temperature: TimeValue[],  // immer vorhanden
  humidity: TimeValue[]      // leer, wenn Sensor keine Feuchte liefert
}
TimeValue { time: LocalDateTime/ISO, value: BigDecimal }
```

### Repository-Ergänzungen (nur Query-Methoden, keine Liquibase-Migration)
- Zigbee: Query zur Ermittlung der Geräte mit `TEMPERATURE`-Messwerten im Zeitfenster;
  bestehende Range-Query je Gerät+Typ wiederverwenden.
- Weather: `findByReadingTimeBetweenOrderByReadingTimeAsc(from, to)`.
- Alexa: `findByReadingTimeBetweenOrderByReadingTimeAsc(from, to)` (im Service je
  `applianceId` gruppiert).

## Frontend

### Struktur
- Neue Seite `pages/temperatures/` (`temperatures.component.ts` + `.html` + `.scss`),
  standalone, lazy-geladen.
- `TemperatureService` (`services/temperature.service.ts`) → `getSeries(range)`.
- Model `models/temperature.model.ts` (`TemperatureSensorSeries`, `TimeValue`, `TimeRange`).

### Layout (Variante C — Kachel-Grid)
- Responsives Grid; je Sensor **eine Kachel** mit **einem ECharts-Line-Chart**.
- Temperatur auf der linken Y-Achse, Luftfeuchtigkeit als gestrichelte Linie auf der rechten
  Y-Achse (Dual-Y, analog `WeatherComponent.buildForecastChart`).
- Sensor ohne Feuchte → nur Temperaturlinie, keine rechte Achse.
- ECharts-Setup wie bestehende Seiten: `provideEchartsCore`, `LineChart`, `GridComponent`,
  `TooltipComponent`, `LegendComponent`, `CanvasRenderer`; Datums-/Zahlenformat `de-DE`.

### Bedienung
- Zeitraum-Umschalter oben: **24h / 7 Tage / 30 Tage**, Standard **7 Tage**, wirkt auf alle
  Kacheln gemeinsam und lädt neu.
- Zustände: Loading, Fehler (mit Retry-Hinweis), Leer („Keine Temperatursensoren gefunden").

### Navigation & Routing
- Route `temperatures` in `app.routes.ts` (Titel „Temperaturen - Household Manager").
- Nav-Eintrag „Temperaturen" unter „Umwelt" in `header.component.ts`, nach „Luftqualitaet"
  und „Wetter".

## Datenfluss

`TemperaturesComponent` → `TemperatureService.getSeries(range)` →
`GET /api/v1/temperatures?range=` → `TemperatureController` → `TemperatureSeriesService`
→ 3 Repositories → normalisierte `TemperatureSensorSeries[]` → je Eintrag eine ECharts-Kachel.

## Edge Cases

- Sensor ohne Luftfeuchtigkeit: nur Temperaturlinie, rechte Achse ausgeblendet.
- Keine Sensoren: Leer-Zustand.
- Ausfall einer Quelle: geloggt und übersprungen, übrige Kacheln erscheinen.
- Zeitzonen/Formatierung konsistent zu bestehenden Seiten (`de-DE`).

## Tests

- **Backend:** `TemperatureSeriesService`-Unit-Test mit Mock-Repositories — prüft
  Range-Auflösung, Normalisierung, Feuchte-Paarung pro Zigbee-Gerät, Gruppierung je
  Alexa-`applianceId` und Resilienz bei leerer/fehlerhafter Quelle.
- **Frontend:** `TemperatureService`-Spec (HTTP-Call + Params) und
  `TemperaturesComponent`-Spec (Kacheln rendern, Range-Umschalter löst Neuladen aus,
  Leer-Zustand).

## Nicht im Scope

- Persistierung einer generischen Entity-History.
- Live-Aktualisierung (SSE); die Seite lädt bei Aufruf und beim Umschalten des Zeitraums.
- Weitere Messgrößen (Druck, PM2.5 etc.).
