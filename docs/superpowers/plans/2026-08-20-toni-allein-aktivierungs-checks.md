# Aktivierungs-Checks für „Toni allein" und „Abwesend" — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beim Einschalten der Modi „Toni allein" und „Abwesend" zeigt das Dashboard einen Check-Dialog (Fenster/Türen zu? Großverbraucher ≥ 50 W?), der warnt, aber nicht blockiert; erst „Aktivieren" schaltet den Modus.

**Architecture:** Rein Frontend (Ansatz A der Spec `docs/superpowers/specs/2026-08-20-toni-allein-aktivierungs-checks-design.md`). Die Check-Logik ist ein pures Util in `shared/` (Muster `door-insight.util.ts`), die Dialog-Steuerung liegt im `DashboardComponent`, das Markup direkt in `dashboard.component.html` (lumina-Styles sind dort gekapselt — niemals in eine Kind-Komponente auslagern, sie renderte lautlos ungestylt).

**Tech Stack:** Angular 19 standalone, RxJS, Jasmine/Karma. Kein Backend-Code, keine Migration.

**Branch:** `feature/toni-allein-checks` (existiert bereits, enthält die Spec).

**Wichtige Projekt-Eigenheiten:**
- Frontend-Tests headless: `npm test -- --watch=false --browsers=ChromeHeadless` (aus `frontend/`). **Baseline: 3 vorbestehende Fails** (`AppComponent` ×2, `HeroComponent`), gelegentlich eine `SmartDeviceListComponent`-Karma-Flake als vierter — nur *zusätzliche* Fails sind Regressionen.
- `dashboard.component.scss` liegt nahe am `anyComponentStyle`-Budget (Fehlergrenze 32 kB in `frontend/angular.json`). Ein Budget-ERROR beim Prod-Build ist Größenpolizei, kein Code-Fehler — dann Budget anheben.
- Zigbee-Kontakte: HA-Semantik `on` = **offen** (siehe `ZigbeeEntityMapper`), `deviceClass` ist `door` (auch für Fensterkontakte).

---

### Task 1: Check-Util `mode-activation-check.util.ts`

Pure Funktionen, die aus den API-Antworten die Check-Ergebnisse bauen. Keine Angular-Abhängigkeiten außer den Model-Interfaces.

**Files:**
- Create: `frontend/src/app/shared/mode-activation-check.util.ts`
- Test: `frontend/src/app/shared/mode-activation-check.util.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

`frontend/src/app/shared/mode-activation-check.util.spec.ts`:

```ts
import { EntityState } from '../models/entity-state.model';
import { PowerConsumer } from '../models/power-consumer.model';
import {
  buildConsumerCheck,
  buildContactCheck,
  failedCheck,
  loadingCheck
} from './mode-activation-check.util';

describe('mode-activation-check.util', () => {

  const contact = (
    entityId: string,
    state: string,
    overrides: Partial<EntityState> = {}
  ): EntityState => ({
    entityId,
    domain: 'BINARY_SENSOR',
    source: 'ZIGBEE',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state,
    attributes: { deviceClass: 'door' },
    lastChanged: '2026-08-20T10:00:00Z',
    lastUpdated: '2026-08-20T10:00:00Z',
    ...overrides
  });

  const consumer = (
    displayName: string,
    powerWatts: number | null
  ): PowerConsumer => ({
    entityId: `sensor.meross_${displayName.toLowerCase()}_power`,
    displayName,
    powerWatts,
    unavailable: powerWatts == null
  });

  describe('buildContactCheck', () => {
    it('meldet OK mit Sammelzeile, wenn alle Kontakte geschlossen sind', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'off', { displayName: 'Küche Kontakt' }),
        contact('binary_sensor.zigbee_bad_contact', 'off', { displayName: 'Bad Kontakt' })
      ]);
      expect(check.status).toBe('ok');
      expect(check.lines).toEqual(['Alle Fenster und Türen geschlossen (2 Kontakte).']);
    });

    it('nutzt bei genau einem Kontakt den Singular', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'off')
      ]);
      expect(check.lines).toEqual(['Alle Fenster und Türen geschlossen (1 Kontakt).']);
    });

    it('warnt je offenem Kontakt mit dessen Anzeigenamen', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'on', { displayName: 'Küche Kontakt' }),
        contact('binary_sensor.zigbee_bad_contact', 'off', { displayName: 'Bad Kontakt' })
      ]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Küche Kontakt ist offen.']);
    });

    it('wertet unavailable als "Zustand unbekannt", nicht als geschlossen', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'unavailable', { displayName: 'Küche Kontakt' })
      ]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Küche Kontakt: Zustand unbekannt.']);
    });

    it('ignoriert Entitäten ohne deviceClass door', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_flur_occupancy', 'on', {
          displayName: 'Flur Bewegung',
          attributes: { deviceClass: 'motion' }
        }),
        contact('binary_sensor.zigbee_kueche_contact', 'off')
      ]);
      expect(check.status).toBe('ok');
      expect(check.lines).toEqual(['Alle Fenster und Türen geschlossen (1 Kontakt).']);
    });

    it('warnt bei leerer Kontaktliste, statt grün zu melden', () => {
      const check = buildContactCheck([]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Keine Fenster-/Türkontakte gefunden.']);
    });
  });

  describe('buildConsumerCheck', () => {
    it('meldet OK, wenn kein Verbraucher die Schwelle erreicht', () => {
      const check = buildConsumerCheck([consumer('Kühlschrank', 49.9)]);
      expect(check.status).toBe('ok');
      expect(check.lines).toEqual(['Keine Großverbraucher aktiv.']);
    });

    it('warnt ab 50 W mit Name und gerundeter Wattzahl', () => {
      const check = buildConsumerCheck([
        consumer('Waschmaschine', 1234.5),
        consumer('Kühlschrank', 30)
      ]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Waschmaschine: 1235 W']);
    });

    it('ignoriert Verbraucher ohne Messwert (powerWatts null)', () => {
      const check = buildConsumerCheck([consumer('Offline-Steckdose', null)]);
      expect(check.status).toBe('ok');
    });

    it('meldet bei leerer Verbraucherliste OK', () => {
      expect(buildConsumerCheck([]).status).toBe('ok');
    });
  });

  describe('Fabriken', () => {
    it('loadingCheck hat Status loading ohne Zeilen', () => {
      expect(loadingCheck()).toEqual({ status: 'loading', lines: [] });
    });

    it('failedCheck ist eine Warnung mit Fehlertext', () => {
      expect(failedCheck()).toEqual({ status: 'warning', lines: ['Prüfung fehlgeschlagen.'] });
    });
  });
});
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Aus `frontend/`:

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/mode-activation-check.util.spec.ts'
```

Erwartet: FAIL / Kompilierfehler („Cannot find module './mode-activation-check.util'").

- [ ] **Step 3: Util implementieren**

`frontend/src/app/shared/mode-activation-check.util.ts`:

```ts
import { EntityState } from '../models/entity-state.model';
import { PowerConsumer } from '../models/power-consumer.model';

/**
 * Aktivierungs-Checks für die bewachten Haus-Modi („Toni allein", „Abwesend"):
 * pure Auswertung der bereits vorhandenen API-Antworten. Reiner UI-Schutz —
 * warnt, blockiert aber nie (Spec 2026-08-20-toni-allein-aktivierungs-checks).
 */

/** Schwelle, ab der ein Verbraucher als Großverbraucher gemeldet wird. */
export const HIGH_CONSUMPTION_THRESHOLD_WATTS = 50;

export type ActivationCheckStatus = 'loading' | 'ok' | 'warning';

/** Anzeigezustand eines Checks: bei 'ok' genau eine Sammelzeile, sonst eine Zeile je Problem. */
export interface ActivationCheck {
  status: ActivationCheckStatus;
  lines: string[];
}

export function loadingCheck(): ActivationCheck {
  return { status: 'loading', lines: [] };
}

/** Ergebnis eines fehlgeschlagenen Check-Requests — Warnung, kein OK und kein Blocker. */
export function failedCheck(): ActivationCheck {
  return { status: 'warning', lines: ['Prüfung fehlgeschlagen.'] };
}

/**
 * Fenster-&-Türen-Check über die Zigbee-Kontakte (deviceClass "door", on = offen).
 * `unavailable` zählt als Warnung — ein toter Sensor beweist nicht, dass zu ist.
 * Eine leere Kontaktliste (z. B. Zigbee-Ausfall) ist ebenfalls eine Warnung.
 */
export function buildContactCheck(entities: EntityState[]): ActivationCheck {
  const contacts = entities.filter(entity => entity.attributes?.['deviceClass'] === 'door');
  if (contacts.length === 0) {
    return { status: 'warning', lines: ['Keine Fenster-/Türkontakte gefunden.'] };
  }
  const problems: string[] = [];
  for (const entity of contacts) {
    if (entity.state === 'on') {
      problems.push(`${entity.displayName} ist offen.`);
    } else if (entity.state !== 'off') {
      problems.push(`${entity.displayName}: Zustand unbekannt.`);
    }
  }
  if (problems.length > 0) {
    return { status: 'warning', lines: problems };
  }
  const label = contacts.length === 1 ? 'Kontakt' : 'Kontakte';
  return {
    status: 'ok',
    lines: [`Alle Fenster und Türen geschlossen (${contacts.length} ${label}).`]
  };
}

/**
 * Großverbraucher-Check: alles ab {@link HIGH_CONSUMPTION_THRESHOLD_WATTS}.
 * Verbraucher ohne Messwert werden bewusst ignoriert — eine offline Steckdose
 * versorgt ihr Gerät im Normalfall gar nicht (siehe Spec).
 */
export function buildConsumerCheck(consumers: PowerConsumer[]): ActivationCheck {
  const active = consumers.filter(
    consumer => consumer.powerWatts != null && consumer.powerWatts >= HIGH_CONSUMPTION_THRESHOLD_WATTS
  );
  if (active.length === 0) {
    return { status: 'ok', lines: ['Keine Großverbraucher aktiv.'] };
  }
  return {
    status: 'warning',
    lines: active.map(consumer => `${consumer.displayName}: ${Math.round(consumer.powerWatts!)} W`)
  };
}
```

- [ ] **Step 4: Tests laufen lassen — müssen grün sein**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/mode-activation-check.util.spec.ts'
```

Erwartet: alle Specs PASS (die 3-Fail-Baseline betrifft nur den Gesamtlauf, nicht diese Datei).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/mode-activation-check.util.ts frontend/src/app/shared/mode-activation-check.util.spec.ts
git commit -m "feat(dashboard): Check-Util fuer Modus-Aktivierungs-Checks"
```

---

### Task 2: Dialog-Steuerung im DashboardComponent

Weiche in `toggleMode`, Dialog-Zustand, Bestätigen/Abbrechen. Noch kein Markup — die Tests dieses Tasks prüfen nur Component-Zustand und Service-Aufrufe.

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (Weiche um Zeile 518 `toggleMode`, neue Felder/Methoden daneben; Import um Zeile 40 ergänzen)
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (neuer describe-Block am Dateiende, nach `DashboardComponent (Reboot-Button)`)

- [ ] **Step 1: Failing Tests schreiben**

Neuer describe-Block in `dashboard.component.spec.ts`. Benötigte Imports existieren oben in der Datei bereits (`ModeService`, `EntityStateService`, `ModeEntity`, `EntityState`, `of`, `throwError`, `fakeAsync`, `tick`, `discardPeriodicTasks`); zusätzlich `PowerConsumerService` und `PowerConsumer` importieren, falls noch nicht vorhanden:

```ts
import { PowerConsumerService } from '../../services/power-consumer.service';
import { PowerConsumer } from '../../models/power-consumer.model';
```

```ts
describe('DashboardComponent (Aktivierungs-Checks)', () => {
  let modeServiceSpy: jasmine.SpyObj<ModeService>;
  let entityStateServiceSpy: jasmine.SpyObj<EntityStateService>;
  let powerConsumerServiceSpy: jasmine.SpyObj<PowerConsumerService>;

  const toniAllein = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_toni_allein',
    displayName: 'Toni allein',
    icon: 'pets',
    state: 'off',
    ...overrides
  });

  const nachtmodus = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    ...overrides
  });

  const doorContact = (entityId: string, state: string, displayName: string): EntityState => ({
    entityId,
    domain: 'BINARY_SENSOR',
    source: 'ZIGBEE',
    sourceRef: entityId,
    friendlyName: displayName,
    displayName,
    state,
    attributes: { deviceClass: 'door' },
    lastChanged: '2026-08-20T10:00:00Z',
    lastUpdated: '2026-08-20T10:00:00Z'
  });

  const consumer = (displayName: string, powerWatts: number | null): PowerConsumer => ({
    entityId: `sensor.meross_${displayName.toLowerCase()}_power`,
    displayName,
    powerWatts,
    unavailable: powerWatts == null
  });

  beforeEach(async () => {
    modeServiceSpy = jasmine.createSpyObj('ModeService', ['getModes', 'toggle']);
    modeServiceSpy.getModes.and.returnValue(of([toniAllein(), nachtmodus()]));
    modeServiceSpy.toggle.and.returnValue(of(toniAllein({ state: 'on' })));

    entityStateServiceSpy = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityStateServiceSpy.getEntities.and.returnValue(of([]));

    powerConsumerServiceSpy = jasmine.createSpyObj('PowerConsumerService', ['getConsumers', 'getHistory']);
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

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
        { provide: ModeService, useValue: modeServiceSpy },
        { provide: EntityStateService, useValue: entityStateServiceSpy },
        { provide: PowerConsumerService, useValue: powerConsumerServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('oeffnet beim Einschalten eines bewachten Modus den Dialog statt zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckMode?.entityId).toBe('input_boolean.manual_toni_allein');
    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('schaltet einen bewachten Modus beim Ausschalten direkt, ohne Dialog', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([toniAllein({ state: 'on' })]));
    modeServiceSpy.toggle.and.returnValue(of(toniAllein({ state: 'off' })));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_toni_allein');

    discardPeriodicTasks();
  }));

  it('schaltet einen unbewachten Modus direkt in beide Richtungen', fakeAsync(() => {
    modeServiceSpy.toggle.and.returnValue(of(nachtmodus({ state: 'on' })));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[1]);
    tick();

    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_nachtmodus');

    discardPeriodicTasks();
  }));

  it('baut die Checks aus den API-Antworten (offene Tuer + Grossverbraucher)', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    entityStateServiceSpy.getEntities.and.returnValue(of([
      doorContact('binary_sensor.zigbee_kueche_contact', 'on', 'Küche Kontakt')
    ]));
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([consumer('Waschmaschine', 800)]));

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckContacts.status).toBe('warning');
    expect(fixture.componentInstance.modeCheckContacts.lines).toEqual(['Küche Kontakt ist offen.']);
    expect(fixture.componentInstance.modeCheckConsumers.status).toBe('warning');
    expect(fixture.componentInstance.modeCheckConsumers.lines).toEqual(['Waschmaschine: 800 W']);

    discardPeriodicTasks();
  }));

  it('zeigt bei einem fehlgeschlagenen Check-Request eine Warnung, der andere Check bleibt unberuehrt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    entityStateServiceSpy.getEntities.and.returnValue(throwError(() => new Error('kaputt')));
    powerConsumerServiceSpy.getConsumers.and.returnValue(of([]));

    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    expect(fixture.componentInstance.modeCheckContacts.lines).toEqual(['Prüfung fehlgeschlagen.']);
    expect(fixture.componentInstance.modeCheckConsumers.status).toBe('ok');

    discardPeriodicTasks();
  }));

  it('schaltet beim Bestaetigen und schliesst den Dialog', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    fixture.componentInstance.confirmModeActivation();
    tick();

    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_toni_allein');
    expect(fixture.componentInstance.modeCheckMode).toBeNull();
    expect(fixture.componentInstance.modes[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('schaltet beim Bestaetigen NICHT, wenn der Modus inzwischen an ist', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    // Hintergrund-Refresh hat den Modus inzwischen eingeschaltet (z. B. via Telegram)
    fixture.componentInstance.modes[0].state = 'on';
    fixture.componentInstance.confirmModeActivation();
    tick();

    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.modeCheckMode).toBeNull();

    discardPeriodicTasks();
  }));

  it('schaltet beim Abbrechen nicht', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
    tick();

    fixture.componentInstance.closeModeCheckDialog();
    tick();

    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.modeCheckMode).toBeNull();

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartet: Kompilierfehler („Property 'modeCheckMode' does not exist …").

- [ ] **Step 3: Component-Logik implementieren**

In `frontend/src/app/pages/dashboard/dashboard.component.ts`:

**(a)** Import ergänzen (bei den anderen `shared/`-Imports, um Zeile 40):

```ts
import {
  ActivationCheck,
  buildConsumerCheck,
  buildContactCheck,
  failedCheck,
  loadingCheck
} from '../../shared/mode-activation-check.util';
```

**(b)** Konstante und Dialog-Zustand (bei den anderen Modus-Feldern, in der Nähe von `modes`/`pendingModeIds`):

```ts
/**
 * Modi mit Aktivierungs-Checks (Fenster/Türen, Großverbraucher): beim
 * Einschalten öffnet ein Dialog statt direkt zu schalten. Reiner UI-Schutz —
 * Telegram, Flows und API schalten unverändert direkt (Muster confirmRequired).
 */
private static readonly CHECKED_MODE_IDS = new Set([
  'input_boolean.manual_toni_allein',
  'input_boolean.manual_abwesend'
]);

/** Modus, für den der Aktivierungs-Check-Dialog offen ist; null = geschlossen. */
modeCheckMode: ModeEntity | null = null;
modeCheckContacts: ActivationCheck = loadingCheck();
modeCheckConsumers: ActivationCheck = loadingCheck();
```

**(c)** `toggleMode` (aktuell ab Zeile 518) umbauen — Weiche plus Extraktion der bestehenden Logik nach `performModeToggle`. Der Rumpf von `performModeToggle` ist der bisherige `toggleMode`-Rumpf, unverändert:

```ts
/**
 * Schaltet einen Haus-Modus. Beim Einschalten eines bewachten Modus
 * ("Toni allein", "Abwesend") öffnet stattdessen der Check-Dialog —
 * Ausschalten bleibt immer direkt.
 */
toggleMode(mode: ModeEntity): void {
  if (mode.state !== 'on' && DashboardComponent.CHECKED_MODE_IDS.has(mode.entityId)) {
    this.openModeCheckDialog(mode);
    return;
  }
  this.performModeToggle(mode);
}

/**
 * Führt den Toggle aus. Der Zustand wird optimistisch umgeschaltet und
 * bei einem Fehler zurueckgesetzt (gleiches Muster wie {@link toggleSwitch}).
 */
private performModeToggle(mode: ModeEntity): void {
  if (this.pendingModeIds.has(mode.entityId)) {
    return;
  }
  const previousState = mode.state;
  this.pendingModeIds.add(mode.entityId);
  this.modeError = null;
  this.applyModeState(mode.entityId, previousState === 'on' ? 'off' : 'on');

  this.modeService.toggle(mode.entityId).subscribe({
    next: updated => {
      this.pendingModeIds.delete(mode.entityId);
      this.applyModeState(updated.entityId, updated.state);
    },
    error: () => {
      this.pendingModeIds.delete(mode.entityId);
      this.applyModeState(mode.entityId, previousState);
      this.modeError = `${mode.displayName} konnte nicht geschaltet werden.`;
    }
  });
}
```

**(d)** Dialog-Methoden (direkt unter `performModeToggle`):

```ts
/** Öffnet den Check-Dialog und startet beide Prüfungen parallel. */
private openModeCheckDialog(mode: ModeEntity): void {
  this.modeCheckMode = mode;
  this.modeCheckContacts = loadingCheck();
  this.modeCheckConsumers = loadingCheck();

  this.entityStateService.getEntities('BINARY_SENSOR', 'ZIGBEE').subscribe({
    next: entities => this.modeCheckContacts = buildContactCheck(entities),
    error: () => this.modeCheckContacts = failedCheck()
  });
  this.powerConsumerService.getConsumers().subscribe({
    next: consumers => this.modeCheckConsumers = buildConsumerCheck(consumers),
    error: () => this.modeCheckConsumers = failedCheck()
  });
}

/**
 * Aktiviert den Modus aus dem Check-Dialog. Der Modus wird aus der aktuellen
 * Liste re-resolved (Muster confirmToggle): ist er inzwischen an — z. B. per
 * Telegram oder Flow, die Liste wird alle 30 s aufgefrischt — würde der Toggle
 * ihn ausgerechnet wieder ausschalten, also passiert dann nichts.
 */
confirmModeActivation(): void {
  const dialogMode = this.modeCheckMode;
  this.closeModeCheckDialog();
  if (!dialogMode) {
    return;
  }
  const current = this.modes.find(item => item.entityId === dialogMode.entityId);
  if (!current || current.state === 'on') {
    return;
  }
  this.performModeToggle(current);
}

closeModeCheckDialog(): void {
  this.modeCheckMode = null;
}

/** Material-Symbol für den Anzeige-Zustand eines Checks. */
modeCheckIcon(check: ActivationCheck): string {
  if (check.status === 'ok') {
    return 'check_circle';
  }
  return check.status === 'warning' ? 'warning' : 'hourglass_empty';
}
```

- [ ] **Step 4: Tests laufen lassen — müssen grün sein**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartet: alle neuen Specs PASS, die bestehenden Modus-Leiste-Specs weiterhin PASS (die Weiche darf `input_boolean.manual_nachtmodus` nicht berühren).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): Aktivierungs-Checks fuer Toni allein und Abwesend"
```

---

### Task 3: Dialog-Markup und Styles

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (neuer Dialog nach dem Neustart-Hinweis-Dialog, aktuell um Zeile 793, vor dem Spaziergänge-Dialog)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (neuer Block hinter `.lumina__confirm-go`, aktuell um Zeile 1605)
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (DOM-Tests im describe-Block aus Task 2)

- [ ] **Step 1: Failing DOM-Tests schreiben**

Im describe-Block `DashboardComponent (Aktivierungs-Checks)` ergänzen:

```ts
it('rendert den Dialog mit beiden Checks und Warnzeilen', fakeAsync(() => {
  const fixture = TestBed.createComponent(DashboardComponent);
  fixture.detectChanges();
  entityStateServiceSpy.getEntities.and.returnValue(of([
    doorContact('binary_sensor.zigbee_kueche_contact', 'on', 'Küche Kontakt')
  ]));
  powerConsumerServiceSpy.getConsumers.and.returnValue(of([consumer('Waschmaschine', 800)]));

  fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
  tick();
  fixture.detectChanges();

  const host = fixture.nativeElement as HTMLElement;
  const dialog = host.querySelector('.lumina__dialog--modecheck');
  expect(dialog?.textContent).toContain('Toni allein aktivieren?');
  expect(dialog?.textContent).toContain('Küche Kontakt ist offen.');
  expect(dialog?.textContent).toContain('Waschmaschine: 800 W');
  const sections = host.querySelectorAll('.lumina__check[data-status="warning"]');
  expect(sections.length).toBe(2);

  discardPeriodicTasks();
}));

it('aktiviert ueber den Dialog-Button', fakeAsync(() => {
  const fixture = TestBed.createComponent(DashboardComponent);
  fixture.detectChanges();
  fixture.componentInstance.toggleMode(fixture.componentInstance.modes[0]);
  tick();
  fixture.detectChanges();

  const button = (fixture.nativeElement as HTMLElement)
    .querySelector('.lumina__confirm-go--mode') as HTMLButtonElement;
  button.click();
  tick();

  expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_toni_allein');

  discardPeriodicTasks();
}));
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartet: FAIL (Dialog-Element nicht im DOM).

- [ ] **Step 3: Markup einfügen**

In `dashboard.component.html`, nach dem schließenden `</div>` des Neustart-Hinweis-Dialogs (`rebootInProgress`, um Zeile 793) und vor dem Spaziergänge-Dialog:

```html
  <!-- Aktivierungs-Checks fuer bewachte Modi (Toni allein / Abwesend): warnt, blockiert nicht -->
  <div
    *ngIf="modeCheckMode"
    class="lumina__dialog-backdrop"
    (click)="closeModeCheckDialog()"
  >
    <div
      class="lumina__dialog lumina__dialog--confirm lumina__dialog--modecheck"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="modeCheckMode.displayName + ' aktivieren'"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">{{ modeCheckMode.displayName }} aktivieren?</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closeModeCheckDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <section class="lumina__check" [attr.data-status]="modeCheckContacts.status">
          <h3 class="lumina__check-title">
            <span class="material-symbols-outlined">{{ modeCheckIcon(modeCheckContacts) }}</span>
            Fenster &amp; Türen
          </h3>
          <p *ngIf="modeCheckContacts.status === 'loading'" class="lumina__check-line">Wird geprüft …</p>
          <p *ngFor="let line of modeCheckContacts.lines" class="lumina__check-line">{{ line }}</p>
        </section>
        <section class="lumina__check" [attr.data-status]="modeCheckConsumers.status">
          <h3 class="lumina__check-title">
            <span class="material-symbols-outlined">{{ modeCheckIcon(modeCheckConsumers) }}</span>
            Großverbraucher
          </h3>
          <p *ngIf="modeCheckConsumers.status === 'loading'" class="lumina__check-line">Wird geprüft …</p>
          <p *ngFor="let line of modeCheckConsumers.lines" class="lumina__check-line">{{ line }}</p>
        </section>
        <button type="button" class="lumina__confirm-go lumina__confirm-go--mode" (click)="confirmModeActivation()">
          Aktivieren
        </button>
        <button type="button" class="lumina__confirm-cancel" (click)="closeModeCheckDialog()">
          Abbrechen
        </button>
      </div>
    </div>
  </div>
```

- [ ] **Step 4: Styles ergänzen**

In `dashboard.component.scss`, direkt hinter dem `.lumina__confirm-go`-Block (um Zeile 1605). Bewusst klein gehalten — die Datei steht nahe am `anyComponentStyle`-Budget:

```scss
// Aktivierungs-Checks der bewachten Modi (Toni allein / Abwesend)
.lumina__check {
  margin-bottom: 0.75rem;
  padding: 0.6rem 0.75rem;
  border-radius: 0.75rem;
  background: rgba(15, 23, 42, 0.04);

  &[data-status='ok'] .lumina__check-title .material-symbols-outlined {
    color: #16a34a;
  }

  &[data-status='warning'] .lumina__check-title .material-symbols-outlined {
    color: #d97706;
  }

  &[data-status='warning'] .lumina__check-line {
    color: #b45309;
  }
}

.lumina__check-title {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  margin: 0 0 0.35rem;
  font-size: 0.95rem;
  font-weight: 600;
  color: #0f172a;
}

.lumina__check-line {
  margin: 0.15rem 0 0;
  font-size: 0.9rem;
  color: #475569;
}

// Aktivieren ist keine destruktive Aktion: gruen statt des roten confirm-go
.lumina__confirm-go--mode {
  background: #16a34a;

  &:hover {
    background: #15803d;
  }
}
```

- [ ] **Step 5: Tests laufen lassen — müssen grün sein**

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartet: alle Specs der Datei PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.scss frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): Dialog-Markup fuer Aktivierungs-Checks"
```

---

### Task 4: Gesamtlauf und Prod-Build

**Files:** keine neuen; ggf. Modify: `frontend/angular.json` (nur falls Budget-ERROR)

- [ ] **Step 1: Kompletten Frontend-Testlauf ausführen**

Aus `frontend/`:

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: **genau 3 FAILED** (Baseline: `AppComponent` ×2, `HeroComponent`), Rest grün. Ein vierter Fail `SmartDeviceListComponent … afterAll` ist eine bekannte Karma-Flake → Lauf wiederholen. Jeder andere zusätzliche Fail ist eine Regression dieses Features.

- [ ] **Step 2: Produktions-Build prüfen (SCSS-Budget!)**

```bash
ng build --configuration production
```

Erwartet: Build OK. Die 16-kB-**Warnung** zu `dashboard.component.scss` und die Leaflet-CommonJS-Warnung sind erwartete Baseline. Bricht der Build mit Budget-**ERROR** für `anyComponentStyle` ab: in `frontend/angular.json` die `maximumError`-Grenze des `anyComponentStyle`-Budgets moderat anheben (aktuell 32 kB, z. B. auf 36 kB) und diese Änderung mit committen:

```bash
git add frontend/angular.json
git commit -m "build: anyComponentStyle-Budget fuer Dashboard-Styles anheben"
```

- [ ] **Step 3: Abschluss**

Arbeitsstand prüfen (`git status` sauber bis auf Unbeteiligtes, `git log --oneline` zeigt die Task-Commits). Danach die Skill `superpowers:finishing-a-development-branch` für Merge/PR-Entscheidung nutzen.

---

## Verifikation gegen die Spec

| Spec-Anforderung | Task |
| --- | --- |
| Weiche nur beim Einschalten, nur bewachte Modi | Task 2 |
| Kontakt-Check (deviceClass door, on/unavailable/leer = Warnung) | Task 1 |
| Verbraucher-Check (≥ 50 W, null ignoriert) | Task 1 |
| Warnen statt blockieren (Aktivieren immer möglich, auch bei Fehler/Laden) | Task 2 + 3 (Button ohne disabled) |
| Re-Resolve beim Bestätigen, kein Toggle wenn schon an | Task 2 |
| Fehler eines Requests isoliert | Task 2 |
| Markup direkt im Dashboard (lumina-Kapselung) | Task 3 |
| Kein Backend, keine Security-Änderung | — (nichts zu tun) |
