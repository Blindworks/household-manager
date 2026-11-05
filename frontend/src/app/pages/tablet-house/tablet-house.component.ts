import {
  AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, NgZone, OnDestroy, QueryList,
  ViewChild, ViewChildren, computed, inject, signal
} from '@angular/core';
import { Subscription } from 'rxjs';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { FLOORS, FloorId, HOUSE_ROOMS, HouseRoom } from '../../shared/house/house-plan';
import { CameraView, cameraForOverview, cameraForRoom, roomAnchor } from '../../shared/house/house-geometry.util';
import { HOUSE_RENDERER_FACTORY, HouseSceneRenderer } from './house-scene-renderer';

/** Weiter als so viele Pixel zwischen Druck und Klick ist ein Ziehen (Drehen), kein Tipp. */
const TAP_TOLERANCE_PX = 8;

/**
 * 3D-Hausansicht fuer das Wandtablet („digitaler Zwilling“): das Haus als
 * Neon-Drahtmodell mit Etagenwahl, je Raum eine Bubble; ein Tipp auf Raum oder
 * Bubble fliegt hinein.
 *
 * Die Bubbles folgen der Kamera je gezeichnetem Frame. Das laeuft bewusst an
 * Angular vorbei (direkte DOM-Transforms, Renderer ausserhalb der Zone, OnPush),
 * sonst loeste jeder Frame beim Drehen eine Change Detection aus.
 */
@Component({
  selector: 'app-tablet-house',
  standalone: true,
  imports: [TabletShellComponent],
  templateUrl: './tablet-house.component.html',
  styleUrl: './tablet-house.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class TabletHouseComponent implements AfterViewInit, OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly createRenderer = inject(HOUSE_RENDERER_FACTORY);

  @ViewChild('sceneHost', { static: true }) private sceneHost!: ElementRef<HTMLElement>;
  @ViewChildren('roomBubble') private roomBubbleRefs!: QueryList<ElementRef<HTMLElement>>;

  private renderer: HouseSceneRenderer | null = null;
  private pointerStart: { x: number; y: number } | null = null;
  private bubbleChanges: Subscription | null = null;

  readonly floors = FLOORS;
  readonly selectedFloor = signal<FloorId>('ground');
  readonly focusedRoom = signal<string | null>(null);
  readonly sceneUnavailable = signal(false);
  readonly visibleRooms = computed<HouseRoom[]>(() =>
    HOUSE_ROOMS.filter(room => room.floor === this.selectedFloor()));

  ngAfterViewInit(): void {
    try {
      this.renderer = this.zone.runOutsideAngular(() => this.createRenderer(this.sceneHost.nativeElement));
    } catch (error) {
      // Ohne WebGL (oder bei einem Kontextfehler) bleibt die Seite stehen statt leer abzustuerzen.
      console.error('3D-Ansicht konnte nicht gestartet werden', error);
      this.sceneUnavailable.set(true);
      return;
    }
    this.outside(renderer => {
      renderer.onRendered(() => this.positionBubbles());
      renderer.setFloor(this.selectedFloor());
      renderer.flyTo(cameraForOverview(this.selectedFloor()), 0);
    });
    // Nach einem Etagenwechsel stehen neue Bubble-Elemente im DOM - sofort einordnen.
    this.bubbleChanges = this.roomBubbleRefs.changes.subscribe(() => this.outside(renderer => renderer.requestRender()));
  }

  ngOnDestroy(): void {
    this.bubbleChanges?.unsubscribe();
    this.renderer?.dispose();
    this.renderer = null;
  }

  selectFloor(floor: FloorId): void {
    this.selectedFloor.set(floor);
    this.focusedRoom.set(null);
    this.outside(renderer => renderer.setFloor(floor));
    this.fly(cameraForOverview(floor));
  }

  focusRoom(roomId: string): void {
    this.focusedRoom.set(roomId);
    this.fly(cameraForRoom(roomId));
  }

  backToOverview(): void {
    this.focusedRoom.set(null);
    this.fly(cameraForOverview(this.selectedFloor()));
  }

  onScenePointerDown(event: PointerEvent): void {
    this.pointerStart = { x: event.clientX, y: event.clientY };
  }

  onSceneClick(event: MouseEvent): void {
    const start = this.pointerStart;
    this.pointerStart = null;
    if (!this.renderer || !start
      || Math.hypot(event.clientX - start.x, event.clientY - start.y) > TAP_TOLERANCE_PX) {
      return;
    }
    const roomId = this.renderer.pickRoom(event.clientX, event.clientY);
    if (roomId) {
      this.focusRoom(roomId);
    }
  }

  private fly(view: CameraView): void {
    this.outside(renderer => renderer.flyTo(view));
  }

  /** Renderer-Aufrufe ausserhalb der Zone: ihre rAF-Schleifen duerfen keine Change Detection ausloesen. */
  private outside(action: (renderer: HouseSceneRenderer) => void): void {
    const renderer = this.renderer;
    if (renderer) {
      this.zone.runOutsideAngular(() => action(renderer));
    }
  }

  private positionBubbles(): void {
    const renderer = this.renderer;
    if (!renderer || !this.roomBubbleRefs) {
      return;
    }
    const elements = this.roomBubbleRefs.map(ref => ref.nativeElement);
    const rooms = elements.map(element => element.dataset['room'] ?? '');
    const points = renderer.project(rooms.map(roomId => roomAnchor(roomId)));
    elements.forEach((element, i) => {
      const point = points[i];
      element.style.transform = `translate(${point.x}px, ${point.y}px) translate(-50%, -50%)`;
      element.style.visibility = point.visible ? 'visible' : 'hidden';
    });
  }
}
