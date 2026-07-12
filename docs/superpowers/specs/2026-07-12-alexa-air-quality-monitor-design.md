# Design: Amazon Smart Air Quality Monitor („Innenraum-Luftqualität")

**Datum:** 2026-07-12
**Status:** Entwurf zur Review

## Ziel

Zwei Amazon Smart Air Quality Monitore sollen in den Household-Manager integriert werden:

1. **Alle Sensorwerte** übernehmen: IAQ-Score, PM2.5, VOC, CO, Temperatur, Luftfeuchte.
2. **Historisierung** per Scheduled Polling in einer eigenen Tabelle (Charts wie bei Airrohr).
3. **Entity-States** melden, damit die Werte in der Entity-Schicht und als Flow-Trigger nutzbar sind.
4. **Frontend**: Integration in die bestehende Luftqualitäts-Seite (Airrohr-Charts) als neue Sektion „Innenraum" mit Kacheln pro Gerät und Verlaufscharts.

## Gewählter Ansatz

Der Monitor hat **keine lokale und keine offizielle API** — die Werte sind nur über die inoffizielle **Alexa-Smart-Home-API** (`/api/phoenix`) erreichbar, derselbe Weg, den Home Assistants alexa_media_player nutzt. Der bestehende **alexa-remote2-Sidecar** wird erweitert; die gevendorte Bibliothek bringt die nötigen Funktionen bereits mit:

- `getSmarthomeDevices()` — Discovery über `/api/phoenix?includeRelationships=true`, liefert alle Smart-Home-Geräte inkl. Capability-Metadaten.
- `querySmarthomeDevices()` — Zustandsabfrage über `POST /api/phoenix/state`.

Verworfene Alternativen:

- **Backend spricht Alexa-API direkt**: Der Eigenbau-Login am Amazon-OAuth2 ist bereits einmal gescheitert (deshalb existiert der Sidecar). Das Amazon-spezifische, brüchige Format bleibt im Sidecar isoliert.
- **Lokale Abfrage**: Nicht möglich, das Gerät bietet keine lokale Schnittstelle.

## Datenfluss

AQM → Alexa-Cloud → Sidecar (`/smarthome/...`) → `AlexaAirQualityPollingService` → DB (`alexa_air_quality_reading`) + Entity-State-Schicht → Frontend.

## Sidecar-Erweiterung (`alexa-sidecar/server.js`)

Zwei neue Endpoints, beide nur bei `loggedIn` (sonst 409, wie `/devices`):

```
GET  /smarthome/air-quality-monitors
     → [{applianceId, entityId, friendlyName, model, sensors: {pm25: <instance>, voc: <instance>, ...}}]

POST /smarthome/air-quality-monitors/state   {applianceIds: [...]}
     → [{applianceId, friendlyName, iaq, pm25, voc, co, temperature, humidity}]
```

- **Discovery** filtert die Geräteliste auf Amazon-AQMs (Erkennung über Hersteller/Modell bzw. die charakteristische Sensor-Capability-Kombination — wird im Spike festgezogen).
- **Instanz-Mapping**: Die Sensorwerte kommen als generische `Alexa.RangeController`-Instanzen; welche Instanz welcher Sensor ist, steht nur in den `friendlyNames` der Capability-Metadaten der Discovery-Antwort. Der Sidecar löst dieses Mapping auf und liefert dem Backend **flache, normalisierte Objekte** — das Amazon-Format verlässt den Sidecar nicht.
- Das Mapping wird als exportierte, reine Funktion implementiert und wie `isTtsCapable` getestet.
- Temperatur kann als `Alexa.TemperatureSensor` (statt RangeController) kommen — beide Fälle behandeln.

## Backend

Muster: Airrohr-Polling (`AirrohrPollingService`).

| Baustein | Verantwortung |
|---|---|
| `AlexaSidecarClient` (erweitert) | Zwei neue Methoden: `getAirQualityMonitors()`, `getAirQualityStates(ids)` |
| `AlexaAirQualityPollingService` | `@Scheduled`-Polling (Default 5 min, `alexa.air-quality.polling.interval-ms`), Discovery + State-Abfrage, Persistenz, Entity-State-Meldung, `getStatus()`/`triggerOnce()` |
| `AlexaAirQualityController` | REST-Endpoints (siehe unten) |
| `AlexaAirQualityReadingRepository` | in `com.household.manager.repository` (JpaConfig-Einschränkung) |

- **Geräte-Cache**: Discovery (Geräteliste + Instanz-Mapping) wird beim Polling gecacht und nur periodisch bzw. bei Fehlern erneuert — nicht bei jedem Poll zwei Cloud-Calls.
- **Entity-States**: pro Gerät und Sensor `sensor.alexa_<applianceIdKurzform>_<sensor>` mit neuem Enum-Wert `EntitySource.ALEXA` (existiert noch nicht, wird ergänzt), `friendlyName` aus Alexa-Gerätename + Sensorlabel, `unit`/`deviceClass` in den Attributen. Damit stehen die Werte automatisch der Flow-Engine als Trigger zur Verfügung.
- **Fehlerbehandlung**: Sidecar nicht erreichbar oder nicht eingeloggt → `lastError` im Status, Warn-Log, kein Crash. `ENDPOINT_UNREACHABLE` einzelner Geräte wird toleriert (das andere Gerät wird trotzdem gespeichert). Fehlende einzelne Sensorwerte → Spalte bleibt `null`.

### Datenmodell (Liquibase-Changeset)

**`alexa_air_quality_reading`** — Identität des Geräts über die stabile **`appliance_id`** (Lehre aus der Kasa-Integration: nie über Listenreihenfolge/IP):

| Spalte | Typ | Bemerkung |
|---|---|---|
| `id` | bigint PK auto | |
| `appliance_id` | varchar | stabile Alexa-Appliance-ID |
| `device_name` | varchar | Anzeigename zum Zeitpunkt der Messung |
| `reading_time` | datetime | Index zusammen mit `appliance_id` |
| `iaq` | int nullable | Indoor-Air-Quality-Score |
| `pm25` | decimal nullable | µg/m³ |
| `voc` | decimal nullable | ppb |
| `co` | decimal nullable | ppm |
| `temperature` | decimal nullable | °C |
| `humidity` | decimal nullable | % |

Keine eigene Gerätetabelle: Die Geräte kommen vollständig aus der Alexa-Discovery; Name und ID stehen denormalisiert am Reading.

### REST-API

```
GET  /api/alexa/air-quality/devices              → Geräteliste (aus Discovery-Cache)
GET  /api/alexa/air-quality/latest               → letzter Messwert pro Gerät
GET  /api/alexa/air-quality/history?applianceId=&from=&to=  → Zeitreihe
GET  /api/alexa/air-quality/polling/status       → {schedule, lastPollTime, lastError}
POST /api/alexa/air-quality/polling/trigger      → sofortiger Poll
```

## Frontend

Die bestehende **Airrohr-Charts-Seite wird zur gemeinsamen Luftqualitäts-Seite** erweitert:

- Neue Sektion **„Innenraum"** oberhalb/neben den Airrohr-Charts: eine **Kachel pro Monitor** mit den aktuellen sechs Werten, IAQ farbcodiert (grün/gelb/rot nach Amazons Skala: 0–50 gut, 51–100 mäßig, 101+ schlecht).
- **Verlaufscharts** (ECharts, Muster der vorhandenen Airrohr-Charts inkl. Jahr/Monat/Tag-Filter): Kennzahl wählbar, beide Geräte als getrennte Serien.
- Neuer `AlexaAirQualityService` (`services/`) + Model (`models/`); Anzeige-Labels und Einheiten zentral im Model.
- Ist der Sidecar nicht eingeloggt oder liegen keine Daten vor, zeigt die Sektion einen Hinweis statt leerer Kacheln (mit Verweis auf die Ansagen-Seite für den Login).

## Teststrategie

- **Sidecar**: Mapping-Funktion (Discovery-JSON → Sensor-Instanz-Map, State-JSON → flaches Objekt) als reine Funktion exportieren und mit echten (anonymisierten) Antwort-Fixtures testen.
- **Backend**: Unit-Tests für Polling-Service (Sidecar-Client gemockt): Persistenz, Entity-State-Meldung, Fehlerfälle (Sidecar down, Teilausfall eines Geräts, fehlende Sensorwerte).
- **Frontend**: Bestehende Testmuster der Seite; Chart-Aufbereitung als reine Methoden testbar.

## Offenes Risiko / erster Schritt

Das exakte JSON-Format der AQM-Capabilities und -States ist nicht hundertprozentig im Voraus bekannt. **Erster Implementierungsschritt ist ein Discovery-Spike**: Sidecar-Endpoints roh bauen, echte Antworten der zwei Geräte inspizieren, Mapping und Geräte-Erkennung danach festziehen. Erst dann werden Backend-Persistenz und Frontend umgesetzt.
