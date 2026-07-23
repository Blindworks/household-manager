# Design: Verbrauchsgraph je Verbraucher

**Datum:** 2026-07-23
**Status:** Vom Nutzer freigegeben

## Ziel

Ein Klick auf einen Verbraucher in der Verbraucher-Kachel öffnet einen Dialog mit
dem Leistungsverlauf dieses Geräts (Watt über Zeit), umschaltbar zwischen
24 Stunden, 7 Tagen und 30 Tagen.

Baut auf [Verbraucher-Kachel](2026-07-22-verbraucher-kachel-design.md) auf.

## Kontext

- Die Verbraucher-Kachel zeigt alle Sensoren mit `deviceClass = "power"` außer den
  Nicht-Verbrauchern (`TASMOTA`, `ANKER_SOLIX`, `SHELLY`). Praktisch sind das heute
  ausschließlich Meross-Steckdosen.
- **Für diese Geräte existiert keine Historie.** `MerossElectricityPollingService`
  schreibt nur den aktuellen Zustand über `EntityStateService.reportState` in die
  Entity-State-Schicht (eine Zeile je Entität, bei jedem Poll überschrieben).
  Shelly und Tasmota haben eigene Historie-Tabellen, Meross nicht.
- Eine generische Entity-State-Historie gibt es ebenfalls nicht.
- `EntityStateService.reportState` ist die **einzige Schreibstelle** der Schicht und
  publiziert nach Commit ein `EntityStateChangedEvent` — inklusive `attributes`,
  `newState` und `timestamp`. Damit ist der Filter auf `deviceClass = "power"` direkt
  am Event möglich, ohne die Entität nachzuladen.
- Das Event feuert nur bei **Wertänderung**, nicht bei jedem Poll.

## Entscheidungen

1. **Graph-Inhalt:** Leistungsverlauf in Watt (Liniendiagramm). Kein kWh, keine Kosten.
2. **Zeiträume:** umschaltbar 24 h / 7 Tage / 30 Tage; Standard 24 Stunden.
3. **Persistenz:** generische Historie für Power-Sensoren per Event-Listener
   (nicht: Meross-Fachtabelle). Begründung: Die Kachel ist bewusst quellenoffen —
   eine Meross-Tabelle würde Geräte auf der Kachel zeigen, für die es keinen Graphen
   gibt. Der Listener sitzt hinter der einzigen Schreibstelle, die Integrationen
   bleiben unverändert.
4. **Aufbewahrung:** 30 Tage (der längste anzeigbare Zeitraum).
5. **Klickziel:** Verbraucherzeilen sind sowohl auf der Kachel als auch im
   „Alle Verbraucher"-Dialog klickbar.

## Backend

### Tabelle `entity_power_history` (Liquibase-Changeset 0038)

| Spalte        | Typ           | Bedeutung                                        |
| ------------- | ------------- | ------------------------------------------------ |
| `id`          | BIGINT, PK    | Auto-Increment                                   |
| `entity_id`   | VARCHAR(150)  | Entity-ID des Power-Sensors                      |
| `measured_at` | DATETIME      | Zeitpunkt der Messung                            |
| `power_watts` | DOUBLE, NULL  | Leistung; `NULL` = Sensor war nicht erreichbar   |

Index über (`entity_id`, `measured_at`) für die Bereichsabfragen.

Entity `EntityPowerHistory`, Repository `EntityPowerHistoryRepository` — das
Repository muss in `com.household.manager.repository` liegen (JpaConfig schränkt
das Scanning darauf ein).

### PowerHistoryRecorder

`@EventListener` auf `EntityStateChangedEvent`:

- Filter: `attributes.get("deviceClass")` gleich `"power"`.
- Numerischer `newState` → Zeile mit dem Wert.
- Nicht-numerischer `newState` (`unavailable`, `unknown`) → Zeile mit
  `power_watts = NULL`. **Absicht:** Der Graph unterbricht die Linie dort, statt
  über eine Offline-Phase hinwegzuzeichnen und Kontinuität vorzutäuschen.
- Vollständig in try/catch gekapselt: ein Fehler hier darf nie ins Polling
  zurückschlagen.

### PowerHistoryAggregationJob

`@Scheduled(fixedDelay = 60_000)`, Vorbild `ShellyReadingAggregationJob`:

- älter als 10 Minuten → auf Minuten verdichten (Mittelwert je `entity_id` + Minute)
- älter als 2 Tage → auf Stunden verdichten
- älter als 30 Tage → löschen

Beim Verdichten zählen nur Werte ungleich `NULL` in den Mittelwert. Enthält ein
Bucket ausschließlich `NULL`, bleibt eine `NULL`-Zeile stehen (die Lücke bleibt
sichtbar).

### API

`GET /v1/power-consumers/{entityId}/history?range=DAY|WEEK|MONTH` (Default `DAY`)

Antwort `PowerHistoryResponse`:

| Feld          | Typ              | Bedeutung                                  |
| ------------- | ---------------- | ------------------------------------------ |
| `entityId`    | String           | Entity-ID                                  |
| `displayName` | String           | customName vor friendlyName                |
| `points`      | Liste `TimeValue`| `{ time: ISO-String, value: Double\|null }` |

- `PowerRange`-Enum (`DAY(1)`, `WEEK(7)`, `MONTH(30)`) analog zu `TemperatureRange`.
- 404, wenn die `entityId` unbekannt ist oder nicht zu einem Power-Sensor gehört.
- Punkte aufsteigend nach Zeit.

### Tests

- `PowerHistoryRecorderTest`: schreibt bei Power-Sensor, ignoriert andere
  deviceClasses, `NULL` bei `unavailable`, ein Repository-Fehler wird geschluckt.
- `PowerHistoryAggregationJobTest`: verdichtet Minuten- und Stunden-Buckets,
  löscht über 30 Tage alte Zeilen, `NULL`-only-Buckets bleiben `NULL`.
- `PowerHistoryServiceTest`: Range-Grenzen, Sortierung, 404-Fälle.

## Frontend

- Model `PowerHistory`, `PowerHistoryPoint`, `PowerRange`.
- `PowerConsumerService.getHistory(entityId, range)`.
- Verbraucherzeilen werden klickbar (Kachel und „Alle Verbraucher"-Dialog);
  Klick öffnet den Graph-Dialog über dem Listendialog — dasselbe Schichtmuster wie
  Bestätigungsdialog über Schalter-Dialog.
- Dialog-Inhalt:
  - Titel = Gerätename.
  - Umschalter „24 Stunden / 7 Tage / 30 Tage", Standard 24 Stunden; ein Wechsel
    lädt die Serie neu.
  - ECharts-Liniendiagramm, `provideEchartsCore({ echarts })` auf der
    Dashboard-Komponente (Muster `temperatures.component.ts`), Y-Achse in W,
    Tooltip mit Zeit und Wert.
  - Leerzustand „Noch keine Daten aufgezeichnet".
  - Ladefehler: „Verlauf konnte nicht geladen werden."
- Escape schließt den Graph-Dialog (der Listendialog darunter bleibt offen).

### Tests

- Klick auf eine Zeile öffnet den Dialog mit der richtigen `entityId`.
- Beim Öffnen wird `getHistory` mit `DAY` gerufen.
- Zeitraumwechsel ruft `getHistory` mit dem neuen Range.
- Leere Antwort zeigt den Leerzustand.
- Ladefehler zeigt die Fehlermeldung.

## Fehlerbehandlung

- Recorder: alle Fehler werden geloggt und geschluckt (Polling darf nie brechen).
- API: unbekannte Entität → 404, nicht 500.
- Frontend: Ladefehler zeigt eine Meldung im Dialog; der Rest des Dashboards
  bleibt unberührt.

## Bekannte Einschränkungen

- **Der Graph ist nach dem Deploy leer** und füllt sich erst mit der Zeit.
  Rückwirkende Daten existieren nicht.
- Die Meross-Zugangsdaten liegen nur im Docker-Deployment vor; lokal liefert
  vermutlich kein Verbraucher echte Daten. Die Verifikation stützt sich deshalb auf
  Unit-Tests, die reale Sichtprüfung erfolgt auf dem Deployment.

## Nicht enthalten (YAGNI)

- Keine kWh-/Kostenrechnung, kein Vergleich mehrerer Geräte in einem Chart,
  kein Datenexport, kein Zoom/Brush, keine konfigurierbare Aufbewahrungsdauer.
