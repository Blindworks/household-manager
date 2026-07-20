# Flow-MCP-Server — Design

**Datum:** 2026-07-20
**Status:** Umgesetzt (direkt beauftragt: „setze 3 direkt um" — KI-Schnittstelle für die Flow-Engine)

## Ziel

Eine KI (primär Claude Code in diesem Repo) soll Flows der Flow-Engine direkt anlegen,
ändern, deployen und (de)aktivieren können — ohne Umweg über den visuellen Editor oder
manuelles JSON-Copy-Paste. Der Editor bleibt als Viewer/Debug-Werkzeug bestehen
(siehe Entscheidung vom 2026-07-20: Editor eingefroren, Autorenweg ist die KI).

## Kontext / Befund

Die bestehende REST-API (`/api/v1/flows`) ist bereits fast vollständig KI-tauglich:

- `POST /v1/flows/import` — legt einen Flow aus einer Definition an (schemaVersion 1,
  bewusst deaktiviert und nicht deployt)
- `PUT /v1/flows/{id}` — aktualisiert Name/Beschreibung/Draft-Definition
- `POST /v1/flows/{id}/deploy` — validiert und deployt; liefert `ValidationResult`
  mit brauchbaren Fehlermeldungen (400 bei invalide)
- `POST /v1/flows/{id}/enable|disable`, `DELETE /v1/flows/{id}`
- `GET /v1/flows/node-types` — selbstbeschreibender Node-Katalog inkl. Feld-Deskriptoren
- `GET /v1/flows/{id}/nodes/{nodeId}/inject` + `debug` — Test-Inject und Debug-Puffer
- Referenz-Lookups: `GET /v1/entities` (entityId), `GET /devices` (deviceId für
  switch-device), `GET /v1/alexa/devices` (deviceSerials für alexa-announce)
- Format-Doku existiert: `docs/flows/flow-import-format.md`

Es fehlt nur die MCP-Brücke, damit eine KI diese API als typisierte Tools nutzen kann.

## Entscheidung: Ansatz

**Gewählt: dünner Node.js-stdio-MCP-Server (`flow-mcp-server/`), der die REST-API wrappt.**

Verworfen:

1. *Spring-AI-MCP im Backend* — zusätzliche schwere Dependency, koppelt MCP-Protokoll an
   das Backend-Release; der Wrapper ist trivial genug, dass ein Sidecar reicht.
2. *Kein MCP, nur CLAUDE.md + curl* — funktioniert, aber ohne Tool-Schemas muss die KI
   Endpoints und Formate jedes Mal aus der Doku rekonstruieren; fehleranfälliger.

Abweichung von der mcp-builder-Empfehlung (TypeScript): bewusst **plain JavaScript (ESM)**
wie beim `alexa-sidecar` — kein Build-Schritt, `.mcp.json` startet direkt
`node flow-mcp-server/src/index.js`, Repo-Konvention für Node-Tooling bleibt einheitlich.

## Architektur

```
Claude Code ──stdio/MCP──> flow-mcp-server (Node ≥20)
                              │  fetch, Base-URL: HOUSEHOLD_API_URL
                              ▼
                    Spring-Boot-Backend http://localhost:8080/api
```

- `src/index.js` — Entry Point: McpServer + StdioServerTransport, registriert Tools
- `src/api-client.js` — fetch-Wrapper (Base-URL, Timeouts, Fehler → lesbare Meldungen)
- `src/tools.js` — Tool-Definitionen als Daten (Name, Schema, Handler) → testbar ohne Transport
- `test/` — `node --test` mit Stub-HTTP-Server (wie alexa-sidecar-Stil)
- `.mcp.json` im Repo-Root registriert den Server für Claude Code

Konfiguration: `HOUSEHOLD_API_URL` (Default `http://localhost:8080/api`).

## Tools

| Tool | Endpoint | Anmerkung |
|------|----------|-----------|
| `flow_list` | `GET /v1/flows` | readonly |
| `flow_get` | `GET /v1/flows/{id}` | Definitionen als geparste Objekte |
| `flow_create` | `POST /v1/flows/import` | schemaVersion 1; Ergebnis ist deaktiviert + nicht deployt |
| `flow_update` | `PUT /v1/flows/{id}` | name/description/definition einzeln optional |
| `flow_deploy` | `POST /v1/flows/{id}/deploy` | 400-Body (ValidationResult) wird als Ergebnis durchgereicht, nicht als Fehler |
| `flow_set_enabled` | `POST /v1/flows/{id}/enable\|disable` | |
| `flow_delete` | `DELETE /v1/flows/{id}` | destructiveHint |
| `flow_node_types` | `GET /v1/flows/node-types` | Node-Katalog für Autoring |
| `flow_inject` | `POST /v1/flows/{id}/nodes/{nodeId}/inject` | Test-Trigger (nur deployte Trigger-Nodes) |
| `flow_debug_entries` | `GET /v1/flows/{id}/nodes/{nodeId}/debug` | Debug-Puffer |
| `flow_list_entities` | `GET /v1/entities` | Lookup für `entityId`; getrimmt (entityId, displayName, domain, source, state) |
| `flow_list_switch_devices` | `GET /devices` | Lookup für `deviceId`; getrimmt |
| `flow_list_alexa_devices` | `GET /v1/alexa/devices` | Lookup für `deviceSerials` |

Der typische KI-Workflow ist damit: `flow_node_types` + Lookups → `flow_create` →
`flow_deploy` (Validierungsfeedback, ggf. `flow_update` und erneut) → `flow_set_enabled`.

## Fehlerbehandlung

- Backend nicht erreichbar → klare Meldung „Backend unter <URL> nicht erreichbar — läuft
  `mvn spring-boot:run`?" statt Stacktrace
- Non-2xx → Status + Response-Body (Backend-Fehlermeldungen sind bereits deutsch/lesbar)
- Sonderfall Deploy: HTTP 400 mit ValidationResult ist ein *fachliches* Ergebnis
  (Validierungsfehler), kein Toolfehler — wird als strukturiertes Ergebnis zurückgegeben,
  damit die KI den Flow korrigieren kann

## Tests

`node --test` mit einem Stub-HTTP-Server auf ephemerem Port:

- Happy path je Tool-Gruppe (list/get/create/deploy)
- Deploy mit 400 → ValidationResult wird durchgereicht
- Backend down → lesbare Fehlermeldung
- Trimmen der Lookup-Antworten

## Nicht-Ziele

- Kein neuer Backend-Code (die REST-API reicht; Punkt „Validierung verbessern" ist
  separater Scope)
- Kein Remote-/HTTP-Transport (nur lokal via stdio; Docker-Deployment bei Bedarf später)
- Keine Editor-Änderungen
