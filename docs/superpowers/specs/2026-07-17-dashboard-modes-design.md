# Dashboard-Modi: Schnellaktionen mit Funktion belegen

**Datum:** 2026-07-17
**Status:** Entwurf genehmigt

## Ziel

Die vier Modus-Knöpfe in der Fußleiste des Lumina-Dashboards (bisher statische
Platzhalter in `dashboard.component.ts`) werden echte, umschaltbare **Haus-Modi**:

| Position | Modus | Icon | Farbton (bestehend) |
|---|---|---|---|
| 1 | Abwesend | `exit_to_app` | primary |
| 2 | Toni allein | `pets` | tertiary |
| 3 | Nachtmodus | `nights_stay` | neutral |
| 4 | Ausschalten | `power_settings_new` | error |

„Party" und „Gute Nacht" entfallen; „Toni allein" (der Hund ist allein zu Hause)
und „Nachtmodus" ersetzen sie.

## Entscheidungen

- **Modus = INPUT_BOOLEAN-Entity** (Quelle `MANUAL`). Der Knopf schaltet die
  Entity an/aus und leuchtet, solange der Modus aktiv ist. Was in einem Modus
  passiert, definiert der Nutzer über Flows (`entity-state-trigger`) — dafür ist
  kein weiterer Code nötig.
- **Alle vier Knöpfe** werden Modi (einheitliches Verhalten).
- **Unabhängig kombinierbar:** mehrere Modi dürfen gleichzeitig aktiv sein
  (z. B. „Abwesend" + „Toni allein"). Exklusivität kann später per Flow
  abgebildet werden.
- **Backend legt die Modi automatisch an** (Seed beim Start, idempotent) — kein
  manueller Einrichtungsschritt, das Dashboard kann sich auf die IDs verlassen.
- **Keine Doppelung:** Modus-Entities werden aus der Schalter-API
  (`/v1/switches`) ausgeblendet; sie sind nur über die Modus-Leiste bedienbar.

## Backend

### Modus-Katalog und Marker-Attribut

Feste Definition der vier Modi (Name, Icon, Reihenfolge) an einer Stelle
(`HouseModes` im Package `entitystate`). Modus-Entities tragen das
Attribut `"mode": true` als Marker. Entity-IDs ergeben sich stabil über
`EntityIds.build(...)`:

- `input_boolean.manual_abwesend`
- `input_boolean.manual_toni_allein`
- `input_boolean.manual_nachtmodus`
- `input_boolean.manual_ausschalten`

### Seeding: `HouseModeInitializer`

- Läuft beim Anwendungsstart (`ApplicationReadyEvent`).
- Für jeden Modus: existiert die Entity nicht, wird sie über
  `EntityStateService.reportState` angelegt (Zustand `off`, Attribute
  `icon` + `mode: true`).
- Existiert die Entity, aber ohne Marker (z. B. früher manuell angelegter
  Helfer gleichen Namens), wird nur das `mode`-Attribut ergänzt — Zustand,
  Name und übrige Attribute bleiben unverändert.
- Existiert die Entity mit Marker, passiert nichts (Umbenennung und Zustand
  überleben Neustarts).
- Fehler beim Seeding brechen den Start nicht ab (Log + weiter); fehlende
  Modi werden beim nächsten Start nachgezogen.

### REST-API: `ModeController` (`/api/v1/modes`)

- `GET /api/v1/modes` → Liste der Modi in Katalog-Reihenfolge, je Eintrag
  `entityId`, `displayName` (Kurzname vor Integrationsname), `icon`, `state`.
  Query über das Marker-Attribut; noch nicht geseedete Modi fehlen einfach.
- `POST /api/v1/modes/{entityId}/toggle` → delegiert an
  `ManualEntityService.toggle` (einzige Schreibstelle bleibt
  `EntityStateService.reportState`, Events für die Flow-Engine werden wie
  gewohnt publiziert) und liefert den aktualisierten Modus zurück.

### Schalter-API filtert Modi

`SwitchQueryService.listSwitches` blendet Entities mit `"mode": true` aus
(Attribute via `EntityStateResponseMapper.parseAttributes`). Der Toggle-Pfad
der Switch-API bleibt bewusst unverändert: Modi werden dort nie angeboten,
und ihr Schreibpfad läuft über die Modus-/Manual-API.

## Frontend

### `ModeService` (`services/mode.service.ts`)

- `getModes(): Observable<ModeEntity[]>` → `GET /api/v1/modes`
- `toggle(entityId): Observable<ModeEntity>` → `POST /api/v1/modes/{id}/toggle`
- Modell `ModeEntity` (`models/mode.model.ts`): `entityId`, `displayName`,
  `icon`, `state`.

### Dashboard-Komponente

- Das statische `modes`-Array (`ModeButton[]`) entfällt. Die Leiste lädt die
  Modi beim Start und pollt alle 30 s (analog zur Schalter-Kachel — Flows
  können Modi auch von außen umschalten).
- Farbton je Position (primary, tertiary, neutral, error) wie bisher; bei
  unerwarteter Anzahl Fallback auf `neutral`.
- Klick = optimistisches Umschalten mit Pending-Schutz und Rollback bei
  Fehler, exakt nach dem Muster von `toggleSwitch` (eigenes
  `pendingModeIds`-Set, eigener `modeError`-Hinweis analog `switchError`).
- Aktiver Modus (`state === 'on'`): Zusatzklasse `lumina__mode--active`, die
  je Farbvariante die Hover-Optik dauerhaft anwendet (gefülltes Icon,
  eingefärbtes Label, kräftiger Rahmen). Styling ausschließlich in
  `dashboard.component.scss` (lumina-Klassen bleiben gekapselt).

## Fehlerbehandlung

- `GET /modes` schlägt fehl → Leiste zeigt die zuletzt bekannten Modi weiter;
  beim ersten Laden bleibt sie leer (kein Absturz).
- Toggle schlägt fehl → optimistischer Zustand wird zurückgerollt, kurzer
  Hinweis („<Modus> konnte nicht geschaltet werden.").

## Tests

- **Backend:**
  - `HouseModeInitializer`: legt fehlende Modi an; ergänzt nur den Marker bei
    vorhandener Entity ohne Marker; rührt vollständige Entities nicht an;
    Fehler eines Modus verhindert das Seeding der übrigen nicht.
  - Modus-Query: liefert nur Marker-Entities in Katalog-Reihenfolge.
  - `SwitchQueryService`: Modus-Entities erscheinen nicht mehr in der Liste,
    normale INPUT_BOOLEANs weiterhin.
- **Frontend:**
  - `ModeService`: URLs/Verben korrekt.
  - Dashboard: Toggle optimistisch + Rollback bei Fehler; aktive Klasse wird
    anhand `state` gesetzt.

## Nicht-Ziele

- Keine Exklusivitätslogik zwischen Modi.
- Keine vordefinierten Flows — die Belegung der Modi mit Aktionen erfolgt
  durch den Nutzer im Flow-Editor.
- Keine Änderung an der Helfer-Verwaltung (Modi bleiben dort normale
  manuelle Entities und sind umbenennbar/löschbar; nach dem Löschen legt der
  nächste Neustart sie wieder an).
