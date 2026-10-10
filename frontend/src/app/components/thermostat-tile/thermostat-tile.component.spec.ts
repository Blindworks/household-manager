import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { ThermostatTileComponent } from './thermostat-tile.component';
import { Thermostat, ThermostatMode } from '../../models/thermostat.model';

describe('ThermostatTileComponent', () => {
  let fixture: ComponentFixture<ThermostatTileComponent>;
  let component: ThermostatTileComponent;
  let el: HTMLElement;

  const base: Thermostat = {
    id: 1, name: 'Wohnzimmer', online: true, currentTemp: 20.5, targetTemp: 21,
    mode: 'MANUAL', heating: false, batteryLow: false, lastUpdated: '2026-10-09T18:00:00'
  };

  function setThermostat(overrides: Partial<Thermostat> = {}): void {
    fixture.componentRef.setInput('thermostat', { ...base, ...overrides });
    fixture.detectChanges();
  }

  const q = (selector: string): HTMLElement | null => el.querySelector(selector);
  const click = (selector: string): void => {
    (q(selector) as HTMLButtonElement).click();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ThermostatTileComponent] }).compileComponents();
    fixture = TestBed.createComponent(ThermostatTileComponent);
    component = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => fixture.destroy());

  it('drei schnelle Plus-Klicks ergeben nach 800 ms genau ein targetChange mit +1,5', fakeAsync(() => {
    setThermostat();
    const emitted: number[] = [];
    component.targetChange.subscribe(value => emitted.push(value));

    click('.tile__step--up');
    click('.tile__step--up');
    click('.tile__step--up');
    expect(emitted).toEqual([]);
    expect(q('.tile__target')?.textContent).toContain('22,5');

    tick(799);
    expect(emitted).toEqual([]);
    tick(1);
    expect(emitted).toEqual([22.5]);
  }));

  it('uebernimmt einen neuen Serverwert, solange kein Tipp aussteht', () => {
    setThermostat();
    expect(q('.tile__target')?.textContent).toContain('21,0');
    setThermostat({ targetTemp: 19 });
    expect(q('.tile__target')?.textContent).toContain('19,0');
  });

  it('ein ausstehender Tipp gewinnt gegen einen neuen Serverwert', fakeAsync(() => {
    setThermostat();
    click('.tile__step--up');
    setThermostat({ targetTemp: 19 });
    expect(q('.tile__target')?.textContent).toContain('21,5');
    tick(800);
  }));

  it('an der Obergrenze 30 Grad aendert Plus nichts und emittiert nicht', fakeAsync(() => {
    setThermostat({ targetTemp: 30 });
    const emitted: number[] = [];
    component.targetChange.subscribe(value => emitted.push(value));
    click('.tile__step--up');
    tick(800);
    expect(q('.tile__target')?.textContent).toContain('30,0');
    expect(emitted).toEqual([]);
  }));

  it('offline: Knoepfe gesperrt, Strich statt Zahl, Grundtext', () => {
    setThermostat({ online: false, currentTemp: null });
    el.querySelectorAll('button').forEach(button => expect(button.disabled).toBeTrue());
    expect(q('.tile__current')?.textContent?.trim()).toBe('–');
    expect(q('.tile__offline')?.textContent).toContain('Nicht erreichbar');
    expect(q('.tile__chip--offline')).not.toBeNull();
  });

  it('Modus-Klick emittiert modeChange, der aktive Modus ist markiert', () => {
    setThermostat();
    const emitted: ThermostatMode[] = [];
    component.modeChange.subscribe(mode => emitted.push(mode));
    expect(q('.tile__mode--active')?.textContent).toContain('Manuell');

    click('.tile__mode[data-mode="FROST_PROTECTION"]');
    expect(emitted).toEqual(['FROST_PROTECTION']);
  });

  it('der bereits aktive Modus emittiert nichts', () => {
    setThermostat();
    const emitted: ThermostatMode[] = [];
    component.modeChange.subscribe(mode => emitted.push(mode));
    click('.tile__mode[data-mode="MANUAL"]');
    expect(emitted).toEqual([]);
  });

  it('zeigt die Chips passend zu den Flags', () => {
    setThermostat({ heating: true, batteryLow: true });
    expect(q('.tile__chip--heating')).not.toBeNull();
    expect(q('.tile__chip--battery')).not.toBeNull();
    expect(q('.tile__chip--offline')).toBeNull();

    setThermostat({ heating: false, batteryLow: false });
    expect(q('.tile__chip--heating')).toBeNull();
    expect(q('.tile__chip--battery')).toBeNull();
  });

  it('heizt: der Bogen traegt die warme Klasse, sonst die kuehle', () => {
    setThermostat({ heating: true });
    expect(q('.tile__arc')?.classList.contains('tile__arc--warm')).toBeTrue();
    setThermostat({ heating: false });
    expect(q('.tile__arc')?.classList.contains('tile__arc--warm')).toBeFalse();
  });

  it('busy sperrt die Bedienung', () => {
    setThermostat();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    el.querySelectorAll('button').forEach(button => expect(button.disabled).toBeTrue());
  });

  it('zeigt eine inline Fehlermeldung', () => {
    setThermostat();
    fixture.componentRef.setInput('errorMessage', 'Hub nicht erreichbar.');
    fixture.detectChanges();
    expect(q('.tile__error')?.textContent).toContain('Hub nicht erreichbar.');
  });

  it('verwirft den optimistischen Sollwert, wenn eine Fehlermeldung eintrifft', fakeAsync(() => {
    setThermostat();
    click('.tile__step--up');
    tick(800);
    expect(q('.tile__target')?.textContent).toContain('21,5');
    fixture.componentRef.setInput('errorMessage', 'Befehl fehlgeschlagen.');
    fixture.detectChanges();
    expect(q('.tile__target')?.textContent).toContain('21,0');
  }));

  it('verwirft den optimistischen Modus, wenn eine Fehlermeldung eintrifft', () => {
    setThermostat();
    click('.tile__mode:nth-child(2)');
    expect(component.activeMode).toBe('FROST_PROTECTION');
    fixture.componentRef.setInput('errorMessage', 'Befehl fehlgeschlagen.');
    fixture.detectChanges();
    expect(component.activeMode).toBe('MANUAL');
  });

  it('ein Refresh waehrend busy ueberschreibt den lokalen Modus nicht', () => {
    setThermostat();
    click('.tile__mode:nth-child(2)');
    fixture.componentRef.setInput('busy', true);
    setThermostat({ lastUpdated: '2026-10-09T18:01:00' });
    expect(component.activeMode).toBe('FROST_PROTECTION');
  });

  describe('Layout in kleinen Kacheln (Wandtablet)', () => {
    // Die Kachel bezieht ihre Groesse aus dem Raster. Hier ersetzt ein Container
    // mit fester Groesse das Raster; das Fixture wird dafuer umgehaengt (Muster
    // der Hoehenketten-Tests: nicht den <body> als Flex-Spalte missbrauchen).
    function renderIn(widthPx: number, heightPx: number): HTMLElement {
      const box = document.createElement('div');
      box.style.cssText = `display:flex;flex-direction:column;width:${widthPx}px;height:${heightPx}px`;
      document.body.appendChild(box);
      el.style.flex = '1';
      box.appendChild(el);
      setThermostat();
      return box;
    }

    const rect = (selector: string): DOMRect => (q(selector) as HTMLElement).getBoundingClientRect();

    // Aus jeder getesteten Groesse muss gelten: Soll-Zahl, Knoepfe und
    // Modus-Leiste stehen untereinander, ohne sich zu ueberlappen, und enden
    // innerhalb der Kachel.
    [
      { width: 420, height: 260 },
      { width: 420, height: 230 },
      { width: 420, height: 200 },
      { width: 330, height: 260 },
      { width: 330, height: 220 },
      { width: 330, height: 190 },
      { width: 280, height: 190 },
      { width: 560, height: 360 }
    ].forEach(({ width, height }) => {
      it(`ueberlagert nichts in einer ${width}x${height}-Kachel`, () => {
        const box = renderIn(width, height);
        const tile = rect('.tile');
        const target = rect('.tile__setpoint');
        const steps = rect('.tile__steps');
        const modes = rect('.tile__modes');
        const main = rect('.tile__main');
        const head = rect('.tile__head');

        // Der Steuerblock bleibt im Hauptbereich: nach oben nicht in die
        // Kopfzeile, nach unten nicht in den Verlauf.
        expect(target.top).toBeGreaterThanOrEqual(Math.max(main.top, head.bottom) - 1);
        expect(modes.bottom).toBeLessThanOrEqual(main.bottom + 1);
        expect(steps.top).toBeGreaterThanOrEqual(target.bottom - 1);
        expect(modes.top).toBeGreaterThanOrEqual(steps.bottom - 1);
        expect(modes.bottom).toBeLessThanOrEqual(tile.bottom + 1);
        box.remove();
      });
    });

    it('blendet den Verlauf aus, wenn die Kachel dafuer zu niedrig ist, und zeigt ihn in grossen Kacheln', () => {
      const small = renderIn(330, 190);
      expect(getComputedStyle(q('.tile__extra') as HTMLElement).display).toBe('none');
      small.remove();

      fixture.destroy();
      fixture = TestBed.createComponent(ThermostatTileComponent);
      el = fixture.nativeElement as HTMLElement;
      const large = renderIn(560, 360);
      expect(getComputedStyle(q('.tile__extra') as HTMLElement).display).not.toBe('none');
      large.remove();
    });
  });
});
