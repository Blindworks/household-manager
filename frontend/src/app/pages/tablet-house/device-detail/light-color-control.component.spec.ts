import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { LightColorControlComponent } from './light-color-control.component';
import { BrightnessOutcome, HouseDeviceControlService } from '../house-device-control.service';
import { LightExtras } from '../../../shared/house/detail-extras.util';
import { LightColorRequest } from '../../../shared/house/light-color.util';

class FakeControl {
  calls: { entityId: string; request: LightColorRequest }[] = [];
  response: Observable<BrightnessOutcome> = of('set');
  setLightColor(entityId: string, request: LightColorRequest) {
    this.calls.push({ entityId, request });
    return this.response;
  }
}

function extras(overrides: Partial<LightExtras> = {}): LightExtras {
  return {
    dimmable: true, brightness: 80, supportsColor: true, supportsColorTemp: true,
    hue: null, saturation: null, colorTemp: null, colorTempRange: { min: 2500, max: 6500 },
    ...overrides
  };
}

describe('LightColorControlComponent', () => {
  let fixture: ComponentFixture<LightColorControlComponent>;
  let control: FakeControl;
  let changed: number;

  function create(color: LightExtras, lampOn: boolean | null = true): HTMLElement {
    fixture = TestBed.createComponent(LightColorControlComponent);
    fixture.componentRef.setInput('entityId', 'switch.flur');
    fixture.componentRef.setInput('color', color);
    fixture.componentRef.setInput('lampOn', lampOn);
    fixture.componentInstance.changed.subscribe(() => changed++);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const swatches = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLButtonElement>('.light-color__swatch'));
  const slider = (el: HTMLElement) => el.querySelector<HTMLInputElement>('input[type=range]');
  const swatch = (el: HTMLElement, label: string) =>
    swatches(el).find(s => s.getAttribute('aria-label') === label)!;

  beforeEach(() => {
    control = new FakeControl();
    changed = 0;
    TestBed.configureTestingModule({
      imports: [LightColorControlComponent],
      providers: [{ provide: HouseDeviceControlService, useValue: control }]
    });
  });

  it('COLOR und COLOR_TEMP: 8 Felder und ein Regler', () => {
    const el = create(extras());
    expect(swatches(el).length).toBe(8);
    expect(slider(el)).not.toBeNull();
  });

  it('nur COLOR_TEMP: keine Felder, aber der Regler', () => {
    const el = create(extras({ supportsColor: false }));
    expect(swatches(el).length).toBe(0);
    expect(slider(el)).not.toBeNull();
  });

  it('nur COLOR: 7 Felder ohne Warmweiss, kein Regler', () => {
    const el = create(extras({ supportsColorTemp: false }));
    expect(swatches(el).length).toBe(7);
    expect(swatches(el).some(s => s.getAttribute('aria-label') === 'Warmweiß')).toBeFalse();
    expect(slider(el)).toBeNull();
  });

  it('Regler bekommt Bereich und Schritt', () => {
    const el = create(extras({ colorTempRange: { min: 2700, max: 6000 }, colorTemp: 4000 }));
    const range = slider(el)!;
    expect(range.min).toBe('2700');
    expect(range.max).toBe('6000');
    expect(range.step).toBe('100');
    expect(range.value).toBe('4000');
  });

  it('Tipp auf Blau sendet Farbe und meldet changed', () => {
    const el = create(extras());
    swatch(el, 'Blau').click();
    expect(control.calls).toEqual([{ entityId: 'switch.flur', request: { hue: 225, saturation: 100 } }]);
    expect(changed).toBe(1);
  });

  it('bei ausgeschalteter Lampe ist alles gesperrt und nichts wird gesendet', () => {
    const el = create(extras(), false);
    expect(swatches(el).every(s => s.disabled)).toBeTrue();
    expect(slider(el)!.disabled).toBeTrue();
    swatch(el, 'Blau').click();
    expect(control.calls.length).toBe(0);
  });

  it('lamp-off zeigt „Lampe ist aus“ und meldet changed', () => {
    control.response = of('lamp-off');
    const el = create(extras());
    swatch(el, 'Rot').click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Lampe ist aus');
    expect(changed).toBe(1);
  });

  it('Fehler zeigt „Nicht übernommen“ und der Reglerwert bleibt stehen', () => {
    control.response = throwError(() => new Error('x'));
    const el = create(extras({ colorTemp: 3000 }));
    const range = slider(el)!;
    range.value = '5200';
    range.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(control.calls[0].request).toEqual({ colorTemp: 5200 });
    expect(el.textContent).toContain('Nicht übernommen');
    expect(range.value).toBe('5200');
    expect(changed).toBe(0);
  });

  it('Regler sendet bei change und meldet changed', () => {
    const el = create(extras());
    const range = slider(el)!;
    range.value = '4500';
    range.dispatchEvent(new Event('change'));
    expect(control.calls[0].request).toEqual({ colorTemp: 4500 });
    expect(changed).toBe(1);
  });

  it('aktuelle Farbe: Blau trägt aria-pressed und die Aktiv-Klasse', () => {
    const el = create(extras({ hue: 220, saturation: 100, colorTemp: 0 }));
    const blue = swatch(el, 'Blau');
    expect(blue.getAttribute('aria-pressed')).toBe('true');
    expect(blue.classList).toContain('light-color__swatch--active');
    expect(swatch(el, 'Rot').getAttribute('aria-pressed')).toBe('false');
  });
});
