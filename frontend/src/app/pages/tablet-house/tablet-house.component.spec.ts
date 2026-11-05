import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TabletHouseComponent } from './tablet-house.component';
import { HOUSE_RENDERER_FACTORY, HouseSceneRenderer, ScreenPoint } from './house-scene-renderer';
import { WeatherService } from '../../services/weather.service';
import { AppearanceService } from '../../services/appearance.service';
import { WeatherOverview } from '../../models/weather.model';
import { Vec3, cameraForOverview, cameraForRoom } from '../../shared/house/house-geometry.util';

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

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const roomBubbles = (): HTMLElement[] => Array.from(el().querySelectorAll<HTMLElement>('.house-bubble--room'));
  const bubbleLabels = (): string[] => roomBubbles().map(b => b.textContent?.trim() ?? '');
  const bubble = (label: string): HTMLElement => roomBubbles().find(b => b.textContent?.trim() === label)!;
  const click = (target: HTMLElement): void => {
    target.click();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    fake = new FakeRenderer();
    const weather = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weather.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletHouseComponent],
      providers: [
        provideRouter([]),
        { provide: WeatherService, useValue: weather },
        { provide: AppearanceService, useValue: { isLight$: of(false) } },
        {
          provide: HOUSE_RENDERER_FACTORY,
          useValue: (host: HTMLElement) => {
            fake.host = host;
            return fake as unknown as HouseSceneRenderer;
          }
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletHouseComponent);
    fixture.detectChanges();
  });

  afterEach(() => fixture?.destroy());

  it('steckt in der Tablet-Shell mit Überschrift „Haus“', () => {
    const shell = el().querySelector('app-tablet-shell');
    expect(shell).not.toBeNull();
    expect(shell!.querySelector('.lumina__heading')?.textContent?.trim()).toBe('Haus');
  });

  it('erzeugt den Renderer im Szenen-Host und gibt ihn beim Zerstören frei', () => {
    expect(fake.host).toBe(el().querySelector('.tablet-house__scene') as HTMLElement);
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
    const scene = el().querySelector('.tablet-house__scene') as HTMLElement;

    scene.dispatchEvent(new PointerEvent('pointerdown', { clientX: 100, clientY: 100, bubbles: true }));
    scene.dispatchEvent(new MouseEvent('click', { clientX: 101, clientY: 100, bubbles: true }));
    fixture.detectChanges();

    expect(fake.pickRoom).toHaveBeenCalledWith(101, 100);
    expect(fake.flyTo).toHaveBeenCalledWith(cameraForRoom('kueche'));
  });

  it('ein Ziehen über die Szene ist kein Tipp', () => {
    fake.pickRoom.and.returnValue('kueche');
    const scene = el().querySelector('.tablet-house__scene') as HTMLElement;

    scene.dispatchEvent(new PointerEvent('pointerdown', { clientX: 100, clientY: 100, bubbles: true }));
    scene.dispatchEvent(new MouseEvent('click', { clientX: 160, clientY: 100, bubbles: true }));

    expect(fake.pickRoom).not.toHaveBeenCalled();
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

  describe('Höhenkette', () => {
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
