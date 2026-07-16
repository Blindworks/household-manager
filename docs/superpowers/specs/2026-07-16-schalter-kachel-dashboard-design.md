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
- `SmartDeviceService.turnOn/turnOff` meldet den neuen Zustand bereits selbst
  über `reportEntityState(device)` an die Entity-Schicht. Ein Neuladen der
  Entität nach dem Befehl liefert daher den **aktuellen** Zustand — die Antwort
  ist ohne Zusatzaufwand korrekt.
- `unavailable` (Gerät offline): Toggle wird anhand des zuletzt bekannten States
  versucht (`unavailable` → Behandlung wie „nicht on" → `turnOn`). Schlägt der
  Geräte-Call fehl, propagiert der Fehler (502) und Nutzung wird **nicht**
  gezählt.
- Nur `SmartDeviceEntityMapper` erzeugt überhaupt `SWITCH`-Entitäten (Zigbee und
  Shelly liefern `SENSOR`/`BINARY_SENSOR`). Die Quellen-Prüfung hält Liste und
  Toggle dennoch konsistent: `SwitchableEntities` ist die **eine** Regel, die
  beide nutzen, damit nie ein Schalter erscheint, den der Toggle ablehnt.

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

### 5. Präsentationskomponente `app-switch-list`

Die Toggle-Zeile (Icon + Name + Zustand + Umschalter) wird **einmal** als
präsentationale Komponente gebaut und von Kachel **und** Dialog verwendet.

- `@Input() switches: SwitchEntity[]`
- `@Input() pendingIds: ReadonlySet<string>` — Zeilen mit laufendem Schaltbefehl
  (verhindert Doppelklicks).
- `@Input() variant: 'tile' | 'dialog'` — die Kachel ist dunkles Glas, der Dialog
  hell; die Zeile bringt beide Tonalitäten als Host-Klasse mit.
- `@Output() toggled = EventEmitter<SwitchEntity>`
- Enthält **keine** Service-Aufrufe und keinen eigenen Zustand.

### 6. Dashboard: Kachel, Dialog und Zustand

**Warum nicht in einer gekapselten Kachel-Komponente:** `.lumina-card` setzt
`backdrop-filter` und `.lumina__fade` endet per `forwards` auf
`transform: translateY(0)`. Beides erzeugt einen Containing Block für
`position: fixed`-Nachfahren — ein Dialog innerhalb der Kachel wäre auf die
Kachel eingesperrt statt bildschirmfüllend. `@angular/cdk` (Overlay/Portal) ist
nicht im Projekt. Das Dashboard besitzt bereits exakt dieses Muster für den
Energiefluss-Dialog (Dialog auf oberster Ebene, Zustand im Dashboard,
Inhalt als Kind-Komponente `app-energy-flow`) — dem folgen wir.

**Kachel** (inline im Dashboard-Template, wie die Klima-Kachel; nutzt die
vorhandenen `lumina__room*`-Klassen):
- Ersetzt den `rooms[0]`-Eintrag „Küche"; dieser wird aus dem `rooms`-Array
  entfernt, der Schlafzimmer-Platzhalter bleibt.
- Kopf: Icon `toggle_on` + Button „Alle Schalter" (Icon `expand_content`), der
  den Dialog öffnet.
- Titel „Schalter", darunter `<app-switch-list variant="tile">` mit den Top-4.
- Leerzustand: „Keine Schalter".
- `.lumina__switch-tile` neutralisiert `cursor`/Hover-`transform` von
  `.lumina__room` (die Kachel ist kein einzelnes Klickziel mehr).

**Dialog** (inline im Dashboard-Template, gleiche `lumina__dialog-*`-Hülle wie
der Energiefluss-Dialog): Backdrop-Klick + Escape schließen, `role="dialog"`,
Titel „Alle Schalter", Körper `<app-switch-list variant="dialog">` mit allen
Schaltern.

**Zustand im `DashboardComponent`** (wie bereits für `energyLive`/`ankerLive`/
`flowDialogOpen`):
- `topSwitches`, `allSwitches`, `pendingSwitchIds`, `switchDialogOpen`,
  `switchError`.
- Laden der Top-4 per Intervall (30 s) + `startWith(0)`, analog zu
  `startClimateRefresh()`.
- `toggleSwitch(entity)`: optimistisches Umschalten in beiden Listen (Zuordnung
  über `entityId`, nicht über Objektreferenz), dann `SwitchService.toggle`;
  bei Fehler Zustand zurücksetzen + Hinweis.
- `openSwitchDialog()` lädt alle Schalter; `closeSwitchDialog()` lädt die Top-4
  neu (die nutzungsbasierte Reihenfolge kann sich geändert haben).

## Datenfluss

1. Dashboard-Init → `getSwitches(4)` → Kachel rendert Top-4.
2. Toggle tippen → optimistischer State-Flip (in beiden Listen über `entityId`)
   → `toggle(entityId)` → Erfolg: Zustand aus der Antwort übernehmen /
   Fehler: Flip zurück + Hinweis.
3. Dialog öffnen → `getSwitches()` (alle) → Render; Toggles gleicher Pfad.
   Schließen → Top-4 neu laden (Reihenfolge kann sich geändert haben).
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
- `switch-list`: rendert Zeilen, Klick emittiert `toggled`, `pendingIds`
  deaktiviert die Zeile, `unavailable` wird als solches angezeigt.
- `dashboard`: lädt Top-4, optimistischer Flip + Rücksetzung bei Fehler,
  Dialog öffnen/schließen (inkl. Neuladen der Top-4), Leerzustand.

## Offene Defaults (bei Bedarf trivial änderbar)

- Kachel-Titel „Schalter" (Icon `toggle_on`).
- 4 Schalter auf der Kachel.
