import { TestBed } from '@angular/core/testing';
import { SwitchListComponent } from './switch-list.component';
import { SwitchEntity } from '../../models/switch.model';

describe('SwitchListComponent', () => {
  const entity = (overrides: Partial<SwitchEntity> = {}): SwitchEntity => ({
    entityId: 'switch.kasa_abc',
    domain: 'SWITCH',
    source: 'KASA',
    displayName: 'Stehlampe',
    state: 'on',
    available: true,
    icon: 'toggle_on',
    toggleCount: 3,
    lastToggledAt: null,
    ...overrides
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SwitchListComponent]
    }).compileComponents();
  });

  function render(switches: SwitchEntity[], pendingIds = new Set<string>()) {
    const fixture = TestBed.createComponent(SwitchListComponent);
    fixture.componentRef.setInput('switches', switches);
    fixture.componentRef.setInput('pendingIds', pendingIds);
    fixture.detectChanges();
    return fixture;
  }

  it('zeigt jeden Schalter mit Namen an', () => {
    const fixture = render([entity(), entity({ entityId: 'switch.kasa_x', displayName: 'Ventilator' })]);

    const text = (fixture.nativeElement as HTMLElement).textContent;
    expect(text).toContain('Stehlampe');
    expect(text).toContain('Ventilator');
  });

  it('beschriftet den Zustand', () => {
    const fixture = render([entity({ state: 'on' })]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('An');
  });

  it('zeigt nicht verfuegbare Schalter als solche', () => {
    const fixture = render([entity({ state: 'unavailable', available: false })]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nicht verfügbar');
  });

  it('emittiert den Schalter beim Klick', () => {
    const fixture = render([entity()]);
    const emitted: SwitchEntity[] = [];
    fixture.componentInstance.toggled.subscribe(item => emitted.push(item));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button')!;
    button.click();

    expect(emitted.length).toBe(1);
    expect(emitted[0].entityId).toBe('switch.kasa_abc');
  });

  it('deaktiviert Zeilen mit laufendem Schaltbefehl', () => {
    const fixture = render([entity()], new Set(['switch.kasa_abc']));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button')!;
    expect(button.disabled).toBeTrue();
  });

  it('zeigt bei leerer Liste nichts an', () => {
    const fixture = render([]);

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('button').length).toBe(0);
  });
});
