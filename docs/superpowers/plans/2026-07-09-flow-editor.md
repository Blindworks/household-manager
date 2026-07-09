# Flow-Editor (Stufe 3b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Visueller Node-RED-artiger Canvas-Editor im Angular-Frontend für die Flow-Engine (Stufe 3a): Node-Palette, `@foblex/flow`-Canvas mit Verkabelung, schema-getriebenes Konfig-Panel mit Entity-/Geräte-Pickern, Deploy und Live-Debug.

**Architecture:** Backend liefert einen angereicherten `node-types`-Katalog (typisierte Feld-Deskriptoren + Port-Labels). Der Angular-Editor hält die Flow-Definition in **unserem** JSON-Format (`{nodes,wires}`) als Signal; ein `FlowGraphMapper` übersetzt bidirektional zum `@foblex/flow`-Modell (einzige Lib-Kopplung). Konfig-Panel rendert Widgets schema-getrieben. Speichern manuell (Draft), Deploy validiert im Backend, Debug per Polling. Spec: `docs/superpowers/specs/2026-07-09-flow-editor-design.md`.

**Tech Stack:** Backend: Spring Boot 3.4.1, Java 21, Lombok, JUnit 5. Frontend: Angular 19 standalone, `@foblex/flow`, RxJS, Karma/Jasmine.

---

## Build-Umgebung

**Backend** (Bash, vor jedem mvn): `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend`. Bekannt/zu ignorieren: `HouseholdManagerApplicationTests.contextLoads` + `HealthControllerTest` (lokal keine DB) — Einzeltests mit `-Dtest=...`.

**Frontend** (aus `frontend/`): `npx ng test --watch=false --browsers=ChromeHeadless --include='**/<datei>.spec.ts'` für einzelne Specs; `npx ng build --configuration production` für Build. Karma/Jasmine ist konfiguriert.

**Projektregeln:** Nur die pro Task gelisteten Dateien committen (plus notwendige Konstruktor-/Test-Fixes bestehender Tests — als Abweichung melden). Niemals `git add -A`. `.claude/`- und `nul`-Dateien nicht anfassen. Branch `main` (vom Nutzer freigegeben). Angular: standalone, separate HTML/SCSS, `inject()`-Stil wie in bestehenden Services (`frontend/src/app/services/entity-state.service.ts`).

**Vorhandene Bausteine (Stufe 3a):** REST `/api/v1/flows` (GET Liste, POST create, GET/PUT/DELETE `{id}`, POST `{id}/deploy` → 200/400 mit `{errors,warnings}`, POST `{id}/enable`/`disable`, POST `{id}/nodes/{nodeId}/inject`, GET `{id}/nodes/{nodeId}/debug`, GET `/node-types`). Flow-JSON-Format: `{"nodes":[{"id","type","name","position":{"x","y"},"config":{}}],"wires":[{"from":{"node","port"},"to":{"node"}}]}`. Picker-Datenquellen: `/api/v1/entities`, `/api/devices`, `/api/v1/alexa/devices`. `NodeHandler` hat aktuell `default Map<String,String> configSchema()`; `NodeTypeResponse` = `record(type, outputPorts, trigger, configSchema)`.

---

# BLOCK A — Backend: node-types-Katalog anreichern

### Task A1: FieldDescriptor-Typen + NodeHandler-Erweiterung + DTO/Controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/NodeFieldType.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/NodeFieldDescriptor.java`
- Modify: `backend/src/main/java/com/household/manager/flowengine/NodeHandler.java`
- Modify: `backend/src/main/java/com/household/manager/dto/NodeTypeResponse.java`
- Modify: `backend/src/main/java/com/household/manager/controller/FlowController.java`
- Modify: `backend/src/test/java/com/household/manager/controller/FlowControllerTest.java`

- [ ] **Step 1: Typen anlegen**

`NodeFieldType.java`:
```java
package com.household.manager.flowengine;

/** Feldtyp im node-types-Katalog; steuert das Widget im Frontend-Konfig-Panel. */
public enum NodeFieldType {
    STRING,
    NUMBER,
    ENUM,
    ENTITY_REF,
    DEVICE_REF,
    ALEXA_DEVICE_LIST
}
```

`NodeFieldDescriptor.java`:
```java
package com.household.manager.flowengine;

import lombok.Builder;

import java.util.List;

/**
 * Beschreibt ein Konfig-Feld eines Node-Typs für das schema-getriebene Panel.
 * options ist nur bei type == ENUM gesetzt.
 */
@Builder
public record NodeFieldDescriptor(
        String key,
        String label,
        NodeFieldType type,
        boolean required,
        List<String> options) {

    public static NodeFieldDescriptor field(String key, String label, NodeFieldType type, boolean required) {
        return new NodeFieldDescriptor(key, label, type, required, List.of());
    }

    public static NodeFieldDescriptor enumField(String key, String label, boolean required, List<String> options) {
        return new NodeFieldDescriptor(key, label, NodeFieldType.ENUM, required, options);
    }
}
```

- [ ] **Step 2: NodeHandler um fields()/portLabels() erweitern**

In `NodeHandler.java` die alte `configSchema()`-Default-Methode ERSETZEN durch zwei neue Default-Methoden (Import `java.util.List` ist vorhanden, `java.util.Map` kann bleiben oder entfernt werden):
```java
    /** Typisierte Feld-Deskriptoren für den node-types-Katalog (schema-getriebenes Panel). */
    default List<NodeFieldDescriptor> fields() {
        return List.of();
    }

    /** Labels der Ausgangsports (Länge == outputPorts); Default "Ausgang" je Port. */
    default List<String> portLabels() {
        return java.util.stream.IntStream.range(0, outputPorts())
                .mapToObj(i -> "Ausgang")
                .toList();
    }
```
Die alte `configSchema()`-Methode und ihren `Map`-Import entfernen. (Die 8 Handler überschreiben aktuell `configSchema()` — diese Overrides werden in Task A2 durch `fields()` ersetzt. Damit A1 für sich kompiliert, in A1 NUR das Interface + DTO + Controller + Controller-Test ändern; die Handler-Overrides von `configSchema()` in A1 vorübergehend entfernen ist NICHT nötig, weil `configSchema()` als Interface-Methode wegfällt → die 8 `@Override configSchema()` würden Kompilierfehler werfen. Deshalb: in A1 die 8 `configSchema()`-Overrides mit-entfernen und durch nichts ersetzen (Default `fields()` = leer greift), Task A2 füllt dann `fields()`. Liste der 8 Dateien in Step 3.)

- [ ] **Step 3: Die 8 configSchema()-Overrides entfernen** (damit A1 kompiliert)

In diesen Dateien die komplette `@Override public Map<String,String> configSchema() {...}`-Methode und ggf. den ungenutzten `java.util.Map`-Import entfernen:
`nodes/EntityStateTriggerHandler.java`, `nodes/ScheduleTriggerHandler.java`, `nodes/EntityConditionHandler.java`, `nodes/DelayNodeHandler.java`, `nodes/RateLimitNodeHandler.java`, `nodes/DebugNodeHandler.java`, `nodes/AlexaAnnounceNodeHandler.java`, `nodes/SwitchDeviceNodeHandler.java`.
(Diese Dateien werden in A2 erneut angefasst, um `fields()` zu setzen — hier nur die alte Methode raus.)

- [ ] **Step 4: DTO erweitern**

`NodeTypeResponse.java`:
```java
package com.household.manager.dto;

import com.household.manager.flowengine.NodeFieldDescriptor;
import lombok.Builder;

import java.util.List;

@Builder
public record NodeTypeResponse(
        String type,
        int outputPorts,
        boolean trigger,
        List<String> portLabels,
        List<NodeFieldDescriptor> fields) {
}
```

- [ ] **Step 5: Controller-Mapping anpassen**

In `FlowController.java`, Methode `nodeTypes()`: den Builder auf die neuen Felder umstellen (Import `NodeFieldDescriptor` nicht nötig, da nur durchgereicht):
```java
    @GetMapping("/node-types")
    public List<NodeTypeResponse> nodeTypes() {
        return handlers.stream()
                .map(handler -> NodeTypeResponse.builder()
                        .type(handler.type())
                        .outputPorts(handler.outputPorts())
                        .trigger(handler instanceof TriggerNodeHandler)
                        .portLabels(handler.portLabels())
                        .fields(handler.fields())
                        .build())
                .sorted(Comparator.comparing(NodeTypeResponse::type))
                .toList();
    }
```

- [ ] **Step 6: Controller-Test anpassen**

In `FlowControllerTest.java` den `TestTriggerHandler` und den `listsNodeTypes`-Test auf das neue Schema umstellen. Ersetze im inneren `TestTriggerHandler` die `configSchema()`-Methode durch:
```java
        public java.util.List<com.household.manager.flowengine.NodeFieldDescriptor> fields() {
            return java.util.List.of(com.household.manager.flowengine.NodeFieldDescriptor.field(
                    "k", "Feld K", com.household.manager.flowengine.NodeFieldType.STRING, true));
        }
        public java.util.List<String> portLabels() { return java.util.List.of("Ausgang"); }
```
und ersetze den `listsNodeTypes`-Test-Body durch:
```java
    @Test
    void listsNodeTypes() throws Exception {
        mockMvc.perform(get("/v1/flows/node-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("test-trigger"))
                .andExpect(jsonPath("$[0].trigger").value(true))
                .andExpect(jsonPath("$[0].outputPorts").value(1))
                .andExpect(jsonPath("$[0].portLabels[0]").value("Ausgang"))
                .andExpect(jsonPath("$[0].fields[0].key").value("k"))
                .andExpect(jsonPath("$[0].fields[0].type").value("STRING"))
                .andExpect(jsonPath("$[0].fields[0].required").value(true));
    }
```

- [ ] **Step 7: Rot → implementieren → grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowControllerTest`
Expected: nach den Änderungen 8/8 grün (der bestehende 404-Test + die 7 anderen).

- [ ] **Step 8: Kompletter flowengine-Kompilat-Check** (die 8 Handler kompilieren ohne configSchema())

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**
```bash
git add backend/src/main/java/com/household/manager/flowengine/NodeFieldType.java backend/src/main/java/com/household/manager/flowengine/NodeFieldDescriptor.java backend/src/main/java/com/household/manager/flowengine/NodeHandler.java backend/src/main/java/com/household/manager/flowengine/nodes backend/src/main/java/com/household/manager/dto/NodeTypeResponse.java backend/src/main/java/com/household/manager/controller/FlowController.java backend/src/test/java/com/household/manager/controller/FlowControllerTest.java
git commit -m "feat(floweditor): enrich node-types catalog with typed field descriptors and port labels"
```

---

### Task A2: fields()/portLabels() in allen 8 Handlern + Katalog-Test

**Files:**
- Modify: alle 8 `backend/src/main/java/com/household/manager/flowengine/nodes/*Handler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java`

- [ ] **Step 1: Failing Test schreiben**

`NodeCatalogFieldsTest.java`:
```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NodeCatalogFieldsTest {

    private NodeFieldDescriptor field(List<NodeFieldDescriptor> fields, String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void entityStateTriggerFieldsAndPorts() {
        var h = new EntityStateTriggerHandler(null);
        var fields = h.fields();
        assertEquals(NodeFieldType.ENTITY_REF, field(fields, "entityId").type());
        assertTrue(field(fields, "entityId").required());
        assertEquals(NodeFieldType.ENUM, field(fields, "operator").type());
        assertTrue(field(fields, "operator").options().contains("changed"));
        assertEquals(NodeFieldType.NUMBER, field(fields, "forSeconds").type());
        assertFalse(field(fields, "forSeconds").required());
        assertEquals(List.of("Ausgang"), h.portLabels());
    }

    @Test
    void entityConditionHasTruthyFalsyPortLabels() {
        var h = new EntityConditionHandler(null);
        assertEquals(List.of("wahr", "falsch"), h.portLabels());
        assertEquals(NodeFieldType.ENTITY_REF, field(h.fields(), "entityId").type());
    }

    @Test
    void scheduleTriggerHasCronField() {
        assertEquals(NodeFieldType.STRING, field(new ScheduleTriggerHandler().fields(), "cron").type());
    }

    @Test
    void delayAndRateLimitHaveNumberFields() {
        assertEquals(NodeFieldType.NUMBER, field(new DelayNodeHandler().fields(), "seconds").type());
        assertEquals(NodeFieldType.NUMBER, field(new RateLimitNodeHandler().fields(), "minIntervalSeconds").type());
    }

    @Test
    void debugHasOptionalLabelAndNoPorts() {
        var h = new DebugNodeHandler();
        assertFalse(field(h.fields(), "label").required());
        assertTrue(h.portLabels().isEmpty());
    }

    @Test
    void alexaAnnounceFields() {
        var fields = new AlexaAnnounceNodeHandler(null).fields();
        assertEquals(NodeFieldType.STRING, field(fields, "text").type());
        assertEquals(List.of("SPEAK", "ANNOUNCE"), field(fields, "mode").options());
        assertEquals(NodeFieldType.ALEXA_DEVICE_LIST, field(fields, "deviceSerials").type());
    }

    @Test
    void switchDeviceFields() {
        var fields = new SwitchDeviceNodeHandler(null).fields();
        assertEquals(NodeFieldType.DEVICE_REF, field(fields, "deviceId").type());
        assertEquals(List.of("on", "off"), field(fields, "action").options());
    }
}
```

- [ ] **Step 2: Test rot**
Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=NodeCatalogFieldsTest`

- [ ] **Step 3: `fields()` je Handler implementieren** (je Handler `import com.household.manager.flowengine.NodeFieldDescriptor;` + `import com.household.manager.flowengine.NodeFieldType;` + `import java.util.List;`)

`EntityStateTriggerHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("entityId", "Entity", NodeFieldType.ENTITY_REF, true),
                NodeFieldDescriptor.enumField("operator", "Operator", true,
                        List.of("<", "<=", ">", ">=", "==", "!=", "changed")),
                NodeFieldDescriptor.field("value", "Wert", NodeFieldType.STRING, false),
                NodeFieldDescriptor.field("forSeconds", "seit (Sek.)", NodeFieldType.NUMBER, false));
    }
```
`ScheduleTriggerHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(NodeFieldDescriptor.field("cron", "Cron-Ausdruck", NodeFieldType.STRING, true));
    }
```
`EntityConditionHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("entityId", "Entity", NodeFieldType.ENTITY_REF, true),
                NodeFieldDescriptor.enumField("operator", "Operator", true,
                        List.of("<", "<=", ">", ">=", "==", "!=")),
                NodeFieldDescriptor.field("value", "Wert", NodeFieldType.STRING, true));
    }

    @Override
    public List<String> portLabels() {
        return List.of("wahr", "falsch");
    }
```
`DelayNodeHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(NodeFieldDescriptor.field("seconds", "Verzögerung (Sek.)", NodeFieldType.NUMBER, true));
    }
```
`RateLimitNodeHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(NodeFieldDescriptor.field("minIntervalSeconds", "Mindestabstand (Sek.)", NodeFieldType.NUMBER, true));
    }
```
`DebugNodeHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(NodeFieldDescriptor.field("label", "Beschriftung", NodeFieldType.STRING, false));
    }
```
`AlexaAnnounceNodeHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("text", "Ansagetext", NodeFieldType.STRING, true),
                NodeFieldDescriptor.enumField("mode", "Modus", true, List.of("SPEAK", "ANNOUNCE")),
                NodeFieldDescriptor.field("deviceSerials", "Alexa-Geräte", NodeFieldType.ALEXA_DEVICE_LIST, true));
    }
```
`SwitchDeviceNodeHandler`:
```java
    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("deviceId", "Gerät", NodeFieldType.DEVICE_REF, true),
                NodeFieldDescriptor.enumField("action", "Aktion", true, List.of("on", "off")));
    }
```

- [ ] **Step 4: Test grün**
Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=NodeCatalogFieldsTest`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: Commit**
```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java
git commit -m "feat(floweditor): declare typed config fields and port labels for all node types"
```

---

# BLOCK B — Frontend: Angular-Editor

### Task B1: Spike — @foblex/flow installieren und minimalen Flow rendern

**Ziel:** Die exakte `@foblex/flow`-API für Angular 19 ermitteln, Build-Kompatibilität sichern, ein minimales Flow-Rendering (2 Nodes, 1 Kante, Pan/Zoom) zum Laufen bringen. Kein TDD (Integrations-Spike).

**Files:**
- Modify: `frontend/package.json` (Dependency)
- Create: `frontend/src/app/pages/flows/foblex-spike.notes.md` (Doku der ermittelten API — wird in B8 genutzt, danach löschbar)

- [ ] **Step 1: Installieren + Angular-19-Kompatibilität prüfen**
Run (aus `frontend/`): `npm install @foblex/flow` und danach `npx ng build --configuration production 2>&1 | tail -15`.
Expected: Installation ohne peer-dependency-Fehler gegen Angular 19; Build erfolgreich. **Falls harte Peer-Konflikte/Build-Bruch:** STOP, melde BLOCKED mit der genauen Fehlermeldung — dann greift der in der Spec dokumentierte CDK-Fallback (separater Re-Plan).

- [ ] **Step 2: Minimale Render-Verifikation**
Lege temporär eine Standalone-Komponente an, die mit den `@foblex/flow`-Komponenten (`f-flow`/`f-canvas` bzw. laut installierter Version) zwei Nodes und eine Verbindung rendert, und binde sie an eine Wegwerf-Route `/flows-spike`. Starte `npx ng build --configuration production` → muss bauen. (Der visuelle Check erfolgt beim Ausführenden; Ziel ist zu bestätigen, dass Template-Tags/Inputs/Outputs der Lib bekannt sind.)

- [ ] **Step 3: API dokumentieren**
Schreibe in `foblex-spike.notes.md` die konkret verwendeten Selektoren/Directives/Inputs/Outputs der installierten `@foblex/flow`-Version auf (z. B. wie Nodes deklariert werden, wie Verbindungen erzeugt/gemeldet werden, wie Positionen gesetzt/gelesen werden, wie Pan/Zoom/Minimap aktiviert werden). Diese Notizen sind die Grundlage für `FlowGraphMapper` (B3) und `FlowCanvasComponent` (B8).

- [ ] **Step 4: Spike-Route/-Komponente wieder entfernen**, `foblex-spike.notes.md` behalten.

- [ ] **Step 5: Commit**
```bash
git add frontend/package.json frontend/package-lock.json frontend/src/app/pages/flows/foblex-spike.notes.md
git commit -m "chore(floweditor): add @foblex/flow and document its Angular 19 API (spike)"
```

---

### Task B2: Frontend-Modelle + FlowService (TDD)

**Files:**
- Create: `frontend/src/app/models/flow.model.ts`
- Create: `frontend/src/app/services/flow.service.ts`
- Test: `frontend/src/app/services/flow.service.spec.ts`

- [ ] **Step 1: Modelle anlegen**

`flow.model.ts`:
```typescript
/** Unser Flow-JSON-Format (identisch zum Backend). */
export interface FlowNode {
  id: string;
  type: string;
  name?: string;
  position: { x: number; y: number };
  config: Record<string, unknown>;
}

export interface FlowWire {
  from: { node: string; port: number };
  to: { node: string };
}

export interface FlowDefinition {
  nodes: FlowNode[];
  wires: FlowWire[];
}

export interface FlowSummary {
  id: number;
  name: string;
  description?: string;
  enabled: boolean;
  deployed: boolean;
  deployedAt?: string;
  updatedAt?: string;
}

export interface FlowDetail extends FlowSummary {
  draftDefinition?: string;
  deployedDefinition?: string;
  createdAt?: string;
}

export type NodeFieldType = 'STRING' | 'NUMBER' | 'ENUM' | 'ENTITY_REF' | 'DEVICE_REF' | 'ALEXA_DEVICE_LIST';

export interface NodeFieldDescriptor {
  key: string;
  label: string;
  type: NodeFieldType;
  required: boolean;
  options: string[];
}

export interface NodeType {
  type: string;
  outputPorts: number;
  trigger: boolean;
  portLabels: string[];
  fields: NodeFieldDescriptor[];
}

export interface ValidationResult {
  errors: string[];
  warnings: string[];
}

export interface DebugEntry {
  timestamp: string;
  label?: string;
  message: Record<string, unknown>;
}
```

- [ ] **Step 2: Failing Service-Test schreiben**

`flow.service.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FlowService } from './flow.service';

describe('FlowService', () => {
  let service: FlowService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(FlowService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists flows', () => {
    service.getFlows().subscribe();
    const req = httpMock.expectOne('/api/v1/flows');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('loads node types', () => {
    service.getNodeTypes().subscribe();
    httpMock.expectOne('/api/v1/flows/node-types').flush([]);
  });

  it('creates a flow', () => {
    service.createFlow('Neu', 'Desc').subscribe();
    const req = httpMock.expectOne('/api/v1/flows');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Neu', description: 'Desc' });
    req.flush({});
  });

  it('saves draft via PUT', () => {
    service.saveDraft(1, 'Name', 'Desc', '{"nodes":[],"wires":[]}').subscribe();
    const req = httpMock.expectOne('/api/v1/flows/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.draftDefinition).toBe('{"nodes":[],"wires":[]}');
    req.flush({});
  });

  it('deploys and returns validation result', () => {
    let result: any;
    service.deploy(1).subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/flows/1/deploy').flush({ errors: [], warnings: ['w'] });
    expect(result.warnings).toEqual(['w']);
  });

  it('maps deploy 400 body to validation result', () => {
    let result: any;
    service.deploy(1).subscribe(r => (result = r));
    httpMock.expectOne('/api/v1/flows/1/deploy')
      .flush({ errors: ['kaputt'], warnings: [] }, { status: 400, statusText: 'Bad Request' });
    expect(result.errors).toEqual(['kaputt']);
  });

  it('enables, disables, deletes, injects, reads debug', () => {
    service.setEnabled(1, true).subscribe();
    httpMock.expectOne('/api/v1/flows/1/enable').flush({});
    service.setEnabled(1, false).subscribe();
    httpMock.expectOne('/api/v1/flows/1/disable').flush({});
    service.deleteFlow(1).subscribe();
    expect(httpMock.expectOne('/api/v1/flows/1').request.method).toBe('DELETE');
    httpMock.expectOne('/api/v1/flows/1').flush(null);
    service.inject(1, 'n1', { newState: '5' }).subscribe();
    const inj = httpMock.expectOne('/api/v1/flows/1/nodes/n1/inject');
    expect(inj.request.body).toEqual({ payload: { newState: '5' } });
    inj.flush(null);
    service.getDebug(1, 'n1').subscribe();
    httpMock.expectOne('/api/v1/flows/1/nodes/n1/debug').flush([]);
  });
});
```

- [ ] **Step 3: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow.service.spec.ts'`

- [ ] **Step 4: Service implementieren**

`flow.service.ts`:
```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  DebugEntry, FlowDetail, FlowSummary, NodeType, ValidationResult
} from '../models/flow.model';

/** REST-Anbindung an die Flow-Engine (/api/v1/flows). */
@Injectable({ providedIn: 'root' })
export class FlowService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/flows';

  getFlows(): Observable<FlowSummary[]> {
    return this.http.get<FlowSummary[]>(this.baseUrl);
  }

  getFlow(id: number): Observable<FlowDetail> {
    return this.http.get<FlowDetail>(`${this.baseUrl}/${id}`);
  }

  getNodeTypes(): Observable<NodeType[]> {
    return this.http.get<NodeType[]>(`${this.baseUrl}/node-types`);
  }

  createFlow(name: string, description: string): Observable<FlowDetail> {
    return this.http.post<FlowDetail>(this.baseUrl, { name, description });
  }

  saveDraft(id: number, name: string, description: string, draftDefinition: string): Observable<FlowDetail> {
    return this.http.put<FlowDetail>(`${this.baseUrl}/${id}`, { name, description, draftDefinition });
  }

  /** Deploy: 200 (valid) und 400 (invalid) liefern denselben ValidationResult-Body. */
  deploy(id: number): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.baseUrl}/${id}/deploy`, {}).pipe(
      catchError(err => of(err.error as ValidationResult))
    );
  }

  setEnabled(id: number, enabled: boolean): Observable<FlowSummary> {
    return this.http.post<FlowSummary>(`${this.baseUrl}/${id}/${enabled ? 'enable' : 'disable'}`, {});
  }

  deleteFlow(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  inject(id: number, nodeId: string, payload: Record<string, unknown>): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/nodes/${nodeId}/inject`, { payload });
  }

  getDebug(id: number, nodeId: string): Observable<DebugEntry[]> {
    return this.http.get<DebugEntry[]>(`${this.baseUrl}/${id}/nodes/${nodeId}/debug`);
  }
}
```

- [ ] **Step 5: Test grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow.service.spec.ts'`
Expected: 7 SUCCESS

- [ ] **Step 6: Commit**
```bash
git add frontend/src/app/models/flow.model.ts frontend/src/app/services/flow.service.ts frontend/src/app/services/flow.service.spec.ts
git commit -m "feat(floweditor): add flow models and REST service"
```

---

### Task B3: FlowGraphMapper (unser Format ↔ @foblex/flow) (TDD)

**Files:**
- Create: `frontend/src/app/pages/flows/flow-graph.mapper.ts`
- Test: `frontend/src/app/pages/flows/flow-graph.mapper.spec.ts`

**Hinweis:** Die konkrete `@foblex/flow`-Modellform stammt aus dem B1-Spike (`foblex-spike.notes.md`). Der Mapper definiert ein schmales **eigenes** Zwischenmodell (`CanvasNode`/`CanvasConnection`), das die Editor-Komponente an `@foblex/flow` bindet — so bleibt der Mapper testbar ohne Lib-Abhängigkeit, und `FlowCanvasComponent` (B8) übersetzt dieses Zwischenmodell in die konkreten Lib-Objekte.

- [ ] **Step 1: Failing Test schreiben**

`flow-graph.mapper.spec.ts`:
```typescript
import { FlowGraphMapper, CanvasNode, CanvasConnection } from './flow-graph.mapper';
import { FlowDefinition } from '../../models/flow.model';

describe('FlowGraphMapper', () => {
  const mapper = new FlowGraphMapper();

  const def: FlowDefinition = {
    nodes: [
      { id: 'n1', type: 'entity-state-trigger', name: 'T', position: { x: 80, y: 120 }, config: { operator: '<' } },
      { id: 'n2', type: 'alexa-announce', position: { x: 400, y: 120 }, config: {} }
    ],
    wires: [{ from: { node: 'n1', port: 0 }, to: { node: 'n2' } }]
  };

  it('maps definition to canvas nodes and connections', () => {
    const { nodes, connections } = mapper.toCanvas(def);
    expect(nodes.length).toBe(2);
    expect(nodes[0]).toEqual(jasmine.objectContaining({ id: 'n1', type: 'entity-state-trigger', x: 80, y: 120 }));
    expect(connections.length).toBe(1);
    expect(connections[0]).toEqual(jasmine.objectContaining({ fromNode: 'n1', fromPort: 0, toNode: 'n2' }));
  });

  it('round-trips definition -> canvas -> definition preserving nodes, wires, positions, config', () => {
    const { nodes, connections } = mapper.toCanvas(def);
    const back = mapper.toDefinition(nodes, connections);
    expect(back).toEqual(def);
  });

  it('preserves node position updates on the way back', () => {
    const { nodes, connections } = mapper.toCanvas(def);
    const moved: CanvasNode[] = nodes.map(n => n.id === 'n1' ? { ...n, x: 200, y: 300 } : n);
    const back = mapper.toDefinition(moved, connections);
    expect(back.nodes.find(n => n.id === 'n1')!.position).toEqual({ x: 200, y: 300 });
  });

  it('drops connections whose endpoints no longer exist', () => {
    const { nodes } = mapper.toCanvas(def);
    const orphan: CanvasConnection[] = [{ fromNode: 'ghost', fromPort: 0, toNode: 'n2' }];
    const back = mapper.toDefinition(nodes, orphan);
    expect(back.wires.length).toBe(0);
  });
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow-graph.mapper.spec.ts'`

- [ ] **Step 3: Mapper implementieren**

`flow-graph.mapper.ts`:
```typescript
import { FlowDefinition, FlowNode } from '../../models/flow.model';

/** Lib-unabhängiges Zwischenmodell für den Canvas. */
export interface CanvasNode {
  id: string;
  type: string;
  name?: string;
  x: number;
  y: number;
  config: Record<string, unknown>;
}

export interface CanvasConnection {
  fromNode: string;
  fromPort: number;
  toNode: string;
}

/** Übersetzt bidirektional zwischen Backend-Flow-Format und Canvas-Zwischenmodell. */
export class FlowGraphMapper {
  toCanvas(def: FlowDefinition): { nodes: CanvasNode[]; connections: CanvasConnection[] } {
    const nodes: CanvasNode[] = def.nodes.map(n => ({
      id: n.id,
      type: n.type,
      name: n.name,
      x: n.position?.x ?? 0,
      y: n.position?.y ?? 0,
      config: n.config ?? {}
    }));
    const connections: CanvasConnection[] = def.wires.map(w => ({
      fromNode: w.from.node,
      fromPort: w.from.port,
      toNode: w.to.node
    }));
    return { nodes, connections };
  }

  toDefinition(nodes: CanvasNode[], connections: CanvasConnection[]): FlowDefinition {
    const ids = new Set(nodes.map(n => n.id));
    const outNodes: FlowNode[] = nodes.map(n => {
      const node: FlowNode = { id: n.id, type: n.type, position: { x: n.x, y: n.y }, config: n.config ?? {} };
      if (n.name !== undefined) { node.name = n.name; }
      return node;
    });
    const wires = connections
      .filter(c => ids.has(c.fromNode) && ids.has(c.toNode))
      .map(c => ({ from: { node: c.fromNode, port: c.fromPort }, to: { node: c.toNode } }));
    return { nodes: outNodes, wires };
  }
}
```

- [ ] **Step 4: Test grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow-graph.mapper.spec.ts'`
Expected: 4 SUCCESS

- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/pages/flows/flow-graph.mapper.ts frontend/src/app/pages/flows/flow-graph.mapper.spec.ts
git commit -m "feat(floweditor): add bidirectional flow graph mapper with round-trip tests"
```

---

### Task B4: Flow-Liste (Seite) + Route + Navigation

**Files:**
- Create: `frontend/src/app/pages/flows/flow-list.component.ts`
- Create: `frontend/src/app/pages/flows/flow-list.component.html`
- Create: `frontend/src/app/pages/flows/flow-list.component.scss`
- Test: `frontend/src/app/pages/flows/flow-list.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`

- [ ] **Step 1: Failing Component-Test schreiben**

`flow-list.component.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { FlowListComponent } from './flow-list.component';
import { FlowService } from '../../services/flow.service';

describe('FlowListComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService', ['getFlows', 'createFlow', 'deleteFlow', 'setEnabled']);
    flowService.getFlows.and.returnValue(of([
      { id: 1, name: 'A', enabled: true, deployed: true },
      { id: 2, name: 'B', enabled: false, deployed: false }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [FlowListComponent],
      providers: [provideRouter([]), { provide: FlowService, useValue: flowService }]
    }).compileComponents();
  });

  it('loads flows on init', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.flows().length).toBe(2);
  });

  it('deletes a flow and reloads', () => {
    flowService.deleteFlow.and.returnValue(of(void 0));
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();
    fixture.componentInstance.deleteFlow({ id: 1, name: 'A', enabled: true, deployed: true } as any);
    expect(flowService.deleteFlow).toHaveBeenCalledWith(1);
    expect(flowService.getFlows).toHaveBeenCalledTimes(2);
  });
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow-list.component.spec.ts'`

- [ ] **Step 3: Komponente implementieren**

`flow-list.component.ts`:
```typescript
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { FlowService } from '../../services/flow.service';
import { FlowSummary } from '../../models/flow.model';

/** Übersicht aller Automatisierungs-Flows. */
@Component({
  selector: 'app-flow-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './flow-list.component.html',
  styleUrl: './flow-list.component.scss'
})
export class FlowListComponent implements OnInit {
  private readonly flowService = inject(FlowService);
  private readonly router = inject(Router);

  readonly flows = signal<FlowSummary[]>([]);

  ngOnInit(): void {
    this.reload();
  }

  private reload(): void {
    this.flowService.getFlows().subscribe(flows => this.flows.set(flows));
  }

  createFlow(): void {
    this.flowService.createFlow('Neuer Flow', '').subscribe(flow => this.router.navigate(['/flows', flow.id]));
  }

  open(flow: FlowSummary): void {
    this.router.navigate(['/flows', flow.id]);
  }

  toggleEnabled(flow: FlowSummary): void {
    this.flowService.setEnabled(flow.id, !flow.enabled).subscribe(() => this.reload());
  }

  deleteFlow(flow: FlowSummary): void {
    this.flowService.deleteFlow(flow.id).subscribe(() => this.reload());
  }
}
```

`flow-list.component.html`:
```html
<div class="flow-list-page">
  <div class="flow-list-page__header">
    <h1>Automatisierungen</h1>
    <button class="flow-list-page__new" (click)="createFlow()">+ Neuer Flow</button>
  </div>

  <table class="flow-table">
    <thead>
      <tr><th>Name</th><th>Status</th><th>Aktiv</th><th></th></tr>
    </thead>
    <tbody>
      @for (flow of flows(); track flow.id) {
        <tr class="flow-table__row" (click)="open(flow)">
          <td>{{ flow.name }}</td>
          <td>
            @if (flow.deployed) { <span class="badge badge--deployed">deployed</span> }
            @else { <span class="badge badge--draft">Entwurf</span> }
          </td>
          <td (click)="$event.stopPropagation()">
            <button class="toggle" [class.toggle--on]="flow.enabled" (click)="toggleEnabled(flow)">
              {{ flow.enabled ? 'aktiv' : 'aus' }}
            </button>
          </td>
          <td (click)="$event.stopPropagation()">
            <button class="flow-table__delete" (click)="deleteFlow(flow)">Löschen</button>
          </td>
        </tr>
      } @empty {
        <tr><td colspan="4" class="flow-table__empty">Noch keine Automatisierungen. Lege eine neue an.</td></tr>
      }
    </tbody>
  </table>
</div>
```

`flow-list.component.scss`:
```scss
.flow-list-page {
  padding: 1.5rem;
  &__header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
  &__new { padding: 0.5rem 1rem; border: none; border-radius: 4px; background: #1565c0; color: #fff; cursor: pointer; }
}
.flow-table {
  width: 100%; border-collapse: collapse; font-size: 0.9rem;
  th, td { text-align: left; padding: 0.6rem 0.75rem; border-bottom: 1px solid #e0e0e0; }
  th { background: #f5f5f5; font-weight: 600; }
  &__row { cursor: pointer; &:hover { background: #fafafa; } }
  &__delete { background: none; border: none; color: #b71c1c; cursor: pointer; }
  &__empty { text-align: center; color: #888; padding: 2rem; }
}
.badge { padding: 0.15rem 0.5rem; border-radius: 10px; font-size: 0.75rem; font-weight: 600;
  &--deployed { background: #e8f5e9; color: #2e7d32; }
  &--draft { background: #eceff1; color: #546e7a; } }
.toggle { padding: 0.2rem 0.6rem; border: 1px solid #ccc; border-radius: 10px; background: #eceff1; cursor: pointer;
  &--on { background: #e8f5e9; color: #2e7d32; border-color: #2e7d32; } }
```

- [ ] **Step 4: Route + Nav**

In `app.routes.ts` nach dem `entities`-Eintrag:
```typescript
  {
    path: 'flows',
    loadComponent: () => import('./pages/flows/flow-list.component').then(m => m.FlowListComponent),
    title: 'Automatisierungen - Household Manager'
  },
  {
    path: 'flows/:id',
    loadComponent: () => import('./pages/flows/flow-editor.component').then(m => m.FlowEditorComponent),
    title: 'Flow-Editor - Household Manager'
  },
```
In `header.component.ts` im `navLinks`-Array nach dem `/entities`-Eintrag:
```typescript
    { path: '/flows', label: 'Automatisierungen' },
```

- [ ] **Step 5: Test grün + Build**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow-list.component.spec.ts'`
Expected: 2 SUCCESS.
Hinweis: Der `flows/:id`-Route-Eintrag verweist auf `FlowEditorComponent` (Task B9) — dieser Import wird erst mit B9 auflösbar. Falls der Build-Test in B4 an diesem fehlenden Import scheitert, den `flows/:id`-Routeneintrag erst in B9 hinzufügen (Notiz im Commit). Der reine Component-Spec-Test von B4 ist davon unabhängig grün.

- [ ] **Step 6: Commit**
```bash
git add frontend/src/app/pages/flows/flow-list.component.ts frontend/src/app/pages/flows/flow-list.component.html frontend/src/app/pages/flows/flow-list.component.scss frontend/src/app/pages/flows/flow-list.component.spec.ts frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(floweditor): add flow list page with route and navigation"
```

---

### Task B5: Picker-Komponenten (Entity, Device, Alexa-Geräte) (TDD)

**Files:**
- Create: `frontend/src/app/pages/flows/pickers/entity-picker.component.ts` (+ `.html`)
- Create: `frontend/src/app/pages/flows/pickers/device-picker.component.ts` (+ `.html`)
- Create: `frontend/src/app/pages/flows/pickers/alexa-device-picker.component.ts` (+ `.html`)
- Create: `frontend/src/app/services/device.service.ts`, `frontend/src/app/services/alexa-device.service.ts` (falls nicht vorhanden — sonst bestehende nutzen)
- Test: `frontend/src/app/pages/flows/pickers/entity-picker.component.spec.ts`

**Hinweis:** Bestehende Services prüfen: `smart-device.service.ts` existiert bereits (Geräte). Für Entities existiert `entity-state.service.ts` mit `getEntities()`. Für Alexa-Geräte den Endpunkt `/api/v1/alexa/devices` nutzen (ggf. bestehenden `alexa.service.ts` erweitern statt neuen Service). Nutze vorhandene Services, lege nur an, was fehlt.

- [ ] **Step 1: Failing Test schreiben** (stellvertretend für alle drei Picker — gleiches Muster)

`entity-picker.component.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { EntityPickerComponent } from './entity-picker.component';
import { EntityStateService } from '../../../services/entity-state.service';

describe('EntityPickerComponent', () => {
  let entityService: jasmine.SpyObj<EntityStateService>;

  beforeEach(async () => {
    entityService = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityService.getEntities.and.returnValue(of([
      { entityId: 'sensor.a', friendlyName: 'Sensor A', state: '5' },
      { entityId: 'switch.b', friendlyName: 'Schalter B', state: 'on' }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [EntityPickerComponent],
      providers: [{ provide: EntityStateService, useValue: entityService }]
    }).compileComponents();
  });

  it('loads options on init', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.options().length).toBe(2);
  });

  it('keeps an unknown selected value as fallback label', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.componentRef.setInput('value', 'sensor.ghost');
    fixture.detectChanges();
    expect(fixture.componentInstance.displayLabel()).toContain('nicht gefunden');
    expect(fixture.componentInstance.displayLabel()).toContain('sensor.ghost');
  });

  it('emits valueChange on select', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe(v => (emitted = v));
    fixture.componentInstance.select('switch.b');
    expect(emitted).toBe('switch.b');
  });
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/entity-picker.component.spec.ts'`

- [ ] **Step 3: EntityPicker implementieren**

`entity-picker.component.ts`:
```typescript
import { Component, EventEmitter, Input, OnInit, Output, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { EntityStateService } from '../../../services/entity-state.service';
import { EntityState } from '../../../models/entity-state.model';

/** Durchsuchbares Dropdown für Entity-Referenzen (Feldtyp ENTITY_REF). */
@Component({
  selector: 'app-entity-picker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './entity-picker.component.html'
})
export class EntityPickerComponent implements OnInit {
  private readonly entityService = inject(EntityStateService);

  readonly options = signal<EntityState[]>([]);
  readonly value = signal<string | undefined>(undefined);

  @Input() set valueInput(v: string | undefined) { this.value.set(v); }
  // Angular signal input alias for template tests:
  @Input('value') set valueAttr(v: string | undefined) { this.value.set(v); }
  @Output() valueChange = new EventEmitter<string>();

  readonly displayLabel = computed(() => {
    const v = this.value();
    if (!v) { return ''; }
    const found = this.options().find(o => o.entityId === v);
    return found ? `${found.friendlyName} (${found.state})` : `nicht gefunden: ${v}`;
  });

  ngOnInit(): void {
    this.entityService.getEntities().subscribe(list => this.options.set(list));
  }

  select(entityId: string): void {
    this.value.set(entityId);
    this.valueChange.emit(entityId);
  }
}
```

`entity-picker.component.html`:
```html
<div class="picker">
  <select [value]="value() ?? ''" (change)="select($any($event.target).value)">
    <option value="" disabled>Entity wählen…</option>
    @for (opt of options(); track opt.entityId) {
      <option [value]="opt.entityId">{{ opt.friendlyName }} ({{ opt.state }})</option>
    }
    @if (value() && !options().length) { <option [value]="value()">{{ value() }}</option> }
  </select>
  @if (displayLabel().startsWith('nicht gefunden')) {
    <span class="picker__missing">{{ displayLabel() }}</span>
  }
</div>
```

**Hinweis Signal-Input:** Falls die im Test genutzte `setInput('value', …)`-API mit dem `@Input`-Setter kollidiert, stattdessen Angulars `input()`-Signal verwenden: `value = input<string>()` und im computed `this.value()`. Wähle die Variante, die den Test grün macht; die Test-Assertions (options-Länge, displayLabel, valueChange) bleiben maßgeblich.

- [ ] **Step 4: Device- und Alexa-Picker analog** (gleiches Muster, andere Datenquelle)

`device-picker.component.ts`: nutzt den bestehenden Geräte-Service (`smart-device.service.ts`, Methode zum Laden aller Geräte — Signatur vorher prüfen), Wert ist die numerische Geräte-ID; Anzeige `deviceName`. Fallback „nicht gefunden: <id>".
`alexa-device-picker.component.ts`: lädt `/api/v1/alexa/devices`, **Mehrfachauswahl** (Wert ist `string[]` von Seriennummern), Anzeige der Gerätenamen; Checkbox-Liste. `valueChange` emittiert `string[]`.

- [ ] **Step 5: Tests grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/entity-picker.component.spec.ts'`
Expected: 3 SUCCESS. (Device-/Alexa-Picker analoge Specs optional; mindestens der Entity-Picker-Spec muss grün sein.)

- [ ] **Step 6: Commit**
```bash
git add frontend/src/app/pages/flows/pickers frontend/src/app/services
git commit -m "feat(floweditor): add entity, device and alexa-device picker widgets"
```

---

### Task B6: NodeConfigPanelComponent (schema-getrieben) (TDD)

**Files:**
- Create: `frontend/src/app/pages/flows/node-config-panel.component.ts` (+ `.html`, `.scss`)
- Test: `frontend/src/app/pages/flows/node-config-panel.component.spec.ts`

- [ ] **Step 1: Failing Test schreiben**

`node-config-panel.component.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { NodeConfigPanelComponent } from './node-config-panel.component';
import { NodeType } from '../../models/flow.model';
import { CanvasNode } from './flow-graph.mapper';

describe('NodeConfigPanelComponent', () => {
  const nodeType: NodeType = {
    type: 'entity-state-trigger', trigger: true, outputPorts: 1, portLabels: ['Ausgang'],
    fields: [
      { key: 'entityId', label: 'Entity', type: 'ENTITY_REF', required: true, options: [] },
      { key: 'operator', label: 'Operator', type: 'ENUM', required: true, options: ['<', '>'] },
      { key: 'forSeconds', label: 'seit', type: 'NUMBER', required: false, options: [] }
    ]
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [NodeConfigPanelComponent] }).compileComponents();
  });

  it('renders one row per field of the node type', () => {
    const fixture = TestBed.createComponent(NodeConfigPanelComponent);
    fixture.componentRef.setInput('node', { id: 'n1', type: 'entity-state-trigger', x: 0, y: 0, config: {} } as CanvasNode);
    fixture.componentRef.setInput('nodeType', nodeType);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.config-field').length).toBe(3);
  });

  it('emits configChange when a value is set', () => {
    const fixture = TestBed.createComponent(NodeConfigPanelComponent);
    fixture.componentRef.setInput('node', { id: 'n1', type: 'entity-state-trigger', x: 0, y: 0, config: {} } as CanvasNode);
    fixture.componentRef.setInput('nodeType', nodeType);
    fixture.detectChanges();
    let emitted: any;
    fixture.componentInstance.configChange.subscribe((c: any) => (emitted = c));
    fixture.componentInstance.setField('operator', '<');
    expect(emitted.operator).toBe('<');
  });

  it('marks required fields', () => {
    const fixture = TestBed.createComponent(NodeConfigPanelComponent);
    fixture.componentRef.setInput('node', { id: 'n1', type: 'entity-state-trigger', x: 0, y: 0, config: {} } as CanvasNode);
    fixture.componentRef.setInput('nodeType', nodeType);
    fixture.detectChanges();
    expect(fixture.componentInstance.isRequired('entityId')).toBe(true);
    expect(fixture.componentInstance.isRequired('forSeconds')).toBe(false);
  });
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/node-config-panel.component.spec.ts'`

- [ ] **Step 3: Implementieren**

`node-config-panel.component.ts`:
```typescript
import { Component, EventEmitter, Output, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NodeType, NodeFieldDescriptor } from '../../models/flow.model';
import { CanvasNode } from './flow-graph.mapper';
import { EntityPickerComponent } from './pickers/entity-picker.component';
import { DevicePickerComponent } from './pickers/device-picker.component';
import { AlexaDevicePickerComponent } from './pickers/alexa-device-picker.component';

/** Schema-getriebenes Konfig-Formular der ausgewählten Node. */
@Component({
  selector: 'app-node-config-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, EntityPickerComponent, DevicePickerComponent, AlexaDevicePickerComponent],
  templateUrl: './node-config-panel.component.html',
  styleUrl: './node-config-panel.component.scss'
})
export class NodeConfigPanelComponent {
  readonly node = input<CanvasNode>();
  readonly nodeType = input<NodeType>();

  @Output() configChange = new EventEmitter<Record<string, unknown>>();

  readonly fields = computed<NodeFieldDescriptor[]>(() => this.nodeType()?.fields ?? []);

  isRequired(key: string): boolean {
    return this.fields().find(f => f.key === key)?.required ?? false;
  }

  fieldValue(key: string): unknown {
    return this.node()?.config?.[key];
  }

  setField(key: string, value: unknown): void {
    const current = { ...(this.node()?.config ?? {}) };
    current[key] = value;
    this.configChange.emit(current);
  }
}
```

`node-config-panel.component.html`:
```html
@if (node() && nodeType()) {
  <div class="config-panel">
    <h3>{{ nodeType()!.type }}</h3>
    @for (field of fields(); track field.key) {
      <div class="config-field">
        <label>{{ field.label }} @if (field.required) { <span class="req">*</span> }</label>

        @switch (field.type) {
          @case ('ENUM') {
            <select [ngModel]="fieldValue(field.key)" (ngModelChange)="setField(field.key, $event)">
              <option value="" disabled>wählen…</option>
              @for (opt of field.options; track opt) { <option [value]="opt">{{ opt }}</option> }
            </select>
          }
          @case ('NUMBER') {
            <input type="number" [ngModel]="fieldValue(field.key)" (ngModelChange)="setField(field.key, $event)" />
          }
          @case ('ENTITY_REF') {
            <app-entity-picker [value]="$any(fieldValue(field.key))" (valueChange)="setField(field.key, $event)" />
          }
          @case ('DEVICE_REF') {
            <app-device-picker [value]="$any(fieldValue(field.key))" (valueChange)="setField(field.key, $event)" />
          }
          @case ('ALEXA_DEVICE_LIST') {
            <app-alexa-device-picker [value]="$any(fieldValue(field.key))" (valueChange)="setField(field.key, $event)" />
          }
          @default {
            <input type="text" [ngModel]="fieldValue(field.key)" (ngModelChange)="setField(field.key, $event)" />
          }
        }
      </div>
    }
  </div>
}
```

`node-config-panel.component.scss`:
```scss
.config-panel { padding: 0.75rem; h3 { margin: 0 0 0.75rem; font-size: 0.95rem; } }
.config-field { margin-bottom: 0.75rem; display: flex; flex-direction: column; gap: 0.25rem;
  label { font-size: 0.8rem; color: #555; } .req { color: #b71c1c; }
  input, select { padding: 0.4rem; border: 1px solid #ccc; border-radius: 4px; font-size: 0.85rem; } }
```

**Hinweis:** Die Picker-Selektoren (`app-entity-picker` etc.) und ihr `value`-Input/`valueChange`-Output müssen zu B5 passen. Falls in B5 die Signal-`input()`-Variante gewählt wurde, ggf. Binding anpassen. Der Test von B6 mockt keine Picker-Datenquellen — daher die Picker-Komponenten in ihren eigenen Specs (B5) getestet; hier zählt das Rendern der Feldzeilen + configChange.

- [ ] **Step 4: Test grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/node-config-panel.component.spec.ts'`
Expected: 3 SUCCESS
Hinweis: Der Test rendert Picker-Kinder mit; deren `ngOnInit` ruft Services. Falls das im Test HTTP-Aufrufe auslöst, im Spec `provideHttpClient()` + `provideHttpClientTesting()` ergänzen und die Requests flushen, ODER die Picker im Test via `overrideComponent` durch Stubs ersetzen. Wähle den einfachsten grünen Weg; Assertions bleiben maßgeblich.

- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/pages/flows/node-config-panel.component.ts frontend/src/app/pages/flows/node-config-panel.component.html frontend/src/app/pages/flows/node-config-panel.component.scss frontend/src/app/pages/flows/node-config-panel.component.spec.ts
git commit -m "feat(floweditor): add schema-driven node config panel"
```

---

### Task B7: NodePaletteComponent (TDD)

**Files:**
- Create: `frontend/src/app/pages/flows/node-palette.component.ts` (+ `.html`, `.scss`)
- Test: `frontend/src/app/pages/flows/node-palette.component.spec.ts`

- [ ] **Step 1: Failing Test schreiben**

`node-palette.component.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { NodePaletteComponent } from './node-palette.component';
import { NodeType } from '../../models/flow.model';

describe('NodePaletteComponent', () => {
  const types: NodeType[] = [
    { type: 'entity-state-trigger', trigger: true, outputPorts: 1, portLabels: ['Ausgang'], fields: [] },
    { type: 'entity-condition', trigger: false, outputPorts: 2, portLabels: ['wahr', 'falsch'], fields: [] },
    { type: 'alexa-announce', trigger: false, outputPorts: 1, portLabels: ['Ausgang'], fields: [] }
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [NodePaletteComponent] }).compileComponents();
  });

  it('groups node types into trigger / logic / action', () => {
    const fixture = TestBed.createComponent(NodePaletteComponent);
    fixture.componentRef.setInput('nodeTypes', types);
    fixture.detectChanges();
    expect(fixture.componentInstance.triggers().length).toBe(1);
    expect(fixture.componentInstance.actions().length).toBe(1);
    expect(fixture.componentInstance.logic().length).toBe(1);
  });

  it('emits add when a palette item is activated', () => {
    const fixture = TestBed.createComponent(NodePaletteComponent);
    fixture.componentRef.setInput('nodeTypes', types);
    fixture.detectChanges();
    let added: string | undefined;
    fixture.componentInstance.add.subscribe(t => (added = t));
    fixture.componentInstance.onAdd('alexa-announce');
    expect(added).toBe('alexa-announce');
  });
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/node-palette.component.spec.ts'`

- [ ] **Step 3: Implementieren**

`node-palette.component.ts`:
```typescript
import { Component, EventEmitter, Output, computed, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NodeType } from '../../models/flow.model';

/** Palette der verfügbaren Node-Typen, gruppiert; per Klick/Drag hinzufügbar. */
@Component({
  selector: 'app-node-palette',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './node-palette.component.html',
  styleUrl: './node-palette.component.scss'
})
export class NodePaletteComponent {
  readonly nodeTypes = input<NodeType[]>([]);
  @Output() add = new EventEmitter<string>();

  private readonly actionTypes = new Set(['alexa-announce', 'switch-device']);

  readonly triggers = computed(() => this.nodeTypes().filter(t => t.trigger));
  readonly actions = computed(() => this.nodeTypes().filter(t => !t.trigger && this.actionTypes.has(t.type)));
  readonly logic = computed(() => this.nodeTypes().filter(t => !t.trigger && !this.actionTypes.has(t.type)));

  onAdd(type: string): void {
    this.add.emit(type);
  }
}
```

`node-palette.component.html`:
```html
<div class="palette">
  <div class="palette__group">
    <div class="palette__title">Trigger</div>
    @for (t of triggers(); track t.type) {
      <button class="palette__item palette__item--trigger" (click)="onAdd(t.type)">{{ t.type }}</button>
    }
  </div>
  <div class="palette__group">
    <div class="palette__title">Logik</div>
    @for (t of logic(); track t.type) {
      <button class="palette__item palette__item--logic" (click)="onAdd(t.type)">{{ t.type }}</button>
    }
  </div>
  <div class="palette__group">
    <div class="palette__title">Aktionen</div>
    @for (t of actions(); track t.type) {
      <button class="palette__item palette__item--action" (click)="onAdd(t.type)">{{ t.type }}</button>
    }
  </div>
</div>
```

`node-palette.component.scss`:
```scss
.palette { padding: 0.5rem; width: 150px; }
.palette__group { margin-bottom: 0.75rem; }
.palette__title { font-size: 0.7rem; font-weight: 600; color: #888; text-transform: uppercase; margin-bottom: 0.35rem; }
.palette__item { display: block; width: 100%; text-align: left; margin-bottom: 0.3rem; padding: 0.35rem 0.5rem;
  border-radius: 5px; border: 1px solid; cursor: grab; font-size: 0.75rem; background: #fff;
  &--trigger { border-color: #1565c0; color: #0d47a1; }
  &--logic { border-color: #f9a825; color: #855b00; }
  &--action { border-color: #2e7d32; color: #1b5e20; } }
```

- [ ] **Step 4: Test grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/node-palette.component.spec.ts'`
Expected: 2 SUCCESS

- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/pages/flows/node-palette.component.ts frontend/src/app/pages/flows/node-palette.component.html frontend/src/app/pages/flows/node-palette.component.scss frontend/src/app/pages/flows/node-palette.component.spec.ts
git commit -m "feat(floweditor): add grouped node palette"
```

---

### Task B8: FlowCanvasComponent (@foblex/flow-Integration)

**Ziel:** Kapselt `@foblex/flow`. Nimmt Canvas-Knoten/-Verbindungen (Zwischenmodell aus B3) als Input, rendert sie mit der Lib, und meldet Interaktionen (Node verschoben, Verbindung erzeugt/gelöscht, Node ausgewählt/gelöscht) als Outputs. **Kein Unit-TDD** (Lib-Integration, DOM-schwer) — Verifikation über Build + die Round-Trip-Tests des Mappers (B3) + manuellen Editor-Test in B11. Die konkreten Lib-Tags/Inputs/Outputs stammen aus `foblex-spike.notes.md` (B1).

**Files:**
- Create: `frontend/src/app/pages/flows/flow-canvas.component.ts` (+ `.html`, `.scss`)

- [ ] **Step 1: Komponenten-Interface festlegen** (fix, unabhängig von der Lib)

`flow-canvas.component.ts` — Grundgerüst mit klarem Vertrag:
```typescript
import { Component, EventEmitter, Output, input } from '@angular/core';
import { CommonModule } from '@angular/common';
// import { ... } from '@foblex/flow';  // konkrete Imports laut foblex-spike.notes.md
import { CanvasNode, CanvasConnection } from './flow-graph.mapper';

/**
 * Kapselt @foblex/flow. Rendert CanvasNodes/-Connections und meldet Editor-Interaktionen.
 * Einzige Stelle (neben FlowGraphMapper), die die Lib kennt.
 */
@Component({
  selector: 'app-flow-canvas',
  standalone: true,
  imports: [CommonModule /*, @foblex/flow-Module laut Spike */],
  templateUrl: './flow-canvas.component.html',
  styleUrl: './flow-canvas.component.scss'
})
export class FlowCanvasComponent {
  readonly nodes = input<CanvasNode[]>([]);
  readonly connections = input<CanvasConnection[]>([]);
  /** Port-Labels je Node-Typ (aus node-types), für die Port-Beschriftung. */
  readonly portLabelsByType = input<Record<string, string[]>>({});

  @Output() nodeMoved = new EventEmitter<{ id: string; x: number; y: number }>();
  @Output() connectionCreated = new EventEmitter<CanvasConnection>();
  @Output() connectionDeleted = new EventEmitter<CanvasConnection>();
  @Output() nodeSelected = new EventEmitter<string>();
  @Output() nodeDeleted = new EventEmitter<string>();

  // Interne Handler übersetzen @foblex/flow-Events in die obigen Outputs.
}
```

- [ ] **Step 2: Template mit `@foblex/flow` füllen** (laut `foblex-spike.notes.md`): Canvas/Flow-Container mit Pan/Zoom/Minimap, Node-Template (Icon/Typ/Name + beschriftete Ein-/Ausgangsports aus `portLabelsByType`), Verbindungs-Rendering. Lib-Events an die Outputs verdrahten (Node-Drag-Ende → `nodeMoved`; neue Verbindung → `connectionCreated`; Verbindung entfernt → `connectionDeleted`; Node-Klick → `nodeSelected`; Node-Löschen → `nodeDeleted`).

- [ ] **Step 3: Build-Verifikation**
Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -8`
Expected: Build erfolgreich.

- [ ] **Step 4: Commit**
```bash
git add frontend/src/app/pages/flows/flow-canvas.component.ts frontend/src/app/pages/flows/flow-canvas.component.html frontend/src/app/pages/flows/flow-canvas.component.scss
git commit -m "feat(floweditor): add @foblex/flow canvas component wrapper"
```

---

### Task B9: FlowEditorComponent (Orchestrierung)

**Ziel:** Bindet alles zusammen: lädt Flow + node-types, hält Canvas-Zustand als Signal, verdrahtet Palette/Canvas/Panel/Debug, Speichern/Deploy/Enable, Unsaved-Guard, Testen.

**Files:**
- Create: `frontend/src/app/pages/flows/flow-editor.component.ts` (+ `.html`, `.scss`)
- Test: `frontend/src/app/pages/flows/flow-editor.component.spec.ts`

- [ ] **Step 1: Failing Test schreiben** (Fokus: Orchestrierungs-Logik, nicht Canvas-DOM)

`flow-editor.component.spec.ts`:
```typescript
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { FlowEditorComponent } from './flow-editor.component';
import { FlowService } from '../../services/flow.service';

describe('FlowEditorComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService',
      ['getFlow', 'getNodeTypes', 'saveDraft', 'deploy', 'setEnabled', 'inject']);
    flowService.getFlow.and.returnValue(of({
      id: 1, name: 'F', enabled: false, deployed: false,
      draftDefinition: '{"nodes":[{"id":"n1","type":"entity-state-trigger","position":{"x":0,"y":0},"config":{}}],"wires":[]}'
    } as any));
    flowService.getNodeTypes.and.returnValue(of([
      { type: 'entity-state-trigger', trigger: true, outputPorts: 1, portLabels: ['Ausgang'], fields: [] }
    ] as any));
    flowService.saveDraft.and.returnValue(of({} as any));
    flowService.deploy.and.returnValue(of({ errors: [], warnings: [] }));
    await TestBed.configureTestingModule({
      imports: [FlowEditorComponent],
      providers: [
        provideRouter([]),
        { provide: FlowService, useValue: flowService },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => '1' } } } }
      ]
    }).compileComponents();
  });

  it('loads flow and node types on init', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.canvasNodes().length).toBe(1);
    expect(fixture.componentInstance.nodeTypes().length).toBe(1);
  });

  it('adds a node from the palette', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    fixture.componentInstance.addNode('entity-state-trigger');
    expect(fixture.componentInstance.canvasNodes().length).toBe(2);
    expect(fixture.componentInstance.dirty()).toBe(true);
  });

  it('saves draft as our JSON format', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    fixture.componentInstance.save();
    expect(flowService.saveDraft).toHaveBeenCalled();
    const draftArg = flowService.saveDraft.calls.mostRecent().args[3];
    expect(JSON.parse(draftArg).nodes[0].id).toBe('n1');
  });

  it('deploy shows returned warnings/errors', () => {
    flowService.deploy.and.returnValue(of({ errors: ['x'], warnings: [] }));
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    fixture.componentInstance.deploy();
    expect(fixture.componentInstance.deployErrors()).toEqual(['x']);
  });
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow-editor.component.spec.ts'`

- [ ] **Step 3: Implementieren** (Kernlogik; Canvas/Palette/Panel/Debug im Template eingebunden)

`flow-editor.component.ts`:
```typescript
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { FlowService } from '../../services/flow.service';
import { FlowDefinition, NodeType } from '../../models/flow.model';
import { CanvasConnection, CanvasNode, FlowGraphMapper } from './flow-graph.mapper';
import { NodePaletteComponent } from './node-palette.component';
import { NodeConfigPanelComponent } from './node-config-panel.component';
import { FlowCanvasComponent } from './flow-canvas.component';
import { DebugPanelComponent } from './debug-panel.component';

/** Der Flow-Editor: orchestriert Palette, Canvas, Konfig-Panel und Debug. */
@Component({
  selector: 'app-flow-editor',
  standalone: true,
  imports: [CommonModule, NodePaletteComponent, NodeConfigPanelComponent, FlowCanvasComponent, DebugPanelComponent],
  templateUrl: './flow-editor.component.html',
  styleUrl: './flow-editor.component.scss'
})
export class FlowEditorComponent implements OnInit {
  private readonly flowService = inject(FlowService);
  private readonly route = inject(ActivatedRoute);
  private readonly mapper = new FlowGraphMapper();

  readonly flowId = Number(this.route.snapshot.paramMap.get('id'));
  readonly name = signal('');
  readonly enabled = signal(false);
  readonly deployed = signal(false);
  readonly nodeTypes = signal<NodeType[]>([]);
  readonly canvasNodes = signal<CanvasNode[]>([]);
  readonly canvasConnections = signal<CanvasConnection[]>([]);
  readonly selectedNodeId = signal<string | null>(null);
  readonly dirty = signal(false);
  readonly deployErrors = signal<string[]>([]);
  readonly deployWarnings = signal<string[]>([]);
  readonly activeTab = signal<'config' | 'debug'>('config');

  private savedSnapshot = '';
  private nodeCounter = 0;

  readonly selectedNode = computed(() => this.canvasNodes().find(n => n.id === this.selectedNodeId()) ?? undefined);
  readonly selectedNodeType = computed(() =>
    this.nodeTypes().find(t => t.type === this.selectedNode()?.type) ?? undefined);
  readonly portLabelsByType = computed<Record<string, string[]>>(() =>
    Object.fromEntries(this.nodeTypes().map(t => [t.type, t.portLabels])));

  ngOnInit(): void {
    forkJoin({ flow: this.flowService.getFlow(this.flowId), types: this.flowService.getNodeTypes() })
      .subscribe(({ flow, types }) => {
        this.name.set(flow.name);
        this.enabled.set(flow.enabled);
        this.deployed.set(flow.deployed);
        this.nodeTypes.set(types);
        const def: FlowDefinition = flow.draftDefinition
          ? JSON.parse(flow.draftDefinition) : { nodes: [], wires: [] };
        const { nodes, connections } = this.mapper.toCanvas(def);
        this.canvasNodes.set(nodes);
        this.canvasConnections.set(connections);
        this.savedSnapshot = this.serialize();
        this.dirty.set(false);
      });
  }

  private serialize(): string {
    return JSON.stringify(this.mapper.toDefinition(this.canvasNodes(), this.canvasConnections()));
  }

  private markDirty(): void {
    this.dirty.set(this.serialize() !== this.savedSnapshot);
  }

  addNode(type: string): void {
    const id = `${type}-${Date.now()}-${this.nodeCounter++}`;
    this.canvasNodes.update(ns => [...ns, { id, type, x: 120, y: 80, config: {} }]);
    this.markDirty();
  }

  onNodeMoved(e: { id: string; x: number; y: number }): void {
    this.canvasNodes.update(ns => ns.map(n => n.id === e.id ? { ...n, x: e.x, y: e.y } : n));
    this.markDirty();
  }

  onConnectionCreated(c: CanvasConnection): void {
    this.canvasConnections.update(cs => [...cs, c]);
    this.markDirty();
  }

  onConnectionDeleted(c: CanvasConnection): void {
    this.canvasConnections.update(cs => cs.filter(x =>
      !(x.fromNode === c.fromNode && x.fromPort === c.fromPort && x.toNode === c.toNode)));
    this.markDirty();
  }

  onNodeSelected(id: string): void {
    this.selectedNodeId.set(id);
    this.activeTab.set('config');
  }

  onNodeDeleted(id: string): void {
    this.canvasNodes.update(ns => ns.filter(n => n.id !== id));
    this.canvasConnections.update(cs => cs.filter(c => c.fromNode !== id && c.toNode !== id));
    if (this.selectedNodeId() === id) { this.selectedNodeId.set(null); }
    this.markDirty();
  }

  onConfigChange(config: Record<string, unknown>): void {
    const id = this.selectedNodeId();
    if (!id) { return; }
    this.canvasNodes.update(ns => ns.map(n => n.id === id ? { ...n, config } : n));
    this.markDirty();
  }

  save(): void {
    const draft = this.serialize();
    this.flowService.saveDraft(this.flowId, this.name(), '', draft).subscribe(() => {
      this.savedSnapshot = draft;
      this.dirty.set(false);
    });
  }

  deploy(): void {
    const draft = this.serialize();
    this.flowService.saveDraft(this.flowId, this.name(), '', draft).subscribe(() => {
      this.savedSnapshot = draft;
      this.dirty.set(false);
      this.flowService.deploy(this.flowId).subscribe(result => {
        this.deployErrors.set(result.errors);
        this.deployWarnings.set(result.warnings);
        if (result.errors.length === 0) { this.deployed.set(true); }
      });
    });
  }

  toggleEnabled(): void {
    this.flowService.setEnabled(this.flowId, !this.enabled()).subscribe(() => this.enabled.set(!this.enabled()));
  }

  testTrigger(nodeId: string): void {
    this.flowService.inject(this.flowId, nodeId, {}).subscribe();
  }
}
```

`flow-editor.component.html` (Layout A: Palette links, Canvas mitte, rechts Tabs):
```html
<div class="editor">
  <div class="editor__toolbar">
    <input class="editor__name" [value]="name()" (input)="name.set($any($event.target).value)" />
    @if (deployed()) { <span class="badge badge--deployed">aktiv/deployed</span> }
    @else { <span class="badge badge--draft">Entwurf</span> }
    @if (dirty()) { <span class="editor__dirty">● ungespeichert</span> }
    <span class="editor__spacer"></span>
    <button (click)="save()" [disabled]="!dirty()">Speichern</button>
    <button (click)="deploy()">Deploy</button>
    <button (click)="toggleEnabled()">{{ enabled() ? 'Deaktivieren' : 'Aktivieren' }}</button>
  </div>

  @if (deployErrors().length) {
    <div class="editor__errors">
      @for (e of deployErrors(); track e) { <div class="editor__error">✕ {{ e }}</div> }
    </div>
  }
  @if (deployWarnings().length) {
    <div class="editor__warnings">
      @for (w of deployWarnings(); track w) { <div class="editor__warning">⚠ {{ w }}</div> }
    </div>
  }

  <div class="editor__body">
    <app-node-palette [nodeTypes]="nodeTypes()" (add)="addNode($event)" />

    <app-flow-canvas class="editor__canvas"
      [nodes]="canvasNodes()" [connections]="canvasConnections()" [portLabelsByType]="portLabelsByType()"
      (nodeMoved)="onNodeMoved($event)" (connectionCreated)="onConnectionCreated($event)"
      (connectionDeleted)="onConnectionDeleted($event)" (nodeSelected)="onNodeSelected($event)"
      (nodeDeleted)="onNodeDeleted($event)" />

    <div class="editor__side">
      <div class="editor__tabs">
        <button [class.active]="activeTab() === 'config'" (click)="activeTab.set('config')">Konfig</button>
        <button [class.active]="activeTab() === 'debug'" (click)="activeTab.set('debug')">Debug</button>
      </div>
      @if (activeTab() === 'config') {
        @if (selectedNode()) {
          <app-node-config-panel [node]="selectedNode()" [nodeType]="selectedNodeType()"
            (configChange)="onConfigChange($event)" />
          @if (selectedNodeType()?.trigger && deployed()) {
            <button class="editor__test" (click)="testTrigger(selectedNodeId()!)">▶ Testen</button>
          }
        } @else {
          <p class="editor__hint">Node auswählen oder aus der Palette ziehen.</p>
        }
      } @else {
        <app-debug-panel [flowId]="flowId" [nodes]="canvasNodes()" [deployed]="deployed()" [active]="activeTab() === 'debug'" />
      }
    </div>
  </div>
</div>
```

`flow-editor.component.scss`:
```scss
.editor { display: flex; flex-direction: column; height: calc(100vh - 60px); }
.editor__toolbar { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 1rem; border-bottom: 1px solid #ddd;
  button { padding: 0.35rem 0.8rem; border: 1px solid #ccc; border-radius: 4px; background: #fff; cursor: pointer;
    &:disabled { opacity: 0.5; cursor: default; } } }
.editor__name { font-size: 1rem; font-weight: 600; border: 1px solid transparent; padding: 0.2rem 0.4rem;
  &:focus { border-color: #ccc; border-radius: 4px; } }
.editor__spacer { flex: 1; }
.editor__dirty { color: #b71c1c; font-size: 0.8rem; }
.editor__body { display: flex; flex: 1; min-height: 0; }
.editor__canvas { flex: 1; min-width: 0; }
.editor__side { width: 260px; border-left: 1px solid #ddd; display: flex; flex-direction: column; }
.editor__tabs { display: flex; button { flex: 1; padding: 0.5rem; border: none; background: #f5f5f5; cursor: pointer;
  &.active { background: #fff; font-weight: 600; border-bottom: 2px solid #1565c0; } } }
.editor__errors { background: #fdecea; color: #b71c1c; padding: 0.5rem 1rem; }
.editor__warnings { background: #fff8e1; color: #855b00; padding: 0.5rem 1rem; }
.editor__test { margin: 0.5rem 0.75rem; padding: 0.4rem 0.8rem; border: 1px solid #2e7d32; color: #1b5e20;
  border-radius: 4px; background: #e8f5e9; cursor: pointer; }
.editor__hint { padding: 0.75rem; color: #888; font-size: 0.85rem; }
.badge { padding: 0.15rem 0.5rem; border-radius: 10px; font-size: 0.75rem; font-weight: 600;
  &--deployed { background: #e8f5e9; color: #2e7d32; } &--draft { background: #eceff1; color: #546e7a; } }
```

**Hinweis:** `DebugPanelComponent` (B10) muss existieren, damit der Import auflöst. Reihenfolge: B10 vor oder zusammen mit B9 umsetzen; im Zweifel den `DebugPanelComponent`-Import + das `<app-debug-panel>`-Tag erst nach B10 aktivieren (Editor-Spec testet Debug nicht).

- [ ] **Step 4: Test grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/flow-editor.component.spec.ts'`
Expected: 4 SUCCESS

- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/pages/flows/flow-editor.component.ts frontend/src/app/pages/flows/flow-editor.component.html frontend/src/app/pages/flows/flow-editor.component.scss frontend/src/app/pages/flows/flow-editor.component.spec.ts
git commit -m "feat(floweditor): add flow editor orchestration (palette, canvas, config, deploy)"
```

---

### Task B10: DebugPanelComponent (Polling) (TDD)

**Files:**
- Create: `frontend/src/app/pages/flows/debug-panel.component.ts` (+ `.html`, `.scss`)
- Test: `frontend/src/app/pages/flows/debug-panel.component.spec.ts`

- [ ] **Step 1: Failing Test schreiben**

`debug-panel.component.spec.ts`:
```typescript
import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { of } from 'rxjs';
import { DebugPanelComponent } from './debug-panel.component';
import { FlowService } from '../../services/flow.service';

describe('DebugPanelComponent', () => {
  let flowService: jasmine.SpyObj<FlowService>;

  beforeEach(async () => {
    flowService = jasmine.createSpyObj('FlowService', ['getDebug']);
    flowService.getDebug.and.returnValue(of([
      { timestamp: '2026-07-09T12:00:00', label: 'x', message: { v: 1 } }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [DebugPanelComponent],
      providers: [{ provide: FlowService, useValue: flowService }]
    }).compileComponents();
  });

  it('polls debug of debug-nodes when active and deployed', fakeAsync(() => {
    const fixture = TestBed.createComponent(DebugPanelComponent);
    fixture.componentRef.setInput('flowId', 1);
    fixture.componentRef.setInput('deployed', true);
    fixture.componentRef.setInput('active', true);
    fixture.componentRef.setInput('nodes', [{ id: 'd1', type: 'debug', x: 0, y: 0, config: {} }]);
    fixture.detectChanges();
    tick(0);
    expect(flowService.getDebug).toHaveBeenCalledWith(1, 'd1');
    expect(fixture.componentInstance.entries().length).toBe(1);
    tick(2000);
    expect(flowService.getDebug).toHaveBeenCalledTimes(2);
    discardPeriodicTasks();
  }));

  it('does not poll when not deployed', fakeAsync(() => {
    const fixture = TestBed.createComponent(DebugPanelComponent);
    fixture.componentRef.setInput('flowId', 1);
    fixture.componentRef.setInput('deployed', false);
    fixture.componentRef.setInput('active', true);
    fixture.componentRef.setInput('nodes', [{ id: 'd1', type: 'debug', x: 0, y: 0, config: {} }]);
    fixture.detectChanges();
    tick(3000);
    expect(flowService.getDebug).not.toHaveBeenCalled();
    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Test rot**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/debug-panel.component.spec.ts'`

- [ ] **Step 3: Implementieren**

`debug-panel.component.ts`:
```typescript
import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap, forkJoin, of, Subscription } from 'rxjs';
import { FlowService } from '../../services/flow.service';
import { DebugEntry } from '../../models/flow.model';
import { CanvasNode } from './flow-graph.mapper';

const POLL_MS = 2000;

/** Debug-Tab: pollt die Debug-Puffer der Debug-Nodes, solange sichtbar und deployed. */
@Component({
  selector: 'app-debug-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './debug-panel.component.html',
  styleUrl: './debug-panel.component.scss'
})
export class DebugPanelComponent {
  private readonly flowService = inject(FlowService);
  private readonly destroyRef = inject(DestroyRef);

  readonly flowId = input.required<number>();
  readonly nodes = input<CanvasNode[]>([]);
  readonly deployed = input(false);
  readonly active = input(false);

  readonly entries = signal<DebugEntry[]>([]);
  private sub?: Subscription;

  constructor() {
    effect(() => {
      const shouldPoll = this.active() && this.deployed();
      this.sub?.unsubscribe();
      if (!shouldPoll) { return; }
      const debugNodeIds = this.nodes().filter(n => n.type === 'debug').map(n => n.id);
      this.sub = interval(POLL_MS).pipe(
        startWith(0),
        switchMap(() => debugNodeIds.length
          ? forkJoin(debugNodeIds.map(id => this.flowService.getDebug(this.flowId(), id)))
          : of([] as DebugEntry[][])),
        takeUntilDestroyed(this.destroyRef)
      ).subscribe(perNode => {
        const all = perNode.flat().sort((a, b) => a.timestamp.localeCompare(b.timestamp));
        this.entries.set(all);
      });
    });
  }
}
```

`debug-panel.component.html`:
```html
<div class="debug-panel">
  @if (!deployed()) {
    <p class="debug-panel__hint">Flow zuerst deployen, um Debug-Nachrichten zu sehen.</p>
  } @else if (!entries().length) {
    <p class="debug-panel__hint">Noch keine Nachrichten. Flow testen oder auf ein Ereignis warten.</p>
  } @else {
    @for (e of entries(); track e.timestamp + ($any(e).label ?? '')) {
      <div class="debug-panel__entry">
        <div class="debug-panel__meta">{{ e.timestamp }} @if (e.label) { · {{ e.label }} }</div>
        <pre class="debug-panel__msg">{{ e.message | json }}</pre>
      </div>
    }
  }
</div>
```

`debug-panel.component.scss`:
```scss
.debug-panel { padding: 0.5rem; overflow-y: auto; font-size: 0.8rem; }
.debug-panel__hint { color: #888; padding: 0.5rem; }
.debug-panel__entry { border-bottom: 1px solid #eee; padding: 0.4rem 0; }
.debug-panel__meta { color: #6a9955; font-family: monospace; font-size: 0.75rem; }
.debug-panel__msg { margin: 0.25rem 0 0; background: #1e1e1e; color: #d4d4d4; padding: 0.4rem;
  border-radius: 4px; overflow-x: auto; font-size: 0.72rem; }
```

- [ ] **Step 4: Test grün**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/debug-panel.component.spec.ts'`
Expected: 2 SUCCESS

- [ ] **Step 5: Commit**
```bash
git add frontend/src/app/pages/flows/debug-panel.component.ts frontend/src/app/pages/flows/debug-panel.component.html frontend/src/app/pages/flows/debug-panel.component.scss frontend/src/app/pages/flows/debug-panel.component.spec.ts
git commit -m "feat(floweditor): add polling debug panel"
```

---

### Task B11: Gesamtverifikation + visuelle Politur

- [ ] **Step 1: Alle Frontend-Tests**
Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: keine neuen Fehlschläge (die bekannten vorbestehenden `ActivatedRoute`-Failures aus AppComponent/HeaderComponent/HeroComponent bleiben, sofern noch vorhanden — benennen und als vorbestehend einordnen).

- [ ] **Step 2: Produktionsbuild**
Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -8`
Expected: erfolgreich.

- [ ] **Step 3: Alle Backend-Tests**
Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn test`
Expected: nur die bekannten DB-Umgebungsfehler; alle flowengine-Tests inkl. NodeCatalogFieldsTest + FlowControllerTest grün.

- [ ] **Step 4: Manueller End-to-End-Test** (dokumentieren; wenn lokale DB nicht läuft, überspringen und vermerken)
Backend + Frontend starten. `/flows` öffnen → „Neuer Flow" → Editor: Entity-Trigger + Alexa-Aktion aus Palette ziehen, verkabeln, konfigurieren (Entity-Picker zeigt echte Entitäten), Speichern, Deploy (Erfolg/Fehler sichtbar), an der Trigger-Node „Testen", Debug-Tab zeigt Nachricht.

- [ ] **Step 5: Visuelle Politur** via `frontend-design`-Skill: Node-Aussehen, Farben, Icons, Panel-Feinschliff, konsistent zum App-Stil (`entities`-Seite als Referenz). Danach erneut Build.

- [ ] **Step 6: Spec-Abgleich** gegen `docs/superpowers/specs/2026-07-09-flow-editor-design.md` (Katalog-Anreicherung, Layout A, schema-getriebenes Panel + Picker, Deploy-Fehler/Warnungen, Debug-Polling, Randfälle) — Checkliste abhaken.

---

## Anmerkungen für die Ausführung

- **Reihenfolge-Abhängigkeiten:** B1 (Spike) zuerst. B8/B9 hängen an B3/B5/B6/B7/B10. Wenn ein Import einer noch nicht existierenden Komponente den Build/Spec bricht: die betroffene Zeile temporär auskommentieren und im jeweiligen Task aktivieren (in den Hinweisen vermerkt).
- **`@foblex/flow`-API:** Die konkreten Template-Tags/Inputs/Outputs stehen erst nach dem B1-Spike fest; B8 füllt sie gemäß `foblex-spike.notes.md`. Sollte die Lib in B1 als unbrauchbar auffallen → BLOCKED melden, CDK-Fallback (Spec) re-planen.
- **Signal-Inputs vs. @Input-Setter:** In den Picker-/Panel-Tests wird `setInput(...)` genutzt — das erfordert `input()`-Signals (Angular 17+). Wo der Plan `@Input`-Setter zeigt, ist bei Testrot auf `input()` umzustellen; die Test-Assertions sind maßgeblich.
- Kanonische Regel: bestehende Tests, die durch Signaturänderungen brechen, minimal fixen und mitcommitten (als Abweichung melden).
