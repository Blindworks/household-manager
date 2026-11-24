import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CommunicatorCard } from '../../../models/communicator.model';
import { ModeEntity } from '../../../models/mode.model';
import { SwitchEntity } from '../../../models/switch.model';
import { Thermostat } from '../../../models/thermostat.model';
import { ModeService } from '../../../services/mode.service';
import { NukiService } from '../../../services/nuki.service';
import { SwitchService } from '../../../services/switch.service';
import { ThermostatService } from '../../../services/thermostat.service';
import { CommunicatorCardComponent } from './communicator-card.component';

describe('CommunicatorCardComponent', () => {
  let fixture: ComponentFixture<CommunicatorCardComponent>;
  let component: CommunicatorCardComponent;
  let thermostats: jasmine.SpyObj<ThermostatService>;
  let switches: jasmine.SpyObj<SwitchService>;
  let modes: jasmine.SpyObj<ModeService>;
  let nuki: jasmine.SpyObj<NukiService>;

  const flur = (state: string, confirmRequired = false): SwitchEntity =>
    ({ entityId: 'switch.flur', displayName: 'Flur', state, confirmRequired } as SwitchEntity);

  function show(card: CommunicatorCard): void {
    fixture.componentRef.setInput('card', card);
    fixture.detectChanges();
  }

  beforeEach(() => {
    thermostats = jasmine.createSpyObj<ThermostatService>('ThermostatService', ['setTarget']);
    switches = jasmine.createSpyObj<SwitchService>('SwitchService', ['getSwitches', 'toggle']);
    modes = jasmine.createSpyObj<ModeService>('ModeService', ['getModes', 'toggle']);
    nuki = jasmine.createSpyObj<NukiService>('NukiService', ['sendAction']);
    TestBed.configureTestingModule({
      imports: [CommunicatorCardComponent],
      providers: [
        { provide: ThermostatService, useValue: thermostats },
        { provide: SwitchService, useValue: switches },
        { provide: ModeService, useValue: modes },
        { provide: NukiService, useValue: nuki }
      ]
    });
    fixture = TestBed.createComponent(CommunicatorCardComponent);
    component = fixture.componentInstance;
  });

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('zeigt Temperatur gross und ohne Thermostat keinen Sollwert-Regler', () => {
    show({ type: 'climate', title: 'Keller', temperature: 12, stale: true });

    expect(el().querySelector('.card__value')?.textContent).toContain('12,0');
    expect(el().querySelector('.card__warn')).not.toBeNull();
    expect(el().querySelector('.target')).toBeNull();
  });

  it('sammelt mehrere Tipps auf + und schreibt einen Sollwert', fakeAsync(() => {
    thermostats.setTarget.and.returnValue(of({ name: 'Wohnzimmer' } as Thermostat));
    show({ type: 'climate', title: 'Wohnzimmer', temperature: 20.4, thermostatId: 3, targetTemp: 21 });

    component.adjustTarget(0.5);
    component.adjustTarget(0.5);
    component.adjustTarget(0.5);
    expect(thermostats.setTarget).not.toHaveBeenCalled();
    tick(600);

    expect(thermostats.setTarget).toHaveBeenCalledOnceWith(3, 22.5);
    expect(component.view().targetTemp).toBe(22.5);
  }));

  it('begrenzt den Sollwert auf 5 bis 30 Grad', fakeAsync(() => {
    thermostats.setTarget.and.returnValue(of({ name: 'Wohnzimmer' } as Thermostat));
    show({ type: 'climate', title: 'Wohnzimmer', thermostatId: 3, targetTemp: 30 });

    component.adjustTarget(0.5);
    tick(600);

    expect(thermostats.setTarget).toHaveBeenCalledOnceWith(3, 30);
  }));

  it('springt nach einem Schreibfehler auf den letzten bestaetigten Sollwert zurueck', fakeAsync(() => {
    thermostats.setTarget.and.returnValue(throwError(() => new Error('502')));
    show({ type: 'climate', title: 'Wohnzimmer', thermostatId: 3, targetTemp: 21 });

    component.adjustTarget(1);
    tick(600);

    expect(component.view().targetTemp).toBe(21);
    expect(component.error()).toContain('Sollwert');
  }));

  it('liest den Schalter frisch und schaltet nur, wenn er dem Wunsch widerspricht', () => {
    switches.getSwitches.and.returnValue(of([flur('on')]));
    switches.toggle.and.returnValue(of(flur('off')));
    show({ type: 'switch', title: 'Flur', entityId: 'switch.flur', state: 'on' });

    component.toggleOwn();

    expect(switches.toggle).toHaveBeenCalledOnceWith('switch.flur');
    expect(component.view().state).toBe('off');
  });

  it('schaltet nicht, wenn der Schalter anderswo schon umgeschaltet wurde', () => {
    switches.getSwitches.and.returnValue(of([flur('off')]));
    show({ type: 'switch', title: 'Flur', entityId: 'switch.flur', state: 'on' });

    component.toggleOwn();

    expect(switches.toggle).not.toHaveBeenCalled();
    expect(component.view().state).toBe('off');
  });

  it('schaltet ein nicht erreichbares Geraet nie', () => {
    switches.getSwitches.and.returnValue(of([flur('unavailable')]));
    show({ type: 'switch', title: 'Flur', entityId: 'switch.flur', state: 'on' });

    component.toggleOwn();

    expect(switches.toggle).not.toHaveBeenCalled();
    expect(component.error()).toContain('nicht erreichbar');
  });

  it('fragt bei einem geschuetzten Geraet vor dem Ausschalten nach', () => {
    switches.getSwitches.and.returnValue(of([flur('on', true)]));
    switches.toggle.and.returnValue(of(flur('off', true)));
    show({ type: 'switch', title: 'Flur', entityId: 'switch.flur', state: 'on' });

    component.toggleOwn();
    expect(switches.toggle).not.toHaveBeenCalled();
    expect(component.confirmingOff()).toBeTrue();

    component.toggleOwn();
    expect(switches.toggle).toHaveBeenCalledTimes(1);
    expect(component.confirmingOff()).toBeFalse();
  });

  it('schaltet einen Modus ueber die Modus-Endpunkte', () => {
    const nacht = (state: string): ModeEntity =>
      ({ entityId: 'input_boolean.manual_nachtmodus', displayName: 'Nachtmodus', icon: 'bedtime', state });
    modes.getModes.and.returnValue(of([nacht('off')]));
    modes.toggle.and.returnValue(of(nacht('on')));
    show({ type: 'mode', title: 'Nachtmodus', entityId: 'input_boolean.manual_nachtmodus', state: 'off' });

    component.toggleOwn();

    expect(modes.toggle).toHaveBeenCalledOnceWith('input_boolean.manual_nachtmodus');
    expect(switches.getSwitches).not.toHaveBeenCalled();
    expect(component.view().state).toBe('on');
  });

  it('die Schloss-Karte kann nur verriegeln', () => {
    nuki.sendAction.and.returnValue(of(undefined));
    show({ type: 'lock', title: 'Haustür', smartlockId: 17, state: 'unlocked', doorState: 'off' });

    const buttons = Array.from(el().querySelectorAll('button')).map(b => b.textContent?.trim());
    expect(buttons).toEqual(['lock Verriegeln']);
    (el().querySelector('.action') as HTMLButtonElement).click();

    expect(nuki.sendAction).toHaveBeenCalledOnceWith(17, 'LOCK');
  });

  it('ein verriegeltes Schloss bietet keinen Knopf an', () => {
    show({ type: 'lock', title: 'Haustür', smartlockId: 17, state: 'locked' });

    expect(el().querySelector('.action')).toBeNull();
  });

  it('ein Sollwert-Vorschlag der eigenen Karte setzt den Regler', fakeAsync(() => {
    thermostats.setTarget.and.returnValue(of({ name: 'Wohnzimmer' } as Thermostat));
    show({ type: 'climate', title: 'Wohnzimmer', thermostatId: 3, targetTemp: 21,
      suggestions: [{ label: 'Aus', kind: 'thermostat-target', thermostatId: 3, value: 5 }] });

    (el().querySelector('.suggestions__chip') as HTMLButtonElement).click();
    tick(600);

    expect(thermostats.setTarget).toHaveBeenCalledOnceWith(3, 5);
    expect(component.view().targetTemp).toBe(5);
  }));
});
