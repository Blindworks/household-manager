# Flow-Bereiche Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jeder Flow bekommt einen optionalen Bereich (Freitext); `/flows` zeigt die Flows in auf-/zuklappbaren Abschnitten je Bereich mit Suchfeld.

**Architecture:** Neue nullable Spalte `flows.category` (Liquibase), durchgereicht durch `FlowService` (Normalisierung trim/leer⇒NULL, Teil-Update-Semantik), DTOs und Controller. Der MCP-Server bekommt den Parameter `category`. Im Frontend gruppiert eine reine Funktion (`flow-grouping.util.ts`), der Klappzustand liegt in `localStorage` (gekapselt in `flow-section-storage.util.ts`), der Editor bekommt ein Bereichsfeld mit `<datalist>`.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / JUnit 5 + Mockito + MockMvc; Angular 19 standalone + Karma/Jasmine; Node ≥20 MCP-Server mit zod + `node:test`.

**Spec:** `docs/superpowers/specs/2026-09-25-flow-bereiche-design.md`

**Arbeitsverzeichnis:** Worktree `C:\Users\bened\IdeaProjects\Household-Manager-flow-categories`, Branch `feature/flow-categories`. **Nicht** im Hauptordner arbeiten — dort läuft eine andere Session auf `feature/dashboard-theme`. Vor jedem Commit `git branch --show-current` prüfen (muss `feature/flow-categories` sein).

**Umgebung:**
- Backend: vor jedem Maven-Aufruf `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`, dann aus `backend/`. `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern lokal immer (keine DB) — ignorieren.
- Frontend: im Worktree einmalig `cd frontend && npm ci`. Baseline im Gesamtlauf: 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent`), gelegentlich ein `SmartDeviceListComponent`-Flake.
- MCP-Server: im Worktree einmalig `cd flow-mcp-server && npm ci`.

---

## Dateiübersicht

| Datei | Aktion | Verantwortung |
|---|---|---|
| `backend/src/main/resources/db/changelog/changes/20260925-0057-add-flow-category.xml` | Create | Spalte `category` |
| `backend/src/main/resources/db/changelog/db.changelog-master.xml` | Modify | Include |
| `backend/.../model/entity/Flow.java` | Modify | Feld `category` |
| `backend/.../dto/FlowFieldLimits.java` | Modify | `CATEGORY_MAX = 60` |
| `backend/.../dto/CreateFlowRequest.java`, `UpdateFlowRequest.java`, `ImportFlowRequest.java` | Modify | Feld + `@Size` |
| `backend/.../dto/FlowSummaryResponse.java`, `FlowDetailResponse.java` | Modify | Feld |
| `backend/.../flowengine/FlowService.java` | Modify | Parameter + `normalizeCategory` |
| `backend/.../controller/FlowController.java` | Modify | Durchreichen + Mapping |
| `backend/src/test/.../flowengine/FlowServiceTest.java` | Modify | Normalisierung/Teil-Update |
| `backend/src/test/.../controller/FlowControllerTest.java` | Modify | Mapping + 400 |
| `flow-mcp-server/src/tools.js`, `flow-mcp-server/test/tools.test.js` | Modify | Parameter + Beschreibungen |
| `frontend/src/app/models/flow.model.ts` | Modify | `category` |
| `frontend/src/app/services/flow.service.ts` (+ `.spec.ts`) | Modify | `saveDraft` mit Bereich |
| `frontend/src/app/pages/flows/flow-grouping.util.ts` (+ `.spec.ts`) | Create | Gruppierung, Suche, Bereichsliste |
| `frontend/src/app/pages/flows/flow-section-storage.util.ts` (+ `.spec.ts`) | Create | Klappzustand in `localStorage` |
| `frontend/src/app/pages/flows/flow-list.component.{ts,html,scss,spec.ts}` | Modify | Abschnitte, Suche |
| `frontend/src/app/pages/flows/flow-editor.component.{ts,html,scss,spec.ts}` | Modify | Bereichsfeld, Änderungserkennung |
| `CLAUDE.md` | Modify | Abschnitt „Flow-Engine: Bereiche“ |

(`backend/...` = `backend/src/main/java/com/household/manager`, `backend/src/test/...` = `backend/src/test/java/com/household/manager`)

---

### Task 1: Schema und Entity

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260925-0057-add-flow-category.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (nach dem Include von `20260924-0056`)
- Modify: `backend/src/main/java/com/household/manager/model/entity/Flow.java`
- Modify: `backend/src/main/java/com/household/manager/dto/FlowFieldLimits.java`

- [ ] **Step 1: Changeset anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260925-0057-add-flow-category" author="claude">
        <comment>
            Bereich eines Flows (Freitext, z. B. "Licht &amp; Bewegung") fuer die Gliederung der Flow-Uebersicht.
            NULL = ohne Bereich, angezeigt als "Sonstiges".
        </comment>
        <addColumn tableName="flows">
            <column name="category" type="VARCHAR(60)"/>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Im Master-Changelog einhängen**

Direkt nach der Zeile `<include file="db/changelog/changes/20260924-0056-add-flow-last-triggered-at.xml"/>` einfügen:

```xml

    <!-- Flow-Uebersicht: Gliederung nach Bereichen -->
    <include file="db/changelog/changes/20260925-0057-add-flow-category.xml"/>
```

- [ ] **Step 3: Limit ergänzen** — in `FlowFieldLimits.java` nach `DESCRIPTION_MAX`:

```java
    public static final int CATEGORY_MAX = 60;
```

Den Javadoc-Satz „gespiegelt aus den Spalten der Tabelle {@code flows} (Liquibase {@code 20260709-0030})" ergänzen zu „(Liquibase {@code 20260709-0030}, {@code category} aus {@code 20260925-0057})".

- [ ] **Step 4: Entity-Feld** — in `Flow.java` direkt nach dem Feld `description`:

```java
    /** Bereich für die Gliederung der Übersicht (Freitext); NULL = ohne Bereich („Sonstiges"). */
    @Column(name = "category", length = 60)
    private String category;
```

- [ ] **Step 5: Kompilieren**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile`
Expected: kein Fehler.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/Flow.java backend/src/main/java/com/household/manager/dto/FlowFieldLimits.java
git commit -m "feat(flows): Spalte category fuer Flow-Bereiche"
```

---

### Task 2: FlowService — Bereich speichern und normalisieren

**Files:**
- Modify: `backend/src/main/java/com/household/manager/flowengine/FlowService.java`
- Modify: `backend/src/main/java/com/household/manager/controller/FlowController.java` (nur Aufrufe anpassen, damit es kompiliert)
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/controller/FlowControllerTest.java` (nur Stubs an neue Signaturen anpassen)

- [ ] **Step 1: Failing Tests schreiben** — in `FlowServiceTest` am Ende der Klasse ergänzen:

```java
    @Test
    void createTrimsCategory() {
        Flow saved = service.create("n", "", "  Licht  ");

        assertEquals("Licht", saved.getCategory());
    }

    @Test
    void createStoresBlankCategoryAsNull() {
        Flow saved = service.create("n", "", "   ");

        assertNull(saved.getCategory());
    }

    @Test
    void updateKeepsCategoryWhenNotGiven() {
        Flow entity = flow(1L, VALID_DEF, null, true);
        entity.setCategory("Licht");
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        service.update(1L, null, "neue Beschreibung", null, null);

        assertEquals("Licht", entity.getCategory());
        assertEquals("neue Beschreibung", entity.getDescription());
    }

    @Test
    void updateClearsCategoryWhenBlank() {
        Flow entity = flow(1L, VALID_DEF, null, true);
        entity.setCategory("Licht");
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        service.update(1L, null, null, "  ", null);

        assertNull(entity.getCategory());
    }

    @Test
    void updateSetsTrimmedCategory() {
        Flow entity = flow(1L, VALID_DEF, null, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        service.update(1L, null, null, " Taster ", null);

        assertEquals("Taster", entity.getCategory());
    }

    @Test
    void importStoresTrimmedCategory() {
        Flow saved = service.importFlow(1, "Imported", "desc", " Taster ", VALID_DEF);

        assertEquals("Taster", saved.getCategory());
    }
```

Bestehende Aufrufe in `FlowServiceTest` (Zeilen ~142–170) um den neuen vierten Parameter `null` ergänzen, z. B.:
`service.importFlow(1, "Imported", "desc", VALID_DEF)` → `service.importFlow(1, "Imported", "desc", null, VALID_DEF)`
`service.importFlow(2, "n", "", VALID_DEF)` → `service.importFlow(2, "n", "", null, VALID_DEF)` — ebenso für alle anderen `importFlow`-Aufrufe der Datei.

- [ ] **Step 2: Test laufen lassen, Fehlschlag prüfen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowServiceTest`
Expected: Kompilierfehler (`create(String,String,String)` / `update(...5 args)` / `importFlow(...5 args)` existieren nicht).

- [ ] **Step 3: FlowService implementieren** — die drei Methoden ersetzen und die Hilfsmethode ergänzen:

```java
    @Transactional
    public Flow create(String name, String description, String category) {
        Flow flow = Flow.builder().name(name).description(description).category(normalizeCategory(category))
                .enabled(true).draftDefinition("{ \"nodes\": [], \"wires\": [] }").build();
        Flow saved = flowRepository.save(flow);
        auditService.record("flow.create", name);
        return saved;
    }

    /**
     * Teil-Update: {@code null} lässt ein Feld unverändert. Beim Bereich entfernt ein leerer
     * Text den Bereich — nur so lässt er sich wieder löschen, ohne dass eine reine
     * Beschreibungskorrektur (ohne {@code category}) ihn still mitlöscht.
     */
    @Transactional
    public Flow update(Long id, String name, String description, String category, String draftDefinition) {
        Flow flow = require(id);
        if (name != null) {
            flow.setName(name);
        }
        if (description != null) {
            flow.setDescription(description);
        }
        if (category != null) {
            flow.setCategory(normalizeCategory(category));
        }
        if (draftDefinition != null) {
            parser.parse(draftDefinition); // wirft IllegalArgumentException bei kaputtem JSON -> 400
            flow.setDraftDefinition(draftDefinition);
        }
        Flow saved = flowRepository.save(flow);
        auditService.record("flow.update", "Flow " + id);
        return saved;
    }
```

`importFlow`: Signatur zu `importFlow(Integer schemaVersion, String name, String description, String category, String definitionJson)` ändern und im Builder `.category(normalizeCategory(category))` nach `.description(description)` ergänzen. Rest unverändert.

Am Ende der Klasse (vor der schließenden Klammer, neben `require`):

```java
    /** Rand-Leerzeichen weg, leerer Text = ohne Bereich. Einzige Stelle dieser Regel. */
    private static String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String trimmed = category.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
```

- [ ] **Step 4: Controller-Aufrufe nachziehen (nur Kompilierbarkeit, Request-Felder folgen in Task 3)** — in `FlowController`:

```java
        return toDetail(flowService.create(request.name(), request.description(), null));
```
```java
        return toDetail(flowService.importFlow(
                request.schemaVersion(), request.name(), request.description(), null, definitionJson));
```
```java
        return toDetail(flowService.update(id, request.name(), request.description(), null, request.draftDefinition()));
```

In `FlowControllerTest` die Stubs anpassen:
- `when(flowService.create("Neu", "Desc"))` → `when(flowService.create("Neu", "Desc", null))`
- `when(flowService.create("Neu", description))` → `when(flowService.create("Neu", description, null))`
- `when(flowService.importFlow(eq(1), eq("Imported"), eq("desc"), any()))` → `when(flowService.importFlow(eq(1), eq("Imported"), eq("desc"), isNull(), any()))`
- `verify(flowService).importFlow(eq(1), eq("Imported"), eq("desc"), eq("{\"nodes\":[],\"wires\":[]}"))` → `verify(flowService).importFlow(eq(1), eq("Imported"), eq("desc"), isNull(), eq("{\"nodes\":[],\"wires\":[]}"))`
- Import ergänzen: `import static org.mockito.ArgumentMatchers.isNull;`

- [ ] **Step 5: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="FlowServiceTest,FlowControllerTest"`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(flows): FlowService speichert und normalisiert den Bereich"
```

---

### Task 3: API — DTOs und Controller

**Files:**
- Modify: `backend/src/main/java/com/household/manager/dto/CreateFlowRequest.java`, `UpdateFlowRequest.java`, `ImportFlowRequest.java`, `FlowSummaryResponse.java`, `FlowDetailResponse.java`
- Modify: `backend/src/main/java/com/household/manager/controller/FlowController.java`
- Test: `backend/src/test/java/com/household/manager/controller/FlowControllerTest.java`

- [ ] **Step 1: Failing Tests** — in `FlowControllerTest` ergänzen:

```java
    @Test
    void listIncludesCategory() throws Exception {
        Flow flow = flow();
        flow.setCategory("Licht");
        when(flowService.getAll()).thenReturn(List.of(flow));

        mockMvc.perform(get("/v1/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("Licht"));
    }

    @Test
    void detailIncludesCategory() throws Exception {
        Flow flow = flow();
        flow.setCategory("Taster");
        when(flowService.getById(1L)).thenReturn(Optional.of(flow));

        mockMvc.perform(get("/v1/flows/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Taster"));
    }

    @Test
    void passesCategoryOnCreate() throws Exception {
        when(flowService.create("Neu", "Desc", "Licht")).thenReturn(flow());

        mockMvc.perform(post("/v1/flows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neu\",\"description\":\"Desc\",\"category\":\"Licht\"}"))
                .andExpect(status().isOk());

        verify(flowService).create("Neu", "Desc", "Licht");
    }

    @Test
    void passesCategoryOnUpdate() throws Exception {
        when(flowService.update(1L, null, null, "Taster", null)).thenReturn(flow());

        mockMvc.perform(put("/v1/flows/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"Taster\"}"))
                .andExpect(status().isOk());

        verify(flowService).update(1L, null, null, "Taster", null);
    }

    @Test
    void passesCategoryOnImport() throws Exception {
        when(flowService.importFlow(eq(1), eq("Imported"), eq("desc"), eq("Taster"), any())).thenReturn(flow());

        mockMvc.perform(post("/v1/flows/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"name\":\"Imported\",\"description\":\"desc\","
                                + "\"category\":\"Taster\",\"definition\":{\"nodes\":[],\"wires\":[]}}"))
                .andExpect(status().isOk());

        verify(flowService).importFlow(eq(1), eq("Imported"), eq("desc"), eq("Taster"), any());
    }

    @Test
    void acceptsCategoryOfExactlyMaxLengthOnCreate() throws Exception {
        String category = "c".repeat(60);
        when(flowService.create("Neu", null, category)).thenReturn(flow());

        mockMvc.perform(post("/v1/flows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neu\",\"category\":\"" + category + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsTooLongCategoryOnCreateWith400() throws Exception {
        mockMvc.perform(post("/v1/flows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neu\",\"category\":\"" + "c".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.category").exists());

        verifyNoInteractions(flowService);
    }

    @Test
    void rejectsTooLongCategoryOnUpdateWith400() throws Exception {
        mockMvc.perform(put("/v1/flows/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"" + "c".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.category").exists());

        verifyNoInteractions(flowService);
    }

    @Test
    void rejectsTooLongCategoryOnImportWith400() throws Exception {
        mockMvc.perform(post("/v1/flows/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"name\":\"Neu\",\"category\":\"" + "c".repeat(61)
                                + "\",\"definition\":{\"nodes\":[],\"wires\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.category").exists());

        verifyNoInteractions(flowService);
    }
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowControllerTest`
Expected: FAIL (u. a. `$[0].category` fehlt, `validationErrors.category` fehlt).

- [ ] **Step 3: DTOs** — jeweils ergänzen:

`CreateFlowRequest` — nach `description`:
```java
        @Size(max = FlowFieldLimits.CATEGORY_MAX,
                message = "Bereich darf höchstens " + FlowFieldLimits.CATEGORY_MAX + " Zeichen lang sein")
        String category
```
(Komma nach dem `description`-Parameter nicht vergessen.)

`UpdateFlowRequest` — zwischen `description` und `draftDefinition` denselben Parameter `category` einfügen. Über dem Record einen Javadoc ergänzen:
```java
/**
 * Teil-Update: fehlende Felder bleiben unverändert. {@code category: ""} entfernt den Bereich.
 */
```

`ImportFlowRequest` — zwischen `description` und `definition` denselben Parameter `category` einfügen.

`FlowSummaryResponse`:
```java
        Long id, String name, String description, String category, boolean enabled, boolean deployed,
        LocalDateTime deployedAt, LocalDateTime updatedAt, LocalDateTime lastTriggeredAt) {
```

`FlowDetailResponse`:
```java
        Long id, String name, String description, String category, boolean enabled, boolean deployed,
```

- [ ] **Step 4: Controller**

```java
        return toDetail(flowService.create(request.name(), request.description(), request.category()));
```
```java
        return toDetail(flowService.importFlow(
                request.schemaVersion(), request.name(), request.description(), request.category(), definitionJson));
```
```java
        return toDetail(flowService.update(
                id, request.name(), request.description(), request.category(), request.draftDefinition()));
```
In `toSummary` und `toDetail` jeweils nach `.description(flow.getDescription())` ergänzen: `.category(flow.getCategory())`.

Die in Task 2 angepassten Stubs (`create("Neu", "Desc", null)`, `create("Neu", description, null)`, `isNull()` beim Import) bleiben unverändert richtig: diese Requests enthalten kein `category`, der Controller reicht also `null` durch.

- [ ] **Step 5: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="FlowServiceTest,FlowControllerTest"`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(flows): Bereich in der Flow-API (anlegen, aendern, importieren, lesen)"
```

---

### Task 4: MCP-Server

**Files:**
- Modify: `flow-mcp-server/src/tools.js`
- Test: `flow-mcp-server/test/tools.test.js`

- [ ] **Step 1: Failing Tests** — in `tools.test.js` nach dem Test `flow_update serialisiert …` ergänzen:

```js
test('flow_create schickt den Bereich mit', async () => {
  route('POST', '/v1/flows/import', 200, { id: 9, name: 'Neu', draftDefinition: '{"nodes":[],"wires":[]}' });
  await tools.flow_create.handler(client, {
    name: 'Neu', category: 'Licht & Bewegung', definition: { nodes: [], wires: [] },
  });

  assert.equal(requests[0].body.category, 'Licht & Bewegung');
});

test('flow_update ohne category lässt den Bereich weg (unverändert)', async () => {
  route('PUT', '/v1/flows/3', 200, { id: 3, name: 'X', draftDefinition: '{"nodes":[],"wires":[]}' });
  await tools.flow_update.handler(client, { id: 3, description: 'neu' });

  assert.equal('category' in requests[0].body, false);
});

test('flow_update mit leerem category entfernt den Bereich', async () => {
  route('PUT', '/v1/flows/3', 200, { id: 3, name: 'X', draftDefinition: '{"nodes":[],"wires":[]}' });
  await tools.flow_update.handler(client, { id: 3, category: '' });

  assert.equal(requests[0].body.category, '');
});
```

Und in der bestehenden `for (const toolName of ['flow_create', 'flow_update'])`-Schleife ergänzen:

```js
  test(`${toolName}: Bereich über 60 Zeichen wird vom Schema abgelehnt`, async () => {
    const { z } = await import('zod');
    const schema = z.object(tools[toolName].inputSchema).partial();
    assert.equal(schema.safeParse({ category: 'c'.repeat(60) }).success, true);
    assert.equal(schema.safeParse({ category: 'c'.repeat(61) }).success, false);
  });
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `cd flow-mcp-server && npm test`
Expected: FAIL in den neuen Tests (`category` wird nicht übertragen / Schema kennt das Feld nicht — `partial()` lässt unbekannte Keys durch, daher schlägt der 61-Zeichen-Fall fehl).

- [ ] **Step 3: Implementieren** — in `tools.js`:

Konstanten ergänzen (nach `DESCRIPTION_MAX`), Kommentar darüber um `category VARCHAR(60)` erweitern:
```js
const CATEGORY_MAX = 60;

const CATEGORY_HINT =
  'Bereich gliedert die Flow-Übersicht (z. B. "Modi & Szenen", "Licht & Bewegung", "Taster", ' +
  '"Sicherheit & Warnungen", "Haushaltsgeräte", "Erinnerungen", "System & Diagnose"). ' +
  'Vorhandene Bereiche aus flow_list wiederverwenden (exakt gleiche Schreibweise), nur bei echtem Bedarf einen neuen anlegen. ' +
  'Namensregel für Flows: was passiert, wann — ohne Bereich und ohne Nummer im Namen.';
```

`flow_list.description`:
```js
      'Listet alle Flows der Flow-Engine mit id, name, description, category (Bereich, null = ohne), ' +
      'enabled, deployed, deployedAt, updatedAt, lastTriggeredAt. Die vorhandenen category-Werte sind ' +
      'die Bereiche, die beim Anlegen/Ändern wiederverwendet werden sollen.',
```

`flow_create`: an die Beschreibung ` ' ' + CATEGORY_HINT` nach `DEFINITION_HINT` anhängen; im `inputSchema` nach `description`:
```js
      category: z
        .string()
        .max(CATEGORY_MAX)
        .optional()
        .describe(`Bereich des Flows (max. ${CATEGORY_MAX} Zeichen). ` + CATEGORY_HINT),
```
Handler: `async (client, { name, description, category, definition })` und im Body `category,` nach `description,`.

`flow_update`: Beschreibung zu
```js
      'Aktualisiert Name, Beschreibung, Bereich und/oder Draft-Definition eines Flows. Nur übergebene Felder werden ' +
      'geändert; category weglassen = Bereich bleibt, category "" = Bereich entfernen. Eine geänderte Definition ' +
      'landet im Draft und wird erst durch flow_deploy aktiv. ' + CATEGORY_HINT + ' ' +
      DEFINITION_HINT,
```
`inputSchema` nach `description`:
```js
      category: z
        .string()
        .max(CATEGORY_MAX)
        .optional()
        .describe(`Neuer Bereich (max. ${CATEGORY_MAX} Zeichen); "" entfernt ihn, weglassen lässt ihn unverändert`),
```
Handler: `async (client, { id, name, description, category, definition })` und im Body `category,` nach `description,` (undefined fällt beim `JSON.stringify` weg — genau das ist „unverändert").

- [ ] **Step 4: Tests grün**

Run: `cd flow-mcp-server && npm test`
Expected: alle Tests pass.

- [ ] **Step 5: README** — in `flow-mcp-server/README.md` beim Tool `flow_create`/`flow_update` einen Satz ergänzen: „Optional `category` (Bereich der Übersicht); bei `flow_update` heißt weglassen unverändert, `""` entfernen." (An der Stelle, an der die Tools beschrieben sind.)

- [ ] **Step 6: Commit**

```bash
git add flow-mcp-server
git commit -m "feat(flow-mcp): Bereich beim Anlegen und Aendern von Flows"
```

---

### Task 5: Frontend — Modell und Service

**Files:**
- Modify: `frontend/src/app/models/flow.model.ts`
- Modify: `frontend/src/app/services/flow.service.ts`
- Test: `frontend/src/app/services/flow.service.spec.ts`

- [ ] **Step 1: Failing Test** — den bestehenden Test `saves draft via PUT` ersetzen durch:

```ts
  it('saves draft via PUT including the category', () => {
    service.saveDraft(1, 'Name', 'Desc', '{"nodes":[],"wires":[]}', 'Licht').subscribe();
    const req = httpMock.expectOne('/api/v1/flows/1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      name: 'Name', description: 'Desc', category: 'Licht', draftDefinition: '{"nodes":[],"wires":[]}'
    });
    req.flush({});
  });
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=src/app/services/flow.service.spec.ts`
Expected: Kompilierfehler (5. Argument unbekannt).

- [ ] **Step 3: Implementieren**

`flow.model.ts`, in `FlowSummary` nach `description?: string;`:
```ts
  /** Bereich für die Gliederung der Übersicht; fehlt/null = „Sonstiges". */
  category?: string | null;
```

`flow.service.ts`:
```ts
  /** `category: ''` entfernt den Bereich (Teil-Update-Semantik des Backends). */
  saveDraft(id: number, name: string, description: string, draftDefinition: string,
            category: string): Observable<FlowDetail> {
    return this.http.put<FlowDetail>(`${this.baseUrl}/${id}`, { name, description, category, draftDefinition });
  }
```

- [ ] **Step 4: Test grün**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=src/app/services/flow.service.spec.ts`
Expected: pass. (Der Editor kompiliert bis Task 8 nicht — deshalb hier nur diese eine Spec laufen lassen.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/flow.model.ts frontend/src/app/services/flow.service.ts frontend/src/app/services/flow.service.spec.ts
git commit -m "feat(flows): Bereich im Frontend-Modell und beim Speichern"
```

---

### Task 6: Gruppierungs- und Speicher-Utils

**Files:**
- Create: `frontend/src/app/pages/flows/flow-grouping.util.ts`
- Create: `frontend/src/app/pages/flows/flow-grouping.util.spec.ts`
- Create: `frontend/src/app/pages/flows/flow-section-storage.util.ts`
- Create: `frontend/src/app/pages/flows/flow-section-storage.util.spec.ts`

- [ ] **Step 1: Failing Tests** — `flow-grouping.util.spec.ts`:

```ts
import { FlowSummary } from '../../models/flow.model';
import {
  UNCATEGORIZED_LABEL, distinctCategories, groupFlowsByCategory, matchesFlowSearch
} from './flow-grouping.util';

function flow(id: number, name: string, category?: string | null, enabled = true, description?: string): FlowSummary {
  return { id, name, category, enabled, deployed: true, description };
}

describe('groupFlowsByCategory', () => {
  it('sorts sections alphabetically (German collation) and puts "Sonstiges" last', () => {
    const sections = groupFlowsByCategory([
      flow(1, 'a', null),
      flow(2, 'b', 'Taster'),
      flow(3, 'c', 'Ärger'),
      flow(4, 'd', 'Licht')
    ]);

    expect(sections.map(s => s.title)).toEqual(['Ärger', 'Licht', 'Taster', UNCATEGORIZED_LABEL]);
  });

  it('treats blank categories as uncategorized', () => {
    const sections = groupFlowsByCategory([flow(1, 'a', '   '), flow(2, 'b', undefined)]);

    expect(sections.length).toBe(1);
    expect(sections[0].title).toBe(UNCATEGORIZED_LABEL);
    expect(sections[0].flows.length).toBe(2);
  });

  it('sorts flows by name within a section', () => {
    const sections = groupFlowsByCategory([flow(1, 'Zeta', 'Licht'), flow(2, 'Älpha', 'Licht'), flow(3, 'beta', 'Licht')]);

    expect(sections[0].flows.map(f => f.name)).toEqual(['Älpha', 'beta', 'Zeta']);
  });

  it('flags sections that contain a disabled flow', () => {
    const sections = groupFlowsByCategory([flow(1, 'a', 'Licht', false), flow(2, 'b', 'Taster', true)]);

    expect(sections.find(s => s.title === 'Licht')!.hasDisabled).toBeTrue();
    expect(sections.find(s => s.title === 'Taster')!.hasDisabled).toBeFalse();
  });

  it('returns no sections for no flows', () => {
    expect(groupFlowsByCategory([])).toEqual([]);
  });
});

describe('matchesFlowSearch', () => {
  it('matches everything for an empty query', () => {
    expect(matchesFlowSearch(flow(1, 'Nachtmodus'), '  ')).toBeTrue();
  });

  it('matches name and description case-insensitively', () => {
    const f = flow(1, 'Nachtmodus', null, true, 'Verriegelt die Tür');
    expect(matchesFlowSearch(f, 'NACHT')).toBeTrue();
    expect(matchesFlowSearch(f, 'tür')).toBeTrue();
    expect(matchesFlowSearch(f, 'Waschmaschine')).toBeFalse();
  });
});

describe('distinctCategories', () => {
  it('lists each non-blank category once, trimmed and sorted', () => {
    expect(distinctCategories([
      flow(1, 'a', 'Taster'), flow(2, 'b', ' Licht '), flow(3, 'c', 'Taster'), flow(4, 'd', null), flow(5, 'e', ' ')
    ])).toEqual(['Licht', 'Taster']);
  });
});
```

`flow-section-storage.util.spec.ts`:

```ts
import { COLLAPSED_SECTIONS_KEY, loadCollapsedSections, saveCollapsedSections } from './flow-section-storage.util';

describe('flow section storage', () => {
  afterEach(() => localStorage.removeItem(COLLAPSED_SECTIONS_KEY));

  it('round-trips the collapsed section titles', () => {
    saveCollapsedSections(new Set(['Licht', 'Taster']));

    expect([...loadCollapsedSections()].sort()).toEqual(['Licht', 'Taster']);
  });

  it('returns an empty set for nothing stored', () => {
    expect(loadCollapsedSections().size).toBe(0);
  });

  it('ignores garbage in storage', () => {
    localStorage.setItem(COLLAPSED_SECTIONS_KEY, '{kaputt');
    expect(loadCollapsedSections().size).toBe(0);

    localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify(['Licht', 42, null]));
    expect([...loadCollapsedSections()]).toEqual(['Licht']);
  });

  it('survives a throwing storage', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('blocked');
    spyOn(Storage.prototype, 'setItem').and.throwError('blocked');

    expect(loadCollapsedSections().size).toBe(0);
    expect(() => saveCollapsedSections(new Set(['Licht']))).not.toThrow();
  });
});
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include="src/app/pages/flows/flow-*.util.spec.ts"`
Expected: Kompilierfehler (Module fehlen).

- [ ] **Step 3: Implementieren** — `flow-grouping.util.ts`:

```ts
import { FlowSummary } from '../../models/flow.model';

/** Abschnitt für Flows ohne Bereich; steht immer am Ende. */
export const UNCATEGORIZED_LABEL = 'Sonstiges';

export interface FlowSection {
  title: string;
  flows: FlowSummary[];
  /** Mindestens ein Flow des Abschnitts ist deaktiviert — im Kopf sichtbar, auch wenn zugeklappt. */
  hasDisabled: boolean;
}

const collator = new Intl.Collator('de', { sensitivity: 'base' });

function categoryOf(flow: FlowSummary): string | null {
  const trimmed = flow.category?.trim();
  return trimmed ? trimmed : null;
}

/**
 * Einzige Definition der Gliederung der Flow-Übersicht: ein Abschnitt je Bereich,
 * alphabetisch, „Sonstiges" zuletzt; innerhalb nach Name.
 */
export function groupFlowsByCategory(flows: FlowSummary[]): FlowSection[] {
  const groups = new Map<string, FlowSummary[]>();
  for (const flow of flows) {
    const title = categoryOf(flow) ?? UNCATEGORIZED_LABEL;
    groups.set(title, [...(groups.get(title) ?? []), flow]);
  }
  return [...groups.entries()]
    .map(([title, members]) => ({
      title,
      flows: [...members].sort((a, b) => collator.compare(a.name, b.name)),
      hasDisabled: members.some(f => !f.enabled)
    }))
    .sort((a, b) => rank(a.title) - rank(b.title) || collator.compare(a.title, b.title));
}

function rank(title: string): number {
  return title === UNCATEGORIZED_LABEL ? 1 : 0;
}

/** Suche über Name und Beschreibung, Groß-/Kleinschreibung egal; leere Suche passt immer. */
export function matchesFlowSearch(flow: FlowSummary, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase('de');
  if (!needle) {
    return true;
  }
  return [flow.name, flow.description ?? '']
    .some(text => text.toLocaleLowerCase('de').includes(needle));
}

/** Vorhandene Bereiche (für die Vorschlagsliste im Editor). */
export function distinctCategories(flows: FlowSummary[]): string[] {
  const categories = new Set<string>();
  for (const flow of flows) {
    const category = categoryOf(flow);
    if (category) {
      categories.add(category);
    }
  }
  return [...categories].sort(collator.compare);
}
```

`flow-section-storage.util.ts`:

```ts
export const COLLAPSED_SECTIONS_KEY = 'flows.collapsedSections';

/**
 * Zugeklappte Abschnitte der Flow-Übersicht. Reine Komfortfunktion: ein gesperrter
 * oder kaputter Storage ergibt „alles aufgeklappt", nie einen Fehler.
 */
export function loadCollapsedSections(): Set<string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(COLLAPSED_SECTIONS_KEY) ?? '[]');
    return new Set(Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []);
  } catch {
    return new Set();
  }
}

export function saveCollapsedSections(titles: Set<string>): void {
  try {
    localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify([...titles]));
  } catch {
    // bewusst geschluckt, siehe loadCollapsedSections
  }
}
```

- [ ] **Step 4: Tests grün**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include="src/app/pages/flows/flow-*.util.spec.ts"`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/flows/flow-grouping.util.ts frontend/src/app/pages/flows/flow-grouping.util.spec.ts frontend/src/app/pages/flows/flow-section-storage.util.ts frontend/src/app/pages/flows/flow-section-storage.util.spec.ts
git commit -m "feat(flows): Gruppierung, Suche und Klappzustand als reine Utils"
```

---

### Task 7: Übersicht in Abschnitten

**Files:**
- Modify: `frontend/src/app/pages/flows/flow-list.component.ts`
- Modify: `frontend/src/app/pages/flows/flow-list.component.html`
- Modify: `frontend/src/app/pages/flows/flow-list.component.scss`
- Test: `frontend/src/app/pages/flows/flow-list.component.spec.ts`

- [ ] **Step 1: Failing Tests** — in `flow-list.component.spec.ts`:

Imports ergänzen:
```ts
import { COLLAPSED_SECTIONS_KEY } from './flow-section-storage.util';
```
Im äußeren `beforeEach` ganz oben: `localStorage.removeItem(COLLAPSED_SECTIONS_KEY);` und einen `afterEach(() => localStorage.removeItem(COLLAPSED_SECTIONS_KEY));` ergänzen.

Neue Tests am Ende des `describe`:

```ts
  describe('sections', () => {
    beforeEach(() => {
      flowService.getFlows.and.returnValue(of([
        { id: 1, name: 'Nachtmodus', category: 'Modi & Szenen', enabled: true, deployed: true },
        { id: 2, name: 'Büro-Taster', category: 'Taster', enabled: false, deployed: true },
        { id: 3, name: 'Diagnose', enabled: true, deployed: true, description: 'prüft die Engine' },
        { id: 4, name: 'Morgenmodus', category: 'Modi & Szenen', enabled: true, deployed: true }
      ] as any));
    });

    function render() {
      const fixture = TestBed.createComponent(FlowListComponent);
      fixture.detectChanges();
      return fixture;
    }

    function sectionTitles(fixture: any): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('.flow-section__title'))
        .map(el => (el as HTMLElement).textContent!.trim());
    }

    function rowNames(fixture: any): string[] {
      return Array.from(fixture.nativeElement.querySelectorAll('.flow-table__row td:first-child'))
        .map(el => (el as HTMLElement).textContent!.trim());
    }

    it('renders one section per category with "Sonstiges" last', () => {
      const fixture = render();

      expect(sectionTitles(fixture)).toEqual(['Modi & Szenen', 'Taster', 'Sonstiges']);
      expect(rowNames(fixture)).toEqual(['Morgenmodus', 'Nachtmodus', 'Büro-Taster', 'Diagnose']);
    });

    it('shows the count and a hint for disabled flows in the section header', () => {
      const fixture = render();
      const headers = Array.from(fixture.nativeElement.querySelectorAll('.flow-section__toggle')) as HTMLElement[];

      expect(headers[0].querySelector('.flow-section__count')!.textContent).toContain('2');
      expect(headers[0].querySelector('.flow-section__hint')).toBeNull();
      expect(headers[1].querySelector('.flow-section__hint')).not.toBeNull();
    });

    it('collapses a section and remembers it across page loads', () => {
      const fixture = render();
      (fixture.nativeElement.querySelector('.flow-section__toggle') as HTMLElement).click();
      fixture.detectChanges();

      expect(rowNames(fixture)).toEqual(['Büro-Taster', 'Diagnose']);

      const reloaded = render();
      expect(rowNames(reloaded)).toEqual(['Büro-Taster', 'Diagnose']);
    });

    it('search filters rows, hides empty sections and ignores collapsed state', () => {
      localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify(['Sonstiges']));
      const fixture = render();

      fixture.componentInstance.query.set('engine');
      fixture.detectChanges();

      expect(sectionTitles(fixture)).toEqual(['Sonstiges']);
      expect(rowNames(fixture)).toEqual(['Diagnose']);
    });

    it('says so when the search finds nothing', () => {
      const fixture = render();
      fixture.componentInstance.query.set('gibtsnicht');
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.flow-table__empty').textContent).toContain('Keine Treffer');
    });

    it('still renders all sections expanded when storage throws', () => {
      spyOn(Storage.prototype, 'getItem').and.throwError('blocked');
      const fixture = render();

      expect(rowNames(fixture).length).toBe(4);
    });
  });
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=src/app/pages/flows/flow-list.component.spec.ts`
Expected: FAIL (`.flow-section__title` gibt es nicht, `query` existiert nicht).

- [ ] **Step 3: Komponente** — in `flow-list.component.ts`:

Import-Zeile Angular: `import { Component, OnInit, computed, inject, signal } from '@angular/core';`
Zusätzliche Imports:
```ts
import { groupFlowsByCategory, matchesFlowSearch } from './flow-grouping.util';
import { loadCollapsedSections, saveCollapsedSections } from './flow-section-storage.util';
```
Nach `readonly error = …`:
```ts
  readonly query = signal('');
  private readonly collapsed = signal<Set<string>>(loadCollapsedSections());

  readonly searching = computed(() => this.query().trim() !== '');
  readonly sections = computed(() =>
    groupFlowsByCategory(this.flows().filter(flow => matchesFlowSearch(flow, this.query()))));

  /** Während einer Suche ist alles aufgeklappt, der gemerkte Zustand bleibt unangetastet. */
  isCollapsed(title: string): boolean {
    return !this.searching() && this.collapsed().has(title);
  }

  toggleSection(title: string): void {
    if (this.searching()) { return; }
    const next = new Set(this.collapsed());
    if (next.has(title)) { next.delete(title); } else { next.add(title); }
    this.collapsed.set(next);
    saveCollapsedSections(next);
  }
```

- [ ] **Step 4: Template** — den gesamten `<table class="flow-table">…</table>`-Block ersetzen durch:

```html
  <input class="flow-list-page__search" type="search" placeholder="Suchen in Name und Beschreibung …"
         aria-label="Flows durchsuchen"
         [value]="query()" (input)="query.set($any($event.target).value)" />

  <table class="flow-table">
    <thead>
      <tr><th>Name</th><th>Status</th><th>Aktiv</th><th>Zuletzt ausgelöst</th><th></th></tr>
    </thead>
    @for (section of sections(); track section.title) {
      <tbody class="flow-section">
        <tr class="flow-section__header">
          <th colspan="5">
            <button class="flow-section__toggle" type="button"
                    [attr.aria-expanded]="!isCollapsed(section.title)"
                    (click)="toggleSection(section.title)">
              <span class="flow-section__chevron"
                    [class.flow-section__chevron--collapsed]="isCollapsed(section.title)">▾</span>
              <span class="flow-section__title">{{ section.title }}</span>
              <span class="flow-section__count">({{ section.flows.length }})</span>
              @if (section.hasDisabled) {
                <span class="flow-section__hint">enthält deaktivierte</span>
              }
            </button>
          </th>
        </tr>
        @if (!isCollapsed(section.title)) {
          @for (flow of section.flows; track flow.id) {
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
              <td class="flow-table__last-triggered" [class.flow-table__last-triggered--never]="!flow.lastTriggeredAt">
                {{ lastTriggered(flow) }}
              </td>
              <td (click)="$event.stopPropagation()">
                <button class="flow-table__delete" (click)="deleteFlow(flow)">Löschen</button>
              </td>
            </tr>
          }
        }
      </tbody>
    } @empty {
      <tbody>
        <tr><td colspan="5" class="flow-table__empty">
          @if (searching()) { Keine Treffer für „{{ query().trim() }}“. }
          @else { Noch keine Automatisierungen. Lege eine neue an. }
        </td></tr>
      </tbody>
    }
  </table>
```

- [ ] **Step 5: Styles** — in `flow-list.component.scss` innerhalb von `.flow-list-page { … }` ergänzen:

```scss
  &__search { width: 100%; max-width: 360px; padding: 0.4rem 0.6rem; margin-bottom: 1rem;
    border: 1px solid #ccc; border-radius: 4px; font: inherit; }
```

und am Dateiende:

```scss
.flow-section {
  &__header th { background: #eef3f8; padding: 0; border-bottom: 1px solid #d5dde6; }
  &__toggle { display: flex; align-items: center; gap: 0.5rem; width: 100%; padding: 0.55rem 0.75rem;
    border: none; background: none; font: inherit; font-weight: 600; text-align: left; cursor: pointer; }
  &__chevron { display: inline-block; transition: transform 0.15s;
    &--collapsed { transform: rotate(-90deg); } }
  &__count { color: #666; font-weight: 400; }
  &__hint { margin-left: auto; font-size: 0.75rem; font-weight: 400; color: #8d6e63; }
}
```

- [ ] **Step 6: Tests grün**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=src/app/pages/flows/flow-list.component.spec.ts`
Expected: pass — auch der bestehende Test `shows when each flow was last triggered` (A und B landen beide unter „Sonstiges", Reihenfolge A, B).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/flows/flow-list.component.*
git commit -m "feat(flows): Uebersicht in aufklappbaren Bereichen mit Suche"
```

---

### Task 8: Editor — Bereichsfeld und Änderungserkennung

Heute zählt nur der Graph als Änderung: eine reine Namensänderung lässt „Speichern" gesperrt. Für das neue Bereichsfeld wäre das genauso — deshalb gehen Name und Bereich mit in den Vergleichsstand.

**Files:**
- Modify: `frontend/src/app/pages/flows/flow-editor.component.ts`
- Modify: `frontend/src/app/pages/flows/flow-editor.component.html`
- Modify: `frontend/src/app/pages/flows/flow-editor.component.scss`
- Test: `frontend/src/app/pages/flows/flow-editor.component.spec.ts`

- [ ] **Step 1: Failing Tests** — in `flow-editor.component.spec.ts`:

Spy-Liste um `'getFlows'` erweitern:
```ts
    flowService = jasmine.createSpyObj('FlowService',
      ['getFlow', 'getFlows', 'getNodeTypes', 'saveDraft', 'deploy', 'setEnabled', 'inject']);
```
`getFlow`-Stub um `category: 'Licht'` ergänzen (im Objekt nach `deployed: false`), und danach:
```ts
    flowService.getFlows.and.returnValue(of([
      { id: 1, name: 'F', category: 'Licht', enabled: true, deployed: true },
      { id: 2, name: 'G', category: 'Taster', enabled: true, deployed: true },
      { id: 3, name: 'H', enabled: true, deployed: true }
    ] as any));
```
Import ergänzen: `import { throwError } from 'rxjs';` (neben `of`).

Neue Tests:
```ts
  it('loads the category and offers existing categories as suggestions', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.category()).toBe('Licht');
    expect(fixture.componentInstance.knownCategories()).toEqual(['Licht', 'Taster']);
    const options = Array.from(fixture.nativeElement.querySelectorAll('#flow-categories option'))
      .map(o => (o as HTMLOptionElement).value);
    expect(options).toEqual(['Licht', 'Taster']);
  });

  it('still loads the flow when the category suggestions fail', () => {
    flowService.getFlows.and.returnValue(throwError(() => new Error('down')));
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.canvasNodes().length).toBe(1);
    expect(fixture.componentInstance.knownCategories()).toEqual([]);
  });

  it('marks the flow dirty when only the category or only the name changes', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    const editor = fixture.componentInstance;

    editor.onCategoryInput('Taster');
    expect(editor.dirty()).toBeTrue();
    editor.onCategoryInput('Licht');
    expect(editor.dirty()).toBeFalse();

    editor.onNameInput('Umbenannt');
    expect(editor.dirty()).toBeTrue();
  });

  it('saves the category and clears the dirty flag', () => {
    const fixture = TestBed.createComponent(FlowEditorComponent);
    fixture.detectChanges();
    const editor = fixture.componentInstance;

    editor.onCategoryInput('Taster');
    editor.save();

    expect(flowService.saveDraft.calls.mostRecent().args[4]).toBe('Taster');
    expect(editor.dirty()).toBeFalse();
  });
```

- [ ] **Step 2: Fehlschlag prüfen**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include=src/app/pages/flows/flow-editor.component.spec.ts`
Expected: Kompilierfehler (`category`, `knownCategories`, `onCategoryInput`, `onNameInput` fehlen; `saveDraft` erwartet 5 Argumente).

- [ ] **Step 3: Komponente** — in `flow-editor.component.ts`:

Imports: `distinctCategories` aus `./flow-grouping.util`; aus `rxjs` zusätzlich `of` (falls noch nicht importiert; `catchError` ist bereits importiert).

Neue Signale nach `readonly description = signal('');`:
```ts
  readonly category = signal('');
  readonly knownCategories = signal<string[]>([]);
```

In `ngOnInit` im `subscribe` nach `this.description.set(...)`:
```ts
        this.category.set(flow.category ?? '');
```
`this.savedSnapshot = this.serialize();` dort ersetzen durch `this.savedSnapshot = this.snapshot();`.
Nach dem `forkJoin(...).subscribe(...)`-Block, vor `this.startStatusPolling();`:
```ts
    this.loadKnownCategories();
```

Neue Methoden (nach `serialize()`):
```ts
  /**
   * Vergleichsstand für „ungespeichert": Graph UND Stammdaten. Früher zählte nur der Graph,
   * eine reine Umbenennung ließ „Speichern" gesperrt.
   */
  private snapshot(): string {
    return JSON.stringify({ definition: this.serialize(), name: this.name(), category: this.category() });
  }

  /** Vorschläge fürs Bereichsfeld; ohne sie bleibt der Editor voll benutzbar. */
  private loadKnownCategories(): void {
    this.flowService.getFlows().pipe(
      catchError(() => of([])),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(flows => this.knownCategories.set(distinctCategories(flows)));
  }

  onNameInput(value: string): void {
    this.name.set(value);
    this.markDirty();
  }

  onCategoryInput(value: string): void {
    this.category.set(value);
    this.markDirty();
  }
```

`markDirty` ändern zu:
```ts
  private markDirty(): void {
    this.dirty.set(this.snapshot() !== this.savedSnapshot);
  }
```

`save()` ersetzen:
```ts
  save(): void {
    const draft = this.serialize();
    const snapshot = this.snapshot();
    this.flowService.saveDraft(this.flowId, this.name(), this.description(), draft, this.category()).subscribe({
      next: () => {
        this.savedSnapshot = snapshot;
        this.dirty.set(false);
      },
      error: () => this.deployErrors.set(['Speichern fehlgeschlagen.'])
    });
  }
```

In `deploy()` analog: vor dem `saveDraft`-Aufruf `const snapshot = this.snapshot();`, Aufruf zu `this.flowService.saveDraft(this.flowId, this.name(), this.description(), draft, this.category())`, und im `next` `this.savedSnapshot = snapshot;` statt `= draft`.

Vor dem Commit prüfen, dass `savedSnapshot` nirgends sonst noch mit `serialize()` verglichen wird: `grep -n "savedSnapshot" frontend/src/app/pages/flows/flow-editor.component.ts` — jede Fundstelle muss `snapshot()` bzw. die lokale `snapshot`-Variable verwenden.

- [ ] **Step 4: Template** — Zeile 3 (`<input class="editor__name" …/>`) ersetzen durch:

```html
    <input class="editor__name" aria-label="Name" [value]="name()"
           (input)="onNameInput($any($event.target).value)" />
    <input class="editor__category" aria-label="Bereich" placeholder="Bereich" maxlength="60"
           list="flow-categories" [value]="category()"
           (input)="onCategoryInput($any($event.target).value)" />
    <datalist id="flow-categories">
      @for (c of knownCategories(); track c) { <option [value]="c"></option> }
    </datalist>
```

- [ ] **Step 5: Styles** — in `flow-editor.component.scss` nach der `.editor__name`-Regel:

```scss
.editor__category { font-size: 0.85rem; border: 1px solid #ddd; border-radius: 4px; padding: 0.2rem 0.4rem;
  width: 12rem; color: #455a64;
  &:focus { border-color: #999; outline: none; } }
```

- [ ] **Step 6: Tests grün**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include="src/app/pages/flows/**/*.spec.ts"`
Expected: alle Flow-Specs pass (inkl. bestehender `saves draft as our JSON format`: `args[3]` ist weiterhin der Draft).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/flows/flow-editor.component.*
git commit -m "feat(flows): Bereichsfeld im Editor, Name und Bereich zaehlen als Aenderung"
```

---

### Task 9: Doku und Gesamtprüfung

**Files:**
- Modify: `CLAUDE.md` (neuer Abschnitt direkt nach „### Flow-Engine: „Zuletzt ausgelöst" in der Übersicht")

- [ ] **Step 1: CLAUDE.md-Abschnitt einfügen**

```markdown
### Flow-Engine: Bereiche in der Übersicht
- Spec: `docs/superpowers/specs/2026-09-25-flow-bereiche-design.md`. Spalte `flows.category` (Changeset `20260925-0057`, VARCHAR(60), NULL = „Sonstiges"); `/flows` zeigt je Bereich einen auf-/zuklappbaren Abschnitt, alphabetisch, „Sonstiges" zuletzt, dazu eine Suche über Name und Beschreibung
- **Gliederung nach Zweck** (Nutzerentscheidung 2026-09-25), nicht nach Raum oder Auslöser: Modi & Szenen, Licht & Bewegung, Taster, Sicherheit & Warnungen, Haushaltsgeräte, Erinnerungen, System & Diagnose. Namensregel: „was passiert, wann" — ohne Bereich, ohne Nummer im Namen
- **Freitext, keine feste Liste.** Konsistenz halten die MCP-Tool-Beschreibung (vorhandene Bereiche aus `flow_list` wiederverwenden) und die `<datalist>`-Vorschläge im Editor. Ein vertippter Bereich erzeugt still einen eigenen Abschnitt — Korrektur per `flow_update`
- `FlowService.normalizeCategory` ist die einzige Normalisierung (trim, leer ⇒ NULL). **`PUT` ist Teil-Update:** `category` fehlt ⇒ unverändert, `""` ⇒ entfernt; ohne diese Unterscheidung löschte jede reine Beschreibungskorrektur per MCP den Bereich. > 60 Zeichen ⇒ 400 (`@Size`, `FlowFieldLimits.CATEGORY_MAX`, im MCP-Schema gespiegelt)
- `groupFlowsByCategory` (`pages/flows/flow-grouping.util.ts`) ist die einzige Gruppierungsregel; der Klappzustand liegt in `localStorage` (`flow-section-storage.util.ts`, wirft nie). Während einer Suche ist alles aufgeklappt, der gemerkte Zustand bleibt unberührt
- Nebenbei behoben: der Editor zählte nur den Graphen als Änderung, eine reine Umbenennung ließ „Speichern" gesperrt — Name und Bereich sind jetzt Teil des Vergleichsstands (`snapshot()`)
```

- [ ] **Step 2: Backend-Gesamtlauf**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test`
Expected: nur die bekannten lokalen Fails `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest`, sonst grün.

- [ ] **Step 3: Frontend-Gesamtlauf und Build**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: „3 FAILED" (Baseline App/Hero), sonst grün.
Run: `cd frontend && npx ng build --configuration production`
Expected: Build ok (eine bekannte Budget-Meldung zu `dashboard.component.scss` ist keine Regression).

- [ ] **Step 4: MCP-Tests**

Run: `cd flow-mcp-server && npm test`
Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Flow-Bereiche in CLAUDE.md"
```

---

### Task 10: Rollout (nur nach Merge, PROD-Deploy und ausdrücklicher Nutzerfreigabe)

Kein Code. Schreibt auf PROD — **vorher den Nutzer fragen**.

- [ ] **Step 1:** Nach dem PROD-Deploy prüfen, dass `flow_list` ein Feld `category` liefert (sonst ist das Backend noch alt; der flow-mcp-Prozess braucht nach einem Code-Update ggf. einen Session-Neustart).
- [ ] **Step 2:** Per `flow_update` Bereiche setzen (nur `id` + `category` übergeben — Teil-Update):

| Bereich | Flow-IDs |
|---|---|
| Modi & Szenen | 14, 11, 7 |
| Licht & Bewegung | 12, 13 |
| Taster | 10, 9 |
| Sicherheit & Warnungen | 2, 4, 3 |
| Haushaltsgeräte | 1, 8 |
| Erinnerungen | 5 |
| System & Diagnose | 6 |

- [ ] **Step 3:** Namen kürzen (per `flow_update`, nur `id` + `name`): #11 → „Morgenmodus", #14 → „Nachtmodus", #6 → „Diagnose (temporär)". Ein Umbenennen braucht **keinen** Re-Deploy (Name ist nicht Teil der Definition).
- [ ] **Step 4:** `/flows` im Browser öffnen und die Abschnitte prüfen.
