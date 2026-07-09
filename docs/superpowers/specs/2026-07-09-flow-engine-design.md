# Design: Flow-Engine (Stufe 3a — Regel-Engine, Node-RED-inspiriert)

**Datum:** 2026-07-09
**Status:** Entwurf zur Review

## Ziel

Der Household-Manager bekommt eine Automatisierungs-Engine im Stil von Node-RED: Flows sind frei verdrahtbare Graphen aus Nodes (Trigger → Logik → Aktionen), die im Backend auf Entity-Zustandsänderungen (Stufe 2, `EntityStateChangedEvent`), Zeitpläne und manuelle Test-Trigger reagieren.

Referenz-Automatisierungen (müssen alle mit v1 baubar sein):

1. **Wäsche fertig:** Steckdosen-Leistung < 5 W seit 3 Min → Alexa-Ansage
2. **Tür offen + kalt:** Türkontakt `on` (offen) UND Außentemperatur < 5 °C → Alexa-Ansage
3. **PV-Überschuss:** Netzleistung < −100 W seit 5 Min → Steckdose einschalten (mit Drossel gegen Flattern)

**Aufteilung des Gesamtvorhabens (beschlossen):**
- **Stufe 3a (diese Spec):** Flow-Datenmodell, Ausführungs-Engine, Node-Katalog v1, REST-API. Flows sind voll lauffähig; Anlage zunächst per REST/JSON.
- **Stufe 3b (eigene Spec):** Freier Canvas-Editor (Node-RED-Stil: Drag & Drop, Verkabelung, Palette, Konfig-Panels, Deploy-Button, Debug-Sidebar). Editor-Stil „freier Canvas" ist vom Nutzer entschieden.

## Gewählter Ansatz

**Eigene Message-Passing-Engine im Spring-Backend** mit Node-RED-Semantik: Nachrichten (`FlowMessage`) wandern von Node zu Node entlang von Wires; jeder Node-Typ ist ein `NodeHandler`-Bean.

Verworfene Alternativen:
- **Echtes Node-RED als Sidecar** (eigene Palette, iframe-Editor): Editor geschenkt, aber zweites System (Node.js) mit eigener Persistenz/Auth, Event-Spiegelung nötig, Fremdkörper in der UI — und der Nutzer will explizit nachbauen.
- **Strukturierter Regelbaum** (Trigger→Bedingung→Aktion ohne freien Graphen): widerspricht der Canvas-Entscheidung.

## Datenmodell (Liquibase-Changeset)

**Tabelle `flows`** — ein Flow = ein Canvas:

| Spalte | Bedeutung |
|---|---|
| `id`, `name`, `description` | Stammdaten |
| `enabled` | Flow aktiv/pausiert (Kill-Switch) |
| `draft_definition` | JSON — Arbeitsstand des Editors |
| `deployed_definition` | JSON — von der Engine ausgeführte Version (NULL = nie deployt) |
| `deployed_at`, `created_at`, `updated_at` | Zeitstempel |

Bewusst **keine** nodes-/wires-Tabellen: Editor und Engine arbeiten immer mit dem ganzen Graphen als Dokument; JSON-Spalten sparen Joins und halten das Format flexibel für neue Node-Typen.

### Flow-Definition (JSON)

```json
{
  "nodes": [
    { "id": "n1", "type": "entity-state-trigger", "name": "Waschmaschine unter 5W",
      "position": { "x": 80, "y": 120 },
      "config": { "entityId": "sensor.shelly_waschmaschine_power",
                  "operator": "<", "value": "5", "forSeconds": 180 } },
    { "id": "n2", "type": "alexa-announce", "position": { "x": 420, "y": 120 },
      "config": { "text": "Die Wäsche ist fertig", "mode": "ANNOUNCE", "deviceSerials": ["G09..."] } }
  ],
  "wires": [ { "from": { "node": "n1", "port": 0 }, "to": { "node": "n2" } } ]
}
```

- Jede Node: **ein Eingang**, **0–n nummerierte Ausgänge** (Bedingungs-Node: Port 0 = wahr, Port 1 = falsch — if/else ist Verkabelung).
- `position` gehört zur Definition (für den Canvas), die Engine ignoriert sie.

### Deploy-Semantik (wie Node-RED)

Editor speichert jederzeit nach `draft_definition` (nebenwirkungsfrei). **Deploy** validiert (unbekannte Node-Typen, Wires auf nicht existente Nodes/Ports, Config-Validierung je Node) und kopiert draft → deployed, dann Engine-Reload für diesen Flow. Validierungsfehler → 400 mit Fehlerliste; Warnungen (z. B. unbekannte `entityId`) blockieren nicht.

## Engine (Package `com.household.manager.flowengine`)

| Baustein | Verantwortung |
|---|---|
| `FlowMessage` | Nachricht im Graphen: Map mit `entityId`, `oldState`, `newState`, `attributes`, `timestamp`, `triggerNodeId` + beliebige von Nodes ergänzte Werte. Bei Verzweigung **Kopie pro Zweig** |
| `NodeHandler` (Interface) | Ein Bean pro Node-Typ: `type()`, `validate(config)` (beim Deploy), `handle(msg, ctx)` → Messages je Ausgangsport. Neuer Node-Typ = neues Bean |
| `FlowRegistry` | Hält die deployten In-Memory-Graphen; atomarer Swap pro Flow beim (Re-)Deploy; lädt beim App-Start alle deployten, enabled Flows |
| `TriggerIndex` | Map `entityId → [Trigger-Nodes]` — ein Event kostet einen Lookup, nicht „alle Flows durchsuchen" |
| `FlowEngineListener` | `@EventListener` auf `EntityStateChangedEvent`; legt Trigger-Prüfungen auf den `flowEngineExecutor` (eigener Thread-Pool) — **asynchron**, damit kein Flow je Polling/MQTT/Schaltbefehle blockiert. Bewusst kein `@TransactionalEventListener` (Footgun laut Stufe-2-Spec) |
| `FlowExecution` | Traversiert den Graphen ab Trigger; **Hop-Limit 100** pro Execution (Zyklen erlaubt, Endlosschleifen werden gekappt + geloggt); Fehler in einer Node → Log + Debug-Eintrag + Abbruch **nur dieses Zweigs** |

**Zeitbehaftete Nodes** (Verweildauer, Delay, Rate-Limit) nutzen den Spring-`TaskScheduler`; ihr Zustand lebt **in-memory** pro deployter Node. Bewusste Konsequenz: **Neustart/Re-Deploy verwirft laufende Timer** (Verweildauer beginnt beim nächsten passenden Event neu, offene Delays verfallen). Für Haushalts-Automatisierungen akzeptabel; spart eine Timer-Persistenzschicht.

**Verweildauer-Trigger:** passender Zustandswechsel startet Timer; bei Ablauf wird der **aktuelle** Zustand erneut geprüft (via `EntityStateService`) und nur bei weiterhin gültiger Bedingung gefeuert; verlässt der Wert vorher den Bereich, wird der Timer storniert.

## Node-Katalog v1

**Trigger (0 Eingänge, 1 Ausgang):**

| Typ | Konfiguration | Verhalten |
|---|---|---|
| `entity-state-trigger` | `entityId`, `operator` (`<`,`>`,`<=`,`>=`,`==`,`!=`,`changed`), `value` (entfällt bei `changed`), `forSeconds` (optional) | Feuert bei passender Zustandsänderung, mit `forSeconds` erst nach ununterbrochener Verweildauer. Numerische Operatoren parsen den State-String; `unavailable`/`unknown`/nicht-numerisch matcht nur `==`/`!=`/`changed` |
| `schedule-trigger` | `cron` (Spring-Cron) | Beim Deploy dynamisch registriert, bei Undeploy/Disable deregistriert |

Jeder Trigger kann zusätzlich manuell per Inject-Endpoint gefeuert werden (kein eigener Node-Typ).

**Message-Inhalt je Trigger-Art:** `entity-state-trigger` füllt `entityId`/`oldState`/`newState`/`attributes`; `schedule-trigger` und Inject füllen nur `timestamp` + `triggerNodeId` (Entity-Felder leer); beim Inject wird ein optional mitgegebenes `payload`-Objekt in die Message gemerged (damit lassen sich Entity-Events realistisch simulieren).

**Logik/Hilfsnodes (1 Eingang):**

| Typ | Ports | Konfiguration | Verhalten |
|---|---|---|---|
| `entity-condition` | 2 Ausgänge (0 = wahr, 1 = falsch) | `entityId`, `operator`, `value` | Prüft den **aktuellen** Zustand einer beliebigen Entität (Cross-Entity-Bedingung) |
| `delay` | 1 | `seconds` | Reicht die Message nach Ablauf weiter (nicht-blockierend) |
| `rate-limit` | 1 | `minIntervalSeconds` | Max. eine Message pro Intervall, Überschuss wird verworfen |
| `debug` | 0 | `label` (optional) | Message in Ring-Puffer (letzte 100 pro Node) + Log; per REST abrufbar, in 3b live in der Sidebar |

**Aktionen (1 Eingang, 1 Ausgang — verkettbar):**

| Typ | Konfiguration | Verhalten |
|---|---|---|
| `alexa-announce` | `text` (mit Platzhaltern `{entityId}`, `{newState}`, `{oldState}`), `mode` (SPEAK/ANNOUNCE), `deviceSerials[]` | Ruft bestehenden `AlexaAnnouncementService` |
| `switch-device` | `deviceId` (SmartDevice-ID), `action` (`on`/`off`) | Ruft `SmartDeviceService.turnOn/turnOff`; der resultierende Entity-Zustand kann weitere Flows triggern — flowübergreifend gibt es kein Hop-Limit, gegen Ping-Pong schützt die Drossel-Node (im Editor als Empfehlung dokumentieren) |

## REST-API

```
GET    /v1/flows                          → Liste (id, name, enabled, deployed?, Zeitstempel)
POST   /v1/flows                          {name, description}         → leerer Flow
GET    /v1/flows/{id}                     → inkl. draft- und deployed-Definition
PUT    /v1/flows/{id}                     {name?, description?, draftDefinition?}
POST   /v1/flows/{id}/deploy              → validieren + draft→deployed + Engine-Reload; 400 + Fehlerliste bei invalid
POST   /v1/flows/{id}/enable | /disable   → Kill-Switch
DELETE /v1/flows/{id}
POST   /v1/flows/{id}/nodes/{nodeId}/inject   {payload?}  → Test-Trigger (nur deployte Trigger-Nodes)
GET    /v1/flows/{id}/nodes/{nodeId}/debug    → Ring-Puffer der Debug-Node
GET    /v1/flows/node-types               → Katalog (Typ, Ports, Config-Schema) für die generische Editor-Palette (3b)
```

## Randfälle

- **Entität existiert nicht (mehr):** Deploy warnt nicht-blockierend; Trigger feuert nie bzw. Bedingung wertet auf „falsch"-Port. `unavailable` matcht nur `==`/`!=`/`changed`; numerische Vergleiche → kein Match.
- **Aktion schlägt fehl** (Alexa nicht eingeloggt, Gerät offline): Log + Debug-Eintrag, Zweig endet — Engine bleibt gesund.
- **Backend-Neustart:** deployte, enabled Flows werden beim Start aus der DB geladen; laufende Timer verfallen (siehe Engine).
- **Deploy während laufender Executions:** laufende Executions beenden auf dem alten Graphen, neue Events treffen den neuen (atomarer Registry-Swap).

## Offene Punkte für Stufe 3b (Canvas-Editor) — aus dem Abschluss-Review

- **`node-types`-Katalog anreichern:** `NodeTypeResponse` liefert aktuell `type`, `outputPorts` (nackte Zahl), `trigger`, `configSchema` (nur Feld→Beschreibung). Für einen generischen Property-Editor und eine Palette braucht 3b reichere Metadaten: pro Config-Feld den Typ (String/Zahl/Enum/Liste), Pflicht-Flag und Enum-Optionen (operator-Menge, `mode` SPEAK/ANNOUNCE, `action` on/off), außerdem **Port-Labels** (bei `entity-condition`: Port 0 = wahr, Port 1 = falsch). Bewusst nach 3b verschoben, weil dort der konkrete Palette-Bedarf bekannt ist und es noch keinen Consumer gibt (kein Contract-Bruch). Diese Erweiterung ist die erste Aufgabe der 3b-Umsetzung.

## Tests

- Engine-Kern: Traversierung inkl. Ports/Verzweigung, Message-Kopie pro Zweig, Hop-Limit, Fehlerisolation pro Zweig
- Zeit-Nodes mit gemocktem `TaskScheduler`/`Clock`: Verweildauer (feuert/storniert), Delay, Rate-Limit
- Jeder `NodeHandler` einzeln (`validate` + `handle`)
- Deploy-Validierung (kaputte Wires, unbekannte Typen → 400 mit Fehlerliste)
- Controller-Tests (Standalone-MockMvc, Muster aus Stufe 2)
