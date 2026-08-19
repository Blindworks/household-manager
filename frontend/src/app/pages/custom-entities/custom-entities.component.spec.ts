import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CustomEntitiesComponent } from './custom-entities.component';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState } from '../../models/entity-state.model';

describe('CustomEntitiesComponent', () => {
  let serviceSpy: jasmine.SpyObj<EntityStateService>;

  // Frische Entitaet pro Aufruf statt eines geteilten Consts: toggle/confirmTurnOff
  // arbeiten ueber die entities()-Liste, ein geteiltes Objekt wuerde bei zufaelliger
  // (Jasmine-Standard) Testreihenfolge Zustand zwischen Tests durchsickern lassen.
  const entity = (overrides: Partial<EntityState> = {}): EntityState => ({
    entityId: 'input_boolean.nachtmodus',
    domain: 'INPUT_BOOLEAN',
    source: 'MANUAL',
    sourceRef: 'nachtmodus',
    friendlyName: 'Nachtmodus',
    displayName: 'Nachtmodus',
    state: 'off',
    attributes: {},
    confirmRequired: false,
    lastChanged: '2026-08-19T10:00:00',
    lastUpdated: '2026-08-19T10:00:00',
    ...overrides
  });

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('EntityStateService', [
      'getEntities', 'createManualEntity', 'setManualState', 'toggleManualEntity',
      'renameManualEntity', 'deleteManualEntity'
    ]);
    serviceSpy.getEntities.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [CustomEntitiesComponent],
      providers: [{ provide: EntityStateService, useValue: serviceSpy }]
    }).compileComponents();
  });

  const createComponent = () => {
    const fixture = TestBed.createComponent(CustomEntitiesComponent);
    fixture.detectChanges();
    return fixture;
  };

  describe('Ausschalt-Bestaetigung (INPUT_BOOLEAN-Helfer)', () => {
    it('oeffnet beim Ausschalten den Dialog statt zu schalten', () => {
      const guarded = entity({ confirmRequired: true, state: 'on' });
      serviceSpy.getEntities.and.returnValue(of([guarded]));
      const fixture = createComponent();

      fixture.componentInstance.toggle(guarded);

      expect(serviceSpy.toggleManualEntity).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffEntity()?.entityId).toBe('input_boolean.nachtmodus');
    });

    it('zeigt den Dialog im DOM an und schaltet ueber den Bestaetigen-Knopf', () => {
      const guarded = entity({ confirmRequired: true, state: 'on' });
      serviceSpy.getEntities.and.returnValue(of([guarded]));
      serviceSpy.toggleManualEntity.and.returnValue(of(entity({ confirmRequired: true, state: 'off' })));
      const fixture = createComponent();

      fixture.componentInstance.toggle(guarded);
      fixture.detectChanges();

      const dialog: HTMLElement = fixture.nativeElement.querySelector('.confirm-dialog');
      expect(dialog).not.toBeNull();
      expect(dialog.textContent).toContain('Nachtmodus');
      expect(serviceSpy.toggleManualEntity).not.toHaveBeenCalled();

      dialog.querySelector<HTMLButtonElement>('.confirm-dialog__confirm')!.click();

      expect(serviceSpy.toggleManualEntity).toHaveBeenCalledWith('input_boolean.nachtmodus');
    });

    it('schaltet nicht, wenn ein Refresh den Helfer zwischenzeitlich als aus meldet', () => {
      const guarded = entity({ confirmRequired: true, state: 'on' });
      serviceSpy.getEntities.and.returnValue(of([guarded]));
      const fixture = createComponent();
      fixture.componentInstance.toggle(guarded);

      // Hintergrund-Refresh (alle 10s) ERSETZT die Liste - hier: schon aus.
      fixture.componentInstance.entities.set([{ ...guarded, state: 'off' }]);
      fixture.componentInstance.confirmTurnOff();

      expect(serviceSpy.toggleManualEntity).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffEntity()).toBeNull();
    });

    it('schaltet nach Bestaetigung aus und schliesst den Dialog', () => {
      const guarded = entity({ confirmRequired: true, state: 'on' });
      serviceSpy.getEntities.and.returnValue(of([guarded]));
      serviceSpy.toggleManualEntity.and.returnValue(of(entity({ confirmRequired: true, state: 'off' })));
      const fixture = createComponent();
      fixture.componentInstance.toggle(guarded);

      fixture.componentInstance.confirmTurnOff();

      expect(serviceSpy.toggleManualEntity).toHaveBeenCalledWith('input_boolean.nachtmodus');
      expect(fixture.componentInstance.confirmOffEntity()).toBeNull();
    });

    it('abbrechen schliesst den Dialog ohne zu schalten', () => {
      const guarded = entity({ confirmRequired: true, state: 'on' });
      serviceSpy.getEntities.and.returnValue(of([guarded]));
      const fixture = createComponent();
      fixture.componentInstance.toggle(guarded);

      fixture.componentInstance.closeConfirmOffDialog();

      expect(serviceSpy.toggleManualEntity).not.toHaveBeenCalled();
      expect(fixture.componentInstance.confirmOffEntity()).toBeNull();
    });

    it('einschalten eines geschuetzten Helfers fragt nicht nach', () => {
      const offEntity = entity({ confirmRequired: true, state: 'off' });
      serviceSpy.getEntities.and.returnValue(of([offEntity]));
      serviceSpy.toggleManualEntity.and.returnValue(of(entity({ confirmRequired: true, state: 'on' })));
      const fixture = createComponent();

      fixture.componentInstance.toggle(offEntity);

      expect(serviceSpy.toggleManualEntity).toHaveBeenCalledWith('input_boolean.nachtmodus');
      expect(fixture.componentInstance.confirmOffEntity()).toBeNull();
    });

    it('ungeschuetzter Helfer schaltet weiterhin direkt aus', () => {
      const plain = entity({ confirmRequired: false, state: 'on' });
      serviceSpy.getEntities.and.returnValue(of([plain]));
      serviceSpy.toggleManualEntity.and.returnValue(of(entity({ confirmRequired: false, state: 'off' })));
      const fixture = createComponent();

      fixture.componentInstance.toggle(plain);

      expect(serviceSpy.toggleManualEntity).toHaveBeenCalledWith('input_boolean.nachtmodus');
      expect(fixture.componentInstance.confirmOffEntity()).toBeNull();
    });
  });
});
