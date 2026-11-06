import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { ThermostatModeControlComponent } from './thermostat-mode-control.component';
import { ActionOutcome, HouseDeviceControlService } from '../house-device-control.service';
import { ThermostatMode } from '../../../models/thermostat.model';
import { TARGET_GRACE_MS } from './detail-texts';

class FakeControl {
  calls: { id: number; mode: ThermostatMode }[] = [];
  response: Observable<ActionOutcome> = of('done');
  setThermostatMode(id: number, mode: ThermostatMode) {
    this.calls.push({ id, mode });
    return this.response;
  }
}

describe('ThermostatModeControlComponent', () => {
  let fixture: ComponentFixture<ThermostatModeControlComponent>;
  let control: FakeControl;
  let changed: number;

  function create(mode: ThermostatMode | null, thermostatId = 8): HTMLElement {
    fixture = TestBed.createComponent(ThermostatModeControlComponent);
    fixture.componentRef.setInput('thermostatId', thermostatId);
    fixture.componentRef.setInput('mode', mode);
    fixture.componentInstance.changed.subscribe(() => changed++);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLButtonElement>('button'));
  const button = (el: HTMLElement, text: string) => buttons(el).find(b => b.textContent!.trim() === text)!;

  beforeEach(() => {
    control = new FakeControl();
    changed = 0;
    TestBed.configureTestingModule({
      imports: [ThermostatModeControlComponent],
      providers: [{ provide: HouseDeviceControlService, useValue: control }]
    });
  });

  it('drei Knoepfe in der Reihenfolge Aus, Manuell, Frostschutz; der aktive ist gedrueckt', () => {
    const el = create('MANUAL');
    expect(buttons(el).map(b => b.textContent!.trim())).toEqual(['Aus', 'Manuell', 'Frostschutz']);
    expect(button(el, 'Manuell').getAttribute('aria-pressed')).toBe('true');
    expect(button(el, 'Aus').getAttribute('aria-pressed')).toBe('false');
  });

  it('Tipp auf Frostschutz schreibt und hebt ihn hervor, auch wenn mode noch MANUAL meldet', () => {
    const el = create('MANUAL');
    button(el, 'Frostschutz').click();
    fixture.detectChanges();
    expect(control.calls).toEqual([{ id: 8, mode: 'FROST_PROTECTION' }]);
    expect(button(el, 'Frostschutz').getAttribute('aria-pressed')).toBe('true');
    expect(button(el, 'Manuell').getAttribute('aria-pressed')).toBe('false');
    expect(changed).toBe(1);
  });

  it('nach der Schonfrist zaehlt wieder mode', fakeAsync(() => {
    const el = create('MANUAL');
    button(el, 'Frostschutz').click();
    fixture.detectChanges();
    tick(TARGET_GRACE_MS);
    fixture.detectChanges();
    expect(button(el, 'Manuell').getAttribute('aria-pressed')).toBe('true');
    expect(button(el, 'Frostschutz').getAttribute('aria-pressed')).toBe('false');
  }));

  it('Tipp auf den aktiven Knopf ruft nichts auf', () => {
    const el = create('MANUAL');
    button(el, 'Manuell').click();
    expect(control.calls.length).toBe(0);
  });

  it('Wechsel der Thermostat-Id setzt den lokalen Modus zurueck', () => {
    const el = create('MANUAL');
    button(el, 'Frostschutz').click();
    fixture.componentRef.setInput('thermostatId', 9);
    fixture.detectChanges();
    expect(button(el, 'Manuell').getAttribute('aria-pressed')).toBe('true');
  });

  it('unavailable zeigt „Gerät nicht erreichbar“ ohne lokalen Modus und ohne changed', () => {
    control.response = of('unavailable');
    const el = create('MANUAL');
    button(el, 'Aus').click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Gerät nicht erreichbar');
    expect(button(el, 'Manuell').getAttribute('aria-pressed')).toBe('true');
    expect(changed).toBe(0);
  });

  it('Fehler zeigt „Gerät nicht erreichbar“', () => {
    control.response = throwError(() => new Error('x'));
    const el = create('MANUAL');
    button(el, 'Aus').click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Gerät nicht erreichbar');
  });

  it('already meldet changed', () => {
    control.response = of('already');
    const el = create('MANUAL');
    button(el, 'Aus').click();
    expect(changed).toBe(1);
  });
});
