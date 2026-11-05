import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { TabletHouseComponent } from './tablet-house.component';
import { HOUSE_RENDERER_FACTORY, HouseSceneRenderer, ScreenPoint } from './house-scene-renderer';
import { WeatherService } from '../../services/weather.service';
import { AppearanceService } from '../../services/appearance.service';
import { EntityStateService } from '../../services/entity-state.service';
import { ViewModeService } from '../../services/view-mode.service';
import { WeatherOverview } from '../../models/weather.model';
import { EntityState } from '../../models/entity-state.model';
import { Vec3, cameraForOverview, cameraForRoom } from '../../shared/house/house-geometry.util';

const MIN = 60_000;
const ALEXA = 'sensor.alexa_gaj2300425330047';

function localIso(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function entity(entityId: string, state: string, ageMin = 1): EntityState {
  const at = localIso(Date.now() - ageMin * MIN);
  return {
    entityId, domain: 'SENSOR', source: 'X', sourceRef: 'x', friendlyName: entityId, displayName: entityId,
    state, attributes: {}, lastChanged: at, lastUpdated: at
  };
}

/** Ersetzt den WebGL-Renderer: zeichnet nichts, zeichnet aber jeden Aufruf auf. */
class FakeRenderer {
  readonly setFloor = jasmine.createSpy('setFloor');
  readonly flyTo = jasmine.createSpy('flyTo').and.returnValue(Promise.resolve());
  readonly pickRoom = jasmine.createSpy('pickRoom').and.returnValue(null);
  readonly project = jasmine.createSpy('project').and.callFake(
    (points: Vec3[]): ScreenPoint[] => points.map((_, i) => ({ x: 10 * i, y: 20, visible: true })));
  readonly requestRender = jasmine.createSpy('requestRender');
  readonly dispose = jasmine.createSpy('dispose');
  readonly renderedCallbacks: (() => void)[] = [];
  readonly onRendered = jasmine.createSpy('onRendered').and.callFake((cb: () => void) => {
    this.renderedCallbacks.push(cb);
  });
  host: HTMLElement | null = null;

  /** Simuliert einen gezeichneten Frame. */
  rendered(): void {
    this.renderedCallbacks.forEach(cb => cb());
  }
}

describe('TabletHouseComponent', () => {
  let fixture: ComponentFixture<TabletHouseComponent>;
  let fake: FakeRenderer;
  let entityService: jasmine.SpyObj<EntityStateService>;
  let tabletView: WritableSignal<boolean>;

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const roomBubbles = (): HTMLElement[] => Array.from(el().querySelectorAll<HTMLElement>('.house-bubble--room'));
  const deviceBubbles = (): HTMLElement[] => Array.from(el().querySelectorAll<HTMLElement>('.house-bubble--device'));
  const labelOf = (b: HTMLElement): string => b.querySelector('.house-bubble__label')?.textContent?.trim() ?? '';
  const bubbleLabels = (): string[] => roomBubbles().map(labelOf);
  const bubble = (label: string): HTMLElement => roomBubbles().find(b => labelOf(b) === label)!;
  const device = (id: string): HTMLElement | null =>
    el().querySelector<HTMLElement>(`.house-bubble--device[data-device="${id}"]`);
  const scene = (): HTMLElement => el().querySelector('.tablet-house__scene') as HTMLElement;
  const click = (target: HTMLElement): void => {
    target.click();
    fixture.detectChanges();
  };
  const tap = (fromX: number, toX: number, y = 100): void => {
    scene().dispatchEvent(new PointerEvent('pointerdown', { clientX: fromX, clientY: y, bubbles: true }));
    scene().dispatchEvent(new PointerEvent('pointerup', { clientX: toX, clientY: y, bubbles: true }));
    fixture.detectChanges();
  };
  const create = (): void => {
    fixture = TestBed.createComponent(TabletHouseComponent);
    fixture.detectChanges();
  };

  beforeEach(async () => {
    fake = new FakeRenderer();
    entityService = jasmine.createSpyObj('EntityStateService', ['getEntities']);
    entityService.getEntities.and.returnValue(of([]));
    tabletView = signal(false);
    const weather = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weather.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletHouseComponent],
      providers: [
        provideRouter([]),
        { provide: WeatherService, useValue: weather },
        { provide: AppearanceService, useValue: { isLight$: of(false) } },
        { provide: EntityStateService, useValue: entityService },
        { provide: ViewModeService, useValue: { isTabletView: tabletView } },
        {
          provide: HOUSE_RENDERER_FACTORY,
          useValue: (host: HTMLElement) => {
            fake.host = host;
            return fake as unknown as HouseSceneRenderer;
          }
        }
      ]
    }).compileComponents();
  });

  afterEach(() => fixture?.destroy());

  describe('Grundfunktionen', () => {
    beforeEach(() => create());

    it('steckt in der Tablet-Shell mit Überschrift „Haus“', () => {
      const shell = el().querySelector('app-tablet-shell');
      expect(shell).not.toBeNull();
      expect(shell!.querySelector('.lumina__heading')?.textContent?.trim()).toBe('Haus');
    });

    it('erzeugt den Renderer im Szenen-Host und gibt ihn beim Zerstören frei', () => {
      expect(fake.host).toBe(scene());
      fixture.destroy();
      expect(fake.dispose).toHaveBeenCalled();
    });

    it('zeigt je Raum der gewählten Etage eine Raum-Bubble mit dem Raumnamen', () => {
      expect(roomBubbles().length).toBe(6);
      expect(bubbleLabels()).toContain('Wohnzimmer');
      expect(bubbleLabels()).not.toContain('Schlafzimmer');
    });

    it('Etagenwahl wechselt die Bubbles und ruft setFloor', () => {
      click(el().querySelector('[data-floor="upper"]') as HTMLElement);

      expect(fake.setFloor).toHaveBeenCalledWith('upper');
      expect(bubbleLabels()).toContain('Schlafzimmer');
      expect(bubbleLabels()).not.toContain('Wohnzimmer');
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForOverview('upper'));
    });

    it('Tipp auf eine Raum-Bubble fliegt in den Raum', () => {
      expect(el().querySelector('.tablet-house__back')).toBeNull();

      click(bubble('Wohnzimmer'));

      expect(fake.flyTo).toHaveBeenCalledWith(cameraForRoom('wohnzimmer'));
      expect(el().querySelector('.tablet-house__back')).not.toBeNull();
    });

    it('Zurück fliegt in den Überblick', () => {
      click(bubble('Wohnzimmer'));
      fake.flyTo.calls.reset();

      click(el().querySelector('.tablet-house__back') as HTMLElement);

      expect(fake.flyTo).toHaveBeenCalledWith(cameraForOverview('ground'));
      expect(el().querySelector('.tablet-house__back')).toBeNull();
    });

    it('Tipp in die Szene fliegt in den getroffenen Raum', () => {
      fake.pickRoom.and.returnValue('kueche');

      tap(100, 101);

      expect(fake.pickRoom).toHaveBeenCalledWith(101, 100);
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForRoom('kueche'));
    });

    it('Ziehen ist kein Tipp', () => {
      fake.pickRoom.and.returnValue('kueche');
      fake.flyTo.calls.reset();

      tap(100, 130);

      expect(fake.pickRoom).not.toHaveBeenCalled();
      expect(fake.flyTo).not.toHaveBeenCalled();

      tap(100, 105);
      expect(fake.pickRoom).toHaveBeenCalledWith(105, 100);
    });

    it('Tipp ins Leere in der Raumansicht kehrt in den Überblick zurück', () => {
      click(bubble('Wohnzimmer'));
      fake.flyTo.calls.reset();
      fake.pickRoom.and.returnValue(null);

      tap(100, 100);

      expect(fake.flyTo).toHaveBeenCalledWith(cameraForOverview('ground'));
      expect(el().querySelector('.tablet-house__back')).toBeNull();
    });

    it('Tipp auf einen anderen Raum in der Raumansicht wechselt dorthin', () => {
      click(bubble('Wohnzimmer'));
      fake.pickRoom.and.returnValue('kueche');

      tap(100, 100);

      expect(fake.flyTo).toHaveBeenCalledWith(cameraForRoom('kueche'));
      expect(device('kueche-thermostat-4')).not.toBeNull();
    });

    it('Tipp ins Leere im Überblick bleibt im Überblick', () => {
      fake.flyTo.calls.reset();

      tap(100, 100);

      expect(fake.flyTo).not.toHaveBeenCalled();
    });

    it('setzt die Bubble-Positionen nach jedem gezeichneten Frame direkt', () => {
      fake.rendered();

      const transforms = roomBubbles().map(b => b.style.transform);
      expect(transforms.every(t => t.includes('translate('))).toBeTrue();
      expect(roomBubbles()[1].style.transform).toContain('10px');
      expect(roomBubbles().every(b => b.style.visibility === 'visible')).toBeTrue();
    });

    it('blendet Bubbles hinter der Kamera aus', () => {
      fake.project.and.callFake((points: Vec3[]) => points.map(() => ({ x: 0, y: 0, visible: false })));
      fake.rendered();

      expect(roomBubbles().every(b => b.style.visibility === 'hidden')).toBeTrue();
    });
  });

  describe('Live-Daten', () => {
    const wohnzimmerData = (): EntityState[] => [
      entity(`${ALEXA}_temperature`, '19.5'),
      entity(`${ALEXA}_humidity`, '45'),
      entity('binary_sensor.zigbee_terassentuer_contact', 'on')
    ];

    it('lädt beim Start einmal und dann alle 30 s', fakeAsync(() => {
      create();
      expect(entityService.getEntities).toHaveBeenCalledTimes(1);

      tick(30000);

      expect(entityService.getEntities).toHaveBeenCalledTimes(2);
      fixture.destroy();
      discardPeriodicTasks();
    }));

    it('Erstabruf-Fehler zeigt eine Fehlermeldung, Refresh-Fehler behält die Werte', fakeAsync(() => {
      entityService.getEntities.and.returnValue(throwError(() => new Error('weg')));
      create();
      expect(el().querySelector('.tablet-house__error')).not.toBeNull();

      entityService.getEntities.and.returnValue(of(wohnzimmerData()));
      tick(30000);
      fixture.detectChanges();
      expect(el().querySelector('.tablet-house__error')).toBeNull();
      expect(bubble('Wohnzimmer').textContent).toContain('19,5°');

      entityService.getEntities.and.returnValue(throwError(() => new Error('weg')));
      tick(30000);
      fixture.detectChanges();
      expect(el().querySelector('.tablet-house__error')).toBeNull();
      expect(bubble('Wohnzimmer').textContent).toContain('19,5°');

      fixture.destroy();
      discardPeriodicTasks();
    }));

    it('Raum-Bubble zeigt Temperatur und Flag-Symbole', () => {
      entityService.getEntities.and.returnValue(of(wohnzimmerData()));
      create();

      const wohnzimmer = bubble('Wohnzimmer');
      expect(wohnzimmer.textContent).toContain('19,5°');
      expect(wohnzimmer.querySelector('.house-bubble__flag--window_open')).not.toBeNull();
      expect(bubble('Küche').querySelector('.house-bubble__flag')).toBeNull();
    });

    it('veraltete Werte sind abgedunkelt', () => {
      entityService.getEntities.and.returnValue(of([entity(`${ALEXA}_temperature`, '19.5', 120)]));
      create();

      expect(bubble('Wohnzimmer').classList).toContain('house-bubble--stale');
    });

    it('frische Werte sind nicht abgedunkelt', () => {
      entityService.getEntities.and.returnValue(of(wohnzimmerData()));
      create();

      expect(bubble('Wohnzimmer').classList).not.toContain('house-bubble--stale');
    });

    it('Raumansicht zeigt die Geräte-Bubbles des Raums, Überblick nicht', () => {
      create();
      expect(device('wohnzimmer-thermostat-8')).toBeNull();

      click(bubble('Wohnzimmer'));

      expect(device('wohnzimmer-thermostat-8')).not.toBeNull();
      expect(device('wohnzimmer-tapo-802366e0')).not.toBeNull();
      expect(device('kueche-thermostat-4')).toBeNull();
      expect(deviceBubbles().every(b => b.dataset['area'] === 'wohnzimmer')).toBeTrue();

      click(el().querySelector('.tablet-house__back') as HTMLElement);
      expect(device('wohnzimmer-thermostat-8')).toBeNull();
    });

    it('Tipp auf eine Geräte-Bubble navigiert zum Ziel', () => {
      create();
      const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.returnValue(Promise.resolve(true));
      click(bubble('Wohnzimmer'));

      click(device('wohnzimmer-thermostat-8')!);
      expect(navigate).toHaveBeenCalledWith('/tablet/heating');

      tabletView.set(true);
      fixture.detectChanges();
      click(device('wohnzimmer-tapo-802366e0')!);
      expect(navigate).toHaveBeenCalledWith('/');
    });

    it('Außen-Bubbles (Mülltonne, Toni, Haustür, Garten) sind im Überblick des EG sichtbar und navigieren direkt', () => {
      create();
      const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.returnValue(Promise.resolve(true));
      fake.flyTo.calls.reset();

      expect(device('front-waste')).not.toBeNull();
      expect(device('front-pet-toni')).not.toBeNull();
      expect(el().querySelector('.house-bubble--device[data-device^="front-contact"]')).not.toBeNull();
      expect(device('garden-climate')).not.toBeNull();
      expect(roomBubbles().length).toBe(6);

      click(device('front-waste')!);
      expect(navigate).toHaveBeenCalledWith('/waste-collection');
      expect(fake.flyTo).not.toHaveBeenCalled();

      click(el().querySelector('[data-floor="upper"]') as HTMLElement);
      expect(device('front-waste')).toBeNull();
    });

    it('positioniert Geräte-Bubbles je Frame', () => {
      create();
      click(bubble('Wohnzimmer'));
      fake.rendered();

      expect(deviceBubbles().length).toBeGreaterThan(0);
      expect(deviceBubbles().every(b => b.style.transform.includes('translate('))).toBeTrue();
    });

    it('fordert nach einem Abruf ein Neuzeichnen an', () => {
      create();
      expect(fake.requestRender).toHaveBeenCalled();
    });
  });

  describe('Höhenkette', () => {
    beforeEach(() => create());

    it('gibt der Bühne die Bildschirmhöhe statt der Inhaltshöhe', () => {
      // Eigener Container statt des Elternknotens - siehe tablet-network-Spec.
      const host = el();
      const frame = document.createElement('div');
      frame.style.display = 'flex';
      frame.style.flexDirection = 'column';
      document.body.appendChild(frame);
      frame.appendChild(host);

      const stageHeight = (): number =>
        (host.querySelector('.tablet-house__stage') as HTMLElement).getBoundingClientRect().height;

      frame.style.height = '600px';
      fixture.detectChanges();
      const small = stageHeight();

      frame.style.height = '900px';
      fixture.detectChanges();
      const large = stageHeight();

      expect(small).toBeGreaterThan(30);
      expect(large).toBeGreaterThanOrEqual(small + 200);

      document.body.appendChild(host);
      frame.remove();
    });
  });
});
