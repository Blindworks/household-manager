import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
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
});
