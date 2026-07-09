# Design: Visueller Flow-Editor (Stufe 3b — Canvas-Editor für die Regel-Engine)

**Datum:** 2026-07-09
**Status:** Entwurf zur Review

## Ziel

Ein visueller, Node-RED-artiger Canvas-Editor im Frontend für die Flow-Engine aus Stufe 3a. Der Nutzer baut Automatisierungen per Drag & Drop: Node-Typen aus einer Palette auf einen Canvas ziehen, per Verkabelung verbinden, konfigurieren, deployen und live testen. Damit wird die in 3a fertige Backend-Engine für Endnutzer bedienbar.

Das Backend (`/api/v1/flows` inkl. `node-types`-Katalog, deploy, inject, debug) existiert bereits (Stufe 3a). Stufe 3b ist überwiegend Frontend, mit einer kleinen additiven Backend-Anreicherung als Fundament.

## Gewählte Grundentscheidungen (aus dem Brainstorming)

- **Canvas-Bibliothek: `@foblex/flow`** — Angular-native Editor-Lib (Signals, Standalone) mit Drag-to-connect, Auswahl, Zoom, Minimap, Snapping. Hinter eigenen Komponenten gekapselt, damit ein späterer Wechsel überschaubar bleibt. Bei der Umsetzung wird eine mit Angular 19 kompatible Version festgelegt (erster Plan-Schritt: `npm install` + Kompatibilität verifizieren); falls sich die Lib als unreif erweist, ist der dokumentierte Fallback der CDK-Eigenbau (Option B aus dem Brainstorming) — der Rest des Designs (Format, Adapter, Panel, Debug) bleibt davon unberührt.
- **Konfig-Panel: schema-getriebener Hybrid** — ein generisches, aus dem Backend-Katalog gerendertes Formular, plus Spezial-Widgets (Entity-Picker, Geräte-Picker, Alexa-Geräte-Mehrfachauswahl). Erhält die „neuer Node-Typ = nur Backend"-Erweiterbarkeit.
- **Layout:** Palette links, Canvas mittig, rechte Spalte mit Tabs **Konfig** / **Debug** (Node-RED-Klassiker).
- **Debug: Polling** (~2 s, nur bei sichtbarem Debug-Tab und deploytem Flow) über die vorhandene REST-Debug-API; kein SSE.
- **Speichern: manuell** per Button (Draft/Deploy-Semantik); Deploy schließt Speichern ein.

## Backend: `node-types`-Katalog anreichern (additiv)

Fundament für das schema-getriebene Panel. Rein additiv — keine Engine-, keine DB-Änderung.

- `NodeHandler` bekommt reichere Schema-Metadaten: statt `Map<Feld→Beschreibung>` eine `List<FieldDescriptor>` sowie Port-Labels. Jeder der 8 Handler liefert seine Felder selbst (Erweiterbarkeit bleibt).
- **FieldDescriptor:** `key`, `label`, `type`, `required`, optional `options` (bei `enum`).
- **Feldtypen:** `string`, `number`, `enum` (mit `options`), `entity-ref` (→ Entity-Picker), `device-ref` (→ SmartDevice-Picker), `alexa-device-list` (→ Alexa-Geräte-Mehrfachauswahl).
- **Port-Labels:** `entity-condition` → `["wahr","falsch"]`, sonst `["Ausgang"]`.
- `NodeTypeResponse`-DTO wird erweitert (`fields`, `portLabels`); `FlowController` `GET /v1/flows/node-types` gibt das Neue aus.

**Feld-Deskriptoren je Node-Typ:**

| Typ | Felder |
|---|---|
| `entity-state-trigger` | entityId (entity-ref, req), operator (enum `<,<=,>,>=,==,!=,changed`, req), value (string), forSeconds (number) |
| `schedule-trigger` | cron (string, req) |
| `entity-condition` | entityId (entity-ref, req), operator (enum `<,<=,>,>=,==,!=`, req), value (string, req) |
| `delay` | seconds (number, req) |
| `rate-limit` | minIntervalSeconds (number, req) |
| `debug` | label (string) |
| `alexa-announce` | text (string, req), mode (enum `SPEAK,ANNOUNCE`, req), deviceSerials (alexa-device-list, req) |
| `switch-device` | deviceId (device-ref, req), action (enum `on,off`, req) |

**Datenquellen der Picker:** Entity-Picker → `GET /api/v1/entities`; Geräte-Picker → `GET /api/devices`; Alexa-Geräte → `GET /api/v1/alexa/devices`.

## Frontend-Architektur

Neue Bausteine (Angular 19 standalone, `frontend/src/app/pages/flows/`, `components/`, `services/`):

| Baustein | Verantwortung |
|---|---|
| `FlowListComponent` (`/flows`) | Übersicht (Name, aktiv/deployed, Zeitstempel); Anlegen/Öffnen/Löschen/Enable-Disable |
| `FlowEditorComponent` (`/flows/:id`) | Hält Flow-Zustand (Signal), orchestriert Palette/Canvas/Panel, Speichern/Deploy |
| `FlowCanvasComponent` | Kapselt `@foblex/flow`; rendert Nodes/Kanten, meldet Interaktionen nach oben |
| `NodePaletteComponent` | Node-Typen (aus `node-types`), gruppiert (Trigger/Logik/Aktionen), per Drag auf Canvas |
| `NodeConfigPanelComponent` | Schema-getriebenes Formular der ausgewählten Node |
| `DebugPanelComponent` | Debug-Tab: pollt Debug-Puffer, Nachrichten nach Zeit sortiert |
| `EntityPickerComponent`, `DevicePickerComponent`, `AlexaDevicePickerComponent` | Spezial-Widgets |
| `FlowService` | REST an `/api/v1/flows` (CRUD, deploy, enable/disable, inject, debug, node-types) |

**Datenfluss & Format-Adapter:**
- Der Editor hält die Flow-Definition in **unserem** Format (`{nodes:[{id,type,name,position,config}], wires:[{from:{node,port},to:{node}}]}`) als Signal — exakt das Backend-JSON.
- Ein schmaler `FlowGraphMapper` übersetzt bidirektional zwischen unserem Format und dem `@foblex/flow`-Modell. Einzige Stelle der Lib-Kopplung (neben `FlowCanvasComponent`).
- Editor-Interaktionen aktualisieren das Signal; „Speichern" → `PUT draftDefinition`; „Deploy" → `POST /deploy` + Anzeige der `ValidationResult`-Fehler/Warnungen.

**Route + Navigation:** `/flows` (Liste), `/flows/:id` (Editor); Nav-Link „Automatisierungen".

## Editor-Interaktionen & UX

- **Palette → Canvas:** Ziehen erzeugt neue Node mit Default-Config an der Ablegeposition und generierter `id`. Farbcodierung: Trigger blau, Logik gelb/violett, Aktionen grün.
- **Canvas:** Nodes zeigen Icon + Typ + optionalen Namen + beschriftete Ports. Verkabeln per Drag; Bezier-Kanten/Snapping/Pan/Zoom/Minimap von der Lib. Auswahl → Konfig-Panel. Löschen entfernt Node + zugehörige Wires. Leichte Client-Vorvalidierung (visuelle Markierung), maßgeblich bleibt der Backend-Deploy.
- **Konfig-Panel:** rendert je Feld das Widget nach Typ (Text/Zahl/Enum generisch; Entity-/Geräte-/Alexa-Picker als Spezial-Widgets, durchsuchbar, zeigen Anzeigenamen). Pflichtfelder markiert; Änderungen sofort ins Draft-Signal, Persistenz erst bei „Speichern".
- **Kopfleiste:** Flow-Name (editierbar), Status-Badge (Entwurf/aktiv/deaktiviert), Buttons Speichern / Deploy / Aktivieren-Deaktivieren. Nach Deploy: Erfolg oder rote Fehlerliste + gelbe Warnungen.
- **Testen & Debug:** „Testen"-Knopf an deployten Trigger-Nodes (→ `inject`). Debug-Tab zeigt Debug-Node-Nachrichten (Polling ~2 s): Zeit, Node-Label, Message als aufklappbares JSON.
- **Visuelle Politur** über die `frontend-design`-Skill bei der Umsetzung, konsistent zum App-Stil.

## Randfälle

- **Ungespeicherte Änderungen:** Rückfrage beim Verlassen; „ungespeichert" = Signal ≠ zuletzt gespeicherter Draft.
- **Deploy-Fehler:** 400 + Fehlerliste angezeigt, bisheriger aktiver Stand unberührt (Backend-Semantik). Warnungen blockieren nicht.
- **Unbekannter Node-Typ im gespeicherten Flow:** als „unbekannter Typ" ohne Konfig rendern statt Absturz.
- **Entität/Gerät im Picker fehlt:** gespeicherten Roh-Wert behalten, als „nicht gefunden: <id>" anzeigen.
- **„Testen" ohne Deploy:** Button nur an deployten Trigger-Nodes aktiv; sonst Hinweis „erst deployen".
- **Leerer/neuer Flow:** Leerzustand mit Hinweis, eine Node aus der Palette zu ziehen.

## Tests

- **Backend:** neue Schema-Tests je Handler (Feldtypen/Optionen/Port-Labels); erweiterter `FlowControllerTest` für den angereicherten `node-types`-Payload.
- **Frontend (Karma/Jasmine):**
  - `FlowService`: REST-Aufrufe (URLs/Methoden/Payloads), deploy-Antwort-Handling
  - `FlowGraphMapper`: bidirektionale Übersetzung (Round-Trip erhält Nodes/Wires/Positionen)
  - `NodeConfigPanelComponent`: richtiges Widget je Feldtyp, Änderungen aktualisieren Modell, Pflichtfeld-Markierung
  - Picker-Komponenten: Optionen laden, unbekannte Roh-Werte behalten
  - `DebugPanelComponent`: Polling nur bei sichtbarem Tab + deploytem Flow, Zeit-Sortierung

## Offene Follow-ups (aus dem Abschluss-Review, nicht blockierend)

- **Palette-Aktionsgruppierung hartkodiert:** `NodePaletteComponent` erkennt Aktions-Nodes über ein hartkodiertes Set (`alexa-announce`, `switch-device`). Ein künftiger Backend-Aktions-Node landet sonst in der Gruppe „Logik". Sauberer wäre eine Kategorie-Angabe im `node-types`-Katalog (Backend liefert `category`: trigger/logic/action) statt der Frontend-Heuristik.
- **Kein UI für `FlowNode.name`:** Der optionale Anzeigename einer Node übersteht den Round-Trip, kann im Editor aber nicht gesetzt werden (Nodes behalten die Auto-ID). Ein Namensfeld im Konfig-Panel wäre reiner Komfort.
- **ENUM-Placeholder-Optik:** Ein noch nicht gesetztes Enum-Feld zeigt initial leer statt „wählen…" (der `value=""`-Platzhalter matcht `undefined` nicht). Kosmetisch, kein Datenverlust.
- **Visuelle Feinpolitur & manueller End-to-End-Test** stehen noch aus (Letzterer braucht eine laufende DB/Backend, in der Entwicklungsumgebung nicht durchführbar).

## Umsetzungsschnitt

Eine Spec, zwei natürliche Blöcke:
1. **Backend-Katalog-Anreicherung** (klein, additiv) — Fundament fürs Panel.
2. **Frontend-Editor** (Hauptteil) — Liste, Editor, Canvas-Integration, Palette, Konfig-Panel + Picker, Deploy/Debug.
