import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { EntitiesComponent } from './entities.component';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState } from '../../models/entity-state.model';

describe('EntitiesComponent', () => {
  let service: jasmine.SpyObj<EntityStateService>;

  const entity = (overrides: Partial<EntityState>): EntityState => ({
    entityId: 'switch.kasa_wm',
    domain: 'SWITCH',
    source: 'KASA',
    sourceRef: 'wm',
    friendlyName: 'Waschmaschine',
    displayName: 'Waschmaschine',
    state: 'off',
    attributes: {},
    lastChanged: '2026-07-20T10:00:00',
    lastUpdated: '2026-07-20T10:00:00',
    ...overrides
  });

  beforeEach(async () => {
    service = jasmine.createSpyObj('EntityStateService', ['getEntities', 'setTileVisibility']);
    service.getEntities.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [EntitiesComponent],
      providers: [{ provide: EntityStateService, useValue: service }]
    }).compileComponents();
  });

  const createComponent = (): EntitiesComponent =>
    TestBed.createComponent(EntitiesComponent).componentInstance;

  it('bietet die Kachel-Einstellung nur fuer schaltbare Entitaeten an', () => {
    const component = createComponent();

    expect(component.isSwitchTileConfigurable(entity({ domain: 'SWITCH' }))).toBeTrue();
    expect(component.isSwitchTileConfigurable(entity({ domain: 'INPUT_BOOLEAN' }))).toBeTrue();
    expect(component.isSwitchTileConfigurable(entity({ domain: 'SENSOR' }))).toBeFalse();
    // Haus-Modi haben eine eigene Leiste und keine Kachel-Einstellung.
    expect(component.isSwitchTileConfigurable(
      entity({ domain: 'INPUT_BOOLEAN', attributes: { mode: true } }))).toBeFalse();
  });

  it('liefert AUTO als Standard-Sichtbarkeit', () => {
    const component = createComponent();

    expect(component.tileVisibilityOf(entity({}))).toBe('AUTO');
    expect(component.tileVisibilityOf(entity({ tileVisibility: { switches: 'WHEN_ON' } })))
      .toBe('WHEN_ON');
  });

  it('speichert die Sichtbarkeit und uebernimmt die aktualisierte Entitaet', () => {
    const updated = entity({ tileVisibility: { switches: 'WHEN_ON' } });
    service.setTileVisibility.and.returnValue(of(updated));
    const component = createComponent();
    component.entities.set([entity({})]);

    component.setTileVisibility(entity({}), 'WHEN_ON');

    expect(service.setTileVisibility).toHaveBeenCalledWith('switch.kasa_wm', 'switches', 'WHEN_ON');
    expect(component.entities()[0].tileVisibility?.['switches']).toBe('WHEN_ON');
  });
});
