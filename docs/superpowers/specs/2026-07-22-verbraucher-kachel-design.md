# Design: Verbraucher-Kachel im Dashboard (ersetzt „Schlafzimmer")

**Datum:** 2026-07-22
**Status:** Vom Nutzer freigegeben

## Ziel

Die statische Platzhalter-Kachel „Schlafzimmer" im Lumina-Dashboard wird durch eine
Kachel ersetzt, die die aktuellen Stromverbraucher im Haus mit ihrer Live-Leistung
(Watt) anzeigt.

## Kontext

- Die Kachel „Schlafzimmer" ist der einzige Eintrag des Platzhalter-Arrays `rooms`
  in `frontend/src/app/pages/dashboard/dashboard.component.ts` — reine Statikdaten.
- Leistungswerte einzelner Verbraucher existieren bereits in der Entity-State-Schicht
  als `SENSOR`-Entitäten mit Attribut `deviceClass = "power"` (Einheit W):
  - Meross-Steckdosen: `sensor.meross_<uuid>_power` (z. B. Waschmaschine)
  - Shelly-Geräte: `sensor.shelly_<name>_power`
- Ebenfalls als Power-Sensoren vorhanden, aber **keine Einzelverbraucher** (Haus-Bilanz):
  - Tasmota: `sensor.tasmota_main_power` (Gesamt-Hausverbrauch)
  - Anker Solix: PV-/Akku-/Netz-/Hausleistung

## Entscheidungen

1. **Datenquelle:** Alle Power-Sensoren automatisch (keine kuratierte Liste).
   Haus-Bilanz-Quellen werden per `EntitySource` ausgeschlossen (`TASMOTA`,
   `ANKER_SOLIX`). Neue Steckdosen-Quellen erscheinen ohne Konfiguration.
2. **Anzeige:** Pro Gerät Name + Live-Leistung in W, absteigend nach Verbrauch
   sortiert. Geräte mit 0 W bleiben sichtbar (rutschen nach unten).
3. **Kapazität:** Kachel zeigt die Top 4; ein Expand-Button öffnet einen Dialog mit
   allen Verbrauchern (Muster der Schalter-Kachel).
4. **Architektur:** Dedizierter Backend-Endpoint (`GET /v1/power-consumers`) statt
   clientseitiger Filterung — konsistent mit `/v1/switches`, testbar, wiederverwendbar.

## Backend

### PowerConsumerQueryService (Package `com.household.manager.entitystate`)

- Liest `SENSOR`-Entitäten über den `EntityStateService`.
- Filter: Attribut `deviceClass == "power"`.
- Ausschluss: `EntitySource.TASMOTA` und `EntitySource.ANKER_SOLIX` (Haus-Bilanz,
  keine Einzelverbraucher).
- Sortierung: absteigend nach Watt; `unavailable`-Geräte ans Ende.
- Optionales `limit` kappt die Liste nach der Sortierung.

### DTO `PowerConsumerResponse`

| Feld          | Typ        | Bedeutung                                            |
| ------------- | ---------- | ---------------------------------------------------- |
| `entityId`    | String     | Entity-ID, z. B. `sensor.meross_<uuid>_power`        |
| `displayName` | String     | customName vor friendlyName (wie Schalter-Kachel)    |
| `powerWatts`  | BigDecimal | aktuelle Leistung; `null` bei `unavailable`          |
| `unavailable` | boolean    | true, wenn der Sensor-State `unavailable` ist        |

### PowerConsumerController

- `GET /v1/power-consumers?limit=<n>` — Muster wie `SwitchController`.
- Kein Schreibzugriff, reine Anzeige-API.

### Tests

- Unit-Test für die Filter- und Sortierlogik des `PowerConsumerQueryService`
  (Power-Filter, Source-Ausschluss, Sortierung, unavailable ans Ende, limit).

## Frontend

- `rooms`-Array und `RoomTile`-Interface werden ersatzlos entfernt (Schlafzimmer war
  der einzige Eintrag); auch der zugehörige `*ngFor`-Block im Template entfällt.
- Neue Verbraucher-Kachel an derselben Grid-Position, Markup direkt im
  Dashboard-Template (lumina-Styles sind in `dashboard.component.scss` gekapselt;
  Kind-Komponenten kämen nicht an sie heran):
  - Icon `bolt`, Titel „Verbraucher".
  - Liste der Top 4: Name links, Watt rechts (de-DE, ganzzahlig, z. B. „1.250 W").
  - `unavailable`-Geräte zeigen „–" statt eines Wertes.
  - Expand-Button (Muster Schalter-Kachel) öffnet einen Dialog mit allen
    Verbrauchern; Escape schließt ihn.
  - Leerzustand: „Keine Verbraucher".
- Neuer `PowerConsumerService` (`getConsumers(limit?)`) + Model `PowerConsumer`.
- Polling alle 30 s (`interval` + `startWith` + `switchMap`); `catchError` behält
  die zuletzt bekannte Liste (kein Flackern bei transienten Fehlern).
- Dialog lädt beim Öffnen die vollständige Liste; solange er offen ist, wird sie im
  selben 30-s-Takt aktualisiert.

## Fehlerbehandlung

- Backend: fehlende/nicht-numerische States werden als `unavailable` behandelt,
  nicht als Fehler.
- Frontend: Ladefehler behalten die letzte bekannte Liste; initialer Fehler zeigt
  den Leerzustand.

## Nicht enthalten (YAGNI)

- Keine Summenzeile, keine Historie/Charts, kein Schalten aus der Kachel heraus,
  keine Konfigurierbarkeit (Sichtbarkeitsregeln, Schwellwerte).
