import {
  AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, ElementRef, NgZone, OnDestroy, QueryList,
  ViewChild, ViewChildren, computed, inject, signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Subscription, catchError, interval, map, of, startWith, switchMap } from 'rxjs';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { EntityState } from '../../models/entity-state.model';
import { EntityStateService } from '../../services/entity-state.service';
import { ViewModeService } from '../../services/view-mode.service';
import { FLOORS, FloorId, HOUSE_ROOMS, HouseRoom } from '../../shared/house/house-plan';
import {
  CameraView, Vec3, cameraForOverview, cameraForRoom, deviceAnchors, roomAnchor
} from '../../shared/house/house-geometry.util';
import {
  BubbleFlag, DeviceBubble, RoomBubble, buildDeviceBubbles, buildOutdoorBubbles, buildRoomBubble
} from '../../shared/house/house-bubbles.util';
import { HOUSE_RENDERER_FACTORY, HouseSceneRenderer } from './house-scene-renderer';

/** Weiter als so viele Pixel zwischen Druck und Loslassen ist ein Ziehen (Drehen), kein Tipp. */
const TAP_TOLERANCE_PX = 8;
const REFRESH_INTERVAL_MS = 30_000;

const FLAG_ICONS: Record<BubbleFlag, { icon: string; label: string }> = {
  window_open: { icon: 'sensor_window', label: 'Fenster offen' },
  heating: { icon: 'mode_heat', label: 'Heizung läuft' },
  appliance_running: { icon: 'local_laundry_service', label: 'Gerät läuft' },
  appliance_finished: { icon: 'check_circle', label: 'Gerät fertig' },
  motion: { icon: 'directions_walk', label: 'Bewegung' }
};

/** Geräte-Bubble samt Anker in der Szene (je Bereich im Raster verteilt). */
interface PlacedDeviceBubble { bubble: DeviceBubble; anchor: Vec3; stale: boolean }

/**
 * 3D-Hausansicht fuer das Wandtablet („digitaler Zwilling“): das Haus als
 * Neon-Drahtmodell mit Etagenwahl. Im Ueberblick je Raum eine Bubble mit
 * Temperatur und Hinweisen (im EG zusaetzlich die Aussenbereiche), in der
 * Raumansicht die Geraete des Raums. Geraete-Bubbles fuehren auf die Fachseite.
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
  private readonly router = inject(Router);
  private readonly entityService = inject(EntityStateService);
  private readonly viewMode = inject(ViewModeService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly createRenderer = inject(HOUSE_RENDERER_FACTORY);

  @ViewChild('sceneHost', { static: true }) private sceneHost!: ElementRef<HTMLElement>;
  @ViewChildren('bubble') private bubbleRefs!: QueryList<ElementRef<HTMLElement>>;

  private renderer: HouseSceneRenderer | null = null;
  private pointerStart: { x: number; y: number } | null = null;
  private bubbleChanges: Subscription | null = null;
  private loaded = false;

  readonly floors = FLOORS;
  readonly selectedFloor = signal<FloorId>('ground');
  readonly focusedRoom = signal<string | null>(null);
  readonly sceneUnavailable = signal(false);
  readonly loadError = signal(false);
  private readonly entities = signal<ReadonlyMap<string, EntityState>>(new Map());
  /** Bezugszeit fuer die Veraltung; laeuft bei jedem Refresh-Takt weiter, auch wenn der Abruf scheitert. */
  private readonly now = signal(Date.now());

  readonly visibleRooms = computed<HouseRoom[]>(() =>
    HOUSE_ROOMS.filter(room => room.floor === this.selectedFloor()));

  /** Ueberblick: genau eine Bubble je Raum der Etage; in der Raumansicht keine. */
  readonly roomBubbles = computed<RoomBubble[]>(() => {
    if (this.focusedRoom()) {
      return [];
    }
    const entities = this.entities();
    const now = this.now();
    return this.visibleRooms().map(room => buildRoomBubble(room.id, entities, now));
  });

  /** Raumansicht: Geraete des Raums; Ueberblick des EG: die Aussenbereiche. */
  readonly deviceBubbles = computed<PlacedDeviceBubble[]>(() => {
    const entities = this.entities();
    const now = this.now();
    const tabletMode = this.viewMode.isTabletView();
    const focused = this.focusedRoom();
    const bubbles = focused
      ? buildDeviceBubbles(focused, entities, now, tabletMode)
      : this.selectedFloor() === 'ground' ? buildOutdoorBubbles(entities, now, tabletMode) : [];
    return placeByArea(bubbles);
  });

  constructor() {
    interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.entityService.getEntities().pipe(
        map(list => new Map(list.map(entity => [entity.entityId, entity] as const))),
        catchError(() => of(null))
      )),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(result => this.applyFetch(result));
  }

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
    // Neue Bubble-Elemente (Etagenwechsel, Raumansicht) sofort einordnen.
    this.bubbleChanges = this.bubbleRefs.changes.subscribe(() => this.outside(renderer => renderer.requestRender()));
    this.outside(renderer => renderer.requestRender());
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

  openDevice(bubble: DeviceBubble): void {
    void this.router.navigateByUrl(bubble.target);
  }

  flagsOf(bubble: RoomBubble): { flag: BubbleFlag; icon: string; label: string }[] {
    return bubble.flags.map(flag => ({ flag, ...FLAG_ICONS[flag] }));
  }

  onScenePointerDown(event: PointerEvent): void {
    this.pointerStart = { x: event.clientX, y: event.clientY };
  }

  onScenePointerCancel(): void {
    this.pointerStart = null;
  }

  onScenePointerUp(event: PointerEvent): void {
    const start = this.pointerStart;
    this.pointerStart = null;
    if (!this.renderer || !start
      || Math.hypot(event.clientX - start.x, event.clientY - start.y) > TAP_TOLERANCE_PX) {
      return;
    }
    const roomId = this.renderer.pickRoom(event.clientX, event.clientY);
    if (roomId) {
      this.focusRoom(roomId);
    } else if (this.focusedRoom()) {
      this.backToOverview();
    }
  }

  private applyFetch(result: Map<string, EntityState> | null): void {
    // Auch ohne neue Daten weiterzaehlen: sonst saehen die letzten Werte waehrend eines Ausfalls ewig frisch aus.
    this.now.set(Date.now());
    if (result) {
      this.loaded = true;
      this.loadError.set(false);
      this.entities.set(result);
    } else if (!this.loaded) {
      // Nur der Erstabruf meldet einen Fehler; spaeter bleiben die letzten Werte stehen.
      this.loadError.set(true);
    }
    this.outside(renderer => renderer.requestRender());
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
    if (!renderer || !this.bubbleRefs) {
      return;
    }
    const deviceAnchorsById = new Map(this.deviceBubbles().map(placed => [placed.bubble.deviceId, placed.anchor]));
    const placed = this.bubbleRefs
      .map(ref => ref.nativeElement)
      .map(element => ({ element, anchor: this.anchorOf(element, deviceAnchorsById) }))
      .filter((entry): entry is { element: HTMLElement; anchor: Vec3 } => entry.anchor !== null);
    const points = renderer.project(placed.map(entry => entry.anchor));
    placed.forEach(({ element }, i) => {
      const point = points[i];
      element.style.transform = `translate(${point.x}px, ${point.y}px) translate(-50%, -50%)`;
      element.style.visibility = point.visible ? 'visible' : 'hidden';
    });
  }

  private anchorOf(element: HTMLElement, deviceAnchorsById: Map<string, Vec3>): Vec3 | null {
    const roomId = element.dataset['room'];
    if (roomId) {
      return roomAnchor(roomId);
    }
    return deviceAnchorsById.get(element.dataset['device'] ?? '') ?? null;
  }
}

/** Verteilt die Bubbles je Bereich auf dessen Raster-Anker. */
function placeByArea(bubbles: DeviceBubble[]): PlacedDeviceBubble[] {
  const byArea = new Map<string, DeviceBubble[]>();
  bubbles.forEach(bubble => byArea.set(bubble.areaId, [...(byArea.get(bubble.areaId) ?? []), bubble]));
  const anchors = new Map([...byArea].map(([areaId, list]) => [areaId, deviceAnchors(areaId, list.length)]));
  const indexInArea = new Map<string, number>();
  return bubbles.map(bubble => {
    const index = indexInArea.get(bubble.areaId) ?? 0;
    indexInArea.set(bubble.areaId, index + 1);
    return {
      bubble,
      anchor: anchors.get(bubble.areaId)![index],
      stale: bubble.lines.length > 0 && bubble.lines.every(line => line.stale)
    };
  });
}
