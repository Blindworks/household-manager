# Fertige Wasch- und Spülmaschine im Intelligence Hub — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der bestehende Flow „Waschmaschine fertig" (und ein neuer für die Spülmaschine) hinterlässt eine Karte im Intelligence Hub, die stehen bleibt, bis jemand sie antippt.

**Architecture:** Ein neuer Flow-Node `helper-set` setzt einen `INPUT_BOOLEAN`-Helfer je Maschine auf `on` (fertig) bzw. `off` (läuft wieder). Das Dashboard liest die Helfer über die vorhandene Entity-API, baut daraus Hub-Karten und schaltet sie beim Antippen über den bereits KIOSK-freigegebenen Toggle-Endpunkt aus. Es entsteht keine zweite Erkennungslogik — der Flow bleibt die einzige.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 (Flow-Engine), Angular 19 standalone / SCSS, flow-mcp-Server für die Flow-Änderungen.

**Spec:** `docs/superpowers/specs/2026-08-30-fertige-maschinen-im-hub-design.md`

---

## Dateiübersicht

**Backend**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/HelperSetNodeHandler.java` — der neue Node-Typ, einzige Stelle, an der ein Flow einen manuellen Helfer setzt.
- Create: `backend/src/test/java/com/household/manager/flowengine/nodes/HelperSetNodeHandlerTest.java`
- Modify: `backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java` — Feld-Deskriptoren des neuen Typs.

**Frontend**
- Create: `frontend/src/app/shared/insight-time.util.ts` — geteilte Zeitformatierung („… seit 17:46 Uhr.").
- Create: `frontend/src/app/shared/insight-time.util.spec.ts`
- Modify: `frontend/src/app/shared/door-insight.util.ts` — nutzt die geteilte Funktion.
- Create: `frontend/src/app/shared/appliance-insight.util.ts` — baut die Karten aus den Helfern.
- Create: `frontend/src/app/shared/appliance-insight.util.spec.ts`
- Modify: `frontend/src/app/shared/hub-insight.model.ts` — optionales `dismissEntityId`.
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` — Helfer laden, Karten einreihen, Wegtippen.
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` — antippbare Karte.
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` — Häkchen-Symbol der antippbaren Karte.
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` — neuer `describe`-Block.

**Doku / Betrieb**
- Modify: `CLAUDE.md`
- Kein Liquibase-Changeset, keine Änderung an `SecurityConfig` — die Helfer werden zur Laufzeit angelegt (Task 7).

**Testkommandos dieser Maschine**

Backend (aus `backend/`, JDK 21 ist Pflicht — der Default zeigt auf JDK 17):
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn test -Dtest=HelperSetNodeHandlerTest
```
`HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern auf dieser Maschine vorbestehend an der Test-DB — bei einem vollen `mvn test` ignorieren.

Frontend (aus `frontend/`):
```bash
npm test -- --watch=false --browsers=ChromeHeadless
```
Baseline: genau **3 FAILED** (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Nur zusätzliche Fehlschläge sind Regressionen. `SmartDeviceListComponent` flakt gelegentlich in `afterAll` — bei Verdacht erneut laufen lassen.

---

## Task 1: Flow-Node `helper-set`

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/HelperSetNodeHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/HelperSetNodeHandlerTest.java`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/flowengine/nodes/HelperSetNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HelperSetNodeHandlerTest {

    private static final String HELPER = "input_boolean.manual_waschmaschine_fertig";

    @Mock
    private ManualEntityService manualEntityService;

    @Mock
    private AuditService auditService;

    private HelperSetNodeHandler handler() {
        return new HelperSetNodeHandler(manualEntityService, auditService);
    }

    @Test
    void setsHelperOnAndPassesTheMessageThrough() {
        NodeConfig cfg = new NodeConfig(Map.of("entityId", HELPER, "action", "on"));

        NodeResult result = handler().handle(FlowMessage.of(Map.of("foo", "bar")), cfg, null);

        verify(manualEntityService).setState(HELPER, "on");
        verify(auditService).record("helper.set", HELPER + " -> on");
        assertEquals("bar", result.outputs().get(0).get(0).get("foo"));
    }

    @Test
    void setsHelperOff() {
        NodeConfig cfg = new NodeConfig(Map.of("entityId", HELPER, "action", "off"));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(manualEntityService).setState(HELPER, "off");
    }

    @Test
    void validationRejectsMissingEntityIdAndUnknownAction() {
        List<String> errors = handler().validate(new NodeConfig(Map.of("action", "an")));

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("entityId")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("action")));
    }

    @Test
    void validationAcceptsACompleteConfiguration() {
        assertTrue(handler().validate(new NodeConfig(Map.of("entityId", HELPER, "action", "off"))).isEmpty());
    }

    @Test
    void validationDoesNotTouchTheDatabase() {
        handler().validate(new NodeConfig(Map.of("entityId", HELPER, "action", "on")));

        // Ein Deploy darf nicht daran scheitern, dass der Helfer noch fehlt.
        verifyNoInteractions(manualEntityService);
    }

    @Test
    void serviceExceptionBreaksTheBranchInsteadOfBeingSwallowed() {
        NodeConfig cfg = new NodeConfig(Map.of("entityId", HELPER, "action", "on"));
        doThrow(new ResourceNotFoundException("Entity not found: " + HELPER))
                .when(manualEntityService).setState(HELPER, "on");

        assertThrows(ResourceNotFoundException.class,
                () -> handler().handle(FlowMessage.of(Map.of()), cfg, null));
    }

    @Test
    void exposesTypeFieldsAndSinglePort() {
        HelperSetNodeHandler h = handler();

        assertEquals("helper-set", h.type());
        assertEquals(1, h.outputPorts());
        assertEquals(List.of("Ausgang"), h.portLabels());
        assertEquals(NodeFieldType.ENTITY_REF, h.fields().get(0).type());
        assertTrue(h.fields().get(0).required());
        assertEquals(NodeFieldType.ENUM, h.fields().get(1).type());
        assertEquals(List.of("on", "off"), h.fields().get(1).options());
        assertFalse(h.fields().isEmpty());
    }
}
```

- [ ] **Step 2: Test laufen lassen und den Fehlschlag prüfen**

Aus `backend/`:
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
mvn test -Dtest=HelperSetNodeHandlerTest
```
Erwartet: Kompilierfehler „cannot find symbol: class HelperSetNodeHandler".

- [ ] **Step 3: Den Handler schreiben**

`backend/src/main/java/com/household/manager/flowengine/nodes/HelperSetNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aktions-Node: setzt einen manuellen Boolean-Helfer auf "on" oder "off" —
 * die Brücke von einem Flow zu einer Anzeige im Dashboard (z. B. die Karte
 * „Waschmaschine fertig" im Intelligence Hub).
 *
 * <p>Geschrieben wird ausschließlich über {@link ManualEntityService}; dessen
 * Beschränkung auf {@code EntitySource.MANUAL} verhindert, dass ein Flow den
 * Zustand eines echten Geräts oder Sensors fälscht.
 *
 * <p>Anders als {@code light-set} schluckt dieser Node Fehler <b>nicht</b>: hier
 * scheitert kein unerreichbares Funkgerät, sondern ein Schreibzugriff auf die
 * eigene Datenbank — ein Konfigurationsfehler, der im Flow-Debug sichtbar sein soll.
 */
@Component
@RequiredArgsConstructor
public class HelperSetNodeHandler implements NodeHandler {

    private static final String ON = "on";
    private static final String OFF = "off";

    private final ManualEntityService manualEntityService;
    private final AuditService auditService;

    @Override
    public String type() {
        return "helper-set";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").filter(id -> !id.isBlank()).isEmpty()) {
            errors.add("entityId fehlt");
        }
        String action = config.string("action").orElse(null);
        if (!ON.equals(action) && !OFF.equals(action)) {
            errors.add("action muss 'on' oder 'off' sein");
        }
        // Ob der Helfer existiert, prueft erst die Laufzeit: ein Deploy darf nicht
        // daran scheitern, dass er im Moment des Deploys noch fehlt.
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String entityId = config.string("entityId").orElseThrow();
        String action = config.string("action").orElseThrow();
        manualEntityService.setState(entityId, action);
        auditService.record("helper.set", entityId + " -> " + action);
        return NodeResult.single(message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("entityId", "Helfer", NodeFieldType.ENTITY_REF, true),
                NodeFieldDescriptor.enumField("action", "Aktion", true, List.of(ON, OFF)));
    }
}
```

- [ ] **Step 4: Test laufen lassen und den Erfolg prüfen**

```bash
mvn test -Dtest=HelperSetNodeHandlerTest
```
Erwartet: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 5: Den Katalog-Test ergänzen**

In `backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java` eine Testmethode ans Ende der Klasse (vor die schließende Klammer) einfügen:

```java
    @Test
    void helperSetHasEntityRefAndOnOffAction() {
        var h = new HelperSetNodeHandler(null, null);
        var fields = h.fields();
        assertEquals(NodeFieldType.ENTITY_REF, field(fields, "entityId").type());
        assertTrue(field(fields, "entityId").required());
        assertEquals(List.of("on", "off"), field(fields, "action").options());
        assertEquals(List.of("Ausgang"), h.portLabels());
    }
```

- [ ] **Step 6: Beide Tests laufen lassen**

```bash
mvn test -Dtest=HelperSetNodeHandlerTest+NodeCatalogFieldsTest
```
Erwartet: alle grün.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/HelperSetNodeHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/HelperSetNodeHandlerTest.java backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java
git commit -m "feat(flows): Node-Typ helper-set setzt manuelle Helfer aus einem Flow"
```

---

## Task 2: Zeitformatierung der Hub-Karten teilen

Die Maschinen-Karte braucht denselben Text-Aufbau wie die Türkarte („… seit 17:46 Uhr.", mit Datum bei einem früheren Tag). Statt ihn zweimal zu schreiben, wandert er aus `door-insight.util.ts` heraus.

**Files:**
- Create: `frontend/src/app/shared/insight-time.util.ts`
- Test: `frontend/src/app/shared/insight-time.util.spec.ts`
- Modify: `frontend/src/app/shared/door-insight.util.ts`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`frontend/src/app/shared/insight-time.util.spec.ts`:

```ts
import { sinceText } from './insight-time.util';

/** Bezugszeitpunkt am selben Tag wie die Test-Zeitstempel. */
const NOW_MS = new Date('2026-08-14T19:00:00').getTime();

describe('sinceText', () => {

  it('nennt nur die Uhrzeit, wenn der Zeitpunkt vom selben Tag stammt', () => {
    expect(sinceText('2026-08-14T17:46:32', NOW_MS, 'Offen', 'egal')).toBe('Offen seit 17:46 Uhr.');
  });

  it('nennt zusaetzlich das Datum, wenn der Zeitpunkt aelter als heute ist', () => {
    expect(sinceText('2026-08-13T22:10:00', NOW_MS, 'Fertig', 'egal'))
      .toBe('Fertig seit 13.08., 22:10 Uhr.');
  });

  it('gibt bei unlesbarem Zeitstempel den Rueckfalltext des Aufrufers zurueck', () => {
    expect(sinceText('kaputt', NOW_MS, 'Fertig', 'Die Maschine ist fertig.'))
      .toBe('Die Maschine ist fertig.');
  });
});
```

- [ ] **Step 2: Test laufen lassen und den Fehlschlag prüfen**

Aus `frontend/`:
```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/insight-time.util.spec.ts
```
Erwartet: Kompilierfehler „Cannot find module './insight-time.util'".

- [ ] **Step 3: Die geteilte Funktion schreiben**

`frontend/src/app/shared/insight-time.util.ts`:

```ts
/**
 * Formatiert "<Praefix> seit 17:46 Uhr." aus dem `lastChanged` einer Entitaet.
 * Liegt der Zeitpunkt vor dem heutigen Tag, steht zusaetzlich das Datum davor
 * ("Offen seit 13.08., 22:10 Uhr.") — sonst waere eine Uhrzeit ohne Datum irrefuehrend.
 *
 * @param prefix Zustandswort des Aufrufers, z. B. "Offen" oder "Fertig".
 * @param fallback Text bei unlesbarem Zeitstempel. Bewusst ein eigener Parameter:
 * er laesst sich nicht aus dem Praefix bilden ("Die Tuer ist gerade offen.").
 */
export function sinceText(lastChanged: string, nowMs: number, prefix: string, fallback: string): string {
  const since = new Date(lastChanged);
  if (isNaN(since.getTime())) {
    return fallback;
  }
  const time = since.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  if (isSameLocalDay(since, new Date(nowMs))) {
    return `${prefix} seit ${time} Uhr.`;
  }
  const date = since.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
  return `${prefix} seit ${date}, ${time} Uhr.`;
}

function isSameLocalDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}
```

- [ ] **Step 4: Test laufen lassen und den Erfolg prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/insight-time.util.spec.ts
```
Erwartet: 3 SUCCESS, 0 FAILED.

- [ ] **Step 5: `door-insight.util.ts` auf die geteilte Funktion umstellen**

In `frontend/src/app/shared/door-insight.util.ts` den Import ergänzen:

```ts
import { sinceText } from './insight-time.util';
```

Den Aufruf in `buildDoorInsights` ersetzen:

```ts
      text: sinceText(entity.lastChanged, nowMs, 'Offen', 'Die Tür ist gerade offen.')
```

Und die beiden lokalen Hilfsfunktionen `openSinceText` und `isSameLocalDay` am Dateiende **löschen** — sie leben jetzt in `insight-time.util.ts`.

- [ ] **Step 6: Türkarten-Tests laufen lassen (unveränderte Absicherung)**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/door-insight.util.spec.ts
```
Erwartet: 6 SUCCESS, 0 FAILED — die Datei wurde **nicht** angefasst und beweist damit, dass das Herauslösen die Türkarten nicht verändert hat.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/insight-time.util.ts frontend/src/app/shared/insight-time.util.spec.ts frontend/src/app/shared/door-insight.util.ts
git commit -m "refactor(hub): Zeitformatierung der Hub-Karten in eine geteilte Funktion"
```

---

## Task 3: `appliance-insight.util.ts`

**Files:**
- Modify: `frontend/src/app/shared/hub-insight.model.ts`
- Create: `frontend/src/app/shared/appliance-insight.util.ts`
- Test: `frontend/src/app/shared/appliance-insight.util.spec.ts`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`frontend/src/app/shared/appliance-insight.util.spec.ts`:

```ts
import { buildApplianceInsights } from './appliance-insight.util';
import { EntityState } from '../models/entity-state.model';

function helper(entityId: string, overrides: Partial<EntityState> = {}): EntityState {
  return {
    entityId,
    domain: 'INPUT_BOOLEAN',
    source: 'MANUAL',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state: 'off',
    attributes: {},
    lastChanged: '2026-08-14T17:46:32',
    lastUpdated: '2026-08-14T17:46:32',
    ...overrides
  };
}

/** Bezugszeitpunkt am selben Tag wie die Test-Zeitstempel. */
const NOW_MS = new Date('2026-08-14T19:00:00').getTime();

describe('buildApplianceInsights', () => {

  it('liefert keine Karte, solange kein Helfer auf on steht', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_fertig'),
      helper('input_boolean.manual_spuelmaschine_fertig')
    ];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('baut fuer die fertige Waschmaschine eine antippbare Karte mit Uhrzeit', () => {
    const entities = [helper('input_boolean.manual_waschmaschine_fertig', { state: 'on' })];

    const insights = buildApplianceInsights(entities, NOW_MS);

    expect(insights.length).toBe(1);
    expect(insights[0].title).toBe('Waschmaschine fertig');
    expect(insights[0].icon).toBe('local_laundry_service');
    expect(insights[0].tone).toBe('primary');
    expect(insights[0].text).toBe('Fertig seit 17:46 Uhr.');
    expect(insights[0].dismissEntityId).toBe('input_boolean.manual_waschmaschine_fertig');
  });

  it('baut zwei Karten in stabiler Reihenfolge, wenn beide Maschinen fertig sind', () => {
    const entities = [
      helper('input_boolean.manual_spuelmaschine_fertig', { state: 'on' }),
      helper('input_boolean.manual_waschmaschine_fertig', { state: 'on' })
    ];

    const titles = buildApplianceInsights(entities, NOW_MS).map(insight => insight.title);

    expect(titles).toEqual(['Waschmaschine fertig', 'Spülmaschine fertig']);
  });

  it('nennt das Datum, wenn die Maschine seit einem frueheren Tag fertig ist', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_fertig', { state: 'on', lastChanged: '2026-08-13T22:10:00' })
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Fertig seit 13.08., 22:10 Uhr.');
  });

  it('wertet unavailable nicht als fertig', () => {
    const entities = [helper('input_boolean.manual_waschmaschine_fertig', { state: 'unavailable' })];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('ignoriert fremde Helfer wie den Nachtmodus', () => {
    const entities = [helper('input_boolean.manual_nachtmodus', { state: 'on' })];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('faellt bei unlesbarem Zeitstempel auf einen neutralen Text zurueck', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_fertig', { state: 'on', lastChanged: 'kaputt' })
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Die Maschine ist fertig.');
  });
});
```

- [ ] **Step 2: Test laufen lassen und den Fehlschlag prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/appliance-insight.util.spec.ts
```
Erwartet: Kompilierfehler „Cannot find module './appliance-insight.util'".

- [ ] **Step 3: `HubInsight` um das optionale Feld erweitern**

In `frontend/src/app/shared/hub-insight.model.ts` innerhalb des Interfaces ergänzen:

```ts
  /**
   * Entity-ID eines manuellen Helfers, den ein Antippen der Karte ausschaltet.
   * Nur Karten mit diesem Feld sind antippbar; alle uebrigen Hub-Karten sind
   * reine Anzeige.
   */
  readonly dismissEntityId?: string;
```

- [ ] **Step 4: Die Util schreiben**

`frontend/src/app/shared/appliance-insight.util.ts`:

```ts
import { EntityState } from '../models/entity-state.model';
import { HubInsight } from './hub-insight.model';
import { sinceText } from './insight-time.util';

/**
 * Ueberwachte Fertig-Helfer: Entity-ID des INPUT_BOOLEAN → Anzeige im Hub.
 * Gesetzt werden sie von den Flows "Waschmaschine fertig" und "Spuelmaschine
 * fertig" ueber den Node `helper-set`.
 *
 * <p>Die IDs entstehen im Backend ueber `EntityIds.build` aus dem Helfer-Namen und
 * ueberleben ein Umbenennen. Ein GELOESCHTER und neu angelegter Helfer traegt zwar
 * dieselbe ID, verliert aber die Kachel-Sichtbarkeitsregel — siehe CLAUDE.md.
 */
const FINISHED_HELPERS: ReadonlyArray<{ entityId: string; title: string; icon: string }> = [
  {
    entityId: 'input_boolean.manual_waschmaschine_fertig',
    title: 'Waschmaschine fertig',
    icon: 'local_laundry_service'
  },
  {
    entityId: 'input_boolean.manual_spuelmaschine_fertig',
    title: 'Spülmaschine fertig',
    icon: 'dishwasher_gen'
  }
];

/**
 * Baut je fertiger Maschine eine Hub-Karte, z. B. "Waschmaschine fertig — Fertig
 * seit 17:46 Uhr.". Die Karte traegt `dismissEntityId` und ist damit antippbar.
 *
 * <p>Nur `state === 'on'` erzeugt eine Karte. Ein fehlender Helfer oder
 * `unavailable` erzeugt keine — geraten wird nicht (Muster `buildDoorInsights`).
 *
 * @param nowMs Bezugszeitpunkt fuer "heute"; ist die Maschine seit einem frueheren
 * Tag fertig, nennt der Text zusaetzlich das Datum.
 */
export function buildApplianceInsights(entities: EntityState[], nowMs: number): HubInsight[] {
  const insights: HubInsight[] = [];
  for (const appliance of FINISHED_HELPERS) {
    const entity = entities.find(candidate => candidate.entityId === appliance.entityId);
    if (entity?.state !== 'on') {
      continue;
    }
    insights.push({
      icon: appliance.icon,
      tone: 'primary',
      title: appliance.title,
      text: sinceText(entity.lastChanged, nowMs, 'Fertig', 'Die Maschine ist fertig.'),
      dismissEntityId: appliance.entityId
    });
  }
  return insights;
}
```

- [ ] **Step 5: Test laufen lassen und den Erfolg prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/appliance-insight.util.spec.ts
```
Erwartet: 7 SUCCESS, 0 FAILED.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared/appliance-insight.util.ts frontend/src/app/shared/appliance-insight.util.spec.ts frontend/src/app/shared/hub-insight.model.ts
git commit -m "feat(hub): Karten fuer fertige Wasch- und Spuelmaschine"
```

---

## Task 4: Die Karten im Dashboard anzeigen

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Ans Ende von `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` anhängen:

```ts
describe('DashboardComponent (Fertige Maschinen im Intelligence Hub)', () => {
  let entityStateServiceSpy: jasmine.SpyObj<EntityStateService>;
  let switchServiceSpy: jasmine.SpyObj<SwitchService>;

  const helper = (entityId: string, state: string): EntityState => ({
    entityId,
    domain: 'INPUT_BOOLEAN',
    source: 'MANUAL',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state,
    attributes: {},
    lastChanged: '2026-08-14T17:46:32',
    lastUpdated: '2026-08-14T17:46:32'
  });

  /**
   * Die Helfer-Karten und die Tuer-Karten holen ihre Daten aus demselben Endpunkt
   * mit verschiedenen Filtern — der Stub muss deshalb nach Argumenten antworten,
   * sonst reicht er Tuerkontakte an die Maschinen-Util (und umgekehrt).
   */
  function entitiesReturning(helpers: EntityState[]): void {
    entityStateServiceSpy.getEntities.and.callFake((domain?: string) =>
      of(domain === 'INPUT_BOOLEAN' ? helpers : []));
  }

  function insightCards(fixture: ComponentFixture<DashboardComponent>): HTMLElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.lumina__insight')
    );
  }

  beforeEach(async () => {
    entityStateServiceSpy = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityStateServiceSpy.getEntities.and.returnValue(of([]));

    switchServiceSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchServiceSpy.getSwitches.and.returnValue(of([]));
    switchServiceSpy.toggle.and.returnValue(of({
      entityId: 'input_boolean.manual_waschmaschine_fertig',
      domain: 'INPUT_BOOLEAN',
      source: 'MANUAL',
      displayName: 'Waschmaschine fertig',
      state: 'off',
      available: true,
      icon: 'local_laundry_service',
      confirmRequired: false,
      toggleCount: 1,
      lastToggledAt: null
    } as SwitchEntity));

    const wasteSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    wasteSpy.getUpcoming.and.returnValue(of([]));

    const calendarSpy = jasmine.createSpyObj('CalendarService', ['getUpcoming']);
    calendarSpy.getUpcoming.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EntityStateService, useValue: entityStateServiceSpy },
        { provide: SwitchService, useValue: switchServiceSpy },
        { provide: WasteCollectionService, useValue: wasteSpy },
        { provide: CalendarService, useValue: calendarSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('zeigt die fertige Waschmaschine als Karte im Hub', fakeAsync(() => {
    entitiesReturning([helper('input_boolean.manual_waschmaschine_fertig', 'on')]);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(entityStateServiceSpy.getEntities).toHaveBeenCalledWith('INPUT_BOOLEAN', 'MANUAL');
    const text = (insightCards(fixture)[0].textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Waschmaschine fertig');

    discardPeriodicTasks();
  }));

  it('zeigt keine Karte, solange der Helfer aus ist', fakeAsync(() => {
    entitiesReturning([helper('input_boolean.manual_waschmaschine_fertig', 'off')]);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const texts = insightCards(fixture).map(card => card.textContent ?? '').join(' ');
    expect(texts).not.toContain('Waschmaschine');

    discardPeriodicTasks();
  }));

  it('stoert das Dashboard nicht, wenn der Abruf der Helfer fehlschlaegt', fakeAsync(() => {
    entityStateServiceSpy.getEntities.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    const texts = insightCards(fixture).map(card => card.textContent ?? '').join(' ');
    expect(texts).not.toContain('Waschmaschine');

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Test laufen lassen und den Fehlschlag prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/dashboard.component.spec.ts
```
Erwartet: der erste neue Test schlägt fehl — `getEntities` wurde nie mit `('INPUT_BOOLEAN', 'MANUAL')` aufgerufen.

- [ ] **Step 3: Das Dashboard die Helfer laden lassen**

In `frontend/src/app/pages/dashboard/dashboard.component.ts`:

Import ergänzen (neben `buildDoorInsights`, ca. Zeile 41):
```ts
import { buildApplianceInsights } from '../../shared/appliance-insight.util';
```

Feld neben `doorInsights` (ca. Zeile 318) ergänzen:
```ts
  /** Karten fuer fertige Maschinen; gesetzt von den Flows ueber Helfer-Entitaeten. */
  private applianceInsights: HubInsight[] = [];

  /**
   * Letzter geladener Stand der manuellen Helfer. Wird beim Wegtippen gebraucht,
   * um das Ziel neu aufzuloesen, statt einer festgehaltenen Kopie zu vertrauen.
   */
  private applianceEntities: EntityState[] = [];
```

`EntityState` ist in dieser Datei noch nicht importiert (nur der Service), der Import kommt also dazu — neben den übrigen Model-Imports:
```ts
import { EntityState } from '../../models/entity-state.model';
```

Subscription-Feld neben `doorSubscription` (ca. Zeile 183):
```ts
  private applianceSubscription?: Subscription;
```

Konstante neben `DOOR_REFRESH_MS` (ca. Zeile 217):
```ts
  /** Wie die Tuerkarten: die Helfer aendern sich selten, 30 s reichen. */
  private static readonly APPLIANCE_REFRESH_MS = 30000;
```

In `ngOnInit` direkt nach `this.startDoorRefresh();` (Zeile 424):
```ts
    this.startApplianceRefresh();
```

In `ngOnDestroy` direkt nach `this.doorSubscription?.unsubscribe();`:
```ts
    this.applianceSubscription?.unsubscribe();
```

In `rebuildInsights` die Maschinen-Karten hinter die Türen einreihen und den Kommentar nachziehen:
```ts
  /** Komponiert den Hub: offene Tueren voran, dann fertige Maschinen, Muell, Termine, Lueften, Tracker-Akku. */
  private rebuildInsights(): void {
    this.insights = [
      ...this.doorInsights,
      ...this.applianceInsights,
      ...(this.wasteInsight ? [this.wasteInsight] : []),
      ...this.calendarInsights,
      ...(this.ventilationInsight ? [this.ventilationInsight] : []),
      ...(this.trackerBatteryInsight ? [this.trackerBatteryInsight] : [])
    ];
  }
```

Und die neue Methode direkt hinter `startDoorRefresh` einfügen:
```ts
  /**
   * Haelt die Karten fertiger Maschinen aktuell. Quelle sind die Helfer, die die
   * Flows "Waschmaschine/Spuelmaschine fertig" setzen. Ein Ladefehler leert die
   * Karten bewusst (Muster Tueren): eine Karte ohne bekannten Serverzustand liesse
   * sich auch nicht mehr sinnvoll wegtippen.
   */
  private startApplianceRefresh(): void {
    this.applianceSubscription = interval(DashboardComponent.APPLIANCE_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.entityStateService.getEntities('INPUT_BOOLEAN', 'MANUAL')
          .pipe(catchError(() => of([]))))
      )
      .subscribe(entities => {
        this.applianceEntities = entities;
        this.applianceInsights = buildApplianceInsights(entities, Date.now());
        this.rebuildInsights();
      });
  }
```

- [ ] **Step 4: Test laufen lassen und den Erfolg prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/dashboard.component.spec.ts
```
Erwartet: alle drei neuen Tests grün, keine bestehenden Dashboard-Tests neu rot.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): fertige Maschinen im Intelligence Hub anzeigen"
```

---

## Task 5: Karte antippen räumt sie weg

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html:204-214`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In den in Task 4 angelegten `describe`-Block „Fertige Maschinen im Intelligence Hub" vor der schließenden Klammer einfügen:

```ts
  it('schaltet den Helfer aus, wenn die Karte angetippt wird', fakeAsync(() => {
    entitiesReturning([helper('input_boolean.manual_waschmaschine_fertig', 'on')]);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    insightCards(fixture)[0].click();
    fixture.detectChanges();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_waschmaschine_fertig');
    const texts = insightCards(fixture).map(card => card.textContent ?? '').join(' ');
    expect(texts).not.toContain('Waschmaschine fertig');

    discardPeriodicTasks();
  }));

  it('schaltet nicht, wenn der Helfer inzwischen nicht mehr auf on steht', fakeAsync(() => {
    entitiesReturning([helper('input_boolean.manual_waschmaschine_fertig', 'on')]);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    // Hintergrund-Refresh bei offener Karte: der Helfer wurde woanders abgeraeumt.
    // Ohne die Neuaufloesung wuerde der Toggle ihn wieder EINschalten.
    entitiesReturning([helper('input_boolean.manual_waschmaschine_fertig', 'off')]);
    tick(30000);
    fixture.detectChanges();
    (fixture.componentInstance as unknown as { dismissInsight(id: string): void })
      .dismissInsight('input_boolean.manual_waschmaschine_fertig');

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('laesst die Karte stehen, wenn das Schalten fehlschlaegt', fakeAsync(() => {
    entitiesReturning([helper('input_boolean.manual_waschmaschine_fertig', 'on')]);
    switchServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    insightCards(fixture)[0].click();
    fixture.detectChanges();

    const texts = insightCards(fixture).map(card => card.textContent ?? '').join(' ');
    expect(texts).toContain('Waschmaschine fertig');

    discardPeriodicTasks();
  }));

  it('macht Karten ohne dismissEntityId nicht antippbar', fakeAsync(() => {
    entitiesReturning([]);
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    // Ohne Hinweise steht im Hub die Ruhemeldung "Alles ruhig" — sie darf kein Button sein.
    expect(insightCards(fixture)[0].getAttribute('role')).toBeNull();

    discardPeriodicTasks();
  }));
```

- [ ] **Step 2: Test laufen lassen und den Fehlschlag prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/dashboard.component.spec.ts
```
Erwartet: Kompilierfehler „Property 'dismissInsight' does not exist" bzw. `toggle` wurde nie aufgerufen.

- [ ] **Step 3: Die beiden Methoden im Dashboard ergänzen**

In `frontend/src/app/pages/dashboard/dashboard.component.ts` als **öffentliche** Methoden (das Template ruft sie), z. B. direkt vor `rebuildInsights`:

```ts
  /** Klick/Tastatur auf eine Hub-Karte; nur antippbare Karten tun etwas. */
  activateInsight(item: HubInsight): void {
    if (item.dismissEntityId) {
      this.dismissInsight(item.dismissEntityId);
    }
  }

  /**
   * Raeumt eine antippbare Hub-Karte weg, indem der zugehoerige Helfer ausgeschaltet
   * wird.
   *
   * <p>Der Endpunkt ist ein *Toggle* und die Helfer-Liste bis zu 30 s alt: ohne die
   * Neuaufloesung aus dem aktuellen Stand wuerde ein Klick auf eine inzwischen
   * anderswo abgeraeumte Karte den Helfer wieder EINschalten (Regel aus
   * {@link confirmToggle}). Schlaegt das Schalten fehl, bleibt die Karte stehen —
   * sie ist die ehrliche Anzeige des Serverzustands.
   */
  dismissInsight(entityId: string): void {
    const current = this.applianceEntities.find(entity => entity.entityId === entityId);
    if (current?.state !== 'on') {
      this.refreshApplianceInsights();
      return;
    }
    this.switchService.toggle(entityId).subscribe({
      next: () => {
        this.applianceEntities = this.applianceEntities.map(entity =>
          entity.entityId === entityId ? { ...entity, state: 'off' } : entity);
        this.refreshApplianceInsights();
      },
      error: () => { /* Karte bleibt stehen, bis der naechste Refresh die Wahrheit bringt. */ }
    });
  }

  private refreshApplianceInsights(): void {
    this.applianceInsights = buildApplianceInsights(this.applianceEntities, Date.now());
    this.rebuildInsights();
  }
```

Und `startApplianceRefresh` auf die neue Hilfsmethode umstellen, damit der Aufbau nur an einer Stelle steht:

```ts
      .subscribe(entities => {
        this.applianceEntities = entities;
        this.refreshApplianceInsights();
      });
```

- [ ] **Step 4: Das Markup antippbar machen**

In `frontend/src/app/pages/dashboard/dashboard.component.html` den `*ngFor`-Block der Hub-Karten (Zeilen 204–214) ersetzen durch:

```html
          <div
            *ngFor="let item of insights"
            class="lumina__insight"
            [ngClass]="'lumina__insight--' + item.tone"
            [attr.role]="item.dismissEntityId ? 'button' : null"
            [attr.tabindex]="item.dismissEntityId ? 0 : null"
            [attr.aria-label]="item.dismissEntityId ? item.title + ' — erledigt' : null"
            (click)="activateInsight(item)"
            (keydown.enter)="activateInsight(item)"
            (keydown.space)="$event.preventDefault(); activateInsight(item)"
          >
            <span class="material-symbols-outlined lumina__insight-icon">{{ item.icon }}</span>
            <div>
              <p class="lumina__insight-title">{{ item.title }}</p>
              <p class="lumina__insight-text">{{ item.text }}</p>
            </div>
            <span
              *ngIf="item.dismissEntityId"
              class="material-symbols-outlined lumina__insight-dismiss"
              aria-hidden="true"
            >done</span>
          </div>
```

- [ ] **Step 5: Das Häkchen stylen**

In `frontend/src/app/pages/dashboard/dashboard.component.scss` direkt hinter dem Block `.lumina__insight-icon` (ca. Zeile 809) ergänzen:

```scss
// Sichtbarer Hinweis, dass diese Karte sich wegtippen laesst. Die Karte selbst
// traegt bereits cursor/hover, es fehlt nur die Erkennbarkeit.
.lumina__insight-dismiss {
  margin-left: auto;
  font-size: 18px;
  line-height: 1;
  opacity: 0.55;
}
```

- [ ] **Step 6: Tests laufen lassen und den Erfolg prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include=**/dashboard.component.spec.ts
```
Erwartet: alle sieben Tests des neuen `describe`-Blocks grün, keine bestehenden Dashboard-Tests neu rot.

- [ ] **Step 7: Den kompletten Frontend-Lauf prüfen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartet: genau 3 FAILED (die bekannte Baseline), sonst grün.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.scss frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): fertige Maschinen im Hub per Antippen wegraeumen"
```

---

## Task 6: Dokumentation in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Abschnitt einfügen**

In `CLAUDE.md` direkt **vor** dem Abschnitt `### Web-Push-Benachrichtigungen (PWA)` einfügen:

```markdown
### Fertige Maschinen im Intelligence Hub
- Wenn Wasch- oder Spülmaschine fertig ist, steht eine Karte im Intelligence Hub, bis jemand sie antippt. Spec: `docs/superpowers/specs/2026-08-30-fertige-maschinen-im-hub-design.md`
- **Die Erkennung bleibt allein Sache der Flows** (#1 „Waschmaschine fertig", plus der gleichnamige Flow für die Spülmaschine): Leistung < 5 W für 600 s → Rate-Limit 3600 s → Alexa + Telegram **+ neuer Zweig `helper-set`**. Es gibt bewusst keine zweite, im Backend rechnende Erkennung, die von der des Flows abweichen könnte
- **Flow-Node `helper-set`** (`HelperSetNodeHandler`) ist die einzige Stelle, an der ein Flow einen manuellen Helfer setzt. Er schreibt über `ManualEntityService.setState`; dessen Beschränkung auf `EntitySource.MANUAL` verhindert, dass ein Flow den Zustand eines echten Geräts oder Sensors fälscht. **Anders als `light-set` schluckt er Fehler nicht** — hier scheitert kein Funkgerät, sondern ein Schreibzugriff auf die eigene DB. `validate` prüft nur die Konfiguration, nicht die Existenz des Helfers: ein Deploy soll nicht daran scheitern, dass der Helfer im Moment des Deploys noch fehlt. Audit `helper.set`
- Träger sind zwei Helfer (`INPUT_BOOLEAN`, `MANUAL`): `input_boolean.manual_waschmaschine_fertig` und `input_boolean.manual_spuelmaschine_fertig`, beide mit Kachel-Sichtbarkeit `NEVER` für `switches` — sonst stünden sie zusätzlich als gewöhnliche Schalter auf dem Dashboard
- **Ein zweiter Trigger je Flow (Leistung > 50 W) setzt den Helfer wieder `off`.** Die Karte verschwindet also auch von selbst, sobald wieder gewaschen wird. Dieser Zweig hängt bewusst **nicht** am Rate-Limit-Node: das entprellt die Ansage, nicht das Zurücksetzen der Karte
- Frontend: `shared/appliance-insight.util.ts` ist die einzige Definition der überwachten Helfer (feste Liste Entity-ID → Titel/Icon, Muster `door-insight.util.ts`); `HubInsight.dismissEntityId` macht genau diese Karten antippbar, alle übrigen Hub-Karten bleiben reine Anzeige. Die Zeitformatierung („… seit 17:46 Uhr.") teilen sich Tür- und Maschinenkarte über `shared/insight-time.util.ts`
- **Das Wegtippen löst den Helfer vorher aus dem aktuellen Stand neu auf** und schaltet nur, wenn er dort noch `on` ist: der Endpunkt `POST /v1/switches/{id}/toggle` ist ein *Toggle* und die Liste bis zu 30 s alt — ohne die Prüfung schaltete ein Klick auf eine anderswo abgeräumte Karte den Helfer wieder **ein** (dieselbe Regel wie `confirmToggle`). Ein fehlgeschlagenes Schalten lässt die Karte stehen
- Sicherheit unverändert: Lesen über die generische `GET /v1/**`-Regel, Wegtippen über den bereits in der KIOSK-POST-Whitelist stehenden Toggle — die Karte funktioniert damit auch auf dem Wandtablet
- **Wer einen Helfer löscht und neu anlegt, verliert dessen Kachel-Sichtbarkeitsregel** (die Entity-ID entsteht wieder gleich, die Regel nicht) — er taucht dann als Schalter im Dashboard auf. Umbenennen ist dagegen gefahrlos, `ManualEntityService.rename` lässt die ID stehen
- **Bekannte Grenze:** 5 W / 600 s und 50 W sind Startschätzungen. Eine Einweich- oder Trockenpause über 10 Minuten unter 5 W meldet „fertig" zu früh — das war schon vor der Karte so und wird durch sie nur sichtbarer. Nachziehen geht ohne Redeploy über flow-mcp
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: fertige Maschinen im Intelligence Hub dokumentiert"
```

---

## Task 7: Rollout (kein Code — Reihenfolge ist zwingend)

Diese Schritte laufen gegen die **laufende Umgebung**. Vorher: Task 1–6 deployt (Backend **vor** Schritt 2, sonst kennt `flow_deploy` den Node-Typ `helper-set` nicht).

- [ ] **Step 1: Die beiden Helfer anlegen**

Auf der Helfer-Seite `/custom-entities` je einen Helfer vom Typ `INPUT_BOOLEAN` anlegen:

| Name                    | erwartete Entity-ID                          | Icon                    |
|-------------------------|----------------------------------------------|-------------------------|
| `Waschmaschine fertig`  | `input_boolean.manual_waschmaschine_fertig`  | `local_laundry_service` |
| `Spülmaschine fertig`   | `input_boolean.manual_spuelmaschine_fertig`  | `dishwasher_gen`        |

Prüfen, dass die IDs **exakt** so heißen — die Frontend-Util findet sie sonst nicht. Danach beide für die Kachel `switches` auf `NEVER` setzen (Schalter-Verwaltung im Dashboard bzw. `PUT /api/v1/entities/{entityId}/tiles/switches` mit `{"visibility":"NEVER"}`), sonst erscheinen sie zusätzlich als gewöhnliche Schalter.

- [ ] **Step 2: Flow #1 „Waschmaschine fertig" erweitern**

Über den flow-mcp-Server `flow_update` mit `id: 1` und dieser Definition, danach `flow_deploy` mit `id: 1`:

```json
{
  "nodes": [
    { "id": "trigger-power-low", "type": "entity-state-trigger", "name": "Leistung unter 5 W für 10 Min",
      "position": { "x": 80, "y": 120 },
      "config": { "entityId": "sensor.meross_2112156531504590863548e1e9817420_power", "operator": "<", "value": "5", "forSeconds": 600 } },
    { "id": "limit", "type": "rate-limit", "name": "Höchstens 1x pro Stunde",
      "position": { "x": 360, "y": 120 }, "config": { "minIntervalSeconds": 3600 } },
    { "id": "announce", "type": "alexa-announce", "name": "Ansage Küche + Wohnzimmer",
      "position": { "x": 640, "y": 40 },
      "config": { "text": "Die Waschmaschine ist fertig.", "mode": "ANNOUNCE", "deviceSerials": ["G0911M11045203UC", "G0911M1103260CQR"] } },
    { "id": "telegram", "type": "telegram-send", "name": "Telegram-Nachricht",
      "position": { "x": 640, "y": 160 }, "config": { "message": "🧺 Die Waschmaschine ist fertig." } },
    { "id": "helper-on", "type": "helper-set", "name": "Hub-Karte zeigen",
      "position": { "x": 640, "y": 280 },
      "config": { "entityId": "input_boolean.manual_waschmaschine_fertig", "action": "on" } },
    { "id": "trigger-power-high", "type": "entity-state-trigger", "name": "Waschgang gestartet (> 50 W)",
      "position": { "x": 80, "y": 400 },
      "config": { "entityId": "sensor.meross_2112156531504590863548e1e9817420_power", "operator": ">", "value": "50" } },
    { "id": "helper-off", "type": "helper-set", "name": "Hub-Karte abräumen",
      "position": { "x": 360, "y": 400 },
      "config": { "entityId": "input_boolean.manual_waschmaschine_fertig", "action": "off" } }
  ],
  "wires": [
    { "from": { "node": "trigger-power-low", "port": 0 }, "to": { "node": "limit" } },
    { "from": { "node": "limit", "port": 0 }, "to": { "node": "announce" } },
    { "from": { "node": "limit", "port": 0 }, "to": { "node": "telegram" } },
    { "from": { "node": "limit", "port": 0 }, "to": { "node": "helper-on" } },
    { "from": { "node": "trigger-power-high", "port": 0 }, "to": { "node": "helper-off" } }
  ]
}
```

- [ ] **Step 3: Flow „Spülmaschine fertig" anlegen**

`flow_create` mit Name `Spülmaschine fertig`, Beschreibung „Meldet per Alexa-Ansage und Telegram, wenn die Spülmaschine fertig ist (Leistung fällt nach dem Spülgang für 10 Minuten unter 5 W), und zeigt eine Karte im Intelligence Hub." und dieser Definition:

```json
{
  "nodes": [
    { "id": "trigger-power-low", "type": "entity-state-trigger", "name": "Leistung unter 5 W für 10 Min",
      "position": { "x": 80, "y": 120 },
      "config": { "entityId": "sensor.meross_2205060757549251080148e1e991c4f9_power", "operator": "<", "value": "5", "forSeconds": 600 } },
    { "id": "limit", "type": "rate-limit", "name": "Höchstens 1x pro Stunde",
      "position": { "x": 360, "y": 120 }, "config": { "minIntervalSeconds": 3600 } },
    { "id": "announce", "type": "alexa-announce", "name": "Ansage Küche + Wohnzimmer",
      "position": { "x": 640, "y": 40 },
      "config": { "text": "Die Spülmaschine ist fertig.", "mode": "ANNOUNCE", "deviceSerials": ["G0911M11045203UC", "G0911M1103260CQR"] } },
    { "id": "telegram", "type": "telegram-send", "name": "Telegram-Nachricht",
      "position": { "x": 640, "y": 160 }, "config": { "message": "🍽️ Die Spülmaschine ist fertig." } },
    { "id": "helper-on", "type": "helper-set", "name": "Hub-Karte zeigen",
      "position": { "x": 640, "y": 280 },
      "config": { "entityId": "input_boolean.manual_spuelmaschine_fertig", "action": "on" } },
    { "id": "trigger-power-high", "type": "entity-state-trigger", "name": "Spülgang gestartet (> 50 W)",
      "position": { "x": 80, "y": 400 },
      "config": { "entityId": "sensor.meross_2205060757549251080148e1e991c4f9_power", "operator": ">", "value": "50" } },
    { "id": "helper-off", "type": "helper-set", "name": "Hub-Karte abräumen",
      "position": { "x": 360, "y": 400 },
      "config": { "entityId": "input_boolean.manual_spuelmaschine_fertig", "action": "off" } }
  ],
  "wires": [
    { "from": { "node": "trigger-power-low", "port": 0 }, "to": { "node": "limit" } },
    { "from": { "node": "limit", "port": 0 }, "to": { "node": "announce" } },
    { "from": { "node": "limit", "port": 0 }, "to": { "node": "telegram" } },
    { "from": { "node": "limit", "port": 0 }, "to": { "node": "helper-on" } },
    { "from": { "node": "trigger-power-high", "port": 0 }, "to": { "node": "helper-off" } }
  ]
}
```

Danach `flow_deploy` (liefert das ValidationResult — muss fehlerfrei sein) und `flow_set_enabled` mit `enabled: true`.

- [ ] **Step 4: Wirkung prüfen**

`flow_inject` auf den Node `helper-on` von Flow #1 auslösen und danach prüfen:
- `flow_list_entities` mit `domain: input_boolean` zeigt `input_boolean.manual_waschmaschine_fertig` mit State `on`.
- Das Dashboard zeigt spätestens nach 30 s die Karte „Waschmaschine fertig".
- Ein Antippen der Karte lässt sie verschwinden; der Helfer steht danach auf `off`.

- [ ] **Step 5: Zwei Beobachtungspunkte für die nächsten Waschgänge notieren**

Nach dem ersten echten Waschgang prüfen, ob die Karte zum richtigen Zeitpunkt kam (Einweichpause → `forSeconds` erhöhen) und ob sie beim Start des nächsten Gangs verschwand (Startleistung unter 50 W → Schwelle senken). Beides ist über flow-mcp ohne Redeploy änderbar.

---

## Nicht Teil dieses Plans

- Kein Trockner und keine weiteren Geräte (kein passender Leistungssensor in PROD).
- Keine Erinnerung, wenn die Wäsche liegen bleibt — die Karte bleibt stehen, sie mahnt nicht.
- Keine Push-Benachrichtigung; Alexa und Telegram bleiben, wie sie sind.
