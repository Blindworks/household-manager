# flow-mcp-server

MCP-Server (stdio) für die Flow-Engine des Household-Managers. Dünner Wrapper über die
REST-API des Spring-Boot-Backends, damit KI-Agenten (z. B. Claude Code) Flows direkt
anlegen, ändern, deployen, (de)aktivieren und testen können — der visuelle Editor bleibt
als Viewer/Debug-Werkzeug.

## Voraussetzungen

- Node.js ≥ 20
- Laufendes Backend (`cd backend && mvn spring-boot:run`), Standard: `http://localhost:8080/api`

## Setup

```bash
cd flow-mcp-server
npm install
```

Die Registrierung für Claude Code liegt in der [.mcp.json](../.mcp.json) im Repo-Root —
nach `npm install` stehen die Tools in neuen Claude-Code-Sitzungen automatisch bereit
(Server-Name: `household-flows`).

## Konfiguration

| Variable | Default | Bedeutung |
|----------|---------|-----------|
| `HOUSEHOLD_API_URL` | `http://localhost:8080/api` | Basis-URL des Backends |

## Tools

**Flows verwalten:** `flow_list`, `flow_get`, `flow_create`, `flow_update`, `flow_deploy`,
`flow_set_enabled`, `flow_delete`

**Autoring-Referenzen:** `flow_node_types` (Node-Katalog mit Pflichtfeldern),
`flow_list_entities` (entityId), `flow_list_switch_devices` (deviceId),
`flow_list_alexa_devices` (deviceSerials)

**Testen/Debuggen:** `flow_inject` (Trigger von Hand feuern), `flow_debug_entries`

Typischer Ablauf beim Flow-Bauen:

1. `flow_node_types` + Lookups → Definition bauen (Format: `docs/flows/flow-import-format.md`)
2. `flow_create` → Flow entsteht deaktiviert und nicht deployt
3. `flow_deploy` → Validierungsfeedback; bei `valid: false` per `flow_update` korrigieren
4. `flow_set_enabled` → Flow scharf schalten

## Tests

```bash
npm test
```

Die Tests starten ein Stub-Backend auf einem ephemeren Port; es wird kein echtes
Backend benötigt.
