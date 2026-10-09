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
});
