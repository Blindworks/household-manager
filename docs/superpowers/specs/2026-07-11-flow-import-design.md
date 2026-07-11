# Flow-Import per Datei — Design

**Datum:** 2026-07-11
**Status:** Entwurf zur Umsetzung

## Ziel

Extern (z. B. mit Claude Code) erzeugte Flow-Definitionen sollen als **JSON-Datei**
über ein **UI-Import-Feature** in die Anwendung geladen werden können. Das erzeugte
JSON ist selbstbeschreibend (trägt Name + Beschreibung + Graph) und legt beim Upload
einen neuen Flow als Draft an.

JSON ist gesetzt: Die Flow-Definition ist intern bereits JSON
(`{ nodes: [...], wires: [...] }`, gespeichert in `Flow.draftDefinition`).

## Nicht-Ziele

- Kein Export (nur Import in dieser Iteration).
- Kein Batch-/Mehrdatei-Import — eine Datei = ein Flow.
- Keine Umgebungsprüfung (Geräte-/Entity-Referenzen) beim Import; das bleibt dem
  expliziten Deploy-Schritt vorbehalten.

## Dateiformat

Selbstbeschreibende Wrapper-Datei:

```json
{
  "schemaVersion": 1,
  "name": "Flur-Licht bei Bewegung",
  "description": "Schaltet das Flurlicht bei Bewegung ein",
  "definition": {
    "nodes": [ /* FlowNode[] */ ],
    "wires": [ /* FlowWire[] */ ]
  }
}
```

- `schemaVersion` (Pflicht): erlaubt spätere Formatmigration. Aktuell nur `1` gültig.
- `name` (Pflicht): Anzeigename des Flows.
- `description` (optional): Freitext.
- `definition` (Pflicht): der Graph im bestehenden internen Format.
  - `FlowNode`: `{ id, type, name?, position:{x,y}, config:{} }`
  - `FlowWire`: `{ from:{node,port}, to:{node} }`

## Backend

### Endpoint
`POST /v1/flows/import`

- Body: die Wrapper-Datei (siehe oben).
- Erfolg: `200` mit `FlowDetailResponse` des neu angelegten Flows.
- Fehler: `400` bei unbekannter/fehlender `schemaVersion`, fehlendem `name` oder
  kaputter/unparsebarer `definition`.

### Neuer DTO
`ImportFlowRequest(int schemaVersion, String name, String description, JsonNode definition)`
— `definition` wird als roher `JsonNode`/String durchgereicht und an den bestehenden
`FlowDefinitionParser` gegeben (Single Source of Truth fürs Parsen).

### Service
`FlowService.importFlow(int schemaVersion, String name, String description, String definitionJson)`:

1. `schemaVersion` prüfen (nur `1`) → sonst `IllegalArgumentException`.
2. `name` nicht leer → sonst `IllegalArgumentException`.
3. `parser.parse(definitionJson)` — validiert die JSON-Struktur (kaputt → 400).
   Volle Graph-Validierung (`FlowValidator`) bewusst **nicht** hier — ein Draft darf
   unvollständig sein und im Editor korrigiert werden.
4. Neuen `Flow` anlegen: `draftDefinition = definitionJson`, **`enabled = false`**,
   `deployedDefinition = null` (nie deployt). Speichern, `FlowDetailResponse` zurück.

Der Import löst nichts aus: Ein neuer Flow ist deaktiviert und nicht deployt; erst der
explizite Deploy-Schritt macht ihn scharf.

### Controller
Neue Methode in `FlowController`, die `ImportFlowRequest` annimmt, `FlowService.importFlow`
aufruft und `FlowDetailResponse` liefert. `IllegalArgumentException` wird über den
bestehenden Exception-Handler zu `400`.

## Frontend

### UI
„Importieren"-Button in `flow-list.component`. Dahinter ein verstecktes
`<input type="file" accept=".json,application/json">`.

Ablauf:
1. Nutzer wählt Datei → Dateitext wird gelesen (`File.text()`).
2. `FlowService.importFlow(fileText)` POSTet den geparsten Inhalt an
   `POST /v1/flows/import`.
3. Erfolg → Navigation in den Editor des neuen Flows (`/flows/:id`).
4. Fehler (ungültiges JSON clientseitig oder 400 vom Backend) → Fehlermeldung anzeigen,
   kein Flow angelegt.

### Service
Neue Methode in `flow.service.ts`:
`importFlow(fileText: string): Observable<FlowDetail>` — parst den Text clientseitig
(früher, klarer Fehler bei kaputtem JSON) und POSTet an den Endpoint.

## Authoring-Referenz

Eingecheckte Datei `docs/flows/flow-import-format.md`:

- Beschreibung des Wrapper-Formats.
- Tabelle aller **8 Node-Typen** mit ihren `config`-Feldern, abgeleitet aus den
  `fields()` der jeweiligen `NodeHandler`-Beans (Feldschlüssel, Typ, Pflicht,
  Enum-Optionen, Port-Bedeutung):
  `entity-state-trigger`, `schedule-trigger`, `entity-condition`, `delay`,
  `rate-limit`, `debug`, `alexa-announce`, `switch-device`.
- 1–2 vollständige Beispiel-Flows (z. B. „Entity-Trigger → Bedingung → Gerät schalten").

Diese Referenz ist der eigentliche Enabler: Sie macht das externe Generieren korrekten
Flow-JSONs möglich, ohne Feldnamen raten zu müssen.

## Tests

- **Backend:**
  - `importFlow` mit gültiger Datei → Flow als Draft, `enabled=false`, nicht deployt.
  - Kaputte `definition` → `IllegalArgumentException` (→ 400).
  - Ungültige/fehlende `schemaVersion` und leerer `name` → `IllegalArgumentException`.
- **Frontend:**
  - `FlowService.importFlow` POSTet korrekt und mappt die Antwort.
  - Kaputtes JSON in der Datei → Fehler ohne Request.

## Betroffene/neue Dateien

**Neu**
- `backend/.../dto/ImportFlowRequest.java`
- `docs/flows/flow-import-format.md`

**Geändert**
- `backend/.../controller/FlowController.java` (Import-Endpoint)
- `backend/.../flowengine/FlowService.java` (`importFlow`)
- `frontend/.../services/flow.service.ts` (`importFlow`)
- `frontend/.../pages/flows/flow-list.component.ts` + `.html` (Button + File-Input)
- zugehörige Testdateien
