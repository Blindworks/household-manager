# Flow-Import per Datei — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extern erzeugte Flow-Definitionen als selbstbeschreibende JSON-Datei über ein UI-Feature importieren; der Import legt einen neuen, deaktivierten Draft an.

**Architecture:** Neuer `POST /v1/flows/import` nimmt eine Wrapper-Datei (`schemaVersion`, `name`, `description`, `definition`) entgegen, validiert sie über den bestehenden `FlowDefinitionParser` und legt einen `Flow` mit `enabled=false` an. Das Frontend liest die Datei per File-Upload, parst sie clientseitig und POSTet sie. Eine eingecheckte Referenz (`docs/flows/flow-import-format.md`) dokumentiert Format und alle 8 Node-Typen; ein Guard-Test hält die Beispiele valide.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Lombok (Backend), Angular 19 standalone / TypeScript / Karma-Jasmine (Frontend).

**Voraussetzung (Backend-Tests):** `JAVA_HOME` muss auf JDK 21 zeigen (Default ist 17). Siehe Memory `backend-jdk21-build`. Die hier definierten Tests sind reine Mockito-Unit-Tests — sie brauchen **keine** Datenbank.

---

## File Structure

**Neu (Backend)**
- `backend/src/main/java/com/household/manager/dto/ImportFlowRequest.java` — Request-DTO des Import-Endpoints.
- `backend/src/test/java/com/household/manager/controller/FlowControllerTest.java` — Unit-Test der Controller-Delegation.
- `backend/src/test/resources/flow-examples/motion-light.json` — Guard-Kopie Beispiel 1.
- `backend/src/test/resources/flow-examples/temperature-announce.json` — Guard-Kopie Beispiel 2.
- `backend/src/test/java/com/household/manager/flowengine/FlowImportExampleTest.java` — Guard-Test: dokumentierte Beispiele bleiben valide.

**Neu (Docs)**
- `docs/flows/flow-import-format.md` — Authoring-Referenz (Format + Node-Typen + Beispiele).

**Geändert (Backend)**
- `backend/src/main/java/com/household/manager/flowengine/FlowService.java` — Methode `importFlow(...)`.
- `backend/src/main/java/com/household/manager/controller/FlowController.java` — Endpoint `POST /import`.
- `backend/src/test/java/com/household/manager/flowengine/FlowServiceTest.java` — Import-Tests.

**Geändert (Frontend)**
- `frontend/src/app/services/flow.service.ts` — Methode `importFlow(...)`.
- `frontend/src/app/services/flow.service.spec.ts` — Test des POST `/import`.
- `frontend/src/app/pages/flows/flow-list.component.ts` — Import-Handler.
- `frontend/src/app/pages/flows/flow-list.component.html` — Import-Button + verstecktes File-Input.
- `frontend/src/app/pages/flows/flow-list.component.scss` — Button-Stil.
- `frontend/src/app/pages/flows/flow-list.component.spec.ts` — Test des Import-Handlers.

---

## Task 1: Backend — `FlowService.importFlow`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/flowengine/FlowService.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowServiceTest.java`

- [ ] **Step 1: Write the failing tests**

In `FlowServiceTest.java`, füge diese fünf Tests am Ende der Klasse (vor der schließenden `}`) ein. Die vorhandene Konstante `VALID_DEF` und der lenient gestubbte `flowRepository.save(...)` werden wiederverwendet.

```java
    @Test
    void importCreatesDisabledDraft() {
        Flow saved = service.importFlow(1, "Imported", "desc", VALID_DEF);

        assertEquals("Imported", saved.getName());
        assertEquals("desc", saved.getDescription());
        assertFalse(saved.isEnabled());
        assertEquals(VALID_DEF, saved.getDraftDefinition());
        assertNull(saved.getDeployedDefinition());
        verify(flowRepository).save(any(Flow.class));
    }

    @Test
    void importRejectsUnsupportedSchemaVersion() {
        assertThrows(IllegalArgumentException.class, () -> service.importFlow(2, "n", "", VALID_DEF));
        assertThrows(IllegalArgumentException.class, () -> service.importFlow(null, "n", "", VALID_DEF));
    }

    @Test
    void importRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> service.importFlow(1, "  ", "", VALID_DEF));
    }

    @Test
    void importRejectsMissingDefinition() {
        assertThrows(IllegalArgumentException.class, () -> service.importFlow(1, "n", "", null));
    }

    @Test
    void importRejectsUnparseableDefinition() {
        assertThrows(IllegalArgumentException.class, () -> service.importFlow(1, "n", "", "{ broken json"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=FlowServiceTest test`
Expected: FAIL — Kompilierfehler „cannot find symbol: method importFlow".

- [ ] **Step 3: Implement `importFlow`**

In `FlowService.java`, füge die Methode direkt nach `update(...)` (nach Zeile 66) ein:

```java
    /**
     * Legt einen Flow aus einer importierten Definition an. Der neue Flow ist
     * bewusst deaktiviert (enabled=false) und nicht deployt — scharf wird er erst
     * durch den expliziten Deploy-Schritt. Es wird nur die JSON-Struktur geprüft
     * (wie beim Draft-Speichern); die volle Graph-Validierung bleibt dem Deploy.
     */
    @Transactional
    public Flow importFlow(Integer schemaVersion, String name, String description, String definitionJson) {
        if (schemaVersion == null || schemaVersion != 1) {
            throw new IllegalArgumentException("Nicht unterstützte schemaVersion: " + schemaVersion);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name fehlt");
        }
        if (definitionJson == null || definitionJson.isBlank()) {
            throw new IllegalArgumentException("definition fehlt");
        }
        parser.parse(definitionJson); // wirft IllegalArgumentException bei kaputtem JSON -> 400
        Flow flow = Flow.builder()
                .name(name).description(description).enabled(false)
                .draftDefinition(definitionJson).build();
        return flowRepository.save(flow);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && mvn -q -Dtest=FlowServiceTest test`
Expected: PASS (alle FlowServiceTest-Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/FlowService.java backend/src/test/java/com/household/manager/flowengine/FlowServiceTest.java
git commit -m "feat(flows): FlowService.importFlow creates disabled draft from definition"
```

---

## Task 2: Backend — `ImportFlowRequest` DTO + Controller-Endpoint

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/ImportFlowRequest.java`
- Modify: `backend/src/main/java/com/household/manager/controller/FlowController.java`
- Test: `backend/src/test/java/com/household/manager/controller/FlowControllerTest.java`

- [ ] **Step 1: Create the DTO**

Create `ImportFlowRequest.java`:

```java
package com.household.manager.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body des Flow-Import-Endpoints. Die {@code definition} bleibt roher JsonNode
 * und wird als kompakter JSON-String an die Engine weitergereicht.
 */
public record ImportFlowRequest(Integer schemaVersion, String name, String description, JsonNode definition) {
}
```

- [ ] **Step 2: Write the failing controller test**

Create `FlowControllerTest.java`:

```java
package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.FlowDetailResponse;
import com.household.manager.dto.ImportFlowRequest;
import com.household.manager.flowengine.DebugBuffer;
import com.household.manager.flowengine.FlowService;
import com.household.manager.model.entity.Flow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowControllerTest {

    private final FlowService flowService = mock(FlowService.class);
    private final FlowController controller =
            new FlowController(flowService, new DebugBuffer(), List.of());

    @Test
    void importDelegatesToServiceAndMapsResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var definition = mapper.readTree("{\"nodes\":[],\"wires\":[]}");
        var request = new ImportFlowRequest(1, "Imported", "desc", definition);
        Flow saved = Flow.builder().id(7L).name("Imported").description("desc")
                .enabled(false).draftDefinition(definition.toString()).build();
        when(flowService.importFlow(eq(1), eq("Imported"), eq("desc"), eq(definition.toString())))
                .thenReturn(saved);

        FlowDetailResponse response = controller.importFlow(request);

        assertEquals(7L, response.getId());
        assertEquals("Imported", response.getName());
        assertFalse(response.isEnabled());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && mvn -q -Dtest=FlowControllerTest test`
Expected: FAIL — „cannot find symbol: method importFlow(ImportFlowRequest)".

- [ ] **Step 4: Add the endpoint**

In `FlowController.java`, füge die Methode direkt nach `createFlow(...)` (nach Zeile 42) ein:

```java
    @PostMapping("/import")
    public FlowDetailResponse importFlow(@RequestBody ImportFlowRequest request) {
        String definitionJson = request.definition() == null ? null : request.definition().toString();
        return toDetail(flowService.importFlow(
                request.schemaVersion(), request.name(), request.description(), definitionJson));
    }
```

(`ImportFlowRequest` liegt im bereits per Wildcard importierten `com.household.manager.dto.*`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=FlowControllerTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/ImportFlowRequest.java backend/src/main/java/com/household/manager/controller/FlowController.java backend/src/test/java/com/household/manager/controller/FlowControllerTest.java
git commit -m "feat(flows): add POST /v1/flows/import endpoint"
```

---

## Task 3: Backend — Guard-Test für dokumentierte Beispiele

Sichert, dass die in der Referenz dokumentierten Beispiel-Flows strukturell valide bleiben (richtige Feldnamen/Typen). Unbekannte Entitäten/Geräte sind laut `FlowValidator` nur Warnungen, daher gilt ein korrektes Beispiel als `valid()`.

**Files:**
- Create: `backend/src/test/resources/flow-examples/motion-light.json`
- Create: `backend/src/test/resources/flow-examples/temperature-announce.json`
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowImportExampleTest.java`

- [ ] **Step 1: Create example resource 1**

Create `motion-light.json`:

```json
{
  "schemaVersion": 1,
  "name": "Flurlicht bei Bewegung",
  "description": "Schaltet Gerät 1 ein, wenn der Bewegungsmelder auslöst",
  "definition": {
    "nodes": [
      {
        "id": "trigger",
        "type": "entity-state-trigger",
        "name": "Bewegung erkannt",
        "position": { "x": 80, "y": 120 },
        "config": { "entityId": "binary_sensor.flur_bewegung", "operator": "==", "value": "on" }
      },
      {
        "id": "switch",
        "type": "switch-device",
        "name": "Flurlicht an",
        "position": { "x": 360, "y": 120 },
        "config": { "deviceId": 1, "action": "on" }
      }
    ],
    "wires": [
      { "from": { "node": "trigger", "port": 0 }, "to": { "node": "switch" } }
    ]
  }
}
```

- [ ] **Step 2: Create example resource 2**

Create `temperature-announce.json`:

```json
{
  "schemaVersion": 1,
  "name": "Warnung bei hoher Temperatur",
  "description": "Sagt eine Warnung an, wenn die Temperatur 25 Grad übersteigt",
  "definition": {
    "nodes": [
      {
        "id": "trigger",
        "type": "entity-state-trigger",
        "name": "Temperatur",
        "position": { "x": 80, "y": 120 },
        "config": { "entityId": "sensor.wohnzimmer_temperatur", "operator": ">", "value": "25" }
      },
      {
        "id": "limit",
        "type": "rate-limit",
        "name": "Höchstens alle 10 Minuten",
        "position": { "x": 340, "y": 120 },
        "config": { "minIntervalSeconds": 600 }
      },
      {
        "id": "say",
        "type": "alexa-announce",
        "name": "Ansage",
        "position": { "x": 620, "y": 120 },
        "config": {
          "text": "Achtung, {entityId} liegt bei {newState} Grad.",
          "mode": "ANNOUNCE",
          "deviceSerials": ["G0000000000000000"]
        }
      }
    ],
    "wires": [
      { "from": { "node": "trigger", "port": 0 }, "to": { "node": "limit" } },
      { "from": { "node": "limit", "port": 0 }, "to": { "node": "say" } }
    ]
  }
}
```

- [ ] **Step 3: Write the failing guard test**

Create `FlowImportExampleTest.java`:

```java
package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.nodes.AlexaAnnounceNodeHandler;
import com.household.manager.flowengine.nodes.DebugNodeHandler;
import com.household.manager.flowengine.nodes.DelayNodeHandler;
import com.household.manager.flowengine.nodes.EntityConditionHandler;
import com.household.manager.flowengine.nodes.EntityStateTriggerHandler;
import com.household.manager.flowengine.nodes.RateLimitNodeHandler;
import com.household.manager.flowengine.nodes.ScheduleTriggerHandler;
import com.household.manager.flowengine.nodes.SwitchDeviceNodeHandler;
import com.household.manager.service.AlexaAnnouncementService;
import com.household.manager.service.SmartDeviceService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hält die in docs/flows/flow-import-format.md dokumentierten Beispiel-Flows valide.
 * Die Ressourcen unter flow-examples/ sind Kopien der Doku-Beispiele — beide synchron halten.
 */
class FlowImportExampleTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FlowDefinitionParser parser = new FlowDefinitionParser(mapper);
    private final FlowValidator validator = buildValidator();

    private FlowValidator buildValidator() {
        EntityStateService entityStateService = mock(EntityStateService.class);
        when(entityStateService.getByEntityId(anyString())).thenReturn(Optional.empty());
        List<NodeHandler> handlers = List.of(
                new EntityStateTriggerHandler(entityStateService),
                new ScheduleTriggerHandler(),
                new EntityConditionHandler(entityStateService),
                new DelayNodeHandler(),
                new RateLimitNodeHandler(),
                new DebugNodeHandler(),
                new AlexaAnnounceNodeHandler(mock(AlexaAnnouncementService.class)),
                new SwitchDeviceNodeHandler(mock(SmartDeviceService.class)));
        return new FlowValidator(handlers, entityStateService);
    }

    @Test
    void motionLightExampleIsValid() throws Exception {
        assertExampleValid("motion-light.json");
    }

    @Test
    void temperatureAnnounceExampleIsValid() throws Exception {
        assertExampleValid("temperature-announce.json");
    }

    private void assertExampleValid(String file) throws Exception {
        JsonNode wrapper;
        try (InputStream in = getClass().getResourceAsStream("/flow-examples/" + file)) {
            assertNotNull(in, "Beispiel-Datei fehlt: " + file);
            wrapper = mapper.readTree(in);
        }
        assertEquals(1, wrapper.get("schemaVersion").asInt());
        FlowDefinition definition = parser.parse(wrapper.get("definition").toString());
        ValidationResult result = validator.validate(definition);
        assertTrue(result.valid(), "Beispiel " + file + " hat Fehler: " + result.errors());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q -Dtest=FlowImportExampleTest test`
Expected: PASS (beide Beispiele valide; nur Warnungen wegen unbekannter Entitäten/Geräte).

Falls FAIL: die Fehlerliste zeigt das falsche Config-Feld — Beispiel korrigieren, bis `valid()`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/resources/flow-examples/ backend/src/test/java/com/household/manager/flowengine/FlowImportExampleTest.java
git commit -m "test(flows): guard that documented import examples stay valid"
```

---

## Task 4: Frontend — `FlowService.importFlow`

**Files:**
- Modify: `frontend/src/app/services/flow.service.ts`
- Test: `frontend/src/app/services/flow.service.spec.ts`

- [ ] **Step 1: Write the failing test**

In `flow.service.spec.ts`, füge diesen Test vor der schließenden `});` der `describe`-Gruppe ein:

```ts
  it('imports a flow via POST /import', () => {
    const payload = { schemaVersion: 1, name: 'X', description: '', definition: { nodes: [], wires: [] } };
    service.importFlow(payload).subscribe();
    const req = httpMock.expectOne('/api/v1/flows/import');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payload);
    req.flush({ id: 9, name: 'X', enabled: false, deployed: false });
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: FAIL — „service.importFlow is not a function".

- [ ] **Step 3: Add the service method**

In `flow.service.ts`, füge nach `createFlow(...)` (nach Zeile 29) ein:

```ts
  /** Importiert eine extern erzeugte Flow-Wrapper-Datei; legt einen deaktivierten Draft an. */
  importFlow(payload: unknown): Observable<FlowDetail> {
    return this.http.post<FlowDetail>(`${this.baseUrl}/import`, payload);
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/flow.service.ts frontend/src/app/services/flow.service.spec.ts
git commit -m "feat(flows): FlowService.importFlow posts wrapper file to /import"
```

---

## Task 5: Frontend — Import-Button in der Flow-Liste

**Files:**
- Modify: `frontend/src/app/pages/flows/flow-list.component.ts`
- Modify: `frontend/src/app/pages/flows/flow-list.component.html`
- Modify: `frontend/src/app/pages/flows/flow-list.component.scss`
- Test: `frontend/src/app/pages/flows/flow-list.component.spec.ts`

- [ ] **Step 1: Write the failing tests**

Ersetze in `flow-list.component.spec.ts` die `createSpyObj`-Zeile (Zeile 11), sodass `importFlow` enthalten ist:

```ts
    flowService = jasmine.createSpyObj('FlowService', ['getFlows', 'createFlow', 'deleteFlow', 'setEnabled', 'importFlow']);
```

Ergänze `Router` in der bestehenden `@angular/router`-Importzeile (Zeile 2), sodass sie lautet:

```ts
import { provideRouter, Router } from '@angular/router';
```

Füge diese zwei Tests vor der schließenden `});` der `describe`-Gruppe ein:

```ts
  it('imports a flow from text and navigates to it', () => {
    flowService.importFlow.and.returnValue(of({ id: 5, name: 'X', enabled: false, deployed: false } as any));
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    fixture.componentInstance['importFromText']('{"schemaVersion":1,"name":"X","definition":{"nodes":[],"wires":[]}}');

    expect(flowService.importFlow).toHaveBeenCalledWith({
      schemaVersion: 1, name: 'X', definition: { nodes: [], wires: [] }
    });
    expect(navigate).toHaveBeenCalledWith(['/flows', 5]);
  });

  it('shows an error and does not post when the file is not valid JSON', () => {
    const fixture = TestBed.createComponent(FlowListComponent);
    fixture.detectChanges();

    fixture.componentInstance['importFromText']('{ not json');

    expect(flowService.importFlow).not.toHaveBeenCalled();
    expect(fixture.componentInstance.error()).toBeTruthy();
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: FAIL — „importFromText is not a function".

- [ ] **Step 3: Implement the handler**

In `flow-list.component.ts`, füge diese zwei Methoden nach `createFlow()` (nach Zeile 41) ein:

```ts
  onImportFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // erlaubt erneuten Upload derselben Datei
    if (!file) { return; }
    file.text().then(text => this.importFromText(text));
  }

  private importFromText(text: string): void {
    let payload: unknown;
    try {
      payload = JSON.parse(text);
    } catch {
      this.error.set('Die Datei enthält kein gültiges JSON.');
      return;
    }
    this.flowService.importFlow(payload).subscribe({
      next: flow => this.router.navigate(['/flows', flow.id]),
      error: err => this.error.set(err.error?.message ?? 'Import fehlgeschlagen.')
    });
  }
```

- [ ] **Step 4: Add the button and hidden input**

In `flow-list.component.html`, ersetze den `__header`-Block (Zeilen 2–5) durch:

```html
  <div class="flow-list-page__header">
    <h1>Automatisierungen</h1>
    <div class="flow-list-page__actions">
      <button class="flow-list-page__import" (click)="importFile.click()">Importieren</button>
      <button class="flow-list-page__new" (click)="createFlow()">+ Neuer Flow</button>
    </div>
  </div>
  <input #importFile type="file" accept=".json,application/json" hidden
         (change)="onImportFileSelected($event)" />
```

- [ ] **Step 5: Add minimal styling**

In `flow-list.component.scss`, füge am Ende ein:

```scss
.flow-list-page__actions {
  display: flex;
  gap: 0.5rem;
}

.flow-list-page__import {
  background: transparent;
  border: 1px solid var(--color-border, #ccc);
  border-radius: 4px;
  padding: 0.4rem 0.9rem;
  cursor: pointer;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`
Expected: PASS (gesamte Suite grün).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/flows/flow-list.component.ts frontend/src/app/pages/flows/flow-list.component.html frontend/src/app/pages/flows/flow-list.component.scss frontend/src/app/pages/flows/flow-list.component.spec.ts
git commit -m "feat(flows): add file import button to flow list"
```

---

## Task 6: Authoring-Referenz

Dokumentiert Wrapper-Format, alle 8 Node-Typen mit ihren `config`-Feldern und zwei vollständige Beispiele. Die Beispiel-JSONs sind identisch zu den Guard-Ressourcen aus Task 3 — bei Änderungen beide anpassen.

**Files:**
- Create: `docs/flows/flow-import-format.md`

- [ ] **Step 1: Write the reference document**

Create `docs/flows/flow-import-format.md` mit exakt diesem Inhalt:

````markdown
# Flow-Import-Format

Extern erzeugte Automatisierungs-Flows werden als **JSON-Datei** über den
„Importieren"-Button in der Flow-Übersicht geladen. Der Import legt einen neuen,
**deaktivierten** Flow als Entwurf (Draft) an; scharf wird er erst durch den
manuellen Deploy im Editor.

## Wrapper-Format

```json
{
  "schemaVersion": 1,
  "name": "Anzeigename des Flows",
  "description": "Optionaler Freitext",
  "definition": {
    "nodes": [ /* siehe unten */ ],
    "wires": [ /* siehe unten */ ]
  }
}
```

| Feld | Pflicht | Bedeutung |
|------|---------|-----------|
| `schemaVersion` | ja | Muss aktuell `1` sein. |
| `name` | ja | Anzeigename (nicht leer). |
| `description` | nein | Freitextbeschreibung. |
| `definition` | ja | Der Graph aus `nodes` und `wires`. |

Beim Import wird nur geprüft, ob die `definition` parsebares JSON ist. Die volle
Prüfung (bekannte Node-Typen, Pflichtfelder, Wire-Ziele) passiert erst beim Deploy —
ein importierter Draft darf also noch unvollständig sein.

## Node

```json
{
  "id": "eindeutige-id",
  "type": "node-typ",
  "name": "optionales Label",
  "position": { "x": 80, "y": 120 },
  "config": { }
}
```

- `id`: innerhalb des Flows eindeutig; von Wires referenziert.
- `type`: einer der Typen unten.
- `position`: Editor-Koordinaten (rein visuell; Standard 0/0).
- `config`: typ-abhängige Felder (siehe unten).

## Wire

```json
{ "from": { "node": "quell-id", "port": 0 }, "to": { "node": "ziel-id" } }
```

- `from.node` / `to.node`: Node-`id`s.
- `from.port`: Ausgangsport der Quell-Node (0-basiert). Die meisten Nodes haben nur
  Port 0; die Bedingung hat Port 0 (wahr) und Port 1 (falsch).

## Node-Typen

### `entity-state-trigger` — Entity-Trigger (Trigger, 1 Ausgang)
Feuert bei Zustandsübergang einer Entität IN den passenden Bereich (flankengetriggert).

| config | Pflicht | Wert |
|--------|---------|------|
| `entityId` | ja | Entity-ID, z. B. `binary_sensor.flur_bewegung` |
| `operator` | ja | einer von `<`, `<=`, `>`, `>=`, `==`, `!=`, `changed` |
| `value` | ja*, außer bei `changed` | Vergleichswert als String, z. B. `"on"`, `"25"` |
| `forSeconds` | nein | Zahl; feuert erst, wenn die Bedingung so viele Sekunden ununterbrochen gilt |

### `schedule-trigger` — Zeitplan (Trigger, 1 Ausgang)

| config | Pflicht | Wert |
|--------|---------|------|
| `cron` | ja | Spring-Cron mit 6 Feldern: `Sek Min Std Tag Monat Wochentag`, z. B. `0 0 7 * * *` (täglich 07:00) |

### `entity-condition` — Bedingung (2 Ausgänge: 0 = wahr, 1 = falsch)
Prüft den AKTUELLEN Zustand einer beliebigen Entität.

| config | Pflicht | Wert |
|--------|---------|------|
| `entityId` | ja | Entity-ID |
| `operator` | ja | einer von `<`, `<=`, `>`, `>=`, `==`, `!=` |
| `value` | ja | Vergleichswert als String |

### `delay` — Verzögerung (1 Ausgang)

| config | Pflicht | Wert |
|--------|---------|------|
| `seconds` | ja | Zahl > 0; Sekunden bis zur Weiterleitung |

### `rate-limit` — Drossel (1 Ausgang)
Lässt höchstens eine Message pro Intervall durch.

| config | Pflicht | Wert |
|--------|---------|------|
| `minIntervalSeconds` | ja | Zahl > 0; Mindestabstand in Sekunden |

### `debug` — Debug (0 Ausgänge)
Schreibt jede Message in den Debug-Puffer.

| config | Pflicht | Wert |
|--------|---------|------|
| `label` | nein | Beschriftung im Debug-Panel |

### `alexa-announce` — Alexa-Ansage (1 Ausgang)
Platzhalter im Text: `{entityId}`, `{newState}`, `{oldState}`.

| config | Pflicht | Wert |
|--------|---------|------|
| `text` | ja | Ansagetext (mit optionalen Platzhaltern) |
| `mode` | ja | `SPEAK` (ein Gerät, ohne Gong) oder `ANNOUNCE` (mit Gong) |
| `deviceSerials` | ja | nicht-leeres Array von Alexa-Seriennummern, z. B. `["G0000000000000000"]` |

### `switch-device` — Gerät schalten (1 Ausgang)

| config | Pflicht | Wert |
|--------|---------|------|
| `deviceId` | ja | numerische SmartDevice-ID (Kasa/Tapo/Meross) |
| `action` | ja | `on` oder `off` |

> `entityId`, `deviceId` und `deviceSerials` müssen zu deiner Umgebung passen. Sind sie
> unbekannt, meldet der Deploy eine **Warnung** (kein Fehler) — der Flow greift, sobald
> die Entität/das Gerät existiert.

## Beispiel 1 — Flurlicht bei Bewegung

```json
{
  "schemaVersion": 1,
  "name": "Flurlicht bei Bewegung",
  "description": "Schaltet Gerät 1 ein, wenn der Bewegungsmelder auslöst",
  "definition": {
    "nodes": [
      {
        "id": "trigger",
        "type": "entity-state-trigger",
        "name": "Bewegung erkannt",
        "position": { "x": 80, "y": 120 },
        "config": { "entityId": "binary_sensor.flur_bewegung", "operator": "==", "value": "on" }
      },
      {
        "id": "switch",
        "type": "switch-device",
        "name": "Flurlicht an",
        "position": { "x": 360, "y": 120 },
        "config": { "deviceId": 1, "action": "on" }
      }
    ],
    "wires": [
      { "from": { "node": "trigger", "port": 0 }, "to": { "node": "switch" } }
    ]
  }
}
```

## Beispiel 2 — Warnung bei hoher Temperatur

```json
{
  "schemaVersion": 1,
  "name": "Warnung bei hoher Temperatur",
  "description": "Sagt eine Warnung an, wenn die Temperatur 25 Grad übersteigt",
  "definition": {
    "nodes": [
      {
        "id": "trigger",
        "type": "entity-state-trigger",
        "name": "Temperatur",
        "position": { "x": 80, "y": 120 },
        "config": { "entityId": "sensor.wohnzimmer_temperatur", "operator": ">", "value": "25" }
      },
      {
        "id": "limit",
        "type": "rate-limit",
        "name": "Höchstens alle 10 Minuten",
        "position": { "x": 340, "y": 120 },
        "config": { "minIntervalSeconds": 600 }
      },
      {
        "id": "say",
        "type": "alexa-announce",
        "name": "Ansage",
        "position": { "x": 620, "y": 120 },
        "config": {
          "text": "Achtung, {entityId} liegt bei {newState} Grad.",
          "mode": "ANNOUNCE",
          "deviceSerials": ["G0000000000000000"]
        }
      }
    ],
    "wires": [
      { "from": { "node": "trigger", "port": 0 }, "to": { "node": "limit" } },
      { "from": { "node": "limit", "port": 0 }, "to": { "node": "say" } }
    ]
  }
}
```
````

- [ ] **Step 2: Verify the examples match the guard resources**

Manueller Abgleich (kein Tooling): Die JSON-Blöcke „Beispiel 1" und „Beispiel 2" in der
Referenz müssen inhaltlich identisch zu `backend/src/test/resources/flow-examples/motion-light.json`
bzw. `temperature-announce.json` (Task 3) sein. Bei Abweichung die Referenz anpassen, damit der
Guard-Test weiterhin genau das dokumentierte Beispiel absichert.

- [ ] **Step 3: Commit**

```bash
git add docs/flows/flow-import-format.md
git commit -m "docs(flows): authoring reference for the flow import format"
```

---

## Manuelle End-to-End-Verifikation (nach allen Tasks)

1. Backend starten (`JAVA_HOME` = JDK 21): `cd backend && mvn spring-boot:run`
2. Frontend starten: `cd frontend && npm start`
3. Beispiel 1 aus der Referenz in eine Datei `flurlicht.json` kopieren.
4. In der Flow-Übersicht „Importieren" klicken, `flurlicht.json` wählen.
5. Erwartet: Weiterleitung in den Editor des neuen Flows; Flow erscheint in der Liste
   als **Entwurf** und **aus** (deaktiviert). Nodes/Wires korrekt gerendert.
6. Kaputte Datei (`{ not json`) importieren → Fehlermeldung, kein neuer Flow.
```
