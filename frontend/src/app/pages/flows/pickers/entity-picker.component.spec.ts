import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { EntityPickerComponent } from './entity-picker.component';
import { EntityStateService } from '../../../services/entity-state.service';

describe('EntityPickerComponent', () => {
  let entityService: jasmine.SpyObj<EntityStateService>;

  beforeEach(async () => {
    entityService = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityService.getEntities.and.returnValue(of([
      { entityId: 'sensor.a', friendlyName: 'Sensor A', state: '5' },
      { entityId: 'switch.b', friendlyName: 'Schalter B', state: 'on' }
    ] as any));
    await TestBed.configureTestingModule({
      imports: [EntityPickerComponent],
      providers: [{ provide: EntityStateService, useValue: entityService }]
    }).compileComponents();
  });

  it('loads options on init', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.options().length).toBe(2);
  });

  it('keeps an unknown selected value as fallback label', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.componentRef.setInput('value', 'sensor.ghost');
    fixture.detectChanges();
    expect(fixture.componentInstance.displayLabel()).toContain('nicht gefunden');
    expect(fixture.componentInstance.displayLabel()).toContain('sensor.ghost');
  });

  it('emits valueChange on select', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v: string) => (emitted = v));
    fixture.componentInstance.select('switch.b');
    expect(emitted).toBe('switch.b');
  });

  it('a real change event on the native <select> emits the chosen entityId', () => {
    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.detectChanges();
    let emitted: string | undefined;
    fixture.componentInstance.valueChange.subscribe((v: string) => (emitted = v));

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    select.value = 'sensor.a';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(emitted).toBe('sensor.a');
  });

  // Regression test for: "ausgewählte Entity wird nicht gespeichert" — the value WAS being
  // persisted correctly (config/save chain), but a saved entityId failed to *display* once
  // the entity list finished loading (a real HttpClient call, always async) after the
  // picker's initial render: a plain `[value]` DOM property binding on <select> is skipped by
  // Angular's change-detection dirty-check on re-render because the bound primitive string is
  // unchanged, even though new <option> elements just appeared — so the browser silently
  // auto-selected an unrelated option (or none) instead of the real saved value. Fixed by
  // switching to [ngModel]/(ngModelChange): NgSelectOption re-invokes writeValue() whenever a
  // new <option> registers, which re-syncs the native selection once options exist.
  it('re-displays an already-saved value once options finish loading asynchronously (fixes non-persistence illusion)', fakeAsync(() => {
    const entities$ = new Subject<any[]>();
    entityService.getEntities.and.returnValue(entities$.asObservable());

    const fixture = TestBed.createComponent(EntityPickerComponent);
    fixture.componentRef.setInput('value', 'switch.b');
    fixture.detectChanges(); // first render: options() still empty, HTTP pending

    let select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    expect(select.options.length).toBe(1); // only the disabled placeholder so far

    entities$.next([
      { entityId: 'sensor.a', friendlyName: 'Sensor A', state: '5' },
      { entityId: 'switch.b', friendlyName: 'Schalter B', state: 'on' }
    ]);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    select = fixture.nativeElement.querySelector('select');
    expect(select.value).toBe('switch.b');
    expect(fixture.componentInstance.displayLabel()).toBe('Schalter B (on)');
  }));
});
