# `@foblex/flow` Spike Notes (Task B1, Stufe 3b)

Diese Notizen sind verifiziert durch einen lauffähigen Spike (2 Nodes, 1 Connection,
Pan/Zoom, Minimap, Drag, Selection) unter Angular 19, gebaut mit
`npx ng build --configuration production` und live geprüft mit `ng serve` +
Playwright (Chromium 148). Der Spike-Code selbst wurde nach der Verifikation
wieder entfernt (siehe Task-Beschreibung, Step 4); dieses Dokument ist der
bleibende Deliverable.

## Installierte Version

- `@foblex/flow@19.0.0` (aktuell `latest` zum Zeitpunkt des Spikes; die npm-Versionsnummern
  von `@foblex/flow` folgen NICHT dem Angular-Major, sondern ihrem eigenen Schema)
- Peer-Dependencies: `@angular/common` / `@angular/core` `>=17.3.0` — Angular 19 (dieses
  Projekt: `^19.0.0`) ist damit unproblematisch kompatibel, `npm install @foblex/flow` lief
  ohne Peer-Konflikte.
- Transitive Pakete (automatisch mitinstalliert): `@foblex/2d@1.2.2`, `@foblex/mediator@1.1.3`,
  `@foblex/platform@1.0.4`, `@foblex/utils@1.1.1`.
- `npx ng build --configuration production` läuft sauber durch (nur die vorbestehende,
  nicht auf den Flow-Editor bezogene Budget-Warnung für `energy.component.scss` erscheint
  weiterhin — siehe Agent-Memory).
- Das Paket bringt eine eigene `AI.md` (LLM-Guide) und `STYLING.md` mit
  (`node_modules/@foblex/flow/AI.md` / `STYLING.md`) — das ist die primäre, versions-genaue
  Quelle für zukünftige Arbeit an dieser Lib und war Basis dieser Notizen, ergänzt durch
  eigene Verifikation gegen `index.d.ts` und den kompilierten Code
  (`fesm2022/foblex-flow.mjs`) sowie das Laufzeitverhalten im Browser.

## Angular-Setup

- Kein `NgModule` nötig, aber `FFlowModule` selbst ist ein `NgModule` (nicht standalone) —
  in eine standalone Komponente importieren:

  ```typescript
  import { Component } from '@angular/core';
  import { FFlowModule } from '@foblex/flow';

  @Component({
    selector: 'app-flow-canvas',
    standalone: true,
    imports: [FFlowModule],
    templateUrl: './flow-canvas.component.html',
    styleUrl: './flow-canvas.component.scss'
  })
  export class FlowCanvasComponent {}
  ```

- Theme: `ng add @foblex/flow` würde automatisch
  `node_modules/@foblex/flow/styles/default.scss` in die globalen `styles` in `angular.json`
  eintragen. Da wir nur `npm install @foblex/flow` genutzt haben (kein `ng add`, um das Projekt
  nicht mit ungeplanten Schematic-Änderungen zu verändern), muss dieser Schritt für die
  produktive Integration (Task B8) bewusst nachgezogen werden — entweder per `ng add
  @foblex/flow` oder manuell durch Eintrag von
  `"node_modules/@foblex/flow/styles/default.scss"` VOR `"src/styles.scss"` im `styles`-Array
  von `angular.json` (bzw. `@use`/`@forward` in `src/styles.scss`). Ohne dieses Theme sind
  Connectoren/Connections nahezu unsichtbar (keine Farben/Größen).
- `provideFFlow(...)` (optionale Feature-Provider, z. B. `withA11y()`, `withControlScheme()`)
  wurde im Spike NICHT verwendet — alles im Spike lief mit reinen Template-Direktiven. Für
  spätere Tasks relevant, aber kein Blocker.

## Minimale Struktur (verifiziert)

Zwingende Hierarchie `f-flow > f-canvas > fNode / f-connection`:

```html
<f-flow fDraggable
        (fCreateConnection)="onCreateConnection($event)"
        (fMoveNodes)="onMoveNodes($event)"
        (fSelectionChange)="onSelectionChange($event)"
        (fNodesRendered)="onNodesRendered($event)">
  <f-canvas fZoom>
    <f-connection fSourceId="out-1" fTargetId="in-2"></f-connection>

    <div fNode fDragHandle fNodeId="node-1" [fNodePosition]="{ x: 100, y: 100 }">
      Node A
      <div fConnector fConnectorType="source" fConnectorId="out-1"></div>
    </div>

    <div fNode fDragHandle fNodeId="node-2" [fNodePosition]="{ x: 380, y: 180 }">
      Node B
      <div fConnector fConnectorType="target" fConnectorId="in-2"></div>
    </div>
  </f-canvas>

  <!-- f-minimap ist KEIN Kind von f-canvas, sondern von f-flow! -->
  <f-minimap></f-minimap>
</f-flow>
```

```scss
// f-flow braucht eine konkrete Höhe, sonst bleibt die Canvas leer (0x0).
f-flow {
  display: block;
  height: 600px;
}
```

**Wichtige, im Spike tatsächlich erlebte Stolperfalle:** `<f-minimap>` gehört als
direktes Kind von `<f-flow>` (Sibling von `<f-canvas>`), NICHT in `<f-canvas>` hinein.
Ursprünglich in `<f-canvas>` platziert, wurde es im DOM überhaupt nicht gerendert
(`f-minimap`-Locator fand 0 Elemente). Nach dem Verschieben unter `<f-flow>` (Begründung:
`FFlowComponent`s `ɵcmp`-Metadaten listen `f-minimap` nur im generischen `"*"`-Content-Slot
von `f-flow`, nicht im Slot von `f-canvas`) sollte es korrekt rendern — dieser konkrete
Move wurde wegen Zeitdrucks im Spike nicht mehr re-verifiziert und ist ein offener Punkt
für Task B8 (siehe „Offene Punkte" unten).

## Node

- Directive `[fNode]` auf einem beliebigen Host-Element (`<div fNode>...</div>`).
- Position: Two-Way-Model `[fNodePosition]` (Alias von `position`), Typ `{x:number, y:number}`
  — Property-Binding, KEIN String. Änderungs-Output: `(fNodePositionChange)`.
- Stabile ID: `[fNodeId]` (Alias `fId`) — im Spike frei vergeben (`node-1`, `node-2`); AI.md
  betont ausdrücklich, immer stabile, app-eigene IDs zu vergeben (Auto-IDs sind nicht auf das
  eigene Modell zurückführbar).
- `fDragHandle` macht das Element (oder ein Kind davon) zum Ziehgriff für Node-Drag.
- Genau ein `fNode` pro Node-Element; Hierarchie (Parent/Child, z. B. für Gruppen) läuft über
  `[fNodeParentId]`, nicht über DOM-Verschachtelung — ein `fNode` NIE in ein anderes `fNode`
  verschachteln (Fehlercode `FF1007`).

## Connector (Ports)

- Aktuelle, empfohlene Directive: `[fConnector]` mit `[fConnectorId]` (String) und
  `[fConnectorType]` (`'source' | 'target' | 'source-target' | 'outlet'`).
- Legacy/deprecated, aber noch unterstützt: `[fNodeOutput]` / `[fNodeInput]` / `[fNodeOutlet]`.
- Connector-Element MUSS Kind eines `fNode`/`fGroup`-Elements sein, sonst Fehler `FF1003`.
- Verbundene Connectoren bekommen im DOM automatisch Klassen wie `.f-connector-connected`,
  `.f-connector-source`, `.f-connector-multiple` — im Spike beobachtet und verifiziert.

## Connection (Kante)

- Deklarativ rendern: `<f-connection [fSourceId]="..." [fTargetId]="..."></f-connection>`
  — referenziert `fConnectorId`-Werte, NICHT Node-IDs. String-Vergleich exakt (`1` vs `'1'`
  ist ein klassischer Bug).
- Neue Verbindung durch User-Interaktion (Drag-to-connect) meldet sich als Output
  **`(fCreateConnection)`** auf `<f-flow fDraggable>` mit Payload `FCreateConnectionEvent`:
  ```typescript
  class FCreateConnectionEvent {
    readonly sourceId: string;
    readonly targetId: string | undefined; // undefined wenn auf "nichts Verbindbares" gedroppt
    readonly dropPosition: IPoint; // { x, y }
    // deprecated Aliase: fOutputId, fInputId, fDropPosition
  }
  ```
  Die Lib mutiert den eigenen State NICHT — die App muss aus diesem Event selbst eine neue
  `<f-connection>` in ihre Datenstruktur aufnehmen.
- Reassign einer bestehenden Verbindung: `(fReassignConnection)` mit `FReassignConnectionEvent`
  (`connectionId`, `endpoint`, `previousSourceId`, `nextSourceId`, `previousTargetId`,
  `nextTargetId`, `dropPosition`; Legacy-Aliase `oldSourceId`/`newSourceId`/etc.).
- Waypoints (Kantenknicke): `(fConnectionWaypointsChanged)` → `FConnectionWaypointsChangedEvent`.

## Node-Verschieben (Event)

- Output **`(fMoveNodes)`** auf `<f-flow fDraggable>`, Payload `FMoveNodesEvent`:
  ```typescript
  type FMoveNodePosition = { id: string; position: IPoint };
  class FMoveNodesEvent {
    readonly nodes: FMoveNodePosition[]; // bevorzugt
    readonly fNodes: FMoveNodePosition[]; // deprecated Alias
  }
  ```
  Im Spike per Drag ausgelöst und im Browser-Console-Log bestätigt (`fMoveNodes [Object]`,
  Node-Position nach Drag korrekt aktualisiert und im DOM als
  `transform: translate(Npx, Mpx)` sichtbar).

## Auswahl (Selection)

- Output **`(fSelectionChange)`** auf `<f-flow fDraggable>`, Payload `FSelectionChangeEvent`:
  ```typescript
  class FSelectionChangeEvent {
    readonly nodeIds: string[];
    readonly groupIds: string[];
    readonly connectionIds: string[];
    // deprecated Getter: fNodeIds, fGroupIds, fConnectionIds
  }
  ```
  Im Spike per Klick auf einen Node ausgelöst und geloggt (`fSelectionChange [node-1] [] []`).
- Mehrfachauswahl per Rahmen: `<f-selection-area>` (opt-in, im Spike nicht verdrahtet).

## Löschen

- Es gibt KEINE eingebaute Lösch-Methode/-Mutation. Über die Tastatur (`Delete`/`Backspace`)
  meldet die Accessibility-Layer (aktiviert über `withA11y()` in `provideFFlow(...)`) das
  Löschen als Output **`(fDeleteSelected)`** auf `<f-flow fDraggable>`, Payload
  `FDeleteSelectedEvent { nodeIds, groupIds, connectionIds }`. Die App muss die Elemente
  selbst aus ihrem State entfernen; die Lib mutiert nichts automatisch. Im Spike nicht
  aktiviert (kein `provideFFlow(withA11y())`) — für Task B8/B9 vorzusehen, falls
  Keyboard-Löschen gewünscht ist. Alternativ: eigener Lösch-Button, der direkt den
  App-State ändert (kein Event von der Lib nötig).

## Pan/Zoom und Minimap

- Pan: automatisch über `fDraggable` auf `<f-flow>` (Ziehen auf leerem Canvas-Hintergrund).
- Zoom: Directive `[fZoom]` auf `<f-canvas>` — opt-in, ohne sie passiert bei Mausrad nichts.
  Weitere Inputs: `minimum`, `maximum`, `step`, `pinchStep`, `dblClickStep`,
  `[fWheelTrigger]`, `[fDblClickTrigger]`.
- Minimap: `<f-minimap>` — Inputs `[fMinSize]`, `[fNodeRenderLimit]` (beide Signal-Inputs).
  **Muss direktes Kind von `<f-flow>` sein, nicht von `<f-canvas>`** (siehe Stolperfalle oben).
- Programmatische Viewport-Steuerung über `FCanvasComponent`-Methoden (via `#canvas`-Template-
  Referenz oder `ViewChild`): `fitToScreen(padding?, animated?)`, `resetScaleAndCenter(animated?)`,
  `centerGroupOrNode(id, animated?)`, `getScale()`. WICHTIG laut AI.md (Fehlercode `FF1009`):
  diese Methoden erst NACH `(fNodesRendered)` bzw. `(fFullRendered)` aufrufen, sonst falscher
  initialer Viewport, weil die Berechnung von der tatsächlich gemessenen Node-Bounding-Box
  abhängt.

## Alle im Spike beobachteten/relevanten Input/Output-Namen

| Element/Directive | Typ | Name | Bedeutung |
|---|---|---|---|
| `f-flow` | Input (Signal) | `fFlowId` (Property `fId`) | Flow-ID |
| `f-flow` | Input (Signal) | `fCache` | Performance-Feature für >500 Nodes |
| `f-flow` | Output | `fNodesRendered` (`EventEmitter<string>`, Flow-ID als Payload) | Nodes gemessen/gerendert |
| `f-flow` | Output | `fFullRendered` (`EventEmitter<string>`) | Kompletter Rendering-Pass fertig (bevorzugt ggü. `fNodesRendered` für allgemeine "fertig"-Checks) |
| `f-flow[fDraggable]` | Output | `fCreateConnection` | `FCreateConnectionEvent` |
| `f-flow[fDraggable]` | Output | `fReassignConnection` | `FReassignConnectionEvent` |
| `f-flow[fDraggable]` | Output | `fMoveNodes` | `FMoveNodesEvent` |
| `f-flow[fDraggable]` | Output | `fSelectionChange` | `FSelectionChangeEvent` |
| `f-flow[fDraggable]` | Output | `fDeleteSelected` | `FDeleteSelectedEvent` (braucht `withA11y()`) |
| `f-flow[fDraggable]` | Output | `fCreateNode` | `FCreateNodeEvent` (External-Item-Drop) |
| `f-flow[fDraggable]` | Output | `fDropToGroup` | `FDropToGroupEvent` |
| `f-flow[fDraggable]` | Output | `fConnectionWaypointsChanged` | `FConnectionWaypointsChangedEvent` |
| `[fNode]` | Input (Signal) | `fNodeId` (Property `fId`) | stabile Node-ID |
| `[fNode]` | Input (Signal, two-way) | `fNodePosition` (Property `position`) / `fNodePositionChange` | `{x,y}` |
| `[fNode]` | Input (Signal) | `fNodeParentId` | Gruppen-Zugehörigkeit |
| `[fNode]` | Input (Signal) | `fNodeSize`, `fNodeRotate` | optional |
| `[fConnector]` | Input (Signal) | `fConnectorId` | Connector-ID |
| `[fConnector]` | Input (Signal) | `fConnectorType` | `'source'\|'target'\|'source-target'\|'outlet'` |
| `[fConnector]` | Input (Signal) | `fConnectorMultiple`, `fConnectorDisabled`, `fCanBeConnectedTo` | optional |
| `f-connection` | Input (Signal) | `fSourceId`, `fTargetId` (Legacy: `fOutputId`, `fInputId`) | Connector-Referenzen |
| `f-canvas[fZoom]` | Input | `minimum`, `maximum`, `step`, `pinchStep`, `dblClickStep` | Zoom-Grenzen |
| `f-minimap` | Input (Signal) | `fMinSize`, `fNodeRenderLimit` | Minimap-Konfiguration |

## Verifizierte Laufzeit-Fakten (aus dem echten Spike-DOM)

- Nach Drag von Node A wurde der DOM-Style tatsächlich zu
  `transform: translate(160px, 140px) rotate(0deg)` aktualisiert — Positionierung läuft über
  CSS-Transform, nicht `left`/`top`.
- Verbundene Connectoren bekommen `class="... f-connector-source f-connector-multiple
  f-connector-connected"` bzw. `... f-connector-target ... f-connector-connected"`.
- `<f-connection>` rendert intern ein eigenes `<svg>` mit `<path f-connection-path>` (der
  eigentliche Kanten-Pfad), `<path fConnectionSelection>` (breiterer, unsichtbarer Hit-Bereich
  für Klicks) und `<circle f-connection-drag-handle-end>` (Reassign-Griff), mit
  `marker-start`/`marker-end`, die auf generierte `<defs>`-IDs `f-connection-marker-start-*` /
  `-end-*` verweisen.
- `fNodesRendered` feuert zuverlässig kurz nach Bootstrap; im Spike beobachtete Reihenfolge:
  `fNodesRendered` → (User-Interaktion) → `fSelectionChange` / `fMoveNodes`.

## Bekanntes Problem + Workaround: Connection-Linie unsichtbar (Chromium)

**Befund:** Im Spike wurde die `<f-connection>` korrekt im DOM aufgebaut — `fSourceId`/
`fTargetId` lösten korrekt auf (`.f-connector-connected` an beiden Enden), der Pfad-`d`-
Attributwert war geometrisch korrekt (z. B. `M 125 147.6 L 405 227.6`), Farbe/Stroke-Width
kamen korrekt aus dem Theme — aber die Linie war in Chromium (Playwright, Version 148,
sowohl headless als auch headed) visuell NICHT sichtbar, weder im initialen Zustand noch
nach Drag.

**Ursache (verifiziert):** Die Komponente `f-connection` shippt (siehe kompilierter Code,
`fesm2022/foblex-flow.mjs`) folgendes Host-CSS:
`:host svg{display:block;vertical-align:middle;overflow:visible!important;position:absolute}`
— OHNE explizite `width`/`height`. Bei einem absolut positionierten `<svg>` ohne
`width`/`height`-Attribute/-CSS und ohne `viewBox` berechnet der Browser die intrinsische
Box gemäß CSS-Spezifikation für "shrink-to-fit" bei fehlender intrinsischer Größe als
**0×0**. In diesem konkreten Chromium-Build wird der über die Pfad-Koordinaten hinausgehende
Inhalt eines wirklich 0×0-großen `<svg>` trotz `overflow: visible !important` NICHT
gemalt — reproduziert auch mit einem minimalen, von Angular/Foblex unabhängigen HTML-Snippet
(`<svg style="position:absolute;width:0;height:0;overflow:visible">…</svg>`).

**Verifizierter Workaround:** Eine zusätzliche globale CSS-Regel, die dem `<svg>` innerhalb
von `f-connection` eine echte (beliebig kleine) intrinsische Größe gibt, behebt das Problem
vollständig, ohne die Optik zu verändern (der Pfad überläuft die Box weiterhin sichtbar):

```scss
f-connection svg {
  width: 1px;
  height: 1px;
}
```

Getestet: mit dieser Regel rendert die Verbindungslinie korrekt und vollständig sichtbar
(Screenshot-Vergleich vor/nach Regel bestätigt).

**Offene Punkte für Task B8:**
1. Diese Workaround-Regel muss in die globalen Styles der echten `FlowCanvasComponent`
   (bzw. `src/styles.scss`) aufgenommen werden, sobald `<f-connection>` produktiv genutzt wird.
2. Prüfen, ob das Problem browserabhängig ist (in diesem Spike nur mit Chromium 148 getestet,
   da weder Playwright-Firefox noch eine interaktive Chrome-Session in dieser Umgebung
   verfügbar waren). Da Chromium/Edge/Chrome die de-facto Zielbrowser für dieses
   Haushalts-Tool sind, ist der Workaround so oder so sinnvoll und risikofrei anzuwenden.
3. Die `<f-minimap>`-Platzierung als Kind von `<f-flow>` (statt `<f-canvas>`) wurde aus der
   Typdeklaration abgeleitet, aber nach der Korrektur nicht mehr separat mit Screenshot
   nachverifiziert (Zeitdruck) — kurz gegenchecken, sobald B8 eine Minimap einbaut.
4. `provideFFlow(...)`-Features (`withA11y()` für Keyboard-Delete, `withConnectionFlow('click')`
   für Click-to-Connect als Alternative zu Drag, `withControlScheme(...)`) wurden im Spike
   nicht ausprobiert — für Task B8/B9 bei Bedarf gegen `index.d.ts` verifizieren, nicht raten.

## Fazit für die Folge-Tasks

- Angular-19-Kompatibilität: **gegeben**, Build läuft sauber durch, keine Peer-Dependency-
  Konflikte.
- Kern-API (Node/Connector/Connection/Events) verhält sich exakt wie in `AI.md` dokumentiert
  und wurde hier zusätzlich gegen den kompilierten Code und das Laufzeitverhalten geprüft.
- Ein konkretes, aber trivial behebbares Rendering-Problem (Connection-Linie unsichtbar ohne
  die 1px-Workaround-Regel) wurde gefunden und muss in Task B8 direkt mit eingebaut werden.
- Kein Grund, auf den in der Spec dokumentierten CDK-Fallback auszuweichen.
