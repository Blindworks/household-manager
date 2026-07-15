# Design: Klima-Kachel im Tablet-Dashboard

**Datum:** 2026-07-15
**Status:** Approved (Design)

## Ziel

Die Energie-Kachel im Tablet-Dashboard („Lumina") wird zu einer **Klima-Kachel**
umgebaut. Statt PV-Erzeugung / Hausverbrauch / Netzbezug zeigt sie die
**Innentemperaturen der Räume im Vergleich zur Außentemperatur**, jeweils mit
einer Komfort-Bewertung.

## Scope

Betroffen ist ausschließlich die Kachel `lumina__energy-tile` in
`frontend/src/app/pages/dashboard/dashboard.component.html` (die Kachel im
Raum-Grid, die aktuell auf `/energy` verlinkt).

**Nicht betroffen:**
- Die separate Energiefluss-Gauge-Karte (`lumina__energy`) im Seitenbereich.
- Der Live-Energie-Stream (`EnergyLiveService`) — er versorgt weiterhin die
  Gauge-Karte.
- Die bestehende Temperatur-Seite (`/temperatures`) und ihr Zeitreihen-Endpoint.

## Datenquellen

Aktuelle Temperaturwerte stammen aus drei Quellen, die bereits im
`TemperatureSeriesService` aggregiert werden:
- **ZIGBEE** — je Gerät ein Innensensor (Name = `friendlyName`).
- **ALEXA** — je Appliance ein Innensensor (Air-Quality-Monitor, Name =
  `deviceName`).
- **WEATHER** — genau eine Serie „Außen" (DWD).

Bisher gibt es nur einen Zeitreihen-Endpoint. Für ein dauerpollendes Wand-Tablet
ist das Laden ganzer Tagesreihen nur für den letzten Wert verschwenderisch,
daher ein neuer schlanker „current"-Endpoint.

## Backend

### Neuer Endpoint

`GET /api/v1/temperatures/current`

Antwort: `List<CurrentTemperatureReading>` mit je Sensor:

| Feld          | Typ             | Beschreibung                          |
|---------------|-----------------|---------------------------------------|
| `sensorId`    | String          | z. B. `zigbee:12`, `alexa:...`, `weather:outdoor` |
| `name`        | String          | Anzeigename (Raum/Sensor bzw. „Außen") |
| `source`      | String          | `ZIGBEE` / `ALEXA` / `WEATHER`        |
| `temperature` | BigDecimal      | jüngster Temperaturwert               |
| `humidity`    | BigDecimal?     | jüngste Feuchte, falls vorhanden      |
| `measuredAt`  | LocalDateTime   | Zeitpunkt der Messung                 |

### Service

Neue Methode `getCurrent()` in `TemperatureSeriesService`. Struktur analog zu
`getSeries(...)`:
- Dasselbe resiliente `safe(source, supplier)`-Muster — fällt eine Quelle aus,
  wird sie geloggt und übersprungen, ohne die Gesamtantwort zu gefährden.
- Je Quelle eine „jüngster Wert"-Ermittlung:
  - **Zigbee:** je Gerät die neueste `TEMPERATURE`-Messung (plus, falls
    vorhanden, die neueste `HUMIDITY`-Messung).
  - **Weather:** die neueste `WeatherReading` mit gesetzter Temperatur.
  - **Alexa:** je Appliance die neueste `AlexaAirQualityReading` mit gesetzter
    Temperatur.
- Bevorzugt über Repository-Queries, die direkt den jüngsten Datensatz liefern
  (statt eine Serie zu laden und clientseitig das letzte Element zu nehmen).

### DTO

Neues DTO `CurrentTemperatureReading` (analog zu `TemperatureSensorSeries`,
Lombok `@Builder`).

## Frontend

### Model & Service

- Neues Interface `CurrentTemperatureReading` in
  `frontend/src/app/models/temperature.model.ts` (Felder wie oben; `time`/
  `measuredAt` als ISO-String).
- `TemperatureService.getCurrent(): Observable<CurrentTemperatureReading[]>`.

### Komfort-Bewertung

Reine, testbare Util-Funktion `temperature-comfort.util.ts` (analog zu
`weather-icon.util.ts`). Bildet eine Innentemperatur auf `{ label, tone }` ab:

| Bereich      | Label     | Ton (Farbe) |
|--------------|-----------|-------------|
| `< 19 °C`    | frisch    | blau        |
| `19–23 °C`   | angenehm  | grün        |
| `23–25 °C`   | warm      | amber       |
| `> 25 °C`    | heiß      | rot         |

Gilt nur für Innensensoren. Die Außentemperatur ist reine Referenz ohne
Komfort-Bewertung. Schwellen sind als Konstanten leicht anpassbar.

### Dashboard-Komponente

`dashboard.component.ts`:
- Lädt die aktuellen Temperaturen beim Start und aktualisiert sie periodisch
  (Intervall, z. B. 60 s; sauber via Subscription in `ngOnDestroy` abmelden).
- Speichert `temperatures: CurrentTemperatureReading[]`.
- Getter/Ableitungen:
  - `outsideTemperature` — die `WEATHER`-Zeile (Fallback: `--`).
  - `insideRows` — alle übrigen Sensoren als View-Model `TemperatureRow`
    mit `name`, `valueLabel` (z. B. `21,4°`), `comfortLabel`, `comfortTone`
    und `stale`.
- **Veraltet-Erkennung:** Ist `measuredAt` älter als eine Schwelle
  (Konstante, z. B. 1 h), gilt die Zeile als `stale`. Statt des Komfort-Worts
  wird „veraltet" gezeigt und die Zeile ausgegraut.

### Template & Styles

`dashboard.component.html`:
- Kachelinhalt ersetzen: Thermometer-Icon, „Außen"-Referenz-Chip oben rechts,
  Titel „Temperaturen", je Innensensor eine Zeile mit Komfort-Punkt (Farbe),
  Raumname, Komfort-Wort/„veraltet" und Temperatur.
- `routerLink` von `/energy` auf `/temperatures` ändern.

`dashboard.component.scss`:
- Neue `lumina__climate-*`-Klassen; die nur hier genutzten
  `lumina__energy-metric-*`-Klassen entfernen. Bestehende Kachel-Grundoptik
  (`lumina-card`, `lumina__room`) wird beibehalten.

## Fehlerbehandlung

- Backend: pro Quelle isoliert (`safe`), Teilausfälle sind unkritisch.
- Frontend: Ladefehler → Kachel zeigt neutralen Leer-/Platzhalterzustand
  (kein Absturz); Außen-Chip zeigt `--`, wenn keine `WEATHER`-Zeile vorliegt.

## Tests

- **Backend:** `TemperatureSeriesServiceTest` um Fälle für `getCurrent()`
  erweitern (je Quelle jüngster Wert; Quelle fällt aus → übersprungen; leere
  Quelle).
- **Frontend:**
  - `temperature-comfort.util` — Grenzfälle der Komfort-Bänder.
  - `temperature.service` — `getCurrent()` (Erfolg + Fehler).
  - Dashboard: Ableitung `insideRows` inkl. `stale`-Erkennung und Trennung
    Innen/Außen.
