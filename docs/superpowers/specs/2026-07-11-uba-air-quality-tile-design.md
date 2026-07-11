# Luftqualitäts-Kachel (UBA) im Weather-View

## Ziel

Im Weather-View rechts neben der aktuellen Wetter-Kachel eine zweite Kachel mit der
Luftqualität anzeigen. Datenquelle ist der **Luftqualitätsindex des Umweltbundesamtes
(UBA)** – der DWD liefert selbst keine Luftqualitätsdaten.

## Datenquelle (verifiziert)

- Endpoint: `https://www.umweltbundesamt.de/api/air_data/v3/airquality/json`
  - Query: `date_from`, `time_from`, `date_to`, `time_to` (Stunden 1–24), `station=<id>`, `lang=de`
  - Redirect: HTTP→HTTPS 301, daher Follow-Redirects nötig (RestTemplate folgt Redirects standardmäßig).
- Antwortstruktur:
  ```
  data["<stationId>"]["<start CET>"] = [
     "<ende CET>",         // [0]
     <total index>,        // [1]  0=sehr gut ... 4=sehr schlecht, -1=keine Daten
     <incomplete 0|1>,     // [2]
     [<compId>, <value>, <subIndex>, "<y>"],   // [3..] je Schadstoff
     ...
  ]
  ```
  Jüngster Eintrag = letzter Schlüssel im Zeit-Map. Abfrage über Zeitraum
  „gestern 01:00" bis „heute 24:00", dann letzten Eintrag nehmen.
- Komponenten-Metadaten: `.../components/json?lang=de`
  (ID → Code/Symbol/Einheit/Name). Relevante IDs:
  1=PM₁₀ (µg/m³), 2=CO (mg/m³), 3=O₃ (µg/m³), 4=SO₂ (µg/m³), 5=NO₂ (µg/m³), 9=PM₂,₅ (µg/m³).
- Default-Station: **636 / DEHE008 „Frankfurt Ost"** (urbaner Hintergrund, passend zur
  DWD-Station 10637). Per Config änderbar.

## Index → Kategorie (Anzeige)

| Index | Label         | Farbe (EEA/UBA) |
|-------|---------------|-----------------|
| 0     | Sehr gut      | `#50f0e6`       |
| 1     | Gut           | `#50ccaa`       |
| 2     | Mäßig         | `#f0e641`       |
| 3     | Schlecht      | `#ff5050`       |
| 4     | Sehr schlecht | `#960032`       |
| -1    | Keine Daten   | `#94a3b8`       |

## Backend

Ansatz **A**: eigener Service + Endpoint, unabhängig vom DWD-Code.

- `UbaAirQualityService`
  - Ruft `airquality/json` für die konfigurierte Station ab, wählt den jüngsten Eintrag.
  - TTL-Cache analog `DwdWeatherService` (`volatile` + `synchronized`), keine DB-Persistenz.
  - Komponenten-Mapping (ID → Name/Symbol/Einheit) als interne Konstante, aus den
    UBA-Metadaten abgeleitet (statisch, ändert sich praktisch nie).
- Config (`application.properties`):
  - `uba.base-url=https://www.umweltbundesamt.de/api/air_data/v3/airquality/json`
  - `uba.station-id=636`
  - `uba.cache-ttl-ms=600000`
- `AirQualityController`: `GET /api/air-quality/overview` → `AirQualityOverviewResponse`.
- Fehler: leere/unparsbare Antwort → `IllegalStateException`; globaler Handler / Controller
  liefert Fehlerstatus. Frontend behandelt das eigenständig.

### DTO

```
AirQualityComponent { code, symbol, name, value (BigDecimal), unit, index (int) }

AirQualityOverviewResponse {
  stationId (String)
  dateTime (LocalDateTime)      // Start-Zeit des jüngsten Eintrags (CET)
  overallIndex (int)            // 0..4, -1 = keine Daten
  incomplete (boolean)
  components (List<AirQualityComponent>)  // alle gelieferten Schadstoffe
}
```
Stationsname kommt nicht aus dem airquality-Endpoint; wird nicht mitgeliefert
(Kachel zeigt konfigurierte Station-ID/Namen optional statisch – siehe Frontend).

## Frontend

- `air-quality.model.ts`: Interfaces analog zum DTO.
- `AirQualityService`: `getOverview(): Observable<AirQualityOverview>` (HttpClient, `/api/air-quality/overview`).
- `air-quality.util.ts`: `airQualityCategory(index)` → `{ label, color }`.
- Weather-View:
  - Die bisherige `weather__current`-Kachel und die neue Luftqualitäts-Kachel liegen in
    einer Flex-Row (`weather__tiles`), die auf schmalen Screens umbricht (Kacheln stapeln).
  - Luftqualitäts-Kachel: großer farbiger Gesamt-Index + Label oben, darunter alle
    Schadstoffe als kompaktes Grid (Symbol, Wert, Einheit).
  - Eigener Lade-/Fehlerzustand: schlägt der UBA-Abruf fehl, zeigt die Kachel dezent
    „Luftqualität nicht verfügbar", ohne die Wetteranzeige zu beeinträchtigen.

## Fehlerbehandlung

- Backend wirft bei leerer/fehlerhafter Antwort; Controller-Fehler → Frontend-`error`-Callback.
- Frontend: Luftqualität wird unabhängig vom Wetter geladen; ein Fehler betrifft nur die Kachel.

## Tests

- `UbaAirQualityServiceTest`: parst gespeichertes UBA-JSON-Sample
  (`src/test/resources/uba-airquality-sample.json`):
  - jüngster Eintrag wird gewählt,
  - Gesamt-Index/incomplete korrekt,
  - alle Komponenten mit Symbol/Einheit gemappt,
  - fehlende/leere Antwort → `IllegalStateException`.

## Out of Scope (YAGNI)

- Keine Historie/DB-Persistenz der Luftqualität.
- Kein Polling-Scheduler (nur TTL-Cache beim Abruf).
- Keine Stations-Auswahl im UI (nur Config).
