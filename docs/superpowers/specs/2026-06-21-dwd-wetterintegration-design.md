# DWD-Wetterintegration — Design

**Datum:** 2026-06-21
**Status:** Genehmigt (bereit für Implementierungsplan)

## Ziel

Die App zeigt das lokale Wetter für den Haushalt an: aktuelle Bedingungen, eine
Stundenvorhersage, amtliche Wetterwarnungen des DWD und einen Hinweis, ob
demnächst Regen erwartet wird. Zusätzlich wird ein Verlauf der tatsächlichen
Bedingungen in der Datenbank persistiert.

## Datenquelle

- **API:** DWD WarnWetter-App-API (community-dokumentiert auf dwd.api.bund.dev),
  kein API-Key, keine Authentifizierung.
- **Endpunkt:** `GET https://app-prod-ws.warnwetter.de/v30/stationOverviewExtended?stationIds=<id>`
  — ein einziger Call liefert `forecast1` (stündliche Arrays), `days`
  (Tagesvorhersage) und `warnings`.
- **Station:** `10637` (Frankfurt/Main) als konfigurierbarer Default für den
  Standort Bad Vilbel (61118). Über `application.properties` änderbar.

### Antwortstruktur (relevant)

- `forecastStart` (epoch ms), `forecast1.timeStep` (3600000 ms = 1 h)
- `forecast1.temperature[]`, `forecast1.precipitationTotal[]`,
  `forecast1.icon1h[]`, `forecast1.windSpeed[]`, `forecast1.windDirection[]`,
  `forecast1.humidity[]`, `forecast1.surfacePressure[]`
- `warnings[]`: `event`, `level`, `start`, `end` (epoch), `headline`,
  `description`, `descriptionText`, `instruction`, `warnId`

### Skalierung / Umrechnung

- Temperatur: Zehntel-°C → `/ 10` (z.B. 260 → 26,0 °C).
- Niederschlag/Wind: beim Parsen auf sinnvolle Einheiten umrechnen (genaue
  Faktoren während der Implementierung anhand der Live-Antwort verifizieren).
- `icon`/`icon1h`-Codes (numerisch) werden im Frontend auf Symbol +
  Beschreibung gemappt.

### „Regen demnächst"

Im Backend `precipitationTotal` über die nächsten ~24 h durchgehen, den ersten
Index mit Wert > 0 finden. Zeitpunkt = `forecastStart + index · timeStep`.
Kein Treffer → `null` (kein Regen erwartet).

## Backend

Paketstruktur und Konventionen gespiegelt am bestehenden Airrohr-Muster
(`com.household.manager`). Bestehende `RestTemplate`- und `ObjectMapper`-Beans
werden wiederverwendet.

### Service-Schicht

- **`DwdWeatherService`**
  - Ruft `stationOverviewExtended` über `RestTemplate` ab, parst mit
    `ObjectMapper` in Domain-DTOs, rechnet Einheiten um, berechnet `nextRain`.
  - **Caching:** einfacher zeitbasierter TTL-Cache im Service (Default 10 Min),
    um die DWD-Server zu schonen. Keine zusätzliche Dependency.
  - Robuste Fehlerbehandlung (leere/ungültige Antwort → aussagekräftige
    Exception), analog `AirrohrService`.

- **`WeatherPollingService`**
  - `@Scheduled` (Default alle 15 Min, `initialDelay` ~20 s).
  - Speichert pro Poll einen Snapshot der **Ist-Bedingungen** in
    `weather_readings`.
  - Hält `lastPollTime` / `lastError`, bietet `triggerOnce()` (wie
    `AirrohrPollingService`).

### Persistenz

- **Entity `WeatherReading`** + **`WeatherReadingRepository`** (Spring Data JPA).
- **Liquibase-Changeset** `20260621-0015-create-weather-readings-table.xml`,
  eingebunden in `db.changelog-master.xml`.
- **Tabelle `weather_readings`** — Felder:
  `id`, `reading_time` (NOT NULL), `temperature`, `precipitation`,
  `wind_speed`, `wind_direction`, `humidity`, `pressure`, `icon`, `condition`,
  `created_at`, `updated_at`; Index auf `reading_time`.
- Vorhersage und Warnungen werden **nicht** persistiert — immer live abgerufen,
  damit nie veraltete Warnungen angezeigt werden.

### DTOs

- **`WeatherOverviewResponse`** (Hauptdaten der Seite):
  - `current` — aktuelle Bedingungen (Temp, Niederschlag, Wind, Feuchte, Druck,
    Icon, Condition).
  - `hourlyForecast[]` — nächste Stunden: Zeit, Temperatur, Niederschlag, Icon.
  - `warnings[]` — `event`, `level`, `headline`, `descriptionText`,
    `instruction`, `start`, `end`.
  - `nextRain` — Zeitpunkt des nächsten erwarteten Regens oder `null`.
- **`WeatherReadingHistoryResponse`** — für den Verlauf-Chart.
- **`WeatherPollingStatusResponse`** — Status der Polling-Aufgabe.

### Controller

- **`WeatherController`**
  - `GET /weather/overview` — aktuelles Wetter + Vorhersage + Warnungen +
    nextRain.
  - `GET /weather/history?from=&to=` — persistierte Bedingungen für den Chart.
- **`WeatherPollingAdminController`** (analog Airrohr, in Admin-Seite eingebunden)
  - `GET /admin/weather-polling` — Status.
  - `POST /admin/weather-polling/trigger` — manueller Abruf.

### Konfiguration (`application.properties`)

```
# DWD Wetter
dwd.base-url=https://app-prod-ws.warnwetter.de/v30/stationOverviewExtended
dwd.station-id=10637
dwd.cache-ttl-ms=600000
dwd.polling.interval-ms=900000
dwd.polling.initial-delay-ms=20000
```

## Frontend (Angular 19, standalone, SCSS)

- **Model** `models/weather.model.ts` — Interfaces für Overview, Forecast,
  Warning, History. Kein `any`.
- **Service** `services/weather.service.ts` — `getOverview()`, `getHistory()`
  (RxJS-Observables).
- **Seite** `pages/weather/weather.component` (`.ts`/`.html`/`.scss`):
  - Route `/weather`, Nav-Eintrag „Wetter".
  - Aktuelle-Bedingungen-Karte (Symbol, Temperatur, Wind, Feuchte, Druck).
  - „Regen demnächst"-Hinweis (z.B. „Regen ab 16:00" oder „kein Regen in den
    nächsten 24 h").
  - Warnungsliste — prominent/farbig nach `level`, komplett ausgeblendet wenn
    keine aktiven Warnungen.
  - ECharts: Stundenvorhersage (Temperatur-Linie + Niederschlag-Balken) und
    Wetterverlauf aus der DB (`getHistory`).
- **Dashboard-Widget** — kompakte Karte (aktuelle Temp + Symbol +
  Warnungs-Badge), verlinkt auf `/weather`.
- **Icon-Mapping** — Hilfsfunktion DWD-`icon`-Code → Symbol + Text.

## Tests

- **Backend:** `DwdWeatherServiceTest` mit gespeichertem JSON-Fixture —
  prüft Parsing, Einheiten-Skalierung und `nextRain`-Logik (AAA-Muster).
- **Frontend:** schlanker Service-/Component-Test für `WeatherService` und
  `WeatherComponent`.

## Bewusst ausgeklammert (YAGNI)

- Persistierung von Vorhersage- und Warnungs-Historie.
- Mehrere Standorte/Stationen gleichzeitig.
- Regenradar-Karte (RADOLAN) — nicht Teil der gewünschten Funktionen.
