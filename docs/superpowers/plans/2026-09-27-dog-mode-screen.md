# Dog-Mode-Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Solange „Toni allein" an ist, zeigt das Wandtablet (Tablet-Ansicht) statt des Dashboards einen Vollbild-Screen wie der Tesla-Dog-Mode: Hunde-Illustration links, Uhrzeit groß, Innentemperatur groß, Außentemperatur klein, Knopf „Toni allein beenden".

**Architecture:** Reines Frontend. Eine Util (`shared/dog-mode.util.ts`) ist die einzige Definition von „Screen aktiv" und „welche Temperaturen". Eine Standalone-Komponente (`components/dog-mode-screen/`) mit eigenem SCSS rendert den Screen, Farben nur über die lumina-Theme-Tokens (per CSS-Vererbung). Das Dashboard bindet sie ein und liefert die Daten aus den ohnehin laufenden Abfragen (Modi 30 s, Temperaturen) sowie `clockTime`.

**Tech Stack:** Angular 19 standalone, SCSS, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-09-27-dog-mode-screen-design.md`

**Hinweise für die Ausführung:**
- Alle Befehle aus `frontend/`. Tests headless: `npm test -- --watch=false --browsers=ChromeHeadless` (optional `--include <pfad>`).
- **Test-Baseline:** 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent should create`) plus gelegentlich ein Karma-Flake in `SmartDeviceListComponent`. Nur zusätzliche Fails sind Regressionen.
- PowerShell-Commits: Commit-Message ohne Anführungszeichen im Text oder per `-F <datei>`.
- Commit-Footer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

---

## File Structure

| Datei | Aktion | Verantwortung |
|---|---|---|
| `frontend/src/app/shared/temperature-comfort.util.ts` | Modify | `isStale` → exportiertes `isReadingStale` (eine Schwelle, 60 min) |
| `frontend/src/app/shared/dog-mode.util.ts` | Create | Konstanten, `isDogModeActive`, `buildDogModeClimate` |
| `frontend/src/app/shared/dog-mode.util.spec.ts` | Create | Tests der Util |
| `frontend/src/assets/dog-mode/dog.svg` | Create | Hunde-Illustration (lokal) |
| `frontend/src/app/components/dog-mode-screen/dog-mode-screen.component.ts/.html/.scss` | Create | Vollbild-Screen |
| `frontend/src/app/components/dog-mode-screen/dog-mode-screen.component.spec.ts` | Create | Komponententests |
| `frontend/src/app/pages/dashboard/dashboard.component.ts/.html` | Modify | Einbindung, `dogModeVisible`, `dogModeClimate`, `endDogMode()` |
| `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` | Modify | neue Suite „Dog-Mode-Screen" |
| `CLAUDE.md` | Modify | Abschnitt zum Feature |

---

### Task 1: `isReadingStale` exportieren

**Files:**
- Modify: `frontend/src/app/shared/temperature-comfort.util.ts:165,171,183`

- [ ] **Step 1: Umbenennen und exportieren**

In `temperature-comfort.util.ts` die private Funktion (Zeile 183) ersetzen:

```ts
/**
 * Messung gilt als veraltet, wenn aelter als STALE_THRESHOLD_MS. Einzige Definition —
 * die Klima-Kachel und der Dog-Mode-Screen fragen dieselbe Funktion.
 */
export function isReadingStale(reading: CurrentTemperatureReading, nowMs: number): boolean {
  return nowMs - new Date(reading.measuredAt).getTime() > STALE_THRESHOLD_MS;
}
```

und die drei Aufrufe `isStale(reading, nowMs)` in `buildSensorDetail`, `toOutdoorReading` und `toRow` auf `isReadingStale(reading, nowMs)` umstellen.

- [ ] **Step 2: Bestehende Tests laufen lassen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/shared/temperature-comfort.util.spec.ts`
Expected: alle PASS (reines Umbenennen).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/shared/temperature-comfort.util.ts
git commit -m "refactor(temperatur): isReadingStale exportieren" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `dog-mode.util.ts`

**Files:**
- Create: `frontend/src/app/shared/dog-mode.util.ts`
- Test: `frontend/src/app/shared/dog-mode.util.spec.ts`

- [ ] **Step 1: Failing Test schreiben**

`frontend/src/app/shared/dog-mode.util.spec.ts`:

```ts
import { CurrentTemperatureReading } from '../models/temperature.model';
import { ModeEntity } from '../models/mode.model';
import { DOG_MODE_ENTITY_ID, buildDogModeClimate, isDogModeActive } from './dog-mode.util';

describe('dog-mode.util', () => {
  const now = new Date('2026-09-27T18:00:00').getTime();
  const minutesAgo = (minutes: number): string => new Date(now - minutes * 60_000).toISOString();

  const mode = (entityId: string, state: string): ModeEntity =>
    ({ entityId, displayName: entityId, icon: 'pets', state });

  const reading = (overrides: Partial<CurrentTemperatureReading>): CurrentTemperatureReading => ({
    sensorId: 'zigbee:1',
    name: 'Büro',
    source: 'ZIGBEE',
    temperature: 21,
    measuredAt: minutesAgo(5),
    ...overrides
  });

  describe('isDogModeActive', () => {
    it('ist aktiv, wenn Toni allein an ist', () => {
      expect(isDogModeActive([mode('input_boolean.manual_nachtmodus', 'off'), mode(DOG_MODE_ENTITY_ID, 'on')]))
        .toBeTrue();
    });

    it('ist inaktiv, wenn Toni allein aus ist', () => {
      expect(isDogModeActive([mode(DOG_MODE_ENTITY_ID, 'off')])).toBeFalse();
    });

    it('ist inaktiv, wenn Toni allein nicht in der Liste steht — nie geraten', () => {
      expect(isDogModeActive([])).toBeFalse();
      expect(isDogModeActive([mode('input_boolean.manual_nachtmodus', 'on')])).toBeFalse();
    });
  });

  describe('buildDogModeClimate', () => {
    it('findet den Wohnzimmer-Monitor ueber die sensorId, unabhaengig von Name und Schreibweise', () => {
      const climate = buildDogModeClimate([
        reading({ sensorId: 'zigbee:7', name: 'Wohnzimmer', temperature: 30 }),
        reading({ sensorId: 'alexa:GAJ2300425330047', name: 'Irgendein Name', source: 'ALEXA', temperature: 22.4 })
      ], now);

      expect(climate.indoor).toEqual({ label: '22°', stale: false });
    });

    it('rundet auf ganze Grad', () => {
      const climate = buildDogModeClimate(
        [reading({ sensorId: 'alexa:gaj2300425330047', source: 'ALEXA', temperature: 22.6 })], now);

      expect(climate.indoor?.label).toBe('23°');
    });

    it('markiert einen Innenwert aelter als 60 Minuten als veraltet', () => {
      const climate = buildDogModeClimate([reading({
        sensorId: 'alexa:GAJ2300425330047', source: 'ALEXA', measuredAt: minutesAgo(61)
      })], now);

      expect(climate.indoor?.stale).toBeTrue();
    });

    it('liefert indoor null ohne Wohnzimmer-Messung', () => {
      expect(buildDogModeClimate([reading({})], now).indoor).toBeNull();
    });

    it('nimmt den realen Aussenfuehler vor dem DWD-Wert', () => {
      const climate = buildDogModeClimate([
        reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 12 }),
        reading({ sensorId: 'zigbee:3', name: 'Temperatur Aqara Garten', temperature: 14.4 })
      ], now);

      expect(climate.outdoorLabel).toBe('14°');
    });

    it('faellt ohne realen Aussenfuehler auf den DWD-Wert zurueck', () => {
      const climate = buildDogModeClimate(
        [reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 11.6 })], now);

      expect(climate.outdoorLabel).toBe('12°');
    });

    it('liefert outdoorLabel null ohne jeden Aussenwert', () => {
      expect(buildDogModeClimate([reading({})], now).outdoorLabel).toBeNull();
    });
  });
});
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/shared/dog-mode.util.spec.ts`
Expected: FAIL / Kompilierfehler „Cannot find module './dog-mode.util'".

- [ ] **Step 3: Implementierung**

`frontend/src/app/shared/dog-mode.util.ts`:

```ts
import { CurrentTemperatureReading } from '../models/temperature.model';
import { ModeEntity } from '../models/mode.model';
import { buildClimateView, isReadingStale } from './temperature-comfort.util';

/**
 * Dog-Mode-Screen des Wandtablets (Muster Tesla-Dog-Mode): solange „Toni allein" an ist,
 * ersetzt ein Vollbild mit Uhr und Temperatur das Dashboard. Einzige Definition, wann
 * der Screen steht und welche Werte er zeigt.
 */
export const DOG_MODE_ENTITY_ID = 'input_boolean.manual_toni_allein';

/**
 * Wohnzimmer = Amazon-Luftqualitaetsmonitor (Hardware-Seriennummer). Bewusst die
 * sensorId statt des Anzeigenamens: die Temperatur-API liefert den Custom-Namen, ein
 * Umbenennen braeche eine Namenspruefung still. Vergleich ohne Gross-/Kleinschreibung,
 * weil die Entity-ID die Seriennummer kleingeschrieben fuehrt.
 */
export const DOG_MODE_INDOOR_SENSOR_ID = 'alexa:gaj2300425330047';

export interface DogModeIndoor {
  /** Ganze Grad, z. B. "22°". */
  label: string;
  stale: boolean;
}

export interface DogModeClimate {
  indoor: DogModeIndoor | null;
  /** Ganze Grad, z. B. "14°", oder null ohne jeden Aussenwert. */
  outdoorLabel: string | null;
}

export const EMPTY_DOG_MODE_CLIMATE: DogModeClimate = { indoor: null, outdoorLabel: null };

/** Aktiv nur bei explizitem "on" — fehlt der Modus in der Liste, wird nicht geraten. */
export function isDogModeActive(modes: readonly ModeEntity[]): boolean {
  return modes.some(mode => mode.entityId === DOG_MODE_ENTITY_ID && mode.state === 'on');
}

export function buildDogModeClimate(readings: CurrentTemperatureReading[], nowMs: number): DogModeClimate {
  const indoorReading = readings.find(
    reading => reading.sensorId.toLowerCase() === DOG_MODE_INDOOR_SENSOR_ID);
  const indoor = indoorReading
    ? { label: wholeDegrees(indoorReading.temperature), stale: isReadingStale(indoorReading, nowMs) }
    : null;

  const outdoor = findOutdoorReading(readings, nowMs);
  return { indoor, outdoorLabel: outdoor ? wholeDegrees(outdoor.temperature) : null };
}

/** Realer Aussenfuehler (Erkennung wie in der Klima-Kachel), sonst der DWD-Wert. */
function findOutdoorReading(
  readings: CurrentTemperatureReading[],
  nowMs: number
): CurrentTemperatureReading | undefined {
  const primaryOutdoorId = buildClimateView(readings, nowMs).outdoor[0]?.sensorId;
  return readings.find(reading => reading.sensorId === primaryOutdoorId)
    ?? readings.find(reading => reading.source === 'WEATHER');
}

function wholeDegrees(celsius: number): string {
  return `${Math.round(celsius)}°`;
}
```

- [ ] **Step 4: Test laufen lassen, muss grün sein**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/shared/dog-mode.util.spec.ts`
Expected: alle PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/dog-mode.util.ts frontend/src/app/shared/dog-mode.util.spec.ts
git commit -m "feat(dashboard): Regeln fuer den Dog-Mode-Screen" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Hunde-Illustration

**Files:**
- Create: `frontend/src/assets/dog-mode/dog.svg`

- [ ] **Step 1: SVG anlegen**

`src/assets` wird laut `angular.json` ausgeliefert, die Datei ist danach unter `assets/dog-mode/dog.svg` erreichbar. Feste Farben — die Illustration sieht in beiden Designs gleich aus (wie die Tesla-Knöpfe).

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 240" role="img" aria-label="Hund">
  <!-- Schwanz -->
  <path d="M178 170 C205 160 214 132 204 116" fill="none" stroke="#b7793f" stroke-width="12" stroke-linecap="round"/>
  <!-- Koerper -->
  <ellipse cx="140" cy="170" rx="52" ry="42" fill="#d4945a"/>
  <!-- Vorderbeine -->
  <rect x="104" y="180" width="18" height="44" rx="9" fill="#c88649"/>
  <rect x="134" y="182" width="18" height="42" rx="9" fill="#c88649"/>
  <!-- Pfoten -->
  <ellipse cx="113" cy="224" rx="13" ry="7" fill="#f3d9b8"/>
  <ellipse cx="143" cy="224" rx="13" ry="7" fill="#f3d9b8"/>
  <!-- Brust -->
  <ellipse cx="116" cy="162" rx="22" ry="28" fill="#f3d9b8"/>
  <!-- Ohren -->
  <path d="M66 62 C44 70 38 112 56 128 C66 110 72 90 80 74 Z" fill="#8a5a2e"/>
  <path d="M146 62 C168 70 174 112 156 128 C146 110 140 90 132 74 Z" fill="#8a5a2e"/>
  <!-- Kopf -->
  <circle cx="106" cy="92" r="46" fill="#d4945a"/>
  <!-- Halsband -->
  <path d="M74 128 Q106 146 138 128" fill="none" stroke="#3b82f6" stroke-width="9" stroke-linecap="round"/>
  <circle cx="106" cy="142" r="7" fill="#fbbf24"/>
  <!-- Schnauze -->
  <ellipse cx="106" cy="112" rx="26" ry="19" fill="#f3d9b8"/>
  <ellipse cx="106" cy="102" rx="10" ry="7" fill="#2b1a0e"/>
  <path d="M106 109 V116 M106 116 Q98 123 92 118 M106 116 Q114 123 120 118" fill="none" stroke="#2b1a0e" stroke-width="3" stroke-linecap="round"/>
  <!-- Zunge -->
  <path d="M100 121 Q106 134 112 121 Z" fill="#f87171"/>
  <!-- Augen -->
  <circle cx="88" cy="82" r="6" fill="#2b1a0e"/>
  <circle cx="124" cy="82" r="6" fill="#2b1a0e"/>
  <circle cx="90" cy="80" r="2" fill="#ffffff"/>
  <circle cx="126" cy="80" r="2" fill="#ffffff"/>
</svg>
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/assets/dog-mode/dog.svg
git commit -m "feat(dashboard): Hunde-Illustration fuer den Dog-Mode-Screen" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `DogModeScreenComponent`

**Files:**
- Create: `frontend/src/app/components/dog-mode-screen/dog-mode-screen.component.ts`
- Create: `frontend/src/app/components/dog-mode-screen/dog-mode-screen.component.html`
- Create: `frontend/src/app/components/dog-mode-screen/dog-mode-screen.component.scss`
- Test: `frontend/src/app/components/dog-mode-screen/dog-mode-screen.component.spec.ts`

- [ ] **Step 1: Failing Test schreiben**

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DogModeScreenComponent } from './dog-mode-screen.component';
import { DogModeClimate } from '../../shared/dog-mode.util';

describe('DogModeScreenComponent', () => {
  let fixture: ComponentFixture<DogModeScreenComponent>;

  const render = (climate: DogModeClimate, extra: { busy?: boolean; error?: string | null } = {}): HTMLElement => {
    fixture.componentRef.setInput('clockTime', '18:42');
    fixture.componentRef.setInput('climate', climate);
    fixture.componentRef.setInput('busy', extra.busy ?? false);
    fixture.componentRef.setInput('error', extra.error ?? null);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DogModeScreenComponent] }).compileComponents();
    fixture = TestBed.createComponent(DogModeScreenComponent);
  });

  it('zeigt Uhrzeit, Innen- und Aussentemperatur', () => {
    const el = render({ indoor: { label: '22°', stale: false }, outdoorLabel: '14°' });

    expect(el.querySelector('.dog-mode__time')?.textContent?.trim()).toBe('18:42');
    expect(el.querySelector('.dog-mode__indoor-value')?.textContent?.trim()).toBe('22°');
    expect(el.querySelector('.dog-mode__outdoor')?.textContent).toContain('Draußen 14°');
    expect(el.querySelector('.dog-mode__image')?.getAttribute('src')).toBe('assets/dog-mode/dog.svg');
    expect(el.textContent).toContain('Toni ist allein zu Haus');
  });

  it('zeigt einen Strich ohne Innenwert', () => {
    const el = render({ indoor: null, outdoorLabel: '14°' });

    expect(el.querySelector('.dog-mode__indoor-value')?.textContent?.trim()).toBe('–');
  });

  it('dunkelt einen veralteten Innenwert ab', () => {
    const el = render({ indoor: { label: '22°', stale: true }, outdoorLabel: null });

    expect(el.querySelector('.dog-mode__indoor')?.classList).toContain('dog-mode__indoor--stale');
  });

  it('laesst die Aussenzeile ohne Aussenwert weg', () => {
    const el = render({ indoor: { label: '22°', stale: false }, outdoorLabel: null });

    expect(el.querySelector('.dog-mode__outdoor')).toBeNull();
  });

  it('meldet den Knopfdruck ueber endMode', () => {
    const el = render({ indoor: null, outdoorLabel: null });
    let emitted = 0;
    fixture.componentInstance.endMode.subscribe(() => emitted++);

    (el.querySelector('.dog-mode__end') as HTMLButtonElement).click();

    expect(emitted).toBe(1);
  });

  it('sperrt den Knopf waehrend der Aktion', () => {
    const el = render({ indoor: null, outdoorLabel: null }, { busy: true });

    expect((el.querySelector('.dog-mode__end') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('zeigt einen Fehlertext', () => {
    const el = render({ indoor: null, outdoorLabel: null }, { error: 'Toni allein konnte nicht geschaltet werden.' });

    expect(el.querySelector('.dog-mode__error')?.textContent).toContain('konnte nicht geschaltet werden');
  });
});
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/components/dog-mode-screen/dog-mode-screen.component.spec.ts`
Expected: FAIL (Modul fehlt).

- [ ] **Step 3: Komponente**

`dog-mode-screen.component.ts`:

```ts
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { DogModeClimate, EMPTY_DOG_MODE_CLIMATE } from '../../shared/dog-mode.util';

/**
 * Vollbild-Screen „Toni allein" (Muster Tesla-Dog-Mode). Reine Anzeige: Uhr und Werte
 * kommen vom Dashboard, das Beenden meldet der Knopf per endMode zurueck.
 *
 * Eigene Styles statt lumina-Klassen — die sind in dashboard.component.scss gekapselt
 * und griffen hier lautlos nicht. Farben nur ueber die Theme-Tokens, die vom
 * .lumina-Wurzelelement per CSS-Vererbung ankommen (hell und dunkel).
 */
@Component({
  selector: 'app-dog-mode-screen',
  standalone: true,
  templateUrl: './dog-mode-screen.component.html',
  styleUrls: ['./dog-mode-screen.component.scss']
})
export class DogModeScreenComponent {
  @Input({ required: true }) clockTime = '';
  @Input({ required: true }) climate: DogModeClimate = EMPTY_DOG_MODE_CLIMATE;
  @Input() busy = false;
  @Input() error: string | null = null;
  @Output() endMode = new EventEmitter<void>();
}
```

`dog-mode-screen.component.html`:

```html
<section class="dog-mode" aria-label="Toni ist allein zu Haus">
  <div class="dog-mode__body">
    <img class="dog-mode__image" src="assets/dog-mode/dog.svg" alt="Hund" />

    <div class="dog-mode__info">
      <div class="dog-mode__time">{{ clockTime }}</div>
      <div class="dog-mode__indoor" [class.dog-mode__indoor--stale]="climate.indoor?.stale">
        <span class="dog-mode__indoor-label">Wohnzimmer</span>
        <span class="dog-mode__indoor-value">{{ climate.indoor?.label ?? '–' }}</span>
      </div>
      @if (climate.outdoorLabel) {
        <div class="dog-mode__outdoor">Draußen {{ climate.outdoorLabel }}</div>
      }
    </div>
  </div>

  <footer class="dog-mode__footer">
    <p class="dog-mode__message">Toni ist allein zu Haus. Herrchen kommt bald zurück – keine Sorge!</p>
    @if (error) {
      <p class="dog-mode__error">{{ error }}</p>
    }
    <button type="button" class="dog-mode__end" [disabled]="busy" (click)="endMode.emit()">
      <span class="material-symbols-outlined">pets</span>
      Toni allein beenden
    </button>
  </footer>
</section>
```

`dog-mode-screen.component.scss`:

```scss
// Farben ausschliesslich ueber lumina-Tokens (vererbt vom .lumina-Wurzelelement),
// damit der Screen im hellen und im dunklen Design stimmt.
.dog-mode {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  flex-direction: column;
  padding: clamp(1rem, 4vw, 3rem);
  background: var(--surface-solid, #111113);
  color: var(--on-surface, #e4e2e4);
}

.dog-mode__body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  align-items: center;
  gap: clamp(1rem, 4vw, 4rem);
}

.dog-mode__image {
  width: 100%;
  max-height: 70vh;
  object-fit: contain;
}

.dog-mode__info {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.dog-mode__time {
  font-size: clamp(5rem, 16vw, 13rem);
  font-weight: 300;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--text-strong, #f4f3f5);
  font-variant-numeric: tabular-nums;
}

.dog-mode__indoor {
  display: flex;
  align-items: baseline;
  gap: 1rem;
}

.dog-mode__indoor--stale {
  opacity: 0.45;
}

.dog-mode__indoor-label {
  font-size: clamp(1.25rem, 2.5vw, 2rem);
  color: var(--on-surface-variant, #c0c6d6);
}

.dog-mode__indoor-value {
  font-size: clamp(3rem, 8vw, 6rem);
  font-weight: 400;
  color: var(--primary, #aac7ff);
}

.dog-mode__outdoor {
  font-size: clamp(1.25rem, 2.5vw, 2rem);
  color: var(--on-surface-variant, #c0c6d6);
}

.dog-mode__footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.dog-mode__message {
  margin: 0;
  font-size: clamp(1.1rem, 2.2vw, 1.75rem);
  color: var(--on-surface, #e4e2e4);
}

.dog-mode__error {
  margin: 0;
  color: var(--error, #ffb4ab);
}

.dog-mode__end {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.25rem;
  border-radius: 999px;
  border: 1px solid var(--card-border, rgba(255, 255, 255, 0.08));
  background: var(--surface-control, rgba(30, 41, 59, 0.8));
  color: var(--on-surface, #e4e2e4);
  font-size: 1rem;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
}

@media (max-width: 768px) {
  .dog-mode__body {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
  }

  .dog-mode__image {
    max-height: 30vh;
  }

  .dog-mode__indoor {
    justify-content: center;
  }
}
```

- [ ] **Step 4: Test laufen lassen, muss grün sein**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/components/dog-mode-screen/dog-mode-screen.component.spec.ts`
Expected: alle PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/components/dog-mode-screen
git commit -m "feat(dashboard): Dog-Mode-Screen-Komponente" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Einbindung ins Dashboard

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (imports Zeile 97, Felder bei Zeile 321–330, `startClimateRefresh` Zeile 1440–1444, neue Methode nach `confirmModeActivation` ~Zeile 768)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (vor dem letzten schließenden `</div>` des `.lumina`-Wurzelelements)
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (neue Suite am Dateiende)

- [ ] **Step 1: Failing Tests schreiben**

Am Ende von `dashboard.component.spec.ts` anfügen (alle Imports sind oben in der Datei bereits vorhanden):

```ts
/**
 * Dog-Mode-Screen: nur Tablet-Ansicht UND „Toni allein" an. `viewMode` wird wie in der
 * Schnellzugriff-Suite nicht gemockt — der Schluessel im localStorage wird vor jedem
 * Test entfernt und die Tablet-Ansicht per toggle() eingeschaltet.
 */
describe('DashboardComponent (Dog-Mode-Screen)', () => {
  let modeServiceSpy: jasmine.SpyObj<ModeService>;
  let temperatureSpy: jasmine.SpyObj<TemperatureService>;

  const toniAllein = (state: string): ModeEntity => ({
    entityId: 'input_boolean.manual_toni_allein',
    displayName: 'Toni allein',
    icon: 'pets',
    state
  });

  beforeEach(async () => {
    localStorage.removeItem('household-manager-view-mode');

    modeServiceSpy = jasmine.createSpyObj('ModeService', ['getModes', 'toggle']);
    modeServiceSpy.getModes.and.returnValue(of([toniAllein('on')]));
    modeServiceSpy.toggle.and.returnValue(of(toniAllein('off')));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ModeService, useValue: modeServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  afterAll(() => localStorage.removeItem('household-manager-view-mode'));

  function createFixture(tablet: boolean): ComponentFixture<DashboardComponent> {
    const fixture = TestBed.createComponent(DashboardComponent);
    if (tablet) {
      fixture.componentInstance.viewMode.toggle();
    }
    fixture.detectChanges();
    return fixture;
  }

  const screen = (fixture: ComponentFixture<DashboardComponent>): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector('app-dog-mode-screen');

  it('zeigt den Screen in der Tablet-Ansicht, wenn Toni allein an ist', fakeAsync(() => {
    const fixture = createFixture(true);

    expect(screen(fixture)).not.toBeNull();

    discardPeriodicTasks();
  }));

  it('zeigt den Screen nicht in der Website-Ansicht', fakeAsync(() => {
    const fixture = createFixture(false);

    expect(screen(fixture)).toBeNull();

    discardPeriodicTasks();
  }));

  it('zeigt den Screen nicht, wenn Toni allein aus ist', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([toniAllein('off')]));
    const fixture = createFixture(true);

    expect(screen(fixture)).toBeNull();

    discardPeriodicTasks();
  }));

  it('zeigt die Wohnzimmer-Temperatur aus den Messwerten', fakeAsync(() => {
    const reading: CurrentTemperatureReading = {
      sensorId: 'alexa:GAJ2300425330047',
      name: 'Wohnzimmer',
      source: 'ALEXA',
      temperature: 22.4,
      measuredAt: new Date().toISOString()
    };
    temperatureSpy.getCurrent.and.returnValue(of([reading]));
    const fixture = createFixture(true);

    expect(screen(fixture)?.querySelector('.dog-mode__indoor-value')?.textContent?.trim()).toBe('22°');

    discardPeriodicTasks();
  }));

  it('schaltet Toni allein ueber den Knopf aus', fakeAsync(() => {
    const fixture = createFixture(true);

    (screen(fixture)!.querySelector('.dog-mode__end') as HTMLButtonElement).click();
    tick();
    fixture.detectChanges();

    expect(modeServiceSpy.toggle).toHaveBeenCalledOnceWith('input_boolean.manual_toni_allein');
    expect(screen(fixture)).toBeNull();

    discardPeriodicTasks();
  }));

  it('schaltet nicht, wenn Toni allein in der aktuellen Liste schon aus ist', fakeAsync(() => {
    const fixture = createFixture(true);
    // Ein Refresh hat den Modus inzwischen als "off" geliefert: ein Toggle schaltete ihn wieder EIN.
    fixture.componentInstance.modes = [toniAllein('off')];

    fixture.componentInstance.endDogMode();

    expect(modeServiceSpy.toggle).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Tests laufen lassen, müssen fehlschlagen**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/pages/dashboard/dashboard.component.spec.ts`
Expected: FAIL — Kompilierfehler `endDogMode` existiert nicht.

- [ ] **Step 3: Dashboard-TS erweitern**

Imports oben in `dashboard.component.ts` ergänzen:

```ts
import { DogModeScreenComponent } from '../../components/dog-mode-screen/dog-mode-screen.component';
import {
  DOG_MODE_ENTITY_ID,
  DogModeClimate,
  EMPTY_DOG_MODE_CLIMATE,
  buildDogModeClimate,
  isDogModeActive
} from '../../shared/dog-mode.util';
```

`imports` im `@Component` (Zeile 97):

```ts
  imports: [CommonModule, RouterLink, FormsModule, EnergyFlowComponent, SwitchListComponent, NgxEchartsDirective, DogModeScreenComponent],
```

Feld direkt unter `modeError: string | null = null;` (Zeile 330):

```ts
  /** Temperaturen fuer den Dog-Mode-Screen, mit jedem Klima-Refresh neu gebaut. */
  dogModeClimate: DogModeClimate = EMPTY_DOG_MODE_CLIMATE;
```

In `startClimateRefresh` die `subscribe`-Funktion um eine Zeile ergänzen:

```ts
      .subscribe(readings => {
        this.currentTemperatures = readings;
        this.climate = buildClimateView(readings, Date.now());
        this.dogModeClimate = buildDogModeClimate(readings, Date.now());
        this.refreshSensorDetail();
      });
```

Nach `confirmModeActivation()` einfügen:

```ts
  /** Dog-Mode-Screen (Muster Tesla): nur am Wandtablet und nur solange „Toni allein" an ist. */
  get dogModeVisible(): boolean {
    return this.viewMode.isTabletView() && isDogModeActive(this.modes);
  }

  get dogModeBusy(): boolean {
    return this.pendingModeIds.has(DOG_MODE_ENTITY_ID);
  }

  /**
   * Beendet „Toni allein" vom Dog-Mode-Screen aus. Der Modus wird aus der aktuellen Liste
   * re-resolved (Muster confirmToggle): ist er dort schon aus, wuerde der Toggle ihn
   * ausgerechnet wieder einschalten — dann passiert nichts. Ausschalten ist direkt,
   * die Aktivierungs-Checks gelten nur beim Einschalten.
   */
  endDogMode(): void {
    const current = this.modes.find(mode => mode.entityId === DOG_MODE_ENTITY_ID);
    if (!current || current.state !== 'on') {
      return;
    }
    this.performModeToggle(current);
  }
```

- [ ] **Step 4: Dashboard-HTML erweitern**

In `dashboard.component.html` direkt **vor** dem letzten `</div>` der Datei (Ende des `.lumina`-Wurzelelements — nur innerhalb davon erben die Tokens) einfügen:

```html
  <!-- Dog-Mode-Screen (Muster Tesla): Vollbild, solange „Toni allein" an ist, nur Tablet-Ansicht -->
  @if (dogModeVisible) {
    <app-dog-mode-screen
      [clockTime]="clockTime"
      [climate]="dogModeClimate"
      [busy]="dogModeBusy"
      [error]="modeError"
      (endMode)="endDogMode()"
    ></app-dog-mode-screen>
  }
```

- [ ] **Step 5: Tests laufen lassen, müssen grün sein**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include src/app/pages/dashboard/dashboard.component.spec.ts`
Expected: alle PASS, einschließlich der bestehenden Dashboard-Suiten.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(dashboard): Dog-Mode-Screen bei Toni allein am Wandtablet" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Gesamtlauf und Build

- [ ] **Step 1: Alle Frontend-Tests**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Baseline — genau 3 FAILED (`AppComponent` ×2, `HeroComponent`), Rest grün. Ein zusätzlicher `SmartDeviceListComponent`-Abbruch ist der bekannte Flake: einmal wiederholen.

- [ ] **Step 2: Build**

Run: `npx ng build --configuration development`
Expected: erfolgreich. (Die Production-Konfiguration kann am vorbestehenden `anyComponentStyle`-Budget von `dashboard.component.scss` scheitern — das ist keine Regression dieses Features, die neue Komponente hat ein eigenes, kleines SCSS.)

---

### Task 7: Im Browser verifizieren (inkl. Garten-Nebenbefund)

- [ ] **Step 1: Preview starten**

`.claude/launch.json` prüfen bzw. den Frontend-Dev-Server per `preview_start` starten (Backend lokal oder Proxy wie gewohnt). Dashboard öffnen, Tablet-Ansicht einschalten.

- [ ] **Step 2: Screen prüfen**

„Toni allein" einschalten (Aktivierungs-Dialog bestätigen), bis zu 30 s warten bzw. neu laden. Prüfen: Screen erscheint, Uhr stimmt mit der Kopfzeile überein, Wohnzimmer-Wert steht da (sonst: `GET /api/v1/temperatures` in `read_network_requests` ansehen und die `sensorId` des Wohnzimmer-Monitors mit `DOG_MODE_INDOOR_SENSOR_ID` vergleichen). Knopf „Toni allein beenden" drücken → Screen verschwindet. Hell/dunkel unter Admin → Darstellung umstellen und beide Designs per Screenshot prüfen. Mobile-Breite (`resize_window` preset mobile) prüfen.

- [ ] **Step 3: Nebenbefund Garten prüfen**

In derselben `GET /api/v1/temperatures`-Antwort nachsehen, welchen `name` der Gartenfühler trägt. Heißt er nicht „Temperatur Aqara Garten", ist `OUTDOOR_SENSOR_NAMES` wirkungslos: dem Nutzer melden (eigener Fix, nicht Teil dieses Plans) — der Dog-Screen zeigt dann den DWD-Wert.

- [ ] **Step 4: Ansichtsmodus zurücksetzen**

Tablet-Ansicht wieder ausschalten, `resize_window` preset desktop.

---

### Task 8: Dokumentation

**Files:**
- Modify: `CLAUDE.md` (neuer Abschnitt nach „### Tablet-Ansichten (Unterseiten des Wandtablets)")

- [ ] **Step 1: Abschnitt ergänzen**

```markdown
### Dog-Mode-Screen („Toni allein" am Wandtablet)
- Spec: `docs/superpowers/specs/2026-09-27-dog-mode-screen-design.md`. Muster Tesla-Dog-Mode: solange `input_boolean.manual_toni_allein` `on` ist, legt das Dashboard **nur in der Tablet-Ansicht** einen Vollbild-Screen darüber — Hunde-Illustration (`assets/dog-mode/dog.svg`, lokal, nie CDN), Uhrzeit groß, Wohnzimmer-Temperatur groß, Außen klein, Knopf „Toni allein beenden". Reines Frontend, keine Backend-/Security-Änderung
- **`shared/dog-mode.util.ts` ist die einzige Definition** von „Screen aktiv" (nur explizites `on`; fehlt der Modus in der Liste, bleibt der Screen aus) und der Werte. Quelle ist die vorhandene 30-s-Modusabfrage — ein Wechsel per Flow/Telegram wird also erst nach bis zu 30 s sichtbar
- **Innenwert über die `sensorId`, nicht den Namen** (`DOG_MODE_INDOOR_SENSOR_ID` = Amazon-Monitor Wohnzimmer, Vergleich ohne Groß-/Kleinschreibung): die Temperatur-API liefert Custom-Namen, eine Namensprüfung bräche beim Umbenennen still. Außen: realer Außenfühler (Erkennung aus `buildClimateView`), sonst DWD. „Veraltet" nutzt `isReadingStale` (60 min, dieselbe Schwelle wie die Klima-Kachel)
- Die Komponente `components/dog-mode-screen/` hat **eigene** Styles (lumina-Kapselung) und bezieht Farben nur über die Theme-Tokens — sie steht deshalb **innerhalb** des `.lumina`-Wurzelelements, sonst erbt sie keine Tokens. Die Uhr ist die `clockTime` des Dashboards (kein zweiter Takt)
- `endDogMode` löst den Modus vor dem Toggle aus der aktuellen Liste neu auf und schaltet nur bei `on` (Regel `confirmToggle`); Ausschalten ohne Dialog
- **Bewusste Grenze:** die Tablet-App schwärzt den Bildschirm weiterhin ohne Präsenz — der Screen ist nur zu sehen, wenn das Tablet durch Bewegung aufwacht (Nutzerentscheidung 2026-09-27)
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Dog-Mode-Screen in CLAUDE.md" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
