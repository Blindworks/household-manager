import { WritableSignal, signal } from '@angular/core';
import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';
import { TabletHouseComponent } from './tablet-house.component';
import { HOUSE_RENDERER_FACTORY, HouseSceneRenderer, ScreenPoint } from './house-scene-renderer';
import { WeatherService } from '../../services/weather.service';
import { AppearanceService } from '../../services/appearance.service';
import { EntityStateService } from '../../services/entity-state.service';
import { ViewModeService } from '../../services/view-mode.service';
import { WeatherOverview } from '../../models/weather.model';
import { EntityState } from '../../models/entity-state.model';
import {
  Vec3, areaFloorBase, cameraForDevice, cameraForOverview, cameraForRoom, toWorldX, toWorldZ
} from '../../shared/house/house-geometry.util';
import { BUBBLE_HEIGHT_M, deviceAnchor, deviceBubbleAnchor } from '../../shared/house/house-placement.util';
import { HouseDevicePositionService } from '../../services/house-device-position.service';
import { HouseOpeningService } from '../../services/house-opening.service';
import { HouseOpening } from '../../models/house-opening.model';
import { OpeningShape } from '../../shared/house/house-openings.util';
import { HOUSE_DEVICES } from '../../shared/house/house-devices';
import { HouseEffect } from '../../shared/house/house-effects.util';
import { BrightnessOutcome, HouseDeviceControlService, SwitchOutcome } from './house-device-control.service';
import { DetailExtras, LightExtras } from '../../shared/house/detail-extras.util';

function lightInfo(brightness: number): LightExtras {
  return {
    dimmable: true, brightness, supportsColor: false, supportsColorTemp: false,
    hue: null, saturation: null, colorTemp: null, colorTempRange: { min: 2500, max: 6500 }
  };
}

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
  readonly setEffects = jasmine.createSpy('setEffects');
  readonly setOpenings = jasmine.createSpy('setOpenings');
  readonly playEffects = jasmine.createSpy('playEffects');
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
  let lightTheme$: BehaviorSubject<boolean>;
  let control: jasmine.SpyObj<HouseDeviceControlService>;
  let positionService: jasmine.SpyObj<HouseDevicePositionService>;
  let openingService: jasmine.SpyObj<HouseOpeningService>;

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
    lightTheme$ = new BehaviorSubject(false);
    control = jasmine.createSpyObj<HouseDeviceControlService>('HouseDeviceControlService',
      ['loadHistory', 'setSwitch', 'setTarget', 'setBrightness', 'loadExtras', 'lampBrightness']);
    control.loadHistory.and.returnValue(of(null));
    control.lampBrightness.and.returnValue(of(new Map<string, number>()));
    control.loadExtras.and.returnValue(of({}));
    control.setSwitch.and.returnValue(of<SwitchOutcome>('switched'));
    positionService = jasmine.createSpyObj<HouseDevicePositionService>('HouseDevicePositionService', ['list']);
    positionService.list.and.returnValue(of([]));
    openingService = jasmine.createSpyObj<HouseOpeningService>('HouseOpeningService', ['list']);
    openingService.list.and.returnValue(of([]));
    const weather = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weather.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletHouseComponent],
      providers: [
        provideRouter([]),
        { provide: WeatherService, useValue: weather },
        { provide: AppearanceService, useValue: { isLight$: lightTheme$ } },
        { provide: EntityStateService, useValue: entityService },
        { provide: HouseDevicePositionService, useValue: positionService },
        { provide: HouseOpeningService, useValue: openingService },
        { provide: ViewModeService, useValue: { isTabletView: tabletView } },
        {
          provide: HOUSE_RENDERER_FACTORY,
          useValue: (host: HTMLElement) => {
            fake.host = host;
            return fake as unknown as HouseSceneRenderer;
          }
        }
      ]
    })
      // Die Seite stellt den Steuer-Dienst selbst bereit; im Test ersetzt ihn ein Spy (auch fuer das Kind).
      .overrideComponent(TabletHouseComponent, {
        set: { providers: [{ provide: HouseDeviceControlService, useValue: control }] }
      })
      .compileComponents();
  });

  afterEach(() => fixture?.destroy());

  describe('Grundfunktionen', () => {
    beforeEach(() => create());

    it('steckt in der Tablet-Shell mit Überschrift „Haus“', () => {
      const shell = el().querySelector('app-tablet-shell');
      expect(shell).not.toBeNull();
      expect(shell!.querySelector('.lumina__heading')?.textContent?.trim()).toBe('Haus');
    });

    it('bleibt auch bei hellem Dashboard-Design dunkel', () => {
      lightTheme$.next(true);
      fixture.detectChanges();
      expect(el().querySelector('.lumina')!.classList).not.toContain('lumina--light');
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
      entity('binary_sensor.dirigera_9defac3086c54acaabc8d1930d87e533_1_contact', 'on')
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

    it('lässt Werte bei andauerndem Refresh-Fehler veralten', fakeAsync(() => {
      entityService.getEntities.and.returnValue(of([entity(`${ALEXA}_temperature`, '19.5', 50)]));
      create();
      const value = (): DOMTokenList => bubble('Wohnzimmer').querySelector('.house-bubble__value')!.classList;
      expect(value()).not.toContain('house-bubble__value--stale');

      // Backend weg: die Werte bleiben stehen, altern aber weiter (Anti-Ziel „veraltet sieht frisch aus“).
      entityService.getEntities.and.returnValue(throwError(() => new Error('weg')));
      tick(11 * MIN);
      fixture.detectChanges();

      expect(bubble('Wohnzimmer').textContent).toContain('19,5°');
      expect(value()).toContain('house-bubble__value--stale');

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

      expect(bubble('Wohnzimmer').querySelector('.house-bubble__value')!.classList)
        .toContain('house-bubble__value--stale');
    });

    it('fehlende Temperatur dunkelt nur den Wert ab, nicht die Hinweise', () => {
      entityService.getEntities.and.returnValue(of([entity('input_boolean.manual_waschmaschine_laeuft', 'on')]));
      create();
      click(el().querySelector('[data-floor="basement"]') as HTMLElement);

      const waschkeller = bubble('Waschkeller');
      expect(waschkeller.classList).not.toContain('house-bubble--stale');
      expect(waschkeller.querySelector('.house-bubble__value')!.classList).toContain('house-bubble__value--stale');
      const flag = waschkeller.querySelector('.house-bubble__flag--appliance_running')!;
      expect(flag).not.toBeNull();
      expect(flag.closest('.house-bubble__value--stale, .house-bubble--stale')).toBeNull();
    });

    it('frische Werte sind nicht abgedunkelt', () => {
      entityService.getEntities.and.returnValue(of(wohnzimmerData()));
      create();

      expect(bubble('Wohnzimmer').classList).not.toContain('house-bubble--stale');
      expect(bubble('Wohnzimmer').querySelector('.house-bubble__value--stale')).toBeNull();
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

    it('Tipp auf eine Kamera-Bubble öffnet das Feld statt zu navigieren, auch im Tablet-Modus', () => {
      create();
      const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.returnValue(Promise.resolve(true));
      click(bubble('Wohnzimmer'));

      click(device('wohnzimmer-camera')!);
      expect(el().querySelector('app-house-device-detail')).not.toBeNull();

      tabletView.set(true);
      fixture.detectChanges();
      click(device('wohnzimmer-camera')!);
      expect(el().querySelector('app-house-device-detail')).not.toBeNull();
      expect(navigate).not.toHaveBeenCalled();
    });

    it('Außen-Bubbles (Mülltonne, Toni, Haustür, Garten) sind im Überblick des EG sichtbar, die Mülltonne öffnet das Feld', () => {
      create();
      const navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.returnValue(Promise.resolve(true));
      fake.flyTo.calls.reset();

      expect(device('front-waste')).not.toBeNull();
      expect(device('front-pet-toni')).not.toBeNull();
      expect(device('front-door')).not.toBeNull();
      expect(device('garden-climate')).not.toBeNull();
      expect(roomBubbles().length).toBe(6);
      expect(deviceBubbles().length).toBe(4);

      click(device('front-waste')!);
      expect(el().querySelector('app-house-device-detail')).not.toBeNull();
      expect(navigate).not.toHaveBeenCalled();

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

  describe('Geräte-Detail', () => {
    const THERMO_TARGET = 'sensor.thermostat_8_target';
    const STEHLAMPE = 'switch.tapo_802366e0c88d0dba80336f9f7363f8c4216c350a';
    const panel = (): HTMLElement | null => el().querySelector('.tablet-house__detail app-house-device-detail');
    const panelText = (): string => panel()?.textContent ?? '';
    const closeButton = (): HTMLElement => el().querySelector('.device-detail__close') as HTMLElement;
    const back = (): HTMLElement => el().querySelector('.tablet-house__back') as HTMLElement;
    const anchorOf = (id: string): Vec3 => (fixture.componentInstance as unknown as {
      deviceBubbles: () => { bubble: { deviceId: string }; anchor: Vec3 }[];
    }).deviceBubbles().find(p => p.bubble.deviceId === id)!.anchor;
    const data = (temperature: string): EntityState[] => [
      entity('sensor.thermostat_8_temperature', temperature),
      entity(THERMO_TARGET, '21'),
      entity(STEHLAMPE, 'on')
    ];
    let navigate: jasmine.Spy;

    beforeEach(() => {
      entityService.getEntities.and.returnValue(of(data('20.5')));
      create();
      navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.returnValue(Promise.resolve(true));
      click(bubble('Wohnzimmer'));
      fake.flyTo.calls.reset();
    });

    it('Tipp auf Geräte-Bubble (opensDetail) ⇒ flyTo(cameraForDevice(anker)), Feld sichtbar, kein navigateByUrl', () => {
      const anchor = anchorOf('wohnzimmer-thermostat-8');

      click(device('wohnzimmer-thermostat-8')!);

      expect(fake.flyTo).toHaveBeenCalledWith(cameraForDevice(anchor));
      expect(panel()).not.toBeNull();
      expect(panelText()).toContain('20,5°');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('Tipp auf die Haustür-Bubble im EG-Überblick öffnet das Feld und navigiert nicht', () => {
      click(back());
      const anchor = anchorOf('front-door');

      click(device('front-door')!);

      expect(panel()).not.toBeNull();
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForDevice(anchor));
      expect(navigate).not.toHaveBeenCalled();
    });

    it('Tipp auf Toni- und Mülltonnen-Bubble öffnet jeweils das Feld, ohne Router', () => {
      click(back());

      for (const id of ['front-pet-toni', 'front-waste']) {
        click(device(id)!);
        expect(panel()).not.toBeNull();
        click(closeButton());
      }
      expect(navigate).not.toHaveBeenCalled();
    });

    it('Bubble ohne bekanntes Gerät (Rückfall) navigiert zum Ziel und öffnet kein Feld', () => {
      fixture.componentInstance.openDevice({
        deviceId: 'gibt-es-nicht', areaId: 'wohnzimmer', label: 'X', icon: 'x', lines: [], target: '/cameras'
      } as unknown as Parameters<TabletHouseComponent['openDevice']>[0]);
      fixture.detectChanges();

      expect(navigate).toHaveBeenCalledWith('/cameras');
      expect(panel()).toBeNull();
      expect(control.loadExtras).not.toHaveBeenCalled();
    });

    it('Tipp auf Garten-Bubble im Überblick ⇒ Feld offen', () => {
      click(back());
      fake.flyTo.calls.reset();
      // Die Kamera faehrt auf Geraetehoehe (nicht auf die Bubble-Hoehe).
      const anchor = deviceAnchor('garden-climate', new Map())!;

      click(device('garden-climate')!);

      expect(panel()).not.toBeNull();
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForDevice(anchor));
      expect(navigate).not.toHaveBeenCalled();

      click(closeButton());
      expect(panel()).toBeNull();
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForOverview('ground'));
    });

    it('✕ oder Tipp ins Leere bei offenem Feld ⇒ Feld zu, flyTo(cameraForRoom(raum)), Raumansicht bleibt', () => {
      click(device('wohnzimmer-thermostat-8')!);
      fake.flyTo.calls.reset();

      click(closeButton());

      expect(panel()).toBeNull();
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForRoom('wohnzimmer'));
      expect(back()).not.toBeNull();

      click(device('wohnzimmer-thermostat-8')!);
      fake.flyTo.calls.reset();
      fake.pickRoom.and.returnValue(null);

      tap(100, 100);

      expect(panel()).toBeNull();
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForRoom('wohnzimmer'));
      expect(fake.flyTo).not.toHaveBeenCalledWith(cameraForOverview('ground'));
      expect(back()).not.toBeNull();
      expect(device('wohnzimmer-thermostat-8')).not.toBeNull();
    });

    it('Ziehen bei offenem Feld schließt nichts', () => {
      click(device('wohnzimmer-thermostat-8')!);

      tap(100, 140);

      expect(panel()).not.toBeNull();
    });

    it('Refresh bei offenem Feld ⇒ detail-Input neu gesetzt, keine Schaltaktion, Feld bleibt offen', fakeAsync(() => {
      // Neu aufbauen, damit der 30-s-Takt in der Fake-Zone laeuft.
      fixture.destroy();
      create();
      click(bubble('Wohnzimmer'));
      click(device('wohnzimmer-thermostat-8')!);
      expect(panelText()).toContain('20,5°');

      entityService.getEntities.and.returnValue(of(data('22')));
      tick(30000);
      fixture.detectChanges();

      expect(panel()).not.toBeNull();
      expect(panelText()).toContain('22,0°');
      expect(control.setSwitch).not.toHaveBeenCalled();
      expect(control.setTarget).not.toHaveBeenCalled();
      expect(control.setBrightness).not.toHaveBeenCalled();
      fixture.destroy();
      discardPeriodicTasks();
    }));

    describe('Nachladen nach einer Aktion', () => {
      const switchButton = (): HTMLElement => el().querySelector('.device-detail__switch') as HTMLElement;
      const openStehlampe = (): void => {
        // Neu aufbauen, damit Takt und Nachlade-Timer in der Fake-Zone laufen.
        fixture.destroy();
        create();
        click(bubble('Wohnzimmer'));
        click(device('wohnzimmer-tapo-802366e0')!);
        entityService.getEntities.calls.reset();
      };
      const finish = (): void => {
        fixture.destroy();
        discardPeriodicTasks();
      };

      it('nach switched sofort und nach 1,5 s und 5 s erneut, ohne weitere Aktion', fakeAsync(() => {
        openStehlampe();

        click(switchButton());
        expect(entityService.getEntities).toHaveBeenCalledTimes(1);
        tick(1500);
        expect(entityService.getEntities).toHaveBeenCalledTimes(2);
        tick(3500);
        expect(entityService.getEntities).toHaveBeenCalledTimes(3);
        tick(20000);
        expect(entityService.getEntities).toHaveBeenCalledTimes(3);

        expect(control.setSwitch).toHaveBeenCalledTimes(1);
        expect(control.setTarget).not.toHaveBeenCalled();
        expect(control.setBrightness).not.toHaveBeenCalled();
        finish();
      }));

      it('neuer Stand landet sofort im Feld', fakeAsync(() => {
        openStehlampe();
        expect(panelText()).toContain('An');
        entityService.getEntities.and.returnValue(of([entity(STEHLAMPE, 'off')]));

        click(switchButton());

        expect(panelText()).toContain('Aus');
        finish();
      }));

      it('already lädt ebenfalls nach', fakeAsync(() => {
        control.setSwitch.and.returnValue(of<SwitchOutcome>('already'));
        openStehlampe();

        click(switchButton());

        expect(entityService.getEntities).toHaveBeenCalledTimes(1);
        tick(5000);
        expect(entityService.getEntities).toHaveBeenCalledTimes(3);
        finish();
      }));

      it('unavailable lädt nicht nach', fakeAsync(() => {
        control.setSwitch.and.returnValue(of<SwitchOutcome>('unavailable'));
        openStehlampe();

        click(switchButton());
        tick(5000);

        expect(entityService.getEntities).not.toHaveBeenCalled();
        finish();
      }));

      it('nach gesetzter Helligkeit: Entitäten und Helligkeit neu', fakeAsync(() => {
        control.loadExtras.and.returnValue(of({ light: lightInfo(40) }));
        control.setBrightness.and.returnValue(of<BrightnessOutcome>('set'));
        openStehlampe();
        control.loadExtras.and.returnValue(of({ light: lightInfo(70) }));

        const slider = el().querySelector('.device-detail__brightness') as HTMLInputElement;
        slider.value = '70';
        slider.dispatchEvent(new Event('change'));
        fixture.detectChanges();

        expect(entityService.getEntities).toHaveBeenCalledTimes(1);
        expect(control.loadExtras).toHaveBeenCalledTimes(2);
        expect(fixture.componentInstance.detailBrightness()).toBe(70);
        tick(5000);
        expect(control.setSwitch).not.toHaveBeenCalled();
        expect(control.setBrightness).toHaveBeenCalledTimes(1);
        finish();
      }));

      it('Fehler beim Nachladen behält den letzten Stand still', fakeAsync(() => {
        openStehlampe();
        entityService.getEntities.and.returnValue(throwError(() => new Error('weg')));

        click(switchButton());
        tick(5000);
        fixture.detectChanges();

        expect(panelText()).toContain('An');
        expect(el().querySelector('.tablet-house__error')).toBeNull();
        finish();
      }));
    });

    it('Feld geschlossen, während ein Schaltaufruf läuft ⇒ Antwort danach öffnet nichts, wirft nicht', () => {
      const pending = new Subject<SwitchOutcome>();
      control.setSwitch.and.returnValue(pending);
      click(device('wohnzimmer-tapo-802366e0')!);
      click(el().querySelector('.device-detail__switch') as HTMLElement);
      expect(control.setSwitch).toHaveBeenCalledWith(STEHLAMPE, false);

      click(closeButton());
      expect(() => {
        pending.next('switched');
        pending.complete();
        fixture.detectChanges();
      }).not.toThrow();

      expect(panel()).toBeNull();
    });

    it('späte Antwort der Zusatzdaten nach dem Schließen öffnet nichts', () => {
      const light = new Subject<Partial<DetailExtras>>();
      control.loadExtras.and.returnValue(light);
      click(device('wohnzimmer-tapo-802366e0')!);
      click(closeButton());

      light.next({ light: lightInfo(40) });
      fixture.detectChanges();

      expect(panel()).toBeNull();
      expect(light.observed).toBeFalse();
    });

    it('dimmbare Lampe: Regler aus den Zusatzdaten, Wert als brightness', () => {
      control.loadExtras.and.returnValue(of({ light: lightInfo(40) }));

      click(device('wohnzimmer-tapo-802366e0')!);

      expect(control.loadExtras).toHaveBeenCalledOnceWith(jasmine.objectContaining({ id: 'wohnzimmer-tapo-802366e0' }));
      const slider = el().querySelector<HTMLInputElement>('.device-detail__brightness');
      expect(slider).not.toBeNull();
      expect(slider!.value).toBe('40');
    });

    it('Steckdose ohne Helligkeit zeigt keinen Regler', () => {
      click(device('wohnzimmer-tapo-802366e0')!);

      expect(el().querySelector('.device-detail__switch')).not.toBeNull();
      expect(el().querySelector('.device-detail__brightness')).toBeNull();
    });

    it('Thermostat zeigt den Raumwert als zusätzliche Zeile, ohne Raumfühler keine', () => {
      control.loadExtras.and.returnValue(of({ roomValue: { text: '19,8°', stale: false } }));
      click(device('wohnzimmer-thermostat-8')!);
      expect(control.loadExtras).toHaveBeenCalledOnceWith(jasmine.objectContaining({ id: 'wohnzimmer-thermostat-8' }));
      expect(panelText()).toContain('Raum 19,8°');

      click(closeButton());
      control.loadExtras.and.returnValue(of({ roomValue: null }));
      click(device('wohnzimmer-thermostat-8')!);
      expect(panel()).not.toBeNull();
      expect(panelText()).not.toContain('Raum');
    });

    describe('Zusatzdaten', () => {
      it('späte Zusatzdaten von Gerät A landen nicht im Feld von Gerät B', () => {
        const extrasA = new Subject<Partial<DetailExtras>>();
        control.loadExtras.and.callFake(entry => entry.id === 'wohnzimmer-thermostat-8' ? extrasA : of({}));
        click(device('wohnzimmer-thermostat-8')!);
        click(device('wohnzimmer-tapo-802366e0')!);

        extrasA.next({ roomValue: { text: '19,8°', stale: false } });
        fixture.detectChanges();

        expect(panel()).not.toBeNull();
        expect(panelText()).not.toContain('Raum');
      });

      it('lädt die Zusatzdaten im Takt nur bei offenem Feld', fakeAsync(() => {
        // Neu aufbauen, damit der 30-s-Takt in der Fake-Zone laeuft.
        fixture.destroy();
        create();
        click(bubble('Wohnzimmer'));
        tick(30000);
        expect(control.loadExtras).not.toHaveBeenCalled();

        click(device('wohnzimmer-thermostat-8')!);
        expect(control.loadExtras).toHaveBeenCalledTimes(1);
        tick(30000);
        expect(control.loadExtras).toHaveBeenCalledTimes(2);

        click(closeButton());
        tick(30000);
        expect(control.loadExtras).toHaveBeenCalledTimes(2);
        fixture.destroy();
        discardPeriodicTasks();
      }));

      it('ein leeres Teil-Ergebnis (alle Quellen gescheitert) behält den zuvor geladenen Raumwert', fakeAsync(() => {
        fixture.destroy();
        create();
        click(bubble('Wohnzimmer'));
        control.loadExtras.and.returnValues(of({ roomValue: { text: '19,8°', stale: false } }), of({}));
        click(device('wohnzimmer-thermostat-8')!);
        expect(panelText()).toContain('Raum 19,8°');

        tick(30000);
        fixture.detectChanges();

        expect(control.loadExtras).toHaveBeenCalledTimes(2);
        expect(panelText()).toContain('Raum 19,8°');
        fixture.destroy();
        discardPeriodicTasks();
      }));
    });

    it('Etagenwechsel oder Zurück schließt das Feld', () => {
      click(device('wohnzimmer-thermostat-8')!);
      click(back());
      expect(panel()).toBeNull();

      click(device('garden-climate')!);
      expect(panel()).not.toBeNull();
      click(el().querySelector('[data-floor="upper"]') as HTMLElement);
      expect(panel()).toBeNull();
    });
  });

  describe('Lebendiges Haus (Zustandseffekte)', () => {
    const STEHLAMPE = 'switch.tapo_802366e0c88d0dba80336f9f7363f8c4216c350a';
    const SCHLAFZIMMER_LICHT = 'switch.tapo_80238e15a36182550737e4ce654d2f2420b98b5c';
    const lastEffects = (): { deviceId: string; kind: string; intensity: number }[] =>
      fake.setEffects.calls.mostRecent().args[0];

    it('gibt die Effekte der gezeigten Etage an den Renderer, Helligkeit aus der Geräteliste', () => {
      entityService.getEntities.and.returnValue(of([entity(STEHLAMPE, 'on'), entity(SCHLAFZIMMER_LICHT, 'on')]));
      control.lampBrightness.and.returnValue(of(new Map([[STEHLAMPE, 50]])));
      create();

      expect(lastEffects()).toEqual([jasmine.objectContaining({ kind: 'light', intensity: 0.5 })]);

      click(el().querySelector('[data-floor="upper"]') as HTMLElement);

      expect(lastEffects()).toEqual([jasmine.objectContaining({ kind: 'light', intensity: 1 })]);
    });

    it('nur das Betreten eines Raums spielt die Animation ab, Etagenwahl und Zurück nicht', () => {
      create();
      click(el().querySelector('[data-floor="upper"]') as HTMLElement);
      click(el().querySelector('[data-floor="ground"]') as HTMLElement);
      expect(fake.playEffects).not.toHaveBeenCalled();

      click(bubble('Wohnzimmer'));
      expect(fake.playEffects).toHaveBeenCalledTimes(1);

      click(el().querySelector('.tablet-house__back') as HTMLElement);
      expect(fake.playEffects).toHaveBeenCalledTimes(1);
    });

    it('Refresh aktualisiert die Effekte ohne Animation; Helligkeits-Fehler behält den letzten Wert', fakeAsync(() => {
      entityService.getEntities.and.returnValues(of([entity(STEHLAMPE, 'off')]), of([entity(STEHLAMPE, 'on')]));
      control.lampBrightness.and.returnValues(of(new Map([[STEHLAMPE, 30]])), throwError(() => new Error('weg')));
      create();
      expect(lastEffects()).toEqual([]);

      tick(30000);
      fixture.detectChanges();

      expect(lastEffects()).toEqual([jasmine.objectContaining({ kind: 'light', intensity: 0.3 })]);
      expect(fake.playEffects).not.toHaveBeenCalled();
      fixture.destroy();
      discardPeriodicTasks();
    }));
  });

  describe('Gespeicherte Geräteplätze', () => {
    const FLUR_LIGHT_ID = 'flur-tapo-80232e39';
    const FLUR_LIGHT = 'switch.tapo_80232e39ed1bbdd537ab39cac50eede2216b55c7';
    const SAVED = [{ deviceId: FLUR_LIGHT_ID, x: 400, y: 300 }];
    const lastEffects = (): HouseEffect[] => fake.setEffects.calls.mostRecent().args[0];
    const placed = (id: string): Vec3 => (fixture.componentInstance as unknown as {
      deviceBubbles: () => { bubble: { deviceId: string }; anchor: Vec3 }[];
    }).deviceBubbles().find(p => p.bubble.deviceId === id)!.anchor;

    it('nutztGespeichertenPlatzFuerBubbleUndEffekt', () => {
      positionService.list.and.returnValue(of(SAVED));
      entityService.getEntities.and.returnValue(of([entity(FLUR_LIGHT, 'on')]));
      create();
      click(bubble('Flur'));

      const anchor = placed(FLUR_LIGHT_ID);
      expect(anchor.x).toBe(toWorldX(400));
      expect(anchor.z).toBe(toWorldZ(300));
      expect(anchor.y).toBeCloseTo(areaFloorBase('flur') + BUBBLE_HEIGHT_M, 6);
      const light = lastEffects().find(e => e.deviceId === FLUR_LIGHT_ID)!;
      expect(light.anchor.x).toBe(toWorldX(400));
    });

    it('neuePositionenLoesenEinRendernAus', fakeAsync(() => {
      create();
      positionService.list.and.returnValue(of(SAVED));
      fake.requestRender.calls.reset();

      tick(30000);
      fixture.detectChanges();

      expect(fake.requestRender).toHaveBeenCalled();
      fixture.destroy();
      discardPeriodicTasks();
    }));

    it('positionsFehlerOhneStandNutztRaster', () => {
      positionService.list.and.returnValue(throwError(() => new Error('weg')));
      create();
      click(bubble('Flur'));

      expect(placed(FLUR_LIGHT_ID)).toEqual(deviceBubbleAnchor(FLUR_LIGHT_ID, new Map())!);
      expect(el().querySelector('.tablet-house__error')).toBeNull();
    });

    it('positionsFehlerBehaeltLetztenStand', fakeAsync(() => {
      positionService.list.and.returnValue(of(SAVED));
      create();
      click(bubble('Flur'));
      expect(placed(FLUR_LIGHT_ID).x).toBe(toWorldX(400));

      positionService.list.and.returnValue(throwError(() => new Error('weg')));
      tick(30000);
      fixture.detectChanges();

      expect(placed(FLUR_LIGHT_ID).x).toBe(toWorldX(400));
      fixture.destroy();
      discardPeriodicTasks();
    }));

    it('unveraenderterStandLoestKeinSetEffectsAus', fakeAsync(() => {
      positionService.list.and.callFake(() => of([...SAVED]));
      entityService.getEntities.and.callFake(() => of([entity(FLUR_LIGHT, 'on')]));
      create();
      fake.setEffects.calls.reset();

      tick(30000);
      fixture.detectChanges();

      expect(fake.setEffects).not.toHaveBeenCalled();
      fixture.destroy();
      discardPeriodicTasks();
    }));

    it('geraeteDetailFaehrtZumGespeichertenPlatz', () => {
      positionService.list.and.returnValue(of(SAVED));
      create();
      click(bubble('Flur'));
      fake.flyTo.calls.reset();

      click(device(FLUR_LIGHT_ID)!);

      const expected = deviceAnchor(FLUR_LIGHT_ID, new Map([[FLUR_LIGHT_ID, { x: 400, y: 300 }]]))!;
      expect(fake.flyTo).toHaveBeenCalledWith(cameraForDevice(expected));
    });
  });

  describe('Fenster und Türen in der Szene', () => {
    const BAD_WINDOW = HOUSE_DEVICES.find(d => d.kind === 'contact' && d.area === 'bad')!;
    const wall = { x1: 20, y1: 200, x2: 20, y2: 260 };
    const opening = (over: Partial<HouseOpening> = {}): HouseOpening => ({
      id: 1, floor: 'ground', kind: 'WINDOW', size: 'NORMAL', ...wall, hinge: 'START', swing: 'POSITIVE',
      deviceId: null, ...over
    });
    const lastShapes = (): OpeningShape[] => fake.setOpenings.calls.mostRecent().args[0];

    it('zeichnetElementeDerEtage', () => {
      openingService.list.and.returnValue(of([opening()]));
      create();
      expect(lastShapes().length).toBe(1);
      expect(lastShapes()[0].open).toBeFalse();
    });

    it('offenesElementWirdOffenGezeichnet', () => {
      openingService.list.and.returnValue(of([opening({ deviceId: BAD_WINDOW.id })]));
      entityService.getEntities.and.returnValue(of([entity((BAD_WINDOW as { entityId: string }).entityId, 'on')]));
      create();
      expect(lastShapes()[0].open).toBeTrue();
    });

    it('verknuepfterKontaktGluehtNichtInDerSzene', () => {
      openingService.list.and.returnValue(of([opening({ deviceId: BAD_WINDOW.id })]));
      entityService.getEntities.and.returnValue(of([entity((BAD_WINDOW as { entityId: string }).entityId, 'on')]));
      create();
      click(el().querySelector('[data-floor="upper"]') as HTMLElement);
      const effects: HouseEffect[] = fake.setEffects.calls.mostRecent().args[0];
      expect(effects.some(e => e.kind === 'open')).toBeFalse();
    });

    it('verknuepfterKontaktGluehtWiederWennSeinElementAufKeinerWandLiegt', () => {
      openingService.list.and.returnValue(of([
        opening({ deviceId: BAD_WINDOW.id, x1: 100, y1: 150, x2: 140, y2: 150 })
      ]));
      entityService.getEntities.and.returnValue(of([entity((BAD_WINDOW as { entityId: string }).entityId, 'on')]));
      create();
      click(el().querySelector('[data-floor="upper"]') as HTMLElement);
      const effects: HouseEffect[] = fake.setEffects.calls.mostRecent().args[0];
      expect(effects.some(e => e.kind === 'open')).toBeTrue();
    });

    it('elementOhneWandWirdNichtGezeichnet', () => {
      openingService.list.and.returnValue(of([opening({ x1: 100, y1: 150, x2: 140, y2: 150 })]));
      create();
      expect(lastShapes()).toEqual([]);
    });

    it('ladefehlerOhneStandZeichnetNichts', () => {
      openingService.list.and.returnValue(throwError(() => new Error('weg')));
      create();
      expect(lastShapes()).toEqual([]);
      expect(el().querySelector('.tablet-house__error')).toBeNull();
    });

    it('ladefehlerBehaeltLetztenStand', fakeAsync(() => {
      openingService.list.and.returnValue(of([opening()]));
      create();
      openingService.list.and.returnValue(throwError(() => new Error('weg')));
      tick(30000);
      fixture.detectChanges();
      expect(lastShapes().length).toBe(1);
      fixture.destroy();
      discardPeriodicTasks();
    }));

    it('unveraenderteElementeRufenSetOpeningsNichtErneut', fakeAsync(() => {
      openingService.list.and.callFake(() => of([opening()]));
      create();
      fake.setOpenings.calls.reset();
      tick(30000);
      fixture.detectChanges();
      expect(fake.setOpenings).not.toHaveBeenCalled();
      fixture.destroy();
      discardPeriodicTasks();
    }));
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
