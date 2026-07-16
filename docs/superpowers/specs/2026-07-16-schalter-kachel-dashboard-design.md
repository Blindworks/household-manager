# Design: Schalter-Kachel im Tablet-Dashboard

**Datum:** 2026-07-16
**Status:** Draft (Design)

## Ziel

Die statische „Küche"-Platzhalterkachel im Tablet-Dashboard („Lumina") wird zu
einer funktionalen **Schalter-Kachel** umgebaut. Sie zeigt die **am häufigsten
genutzten Schalter** mit direktem Umschalter je Zeile. Ein Klick auf einen
Umschalter schaltet das Gerät **sofort**; ein separater Button im Kachel-Kopf
öffnet einen **Dialog mit allen Schaltern**.

## Scope

Betroffen ist die erste Raum-Kachel im `lumina__rooms`-Grid von
`frontend/src/app/pages/dashboard/dashboard.component.html` (aktuell der
`rooms[0]`-Eintrag „Küche"). Sie wird durch eine eigenständige Komponente
`app-switch-tile` ersetzt, die Kachel **und** Dialog kapselt.

**Schalter-Quellen (bewusst gewählt):**
- **SmartDevices** (`SWITCH`-Domain, Source `KASA`/`TAPO`/`MEROSS`) — bereits als
  Entitäten gespiegelt (`SmartDeviceEntityMapper`).
- **Manuelle Boolean-Helfer** (`INPUT_BOOLEAN`, Source `MANUAL`).

**Nicht betroffen / bewusst ausgeschlossen:**
- **Shelly** (separate Quelle, nicht in der Entity-Schicht gespiegelt) — nicht
  Teil dieses Umbaus.
- Der Schlafzimmer-Platzhalter (`rooms[1]`) bleibt unverändert.
- Bestehende Geräte-Endpoints (`/api/devices/**`) und die manuellen
  Entity-Endpoints bleiben unverändert und funktionsfähig.

## Ausgangslage (bestätigt)

- SmartDevices sind über `SmartDeviceEntityMapper` als `SWITCH`-Entitäten mit
  stabiler `entityId` (`switch.<source>_<slug(externalDeviceId)>`) und
  `sourceRef = externalDeviceId` gespiegelt.
- Manuelle Booleans sind `INPUT_BOOLEAN`/`MANUAL` mit stabiler `entityId`.
- Geschaltet wird bisher quellenspezifisch:
  - SmartDevice: `SmartDeviceService.turnOn(Long id)` / `turnOff(Long id)`.
  - Manuell: `ManualEntityService.toggle(entityId)`.
- Auflösung `SWITCH`-Entität → Gerät: `source` (KASA/TAPO/MEROSS) = `DeviceType`,
  `sourceRef` = `externalDeviceId` →
  `SmartDeviceRepository.findByDeviceTypeAndExternalDeviceId(...)` → `id`.
- Es gibt **kein** Nutzungs-Tracking und **kein** einheitliches Toggle über
  Quellen hinweg. Beides wird hier ergänzt.

## Backend

### 1. Nutzungs-Tracking (neu)

Liquibase-Changeset in `src/main/resources/db/changelog/changes/` für Tabelle
`entity_usage`:

| Spalte            | Typ         | Hinweis                          |
|-------------------|-------------|----------------------------------|
| `entity_id`       | varchar     | Primärschlüssel / unique         |
| `toggle_count`    | bigint      | Default 0                        |
| `last_toggled_at` | timestamp   | nullable                         |

Neue Bausteine:
- Entity `EntityUsage` (`model/entity`).
- `EntityUsageRepository` in `com.household.manager.repository` (JpaConfig-Scan).
- `EntityUsageService.recordToggle(entityId)` — Upsert: existiert kein Eintrag,
  wird er mit `toggle_count = 1` angelegt, sonst inkrementiert und
  `last_toggled_at` gesetzt. Konkurrenzsicher über den Unique-Key.
- `EntityUsageService.usageFor(Collection<String> entityIds)` → Map für die
  Listen-Anreicherung.

### 2. Einheitliches Schalten (neu) — `SwitchCommandService`

Im Paket `com.household.manager.entitystate`.

```
EntityState toggle(String entityId):
  entity = entityStateService.getByEntityId(entityId)   // 404 wenn unbekannt
  switch:
    INPUT_BOOLEAN + MANUAL -> manualEntityService.toggle(entityId)
    SWITCH + (KASA|TAPO|MEROSS) ->
        device = smartDeviceRepository
                   .findByDeviceTypeAndExternalDeviceId(DeviceType.valueOf(source), sourceRef)
                   // 404/409 wenn kein Gerät
        wenn state == "on": smartDeviceService.turnOff(device.id)
        sonst:              smartDeviceService.turnOn(device.id)
    sonst -> IllegalArgumentException (400)
  entityUsageService.recordToggle(entityId)   // nur bei Erfolg
  return aktueller EntityState
```

Hinweise:
- Für `SWITCH` aktualisiert sich der gespiegelte Entity-State erst über das
  Geräte-Polling/den Mapper (`reportState`). Die synchrone Antwort kann daher
  noch den alten State zeigen — das Frontend schaltet optimistisch um.
- `unavailable` (Gerät offline): Toggle wird anhand des zuletzt bekannten States
  versucht (`unavailable` → Behandlung wie „nicht on" → `turnOn`). Schlägt der
  Geräte-Call fehl, propagiert der Fehler (502) und Nutzung wird **nicht**
  gezählt.

### 3. Endpoints — `SwitchController` unter `/api/v1/switches`

- `GET /api/v1/switches?limit=N`
  - Liefert alle schaltbaren Entitäten (`SWITCH` + `INPUT_BOOLEAN`) als
    `SwitchResponse`.
  - Sortierung: **`toggleCount` desc → `lastToggledAt` desc (nulls last) →
    `displayName` asc**.
  - `limit` optional (Kachel ruft `limit=4`, Dialog ohne `limit`).
- `POST /api/v1/switches/{entityId}/toggle`
  - Ruft `SwitchCommandService.toggle`, gibt aktualisierte `SwitchResponse`
    zurück.

`SwitchResponse` (DTO):
`entityId, domain, source, displayName, state, available, icon, toggleCount, lastToggledAt`
- `displayName`: `customName` sonst `friendlyName` (wie bestehende
  `EntityStateResponseMapper`-Logik; wiederverwenden).
- `available`: `state != "unavailable"`.
- `icon`: aus Attributen (`icon`) für manuelle Entitäten, sonst Domain-Default
  (`toggle_on`).

## Frontend

### 4. Model + Service

- `models/switch.model.ts`: `SwitchEntity { entityId, domain, source,
  displayName, state, available, icon, toggleCount, lastToggledAt }`.
- `services/switch.service.ts`:
  - `getSwitches(limit?: number): Observable<SwitchEntity[]>`
  - `toggle(entityId: string): Observable<SwitchEntity>`

### 5. Komponente `app-switch-tile`

Eigenständige Einheit (Kachel **und** Dialog gekapselt), platziert im
`lumina__rooms`-Grid an Stelle der bisherigen Küche-Kachel.

**Kachel:**
- Kopf: Icon `toggle_on` + Titel „Schalter" + Button „Alle Schalter"
  (Icon `apps` / `expand_content`), der den Dialog öffnet.
- Körper: bis zu **4** Schalter (`getSwitches(4)`), je Zeile Name + Umschalter.
- Tippen auf den Umschalter → **optimistisches** Umschalten des Zustands +
  `toggle(entityId)`. Bei Fehler: Zustand zurücksetzen + dezenter Hinweis.
- Leerzustand: „Keine Schalter" wenn die Liste leer ist.
- Aktualisierung: Intervall ~30–60 s (Angleichung an das Dashboard-Muster) und
  nach jedem eigenen Toggle.

**Dialog:**
- Folgt dem bestehenden `lumina__dialog`-Backdrop-Muster (wie der
  Energiefluss-Dialog): Backdrop-Klick + Escape schließen, `role="dialog"`.
- Zeigt **alle** Schalter (`getSwitches()`), gleiche Toggle-Zeile.
- Nach Schließen aktualisiert die Kachel ihre (nutzungsbasierte) Reihenfolge.

**Wiederverwendung:** Die Toggle-Zeile (Name + Umschalter + Zustand/Verfügbarkeit)
ist ein gemeinsames Template/Teil-Element für Kachel und Dialog.

### 6. Dashboard-Anpassung

- `rooms[0]` („Küche") aus dem `rooms`-Array in `dashboard.component.ts`
  entfernen.
- `<app-switch-tile>` im `lumina__rooms`-Grid an erster Position einsetzen
  (vor dem Schlafzimmer-Platzhalter, nach der Klima-Kachel).
- Import der neuen Komponente in `DashboardComponent`.

## Datenfluss

1. Kachel-Init → `getSwitches(4)` → Render Top-4.
2. Toggle tippen → optimistischer State-Flip → `toggle(entityId)` →
   Erfolg: Liste neu laden (Reihenfolge kann sich ändern) / Fehler: Flip zurück.
3. Dialog öffnen → `getSwitches()` (alle) → Render; Toggles gleicher Pfad.
4. Geräte-Polling hält den gespiegelten State aktuell; die nutzungsbasierte
   Reihenfolge verschiebt sich mit steigenden Zählern.

## Fehlerbehandlung

- Gerät offline → `available = false`, Zeile zeigt „nicht verfügbar"; Toggle-Call
  kann 502 liefern → optimistischen State zurücksetzen + Hinweis.
- Unbekannte `entityId` → 404.
- Nicht-schaltbare Domain → 400.
- Nutzung wird **nur bei erfolgreichem** Toggle gezählt.
- Upsert des Zählers konkurrenzsicher über Unique-`entity_id`.

## Tests

**Backend:**
- `SwitchCommandService`: Routing manuell vs. SWITCH, `on→off`/`off→on`,
  `unavailable`-Behandlung, Usage-Increment nur bei Erfolg, 400/404-Fälle.
- `EntityUsageService`: Upsert (Neuanlage + Increment).
- `SwitchController`: GET-Sortierung (toggleCount → lastToggledAt → name), `limit`,
  POST-Toggle-Antwort.

**Frontend:**
- `SwitchService`: HTTP-Aufrufe (GET mit/ohne `limit`, POST toggle).
- `switch-tile`: rendert Top-N, Toggle ruft Service + optimistischer Flip,
  Fehler-Rücksetzung, Dialog öffnen/schließen, Leerzustand.

## Offene Defaults (bei Bedarf trivial änderbar)

- Kachel-Titel „Schalter" (Icon `toggle_on`).
- 4 Schalter auf der Kachel.
