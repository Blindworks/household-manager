# Vorrats-Dialog auf dem Wandtablet – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Wandtablet (Rolle KIOSK) kann im Dashboard-Dialog Einkäufe zubuchen und den Bestand korrigieren; der Dialog bekommt ein lesbares, tablettaugliches Layout mit Stepper, Schnellwahl-Chips und Korrektur-Vorschau.

**Architecture:** Zwei POST-Pfade wandern in die bestehende KIOSK-Whitelist von `SecurityConfig` (Backend). Im Frontend rechnet eine neue reine Util (`shared/pet-supply-entry.util.ts`) Stepper, Presets und Delta; das Dialog-Markup in `dashboard.component.html` wird ersetzt, seine Styles ziehen in eine zweite Style-Datei der Dashboard-Komponente (`styleUrls`), weil das `anyComponentStyle`-Budget pro Datei gilt.

**Tech Stack:** Spring Security (MockMvc-Tests), Angular 19 standalone, Karma/Jasmine, SCSS.

Spec: `docs/superpowers/specs/2026-09-11-vorrats-dialog-tablet-design.md`

---

## Umgebungshinweise (vor dem Start lesen)

- **Backend-Tests** brauchen JDK 21. Vor jedem `mvn`: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Git Bash) und aus `backend/` starten. `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern lokal an der fehlenden Test-DB — vorbestehend, ignorieren.
- **Frontend-Tests** headless aus `frontend/`: `npm test -- --watch=false --browsers=ChromeHeadless`. Baseline: 3 vorbestehende Fails (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`) plus gelegentlich eine `SmartDeviceListComponent`-Flake — nur zusätzliche Fails sind Regressionen. Eine einzelne Datei läuft mit `npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/pet-supply-entry.util.spec.ts`.
- **Commits** über das Bash-Tool (Git Bash) absetzen, nicht über PowerShell — Here-Strings mit Anführungszeichen zerlegen dort den `git commit`-Aufruf.
- Templates in diesem Projekt nutzen `*ngIf`/`*ngFor` (CommonModule), keine `@if`-Blöcke — beim Dialog dabei bleiben.

---

## Dateiübersicht

| Datei | Aktion | Verantwortung |
|---|---|---|
| `backend/src/main/java/com/household/manager/security/SecurityConfig.java` | ändern | zwei Pfade in die KIOSK-POST-Whitelist |
| `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` | ändern | zwei Verbotstests kippen, ein neuer Verbotstest für `/target` |
| `frontend/src/app/shared/pet-supply-entry.util.ts` | neu | Stepper-Rechnung, Presets, Korrektur-Delta (rein, ohne Angular) |
| `frontend/src/app/shared/pet-supply-entry.util.spec.ts` | neu | Tests der Util |
| `frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss` | neu | alle Dialog-Styles |
| `frontend/src/app/pages/dashboard/dashboard.component.scss` | ändern | alten Dialog-Block entfernen |
| `frontend/src/app/pages/dashboard/dashboard.component.ts` | ändern | `styleUrls`, Stepper-/Preset-/Preview-Methoden, Erfolgsmeldung |
| `frontend/src/app/pages/dashboard/dashboard.component.html` | ändern | neues Dialog-Markup |
| `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` | ändern | neue Suite „Vorrats-Dialog" |
| `CLAUDE.md` | ändern | Rollen-Absätze der Toni-Vorräte nachziehen |

---

### Task 1: Backend – KIOSK darf Einkauf und Korrektur buchen

**Files:**
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` (Tests `kioskDarfKeinenEinkaufBuchen`, `kioskDarfKeineBestandskorrekturBuchen`, ca. Zeile 340–356)
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (KIOSK-POST-Whitelist, ca. Zeile 205–212)

- [ ] **Step 1: Die zwei Verbotstests zu Erlaubnistests umschreiben und den Ziel-Verbotstest ergänzen**

In `SecurityRulesTest.java` die beiden Methoden `kioskDarfKeinenEinkaufBuchen` und `kioskDarfKeineBestandskorrekturBuchen` **ersetzen** durch:

```java
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfEinkaufBuchen() throws Exception {
        // Der Erfassungs-Dialog der Dashboard-Kachel laeuft auch auf dem Wandtablet.
        mockMvc.perform(post("/v1/pet-supplies/toni_cans/purchases").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 24}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfBestandKorrigieren() throws Exception {
        mockMvc.perform(post("/v1/pet-supplies/toni_vomisan/corrections").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountRemaining\": 10}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfZielbestandNichtAendern() throws Exception {
        // Nur die zwei Dialog-Aktionen sind KIOSK; der Zielbestand bleibt MEMBER.
        mockMvc.perform(put("/v1/pet-supplies/toni_cans/target").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAmount\": 60}"))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: Tests laufen lassen – die zwei Erlaubnistests müssen rot sein**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SecurityRulesTest
```

Erwartet: `Tests run: …, Failures: 2` — `kioskDarfEinkaufBuchen` und `kioskDarfBestandKorrigieren` scheitern mit `Status expected:<200> but was:<403>`; `kioskDarfZielbestandNichtAendern` ist grün.

- [ ] **Step 3: Whitelist erweitern**

In `SecurityConfig.java` den Kommentarblock vor dem KIOSK-POST-Matcher um zwei Zeilen ergänzen und die zwei Pfade in die Liste aufnehmen. Der Block sieht danach so aus (nur der Anfang des Kommentars ist gekürzt dargestellt, die bestehenden Kommentarzeilen bleiben):

```java
                        // ... (bestehende Kommentarzeilen bis "dialog der Tablet-Ansicht, nicht mehr der Server.")
                        // /v1/pet-supplies/*/purchases und /*/corrections: der Erfassungs-
                        // Dialog der Vorrats-Kachel laeuft auf dem Wandtablet; ohne KIOSK
                        // oeffnet er sich dort, das Speichern liefert aber 403. Der Ziel-
                        // bestand (PUT /target) bleibt bewusst MEMBER.
                        .requestMatchers(HttpMethod.POST, "/v1/switches/*/toggle",
                                "/v1/modes/*/toggle", "/v1/nuki/locks/*/actions",
                                "/v1/auth/password", "/v1/tractive/pets/refresh",
                                "/v1/system/reboot", "/v1/network/speedtest",
                                "/v1/blink/cameras/*/snapshot",
                                "/v1/blink/cameras/*/arm", "/v1/blink/cameras/*/disarm",
                                "/v1/blink/system/*/arm", "/v1/blink/system/*/disarm",
                                "/v1/pet-supplies/*/purchases", "/v1/pet-supplies/*/corrections")
                        .hasRole("KIOSK")
```

- [ ] **Step 4: Tests laufen lassen – alles grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SecurityRulesTest
```

Erwartet: `Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(petsupply): KIOSK darf Einkauf zubuchen und Bestand korrigieren

Der Erfassungs-Dialog der Dashboard-Kachel laeuft auch auf dem Wandtablet;
bisher oeffnete er sich dort, das Speichern lief aber in 403. Der Zielbestand
bleibt MEMBER, ein neuer Test haelt die Grenze fest.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Util `pet-supply-entry.util.ts` – Stepper, Presets, Delta

**Files:**
- Create: `frontend/src/app/shared/pet-supply-entry.util.spec.ts`
- Create: `frontend/src/app/shared/pet-supply-entry.util.ts`

- [ ] **Step 1: Failing Tests schreiben**

`frontend/src/app/shared/pet-supply-entry.util.spec.ts`:

```ts
import { correctionDelta, purchasePresets, snapToStep, stepAmount } from './pet-supply-entry.util';

describe('pet-supply-entry.util', () => {
  describe('snapToStep', () => {
    it('rundet auf das Raster und schneidet Gleitkomma-Reste ab', () => {
      expect(snapToStep(0.1 + 0.2, 0.5)).toBe(0.5);
      expect(snapToStep(1.2, 0.5)).toBe(1);
      expect(snapToStep(1.3, 0.5)).toBe(1.5);
      expect(snapToStep(7.4, 1)).toBe(7);
    });

    it('liefert bei 0,5-Raster nie mehr als eine Nachkommastelle', () => {
      // 3 * 0.1 waere 0.30000000000000004 - das darf nicht im Feld landen.
      expect(String(snapToStep(0.3, 0.1))).toBe('0.3');
    });
  });

  describe('stepAmount', () => {
    it('geht einen Rasterschritt hoch und runter', () => {
      expect(stepAmount(2, 0.5, 1, 0.5)).toBe(2.5);
      expect(stepAmount(2, 0.5, -1, 0.5)).toBe(1.5);
      expect(stepAmount(7, 1, 1, 0)).toBe(8);
    });

    it('faellt nie unter die Untergrenze', () => {
      expect(stepAmount(0.5, 0.5, -1, 0.5)).toBe(0.5);
      expect(stepAmount(0, 1, -1, 0)).toBe(0);
    });

    it('startet bei leerem Feld auf der Untergrenze', () => {
      expect(stepAmount(null, 0.5, 1, 0.5)).toBe(0.5);
      expect(stepAmount(null, 1, -1, 0)).toBe(0);
    });

    it('zieht einen Wert ausserhalb des Rasters erst aufs Raster', () => {
      // 2,3 Dosen sind kein gueltiger Bestand; +1 Schritt ergibt 3,0, nicht 2,8.
      expect(stepAmount(2.3, 0.5, 1, 0.5)).toBe(3);
    });
  });

  describe('purchasePresets', () => {
    it('bietet ein Viertel, die Haelfte und das Auffuellen bis zum Ziel', () => {
      const presets = purchasePresets({ targetAmount: 48, amountRemaining: 5, step: 0.5 });
      expect(presets).toEqual([
        { amount: 12, refill: false },
        { amount: 24, refill: false },
        { amount: 43, refill: true }
      ]);
    });

    it('rundet die Anteile auf das Raster des Vorrats', () => {
      // 30 / 4 waere 7,5 Tabletten - bei Raster 1 nicht buchbar, wird 8.
      const presets = purchasePresets({ targetAmount: 30, amountRemaining: 0, step: 1 });
      expect(presets.map(p => p.amount)).toEqual([8, 15, 30]);
    });

    it('laesst das Auffuellen weg, wenn der Vorrat voll ist', () => {
      const presets = purchasePresets({ targetAmount: 48, amountRemaining: 48, step: 0.5 });
      expect(presets.map(p => p.amount)).toEqual([12, 24]);
      expect(presets.some(p => p.refill)).toBeFalse();
    });

    it('zeigt einen Anteil nicht doppelt, wenn er dem Auffuellen entspricht', () => {
      // 24 von 48: die Haelfte IST das Auffuellen - dann nur die Auffuellen-Variante.
      const presets = purchasePresets({ targetAmount: 48, amountRemaining: 24, step: 0.5 });
      expect(presets).toEqual([
        { amount: 12, refill: false },
        { amount: 24, refill: true }
      ]);
    });
  });

  describe('correctionDelta', () => {
    it('liefert die Differenz zum aktuellen Bestand ohne Gleitkomma-Reste', () => {
      expect(correctionDelta(4.5, 5)).toBe(-0.5);
      expect(correctionDelta(40, 5)).toBe(35);
      expect(correctionDelta(0.3, 0.1)).toBe(0.2);
    });

    it('liefert null bei leerem Feld', () => {
      expect(correctionDelta(null, 5)).toBeNull();
    });
  });
});
```

- [ ] **Step 2: Tests laufen lassen – rot**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/pet-supply-entry.util.spec.ts
```

Erwartet: Kompilierfehler `Cannot find module './pet-supply-entry.util'`.

- [ ] **Step 3: Util implementieren**

`frontend/src/app/shared/pet-supply-entry.util.ts`:

```ts
/**
 * Rechenregeln des Erfassungs-Dialogs der Vorrats-Kacheln (Dashboard):
 * Stepper, Schnellwahl-Chips und Korrektur-Vorschau. Reine Funktionen ohne
 * Angular - das Raster eines Vorrats (0,5 Dosen / 1 Tablette) wird an genau
 * einer Stelle gerechnet, und Gleitkomma-Reste wie 0,30000000000000004 landen
 * nie im Zahlenfeld.
 *
 * Die Chips leiten sich aus Ziel und Bestand ab, nicht aus Artikelwissen:
 * die Seite kennt die Vorraete nicht namentlich, ein dritter Vorrat braucht
 * hier keine Aenderung.
 */

export interface PurchasePreset {
  amount: number;
  /** true fuer den Chip, der bis zum Zielbestand auffuellt. */
  refill: boolean;
}

/** Nachkommastellen des Rasters (0.5 -> 1, 1 -> 0, 0.25 -> 2). */
function decimalsOf(step: number): number {
  const text = step.toString();
  const dot = text.indexOf('.');
  return dot === -1 ? 0 : text.length - dot - 1;
}

/** Rundet einen Betrag auf das Raster des Vorrats und schneidet Gleitkomma-Reste ab. */
export function snapToStep(amount: number, step: number): number {
  const snapped = Math.round(amount / step) * step;
  return Number(snapped.toFixed(decimalsOf(step)));
}

/**
 * Naechster Stepper-Wert: ein Rasterschritt in die gewaehlte Richtung, nie
 * unter `min`. Ein leeres Feld (null) startet auf `min`. Ein Wert ausserhalb
 * des Rasters wird erst aufs Raster gezogen, sonst bliebe er fuer immer daneben.
 */
export function stepAmount(current: number | null, step: number, direction: 1 | -1, min: number): number {
  if (current === null || Number.isNaN(current)) {
    return min;
  }
  const next = snapToStep(current, step) + direction * step;
  return Math.max(min, snapToStep(next, step));
}

/**
 * Schnellwahl fuer den Einkauf: ein Viertel und die Haelfte des Ziels sowie
 * "Auffuellen" (Ziel minus Bestand). Betraege <= 0 entfallen; entspricht ein
 * Anteil genau dem Auffuellen, bleibt nur der Auffuellen-Chip - er sagt mehr.
 */
export function purchasePresets(
  supply: { targetAmount: number; amountRemaining: number; step: number }
): PurchasePreset[] {
  const refill = snapToStep(supply.targetAmount - supply.amountRemaining, supply.step);
  const fractions = [supply.targetAmount / 4, supply.targetAmount / 2]
    .map(amount => snapToStep(amount, supply.step))
    .filter((amount, index, all) => amount > 0 && amount !== refill && all.indexOf(amount) === index)
    .map(amount => ({ amount, refill: false }));
  return refill > 0 ? [...fractions, { amount: refill, refill: true }] : fractions;
}

/**
 * Differenz einer Korrektur zum aktuellen Bestand (fuer die Vorschauzeile),
 * auf zwei Nachkommastellen gerundet, damit keine Gleitkomma-Reste erscheinen.
 */
export function correctionDelta(newAmount: number | null, current: number): number | null {
  if (newAmount === null || Number.isNaN(newAmount)) {
    return null;
  }
  return Math.round((newAmount - current) * 100) / 100;
}
```

- [ ] **Step 4: Tests laufen lassen – grün**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/pet-supply-entry.util.spec.ts
```

Erwartet: `Executed 12 of 12 SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/pet-supply-entry.util.ts frontend/src/app/shared/pet-supply-entry.util.spec.ts
git commit -m "feat(petsupply): Util fuer Stepper, Schnellwahl und Korrektur-Vorschau

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Dashboard-Komponente – Methoden und Erfolgsmeldung

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (neue Suite ans Dateiende)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (Imports Zeile 65–67, Felder ca. Zeile 419–425, Methoden ca. Zeile 1738–1810)

- [ ] **Step 1: Failing Tests für die Komponentenlogik schreiben**

Ans Ende von `dashboard.component.spec.ts` anhängen. Die Imports `PetSupplyService` und `PetSupply` oben in der Datei ergänzen:

```ts
import { PetSupplyService } from '../../services/pet-supply.service';
import { PetSupply } from '../../models/pet-supply.model';
```

Neue Suite:

```ts
/**
 * Erfassungs-Dialog der Vorrats-Kachel. `viewMode` wird nicht gemockt (Muster
 * Modus-Schnellzugriff): der echte ViewModeService liest localStorage, deshalb
 * wird der Schluessel vor jedem Test entfernt und die Tablet-Ansicht ueber
 * `viewMode.toggle()` eingeschaltet.
 */
describe('DashboardComponent (Vorrats-Dialog)', () => {
  let petSupplySpy: jasmine.SpyObj<PetSupplyService>;

  const futter = (overrides: Partial<PetSupply> = {}): PetSupply => ({
    key: 'toni_cans',
    name: 'Futter',
    unit: 'Dosen',
    amountRemaining: 5,
    targetAmount: 48,
    step: 0.5,
    perDay: 1,
    percent: 10,
    daysRemaining: 5,
    ...overrides
  });

  beforeEach(async () => {
    localStorage.removeItem('household-manager-view-mode');

    petSupplySpy = jasmine.createSpyObj('PetSupplyService', ['getSupplies', 'recordPurchase', 'correctStock']);
    petSupplySpy.getSupplies.and.returnValue(of([futter()]));
    petSupplySpy.recordPurchase.and.returnValue(of(futter({ amountRemaining: 17, percent: 35, daysRemaining: 17 })));
    petSupplySpy.correctStock.and.returnValue(of(futter({ amountRemaining: 4.5, percent: 9, daysRemaining: 4 })));

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
        { provide: PetSupplyService, useValue: petSupplySpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  afterAll(() => localStorage.removeItem('household-manager-view-mode'));

  /** Dashboard mit geoeffnetem Futter-Dialog; optional in der Tablet-Ansicht. */
  function openedFixture(tablet = false): ComponentFixture<DashboardComponent> {
    const fixture = TestBed.createComponent(DashboardComponent);
    if (tablet) {
      fixture.componentInstance.viewMode.toggle();
    }
    fixture.detectChanges();
    fixture.componentInstance.openPetSupplyDialog(fixture.componentInstance.petSupplies[0]);
    fixture.detectChanges();
    return fixture;
  }

  function dialog(fixture: ComponentFixture<DashboardComponent>): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector('.lumina__dialog--supply') as HTMLElement;
  }

  it('steppt den Einkauf im Raster des Vorrats und nicht unter einen Schritt', fakeAsync(() => {
    const fixture = openedFixture();
    const component = fixture.componentInstance;

    component.stepPetSupplyPurchase(1);
    expect(component.petSupplyPurchaseAmount).toBe(0.5);
    component.stepPetSupplyPurchase(1);
    expect(component.petSupplyPurchaseAmount).toBe(1);
    component.stepPetSupplyPurchase(-1);
    component.stepPetSupplyPurchase(-1);
    expect(component.petSupplyPurchaseAmount).toBe(0.5);

    discardPeriodicTasks();
  }));

  it('bietet Schnellwahl-Chips aus Ziel und Bestand an und uebernimmt sie', fakeAsync(() => {
    const fixture = openedFixture();
    const chips = Array.from(dialog(fixture).querySelectorAll('.lumina__supply-preset')) as HTMLButtonElement[];

    expect(chips.map(chip => chip.textContent?.replace(/\s+/g, ' ').trim()))
      .toEqual(['+12', '+24', 'Auffüllen · +43']);

    chips[0].click();
    expect(fixture.componentInstance.petSupplyPurchaseAmount).toBe(12);

    discardPeriodicTasks();
  }));

  it('bucht den Einkauf mit Betrag und Notiz und meldet den Erfolg', fakeAsync(() => {
    const fixture = openedFixture();
    const component = fixture.componentInstance;
    component.petSupplyPurchaseAmount = 12;
    component.petSupplyPurchaseNote = 'Fressnapf';

    component.submitPetSupplyPurchase();
    tick();
    fixture.detectChanges();

    expect(petSupplySpy.recordPurchase).toHaveBeenCalledWith('toni_cans', 12, 'Fressnapf');
    expect(component.petSupplies[0].amountRemaining).toBe(17);
    expect(component.petSupplyPurchaseAmount).toBeNull();
    expect(dialog(fixture).querySelector('.lumina__supply-feedback--success')?.textContent).toContain('+12 Dosen gebucht');

    discardPeriodicTasks();
  }));

  it('zeigt die Korrektur als Differenz zum aktuellen Bestand', fakeAsync(() => {
    const fixture = openedFixture();
    const component = fixture.componentInstance;

    expect(component.petSupplyCorrectionAmount).toBe(5);
    expect(component.petSupplyCorrectionPreview(component.petSupplies[0])).toBe('Unverändert gegenüber jetzt');

    component.stepPetSupplyCorrection(-1);
    expect(component.petSupplyCorrectionAmount).toBe(4.5);
    expect(component.petSupplyCorrectionPreview(component.petSupplies[0])).toBe('−0,5 Dosen gegenüber jetzt');

    component.petSupplyCorrectionAmount = 40;
    expect(component.petSupplyCorrectionPreview(component.petSupplies[0])).toBe('+35 Dosen gegenüber jetzt');

    discardPeriodicTasks();
  }));

  it('korrigiert den Bestand und meldet den neuen Stand', fakeAsync(() => {
    const fixture = openedFixture();
    const component = fixture.componentInstance;
    component.stepPetSupplyCorrection(-1);

    component.submitPetSupplyCorrection();
    tick();
    fixture.detectChanges();

    expect(petSupplySpy.correctStock).toHaveBeenCalledWith('toni_cans', 4.5, '');
    expect(dialog(fixture).querySelector('.lumina__supply-feedback--success')?.textContent).toContain('Bestand auf 4,5 Dosen gesetzt');

    discardPeriodicTasks();
  }));

  it('zeigt einen Buchungsfehler im Dialog an', fakeAsync(() => {
    petSupplySpy.recordPurchase.and.returnValue(throwError(() => new Error('Menge passt nicht ins Raster.')));
    const fixture = openedFixture();
    fixture.componentInstance.petSupplyPurchaseAmount = 12;

    fixture.componentInstance.submitPetSupplyPurchase();
    tick();
    fixture.detectChanges();

    expect(dialog(fixture).querySelector('.lumina__supply-feedback--error')?.textContent).toContain('Menge passt nicht ins Raster.');
    expect(dialog(fixture).querySelector('.lumina__supply-feedback--success')).toBeNull();

    discardPeriodicTasks();
  }));

  it('verlinkt in der Website-Ansicht auf die Vorrats-Seite', fakeAsync(() => {
    const fixture = openedFixture();

    expect(dialog(fixture).querySelector('.lumina__supply-page-link')).not.toBeNull();

    discardPeriodicTasks();
  }));

  /** /pet-food hat keinen Zurueck-Knopf - das Tablet waere dort gefangen. */
  it('zeigt den Link zur Vorrats-Seite in der Tablet-Ansicht nicht', fakeAsync(() => {
    const fixture = openedFixture(true);

    expect(fixture.componentInstance.viewMode.isTabletView()).toBeTrue();
    expect(dialog(fixture).querySelector('.lumina__supply-page-link')).toBeNull();

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Tests laufen lassen – rot**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/dashboard/dashboard.component.spec.ts
```

Erwartet: Kompilierfehler (`Property 'stepPetSupplyPurchase' does not exist …`).

- [ ] **Step 3: Komponente erweitern**

In `dashboard.component.ts`:

a) Imports: Zeile `import { formatDate } from '@angular/common';` ersetzen durch

```ts
import { formatDate, formatNumber } from '@angular/common';
```

und die Util-Zeile (Zeile 67) ersetzen durch

```ts
import { PetSupplyTone, petSupplyBarWidth as petSupplyLevelBarWidth, petSupplyIcon as petSupplyLevelIcon, petSupplyTone as petSupplyLevelTone, worstPetSupplyTone } from '../../shared/pet-supply-level.util';
import { PurchasePreset, correctionDelta, purchasePresets, stepAmount } from '../../shared/pet-supply-entry.util';
```

b) Im `@Component`-Decorator `styleUrl: './dashboard.component.scss'` ersetzen durch

```ts
  // Zweite Datei fuer den Vorrats-Dialog: das anyComponentStyle-Budget gilt pro
  // Datei, und dashboard.component.scss steht kurz vor der Fehlergrenze.
  styleUrls: ['./dashboard.component.scss', './dashboard-pet-supply-dialog.scss']
```

c) Nach dem Feld `petSupplyError: string | null = null;` ergänzen:

```ts
  petSupplySuccess: string | null = null;
```

d) `openPetSupplyDialog` um das Zurücksetzen der Erfolgsmeldung ergänzen:

```ts
  openPetSupplyDialog(supply: PetSupply): void {
    this.petSupplyDialogKey = supply.key;
    this.petSupplyError = null;
    this.petSupplySuccess = null;
    this.petSupplyPurchaseAmount = null;
    this.petSupplyPurchaseNote = '';
    this.petSupplyCorrectionAmount = supply.amountRemaining;
    this.petSupplyCorrectionNote = '';
  }
```

e) `submitPetSupplyPurchase`, `submitPetSupplyCorrection` und `mutatePetSupply` ersetzen durch:

```ts
  submitPetSupplyPurchase(): void {
    const supply = this.petSupplyDialogSupply;
    const amount = this.petSupplyPurchaseAmount;
    if (supply === null || amount == null || amount <= 0) {
      return;
    }
    this.mutatePetSupply(
      this.petSupplyService.recordPurchase(supply.key, amount, this.petSupplyPurchaseNote),
      updated => {
        this.petSupplySuccess = `+${formatNumber(amount, 'de', '1.0-2')} ${updated.unit} gebucht`;
        this.petSupplyPurchaseAmount = null;
        this.petSupplyPurchaseNote = '';
      });
  }

  submitPetSupplyCorrection(): void {
    const supply = this.petSupplyDialogSupply;
    if (supply === null || this.petSupplyCorrectionAmount == null || this.petSupplyCorrectionAmount < 0) {
      return;
    }
    this.mutatePetSupply(
      this.petSupplyService.correctStock(supply.key, this.petSupplyCorrectionAmount, this.petSupplyCorrectionNote),
      updated => {
        this.petSupplySuccess = `Bestand auf ${formatNumber(updated.amountRemaining, 'de', '1.0-2')} ${updated.unit} gesetzt`;
        this.petSupplyCorrectionNote = '';
      });
  }

  /** Schnellwahl-Chips des Einkaufs, aus Ziel und Bestand des offenen Vorrats. */
  get petSupplyPurchasePresets(): PurchasePreset[] {
    const supply = this.petSupplyDialogSupply;
    return supply ? purchasePresets(supply) : [];
  }

  /** Stepper des Einkaufs: Untergrenze ein Rasterschritt (0 Dosen kauft niemand). */
  stepPetSupplyPurchase(direction: 1 | -1): void {
    const supply = this.petSupplyDialogSupply;
    if (supply) {
      this.petSupplyPurchaseAmount = stepAmount(this.petSupplyPurchaseAmount, supply.step, direction, supply.step);
    }
  }

  /** Stepper der Korrektur: Untergrenze 0 (leerer Vorrat ist ein gueltiger Stand). */
  stepPetSupplyCorrection(direction: 1 | -1): void {
    const supply = this.petSupplyDialogSupply;
    if (supply) {
      this.petSupplyCorrectionAmount = stepAmount(this.petSupplyCorrectionAmount, supply.step, direction, 0);
    }
  }

  applyPetSupplyPreset(amount: number): void {
    this.petSupplyPurchaseAmount = amount;
  }

  /** Vorschauzeile der Korrektur: was aendert der eingegebene Bestand gegenueber jetzt? */
  petSupplyCorrectionPreview(supply: PetSupply): string {
    const delta = correctionDelta(this.petSupplyCorrectionAmount, supply.amountRemaining);
    if (delta === null) {
      return 'Bestand eingeben';
    }
    if (delta === 0) {
      return 'Unverändert gegenüber jetzt';
    }
    const sign = delta > 0 ? '+' : '−';
    return `${sign}${formatNumber(Math.abs(delta), 'de', '1.0-2')} ${supply.unit} gegenüber jetzt`;
  }

  petSupplyBarWidth(percent: number): number {
    return petSupplyLevelBarWidth(percent);
  }

  private mutatePetSupply(request: Observable<PetSupply>, onSuccess: (updated: PetSupply) => void): void {
    this.petSupplySaving = true;
    this.petSupplyError = null;
    this.petSupplySuccess = null;
    request.subscribe({
      next: updated => {
        this.petSupplySaving = false;
        this.petSupplies = this.petSupplies.map(supply => supply.key === updated.key ? updated : supply);
        this.petSupplyCorrectionAmount = updated.amountRemaining;
        onSuccess(updated);
      },
      error: (err: Error) => {
        this.petSupplySaving = false;
        this.petSupplyError = err.message;
      }
    });
  }
```

f) Die leere Style-Datei anlegen, damit `styleUrls` auflöst (Inhalt kommt in Task 4):

```bash
printf '// Erfassungs-Dialog der Vorrats-Kacheln - Inhalt folgt.\n' > frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss
```

- [ ] **Step 4: Tests laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/dashboard/dashboard.component.spec.ts
```

Erwartet: Die Suite kompiliert. Grün sind die Tests, die nur über die Komponente gehen (`steppt den Einkauf …`, `zeigt die Korrektur als Differenz …`); rot bleiben die DOM-Tests (Chips, Feedback, Link), weil das Markup erst in Task 4 kommt — sie melden `Cannot read properties of null` bzw. leere Listen. Alle übrigen Dashboard-Suiten müssen grün bleiben.

- [ ] **Step 5: Commit (Zwischenstand, Tests bewusst noch teilweise rot)**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.spec.ts frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss
git commit -m "feat(dashboard): Stepper, Schnellwahl und Erfolgsmeldung im Vorrats-Dialog (Logik)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Dialog-Markup und Styles

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Block `<!-- Vorrats-Dialog … -->` bis zum schließenden `</div>` des Backdrops, ca. Zeile 974–1043)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (Block `// ---- Erfassungs-Dialog der Futter-Kachel ----` bis einschließlich `.lumina__petfood-page-link { … }`, ca. Zeile 1341–1415)
- Modify: `frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss`

- [ ] **Step 1: Dialog-Markup ersetzen**

In `dashboard.component.html` den gesamten Block vom Kommentar `<!-- Vorrats-Dialog (oeffnet sich beim Klick auf eine Vorrats-Kachel) -->` bis zum schließenden `</div>` des `lumina__dialog-backdrop` (direkt vor dem allerletzten `</div>` der Datei) ersetzen durch:

```html
  <!-- Vorrats-Dialog (oeffnet sich beim Klick auf eine Vorrats-Kachel).
       Styles in dashboard-pet-supply-dialog.scss (zweite styleUrl der Komponente). -->
  <div
    *ngIf="petSupplyDialogSupply as supply"
    class="lumina__dialog-backdrop"
    (click)="closePetSupplyDialog()"
  >
    <div
      class="lumina__dialog lumina__dialog--supply"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="supply.name + ' erfassen'"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <div class="lumina__supply-heading">
          <span class="lumina__supply-heading-icon material-symbols-outlined" [attr.data-tone]="petSupplyTone(supply)">
            {{ petSupplyIcon(supply) }}
          </span>
          <div>
            <h2 class="lumina__dialog-title">{{ supply.name }}</h2>
            <p class="lumina__supply-subtitle">Vorrat erfassen</p>
          </div>
        </div>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closePetSupplyDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>

      <div class="lumina__dialog-body">
        <section class="lumina__supply-summary" [attr.data-tone]="petSupplyTone(supply)">
          <div class="lumina__supply-figures">
            <span class="lumina__supply-amount">{{ supply.amountRemaining | number:'1.0-1':'de' }}</span>
            <span class="lumina__supply-target">von {{ supply.targetAmount | number:'1.0-1':'de' }} {{ supply.unit }}</span>
            <span class="lumina__supply-chip">{{ supply.percent }} %</span>
            <span class="lumina__supply-chip">reicht ~{{ supply.daysRemaining }} Tage</span>
          </div>
          <div class="lumina__supply-track">
            <div class="lumina__supply-fill" [style.width.%]="petSupplyBarWidth(supply.percent)"></div>
          </div>
        </section>

        <p *ngIf="petSupplyError" class="lumina__supply-feedback lumina__supply-feedback--error" role="alert">
          <span class="material-symbols-outlined">error</span>{{ petSupplyError }}
        </p>
        <p *ngIf="petSupplySuccess" class="lumina__supply-feedback lumina__supply-feedback--success" role="status">
          <span class="material-symbols-outlined">check_circle</span>{{ petSupplySuccess }}
        </p>

        <div class="lumina__supply-forms">
          <form class="lumina__supply-card" (ngSubmit)="submitPetSupplyPurchase()">
            <h3 class="lumina__supply-card-title">
              <span class="material-symbols-outlined">add_shopping_cart</span>Einkauf zubuchen
            </h3>
            <div class="lumina__supply-stepper">
              <button type="button" class="lumina__supply-step" (click)="stepPetSupplyPurchase(-1)" aria-label="Weniger">
                <span class="material-symbols-outlined">remove</span>
              </button>
              <input type="number" inputmode="decimal" name="petSupplyPurchaseAmount"
                     [(ngModel)]="petSupplyPurchaseAmount" [min]="supply.step" [step]="supply.step"
                     placeholder="0" [attr.aria-label]="'Einkauf in ' + supply.unit" required>
              <button type="button" class="lumina__supply-step" (click)="stepPetSupplyPurchase(1)" aria-label="Mehr">
                <span class="material-symbols-outlined">add</span>
              </button>
            </div>
            <span class="lumina__supply-unit">{{ supply.unit }}</span>
            <div class="lumina__supply-presets">
              <button
                type="button"
                class="lumina__supply-preset"
                *ngFor="let preset of petSupplyPurchasePresets"
                [class.lumina__supply-preset--active]="petSupplyPurchaseAmount === preset.amount"
                (click)="applyPetSupplyPreset(preset.amount)"
              ><ng-container *ngIf="preset.refill">Auffüllen · </ng-container>+{{ preset.amount | number:'1.0-1':'de' }}</button>
            </div>
            <label class="lumina__supply-note">Notiz (optional)
              <input type="text" name="petSupplyPurchaseNote" [(ngModel)]="petSupplyPurchaseNote"
                     maxlength="255" placeholder="z. B. Fressnapf">
            </label>
            <button type="submit" class="lumina__supply-submit lumina__supply-submit--purchase"
                    [disabled]="petSupplySaving || !petSupplyPurchaseAmount">
              <span class="material-symbols-outlined">add_shopping_cart</span>Zubuchen
            </button>
          </form>

          <form class="lumina__supply-card" (ngSubmit)="submitPetSupplyCorrection()">
            <h3 class="lumina__supply-card-title">
              <span class="material-symbols-outlined">inventory_2</span>Bestand korrigieren
            </h3>
            <div class="lumina__supply-stepper">
              <button type="button" class="lumina__supply-step" (click)="stepPetSupplyCorrection(-1)" aria-label="Weniger">
                <span class="material-symbols-outlined">remove</span>
              </button>
              <input type="number" inputmode="decimal" name="petSupplyCorrectionAmount"
                     [(ngModel)]="petSupplyCorrectionAmount" min="0" [step]="supply.step"
                     [attr.aria-label]="'Gezählter Bestand in ' + supply.unit" required>
              <button type="button" class="lumina__supply-step" (click)="stepPetSupplyCorrection(1)" aria-label="Mehr">
                <span class="material-symbols-outlined">add</span>
              </button>
            </div>
            <span class="lumina__supply-unit">{{ supply.unit }} gezählt</span>
            <p class="lumina__supply-preview">{{ petSupplyCorrectionPreview(supply) }}</p>
            <label class="lumina__supply-note">Notiz (optional)
              <input type="text" name="petSupplyCorrectionNote" [(ngModel)]="petSupplyCorrectionNote"
                     maxlength="255" placeholder="z. B. nachgezählt">
            </label>
            <button type="submit" class="lumina__supply-submit lumina__supply-submit--correction"
                    [disabled]="petSupplySaving || petSupplyCorrectionAmount == null">
              <span class="material-symbols-outlined">inventory_2</span>Korrigieren
            </button>
          </form>
        </div>

        <!-- /pet-food hat keinen Zurueck-Knopf: im Tablet-Modus (ohne Header-Navigation)
             waere das Wandtablet dort gefangen. -->
        <a
          *ngIf="!viewMode.isTabletView()"
          class="lumina__supply-page-link"
          routerLink="/pet-food"
          (click)="closePetSupplyDialog()"
        >
          <span class="material-symbols-outlined">history</span>
          Historie und Zielbestand auf der Vorrats-Seite
        </a>
      </div>
    </div>
  </div>
```

- [ ] **Step 2: Alten Dialog-Style-Block aus `dashboard.component.scss` entfernen**

Den Block, der mit `// ---- Erfassungs-Dialog der Futter-Kachel ----------------------------------` beginnt und mit dem schließenden `}` von `.lumina__petfood-page-link { … }` endet (die Selektoren `.lumina__petfood-summary`, `.lumina__petfood-figures`, `.lumina__petfood-error`, `.lumina__petfood-forms`, `.lumina__petfood-form`, `.lumina__petfood-page-link`), komplett löschen. **Nicht** löschen: `.lumina__petfood`, `.lumina__petfood-track`, `.lumina__petfood-fill` davor — die gehören zur Kachel.

Prüfen, dass nichts übrig ist:

```bash
grep -n "petfood-summary\|petfood-figures\|petfood-forms\|petfood-form\b\|petfood-error\|petfood-page-link" frontend/src/app/pages/dashboard/dashboard.component.scss frontend/src/app/pages/dashboard/dashboard.component.html
```

Erwartet: keine Treffer.

- [ ] **Step 3: Neue Style-Datei füllen**

`frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss` komplett ersetzen durch:

```scss
// Erfassungs-Dialog der Vorrats-Kacheln (Einkauf zubuchen / Bestand korrigieren).
//
// Eigene Datei, nicht dashboard.component.scss: das anyComponentStyle-Budget in
// angular.json gilt PRO Datei, und die Hauptdatei steht kurz vor der Fehlergrenze.
// Die Kapselung bleibt - beide Dateien gehoeren derselben Komponente (styleUrls),
// die lumina-Klassen des Templates sind hier also erreichbar.
//
// Farbwelt ist die des HELLEN Dialogs (.lumina__dialog ist weiss). Die frueheren
// Formularstyles trugen die Werte des dunklen Dashboards und waren darauf
// praktisch unlesbar. Keine color-mix()-Aufrufe: der Android-WebView des
// Wandtablets ist nicht garantiert aktuell, Toene stehen deshalb ausgeschrieben.

$supply-ink: #0f172a;
$supply-muted: #475569;
$supply-panel: rgba(15, 23, 42, 0.04);
$supply-line: rgba(15, 23, 42, 0.12);
$supply-focus: rgba(15, 23, 42, 0.35);
$supply-ok: #16a34a;
$supply-ok-dark: #15803d;
$supply-warn: #d97706;
$supply-critical: #dc2626;
$supply-font-display: 'Space Grotesk', 'Geist', sans-serif;

.lumina__dialog--supply {
  width: min(760px, 100%);
}

// ---- Kopf ------------------------------------------------------------------
.lumina__supply-heading {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.lumina__supply-heading-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: $supply-panel;
  color: $supply-muted;
  font-size: 26px;

  &[data-tone='warn'] { color: $supply-warn; background: rgba(217, 119, 6, 0.1); }
  &[data-tone='critical'] { color: $supply-critical; background: rgba(220, 38, 38, 0.08); }
}

.lumina__supply-subtitle {
  margin: 2px 0 0;
  font-size: 0.85rem;
  color: $supply-muted;
}

// ---- Zusammenfassung -------------------------------------------------------
.lumina__supply-summary {
  padding: 16px 18px;
  border-radius: 0.75rem;
  background: $supply-panel;

  &[data-tone='ok'] {
    .lumina__supply-chip { background: rgba(22, 163, 74, 0.12); color: $supply-ok-dark; }
    .lumina__supply-fill { background: $supply-ok; }
  }
  &[data-tone='warn'] {
    .lumina__supply-chip { background: rgba(217, 119, 6, 0.12); color: #b45309; }
    .lumina__supply-fill { background: $supply-warn; }
  }
  &[data-tone='critical'] {
    .lumina__supply-chip { background: rgba(220, 38, 38, 0.1); color: #b91c1c; }
    .lumina__supply-fill { background: $supply-critical; }
  }
}

.lumina__supply-figures {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
}

.lumina__supply-amount {
  font-family: $supply-font-display;
  font-size: 2.25rem;
  font-weight: 700;
  line-height: 1;
  color: $supply-ink;
}

.lumina__supply-target {
  font-size: 1rem;
  color: $supply-muted;
}

.lumina__supply-chip {
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  white-space: nowrap;
}

.lumina__supply-track {
  margin-top: 12px;
  height: 8px;
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.1);
  overflow: hidden;
}

.lumina__supply-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s ease;
}

// ---- Rueckmeldung ----------------------------------------------------------
.lumina__supply-feedback {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 14px 0 0;
  padding: 10px 14px;
  border-radius: 0.75rem;
  font-size: 0.9rem;
  font-weight: 500;

  .material-symbols-outlined { font-size: 20px; }

  &--error { background: rgba(220, 38, 38, 0.08); color: #b91c1c; }
  &--success { background: rgba(22, 163, 74, 0.1); color: $supply-ok-dark; }
}

// ---- Die zwei Karten -------------------------------------------------------
.lumina__supply-forms {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.lumina__supply-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  border: 1px solid $supply-line;
  border-radius: 1rem;
  background: #ffffff;
}

.lumina__supply-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 1rem;
  font-weight: 700;
  color: $supply-ink;

  .material-symbols-outlined { font-size: 22px; color: $supply-muted; }
}

// Stepper: 56-px-Tasten sind auf dem Wandtablet ohne Zielen treffbar.
.lumina__supply-stepper {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 56px;
  gap: 8px;
  align-items: stretch;

  input {
    width: 100%;
    min-width: 0;
    height: 56px;
    padding: 0 8px;
    border: 1px solid $supply-line;
    border-radius: 12px;
    background: $supply-panel;
    color: $supply-ink;
    font-family: $supply-font-display;
    font-size: 1.75rem;
    font-weight: 700;
    text-align: center;
    -moz-appearance: textfield;
    appearance: textfield;

    // Die Browser-Spinner sind winzig; der Stepper ersetzt sie.
    &::-webkit-outer-spin-button,
    &::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }

    &::placeholder { color: rgba(15, 23, 42, 0.3); }
    &:focus { outline: 2px solid $supply-focus; outline-offset: 1px; }
  }
}

.lumina__supply-step {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid $supply-line;
  border-radius: 12px;
  background: #ffffff;
  color: $supply-ink;
  cursor: pointer;
  transition: background 0.15s ease;

  .material-symbols-outlined { font-size: 26px; }

  &:hover { background: $supply-panel; }
  &:active { background: rgba(15, 23, 42, 0.1); }
}

.lumina__supply-unit {
  margin-top: -6px;
  text-align: center;
  font-size: 0.8rem;
  color: $supply-muted;
}

.lumina__supply-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.lumina__supply-preset {
  padding: 8px 14px;
  border: 1px solid $supply-line;
  border-radius: 999px;
  background: #ffffff;
  color: $supply-ink;
  font: inherit;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;

  &:hover { background: $supply-panel; }

  &--active {
    border-color: $supply-ink;
    background: $supply-ink;
    color: #ffffff;
  }
}

.lumina__supply-preview {
  margin: -4px 0 0;
  text-align: center;
  font-size: 0.9rem;
  color: $supply-muted;
}

.lumina__supply-note {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.8rem;
  color: $supply-muted;

  input {
    padding: 10px 12px;
    border: 1px solid $supply-line;
    border-radius: 10px;
    background: #ffffff;
    color: $supply-ink;
    font: inherit;
    font-size: 0.95rem;

    &::placeholder { color: rgba(15, 23, 42, 0.35); }
    &:focus { outline: 2px solid $supply-focus; outline-offset: 1px; }
  }
}

.lumina__supply-submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: auto;
  min-height: 52px;
  padding: 0 16px;
  border: none;
  border-radius: 12px;
  font: inherit;
  font-size: 1rem;
  font-weight: 700;
  color: #ffffff;
  cursor: pointer;
  transition: background 0.15s ease, opacity 0.15s ease;

  .material-symbols-outlined { font-size: 22px; }

  &:disabled { opacity: 0.45; cursor: default; }

  &--purchase {
    background: $supply-ok;
    &:not(:disabled):hover { background: $supply-ok-dark; }
  }

  // Korrigieren ist weder destruktiv noch "positiv": neutral dunkel.
  &--correction {
    background: $supply-ink;
    &:not(:disabled):hover { background: #1e293b; }
  }
}

.lumina__supply-page-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 18px;
  font-size: 0.85rem;
  color: $supply-muted;
  text-decoration: none;

  .material-symbols-outlined { font-size: 18px; }

  &:hover { color: $supply-ink; text-decoration: underline; }
}
```

- [ ] **Step 4: Dashboard-Suite laufen lassen – alles grün**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/dashboard/dashboard.component.spec.ts
```

Erwartet: alle Tests der Datei grün, insbesondere die 8 der Suite `DashboardComponent (Vorrats-Dialog)`.

- [ ] **Step 5: Produktions-Build zum Budget-Check**

```bash
cd frontend && npx ng build --configuration production 2>&1 | grep -i "dashboard\|budget\|error" | head -20
```

Erwartet: die bekannte 16-kB-**Warnung** für `dashboard.component.scss` (Baseline), **kein** `ERROR` zum Budget, kein Eintrag für `dashboard-pet-supply-dialog.scss` (liegt deutlich unter 16 kB).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.scss frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss
git commit -m "feat(dashboard): Vorrats-Dialog mit Stepper, Schnellwahl und heller Palette

Die alten Formularstyles trugen die Farben des dunklen Dashboards und waren
auf dem weissen Dialog unlesbar. Die Dialog-Styles liegen jetzt in einer
zweiten styleUrl, weil das Style-Budget pro Datei gilt. Der Link zur
Vorrats-Seite fehlt im Tablet-Modus - /pet-food hat keinen Zurueck-Knopf.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Sichtprüfung im Browser

**Files:** keine Änderung erwartet; bei Bedarf `dashboard-pet-supply-dialog.scss` nachziehen.

- [ ] **Step 1: Frontend starten und den Dialog öffnen**

Backend muss lokal laufen (`mvn spring-boot:run` aus `backend/` mit JDK 21, `ZIGBEE_MQTT_CLIENT_ID=household-manager-zigbee-dev` setzen). Dann `npm start` aus `frontend/`, `http://localhost:4200` öffnen, anmelden, im Dashboard-Footer auf die Futter-Kachel klicken.

Prüfen:
- Labels und Eingaben sind dunkel auf hell lesbar; keine grauen Kästen mehr.
- Stepper `−`/`+` ändern den Wert in 0,5er-Schritten; Einkauf fällt nicht unter 0,5, Korrektur nicht unter 0.
- Chips zeigen `+12`, `+24`, `Auffüllen · +43` (bei 5 von 48) und setzen den Betrag; der gewählte Chip ist dunkel hervorgehoben.
- Korrektur-Vorschau reagiert auf jede Änderung („−0,5 Dosen gegenüber jetzt").
- Nach „Zubuchen" erscheint die grüne Erfolgszeile und die Zusammenfassung springt um.
- Ansicht auf Tablet umschalten (Ansichtsmodus-Toggle im Header): der Link zur Vorrats-Seite ist weg, sonst identisch.
- Browserfenster auf ~600 px schmal ziehen: die zwei Karten stehen untereinander, nichts läuft über.

- [ ] **Step 2: Bei Abweichungen nur die SCSS anpassen und erneut committen**

```bash
git add frontend/src/app/pages/dashboard/dashboard-pet-supply-dialog.scss
git commit -m "style(dashboard): Feinschliff Vorrats-Dialog

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: CLAUDE.md nachziehen

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Toni-Vorräte", API-Absatz und Dashboard-Absatz, ca. Zeile 585 und 588)

- [ ] **Step 1: API-Absatz ändern**

Im API-Absatz (beginnt mit `- API \`/api/v1/pet-supplies\`:`) den Teil

```
Lesen KIOSK (generische `GET /v1/**`-Regel), Schreiben MEMBER (`anyRequest`-Regel — bewusst **keine** eigene Zeile in `SecurityConfig`, `SecurityRulesTest` hält beide Richtungen fest);
```

ersetzen durch

```
Lesen KIOSK (generische `GET /v1/**`-Regel); **`POST /{key}/purchases` und `POST /{key}/corrections` stehen seit 2026-09-11 in der KIOSK-POST-Whitelist** (der Erfassungs-Dialog läuft auf dem Wandtablet — vorher öffnete er dort, das Speichern lief in 403), `PUT /{key}/target` bleibt MEMBER über die `anyRequest`-Regel; `SecurityRulesTest` hält alle drei Richtungen fest;
```

- [ ] **Step 2: Dashboard-Absatz ändern**

Im Dashboard-Absatz (beginnt mit `- Dashboard: **eine Footer-Kachel je Vorrat**`) den Teil

```
Klick öffnet den **Erfassungs-Dialog** (Einkauf zubuchen + Bestand korrigieren; Buchungen sind MEMBER — auf dem KIOSK-Wandtablet öffnet der Dialog, das Speichern liefert aber 403); Historie und Zielbestand nur auf der Seite (Link im Dialog)
```

ersetzen durch

```
Klick öffnet den **Erfassungs-Dialog** (Einkauf zubuchen + Bestand korrigieren, auch auf dem KIOSK-Wandtablet). Mengeneingabe über Stepper (`−`/`+` im Raster des Vorrats) plus editierbares Zahlenfeld, beim Einkauf zusätzlich Schnellwahl-Chips (¼ Ziel, ½ Ziel, „Auffüllen" = Ziel − Bestand — aus dem Vorrat abgeleitet, keine Artikelzahlen im Frontend), bei der Korrektur eine Vorschau der Differenz; Rechenregeln in `shared/pet-supply-entry.util.ts`. **Die Dialog-Styles liegen in `dashboard-pet-supply-dialog.scss`, der zweiten `styleUrl` der Dashboard-Komponente** — das `anyComponentStyle`-Budget gilt pro Datei, und `dashboard.component.scss` steht kurz vor der Fehlergrenze; die lumina-Kapselung bleibt, weil beide Dateien derselben Komponente gehören. Der Dialog ist weiß — neue Styles dort brauchen die helle Palette, nicht die Werte des dunklen Dashboards (genau das machte den Dialog vorher unlesbar). Historie und Zielbestand nur auf der Seite; **der Link dorthin fehlt im Tablet-Modus** — `/pet-food` hat keinen Zurück-Knopf, das Tablet wäre dort gefangen
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Vorrats-Dialog auf dem Wandtablet in CLAUDE.md

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Gesamtlauf

- [ ] **Step 1: Backend-Tests komplett**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test 2>&1 | tail -30
```

Erwartet: nur die zwei vorbestehenden DB-Fehler (`contextLoads`, `HealthControllerTest`), sonst grün.

- [ ] **Step 2: Frontend-Tests komplett**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless 2>&1 | tail -15
```

Erwartet: `3 FAILED` (Baseline App/Hero), alles andere grün; bei einer `SmartDeviceListComponent`-Flake erneut laufen lassen.

- [ ] **Step 3: Nichts Uncommittetes übrig**

```bash
git status --short
```

Erwartet: leer.
