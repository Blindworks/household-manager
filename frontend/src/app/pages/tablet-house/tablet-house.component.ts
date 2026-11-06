import {
  AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, ElementRef, NgZone, OnDestroy, QueryList,
  ViewChild, ViewChildren, computed, effect, inject, signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { EMPTY, Subject, Subscription, catchError, interval, map, merge, of, startWith, switchMap, timer } from 'rxjs';
import { HouseDeviceDetailComponent } from './device-detail/house-device-detail.component';
import { HouseDeviceControlService } from './house-device-control.service';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { EntityState } from '../../models/entity-state.model';
import { EntityStateService } from '../../services/entity-state.service';
import { ViewModeService } from '../../services/view-mode.service';
import { FLOORS, FloorId, HOUSE_ROOMS, HouseRoom, OUTDOOR_AREAS } from '../../shared/house/house-plan';
import {
  CameraView, Vec3, cameraForDevice, cameraForOverview, cameraForRoom, roomAnchor
} from '../../shared/house/house-geometry.util';
import {
  BubbleFlag, DeviceBubble, RoomBubble, buildDeviceBubbles, buildOutdoorBubbles, buildRoomBubble
} from '../../shared/house/house-bubbles.util';
import { HOUSE_DEVICES, HouseDevice } from '../../shared/house/house-devices';
import { DeviceDetail, buildDeviceDetail, opensDetail } from '../../shared/house/device-detail.util';
import { DetailExtras } from '../../shared/house/detail-extras.util';
import { HouseEffect, buildHouseEffects, deviceAnchor } from '../../shared/house/house-effects.util';
import { HOUSE_RENDERER_FACTORY, HouseSceneRenderer } from './house-scene-renderer';

/** Weiter als so viele Pixel zwischen Druck und Loslassen ist ein Ziehen (Drehen), kein Tipp. */
const TAP_TOLERANCE_PX = 8;
const REFRESH_INTERVAL_MS = 30_000;
/**
 * Nachladen nach einer Aktion im Detail-Feld (Muster `scheduleSwitchFollowUp` im Dashboard):
 * sofort und noch zweimal kurz danach, weil Geraete ihren neuen Zustand verzoegert melden.
 */
const FOLLOW_UP_AFTER_ACTION_MS = [1500, 5000] as const;

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
 * Raumansicht die Geraete des Raums. Ein Tipp auf eine Geraete-Bubble faehrt die
 * Kamera ans Geraet und oeffnet das Detail-Feld (auch Haustuer, Kamera, Muelltonne
 * und Toni); dessen Zusatzdaten laedt die Seite beim Oeffnen und im Takt.
 *
 * Die Bubbles folgen der Kamera je gezeichnetem Frame. Das laeuft bewusst an
 * Angular vorbei (direkte DOM-Transforms, Renderer ausserhalb der Zone, OnPush),
 * sonst loeste jeder Frame beim Drehen eine Change Detection aus.
 */
@Component({
  selector: 'app-tablet-house',
  standalone: true,
  imports: [TabletShellComponent, HouseDeviceDetailComponent],
  // Je Seite eine Instanz: die Caches des Dienstes leben so lange wie die Seite.
  providers: [HouseDeviceControlService],
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
  private readonly deviceControl = inject(HouseDeviceControlService);

  @ViewChild('sceneHost', { static: true }) private sceneHost!: ElementRef<HTMLElement>;
  @ViewChildren('bubble') private bubbleRefs!: QueryList<ElementRef<HTMLElement>>;

  private renderer: HouseSceneRenderer | null = null;
  private pointerStart: { x: number; y: number } | null = null;
  private bubbleChanges: Subscription | null = null;
  private loaded = false;
  /** Ausserplanmaessige Abrufe (nach einer Aktion); laufen durch dieselbe Kette wie der 30-s-Takt. */
  private readonly refreshRequests = new Subject<void>();
  /** Oeffnen und Schliessen des Felds: startet bzw. bestellt den Zusatzdaten-Abruf ab. */
  private readonly extrasRequests = new Subject<void>();
  private followUps: Subscription | null = null;

  readonly floors = FLOORS;
  readonly selectedFloor = signal<FloorId>('ground');
  readonly focusedRoom = signal<string | null>(null);
  readonly sceneUnavailable = signal(false);
  readonly loadError = signal(false);
  private readonly entities = signal<ReadonlyMap<string, EntityState>>(new Map());
  /** Bezugszeit fuer die Veraltung; laeuft bei jedem Refresh-Takt weiter, auch wenn der Abruf scheitert. */
  private readonly now = signal(Date.now());
  /** Helligkeit dimmbarer Lampen je Schalter-Entity (Prozent), im selben Takt wie die Entitaeten. */
  private readonly brightness = signal<ReadonlyMap<string, number>>(new Map());

  /**
   * Gehalten wird nur die Id des offenen Geraets, nie das Feld-Modell: das Feld
   * wird aus den aktuellen Entitaeten gebaut, der 30-s-Refresh aktualisiert es also,
   * ohne es neu zu erzeugen (Regel aus `petSupplyDialogSupply`).
   */
  private readonly openDeviceId = signal<string | null>(null);
  /**
   * Zusatzdaten des offenen Felds (Lampe, Raumwert, Schloss, Kamera, Toni, Muell), beim Oeffnen
   * und im Takt geholt. Eine gescheiterte Quelle fehlt im Teil-Ergebnis und behaelt so ihren letzten Wert.
   */
  readonly openExtras = signal<DetailExtras>({});

  readonly detail = computed<DeviceDetail | null>(() => {
    const device = deviceById(this.openDeviceId());
    return device ? buildDeviceDetail(device, this.entities(), this.now(), this.openExtras()) : null;
  });

  readonly detailBrightness = computed(() => this.openExtras().light?.brightness ?? null);

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

  /**
   * Zustandseffekte der gezeigten Etage (im EG auch die Aussenbereiche), in Ueberblick
   * und Raumansicht gleich; Regeln in `buildHouseEffects`.
   */
  readonly effects = computed<HouseEffect[]>(() => {
    const floor = this.selectedFloor();
    const areas = [
      ...this.visibleRooms().map(room => room.id),
      ...(floor === 'ground' ? OUTDOOR_AREAS.map(area => area.id) : [])
    ];
    return buildHouseEffects(areas, this.entities(), this.brightness());
  });

  constructor() {
    const ticks = merge(interval(REFRESH_INTERVAL_MS).pipe(startWith(0)), this.refreshRequests);
    ticks.pipe(
      switchMap(() => this.entityService.getEntities().pipe(
        map(list => new Map(list.map(entity => [entity.entityId, entity] as const))),
        catchError(() => of(null))
      )),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(result => this.applyFetch(result));
    // Helligkeit fuer den Lichtschein; ein Fehler behaelt still den letzten Stand.
    ticks.pipe(
      switchMap(() => this.deviceControl.lampBrightness().pipe(catchError(() => of(null)))),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(result => {
      if (result) {
        this.brightness.set(result);
      }
    });
    // Zusatzdaten nur bei offenem Feld; switchMap bestellt den alten Abruf ab (Schliessen, Geraetewechsel).
    merge(ticks, this.extrasRequests).pipe(
      switchMap(() => {
        const id = this.openDeviceId();
        const device = deviceById(id);
        return device ? this.deviceControl.loadExtras(device).pipe(
          map(partial => ({ id, partial })),
          catchError(() => EMPTY)) : EMPTY;
      }),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(({ id, partial }) => {
      // Zusaetzlich zur Abbestellung: eine Antwort fuer ein frueher offenes Geraet landet nie im aktuellen.
      if (id === this.openDeviceId()) {
        this.openExtras.update(previous => ({ ...previous, ...partial }));
      }
    });
    effect(() => {
      const effects = this.effects();
      this.outside(renderer => renderer.setEffects(effects));
    });
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
      renderer.setEffects(this.effects());
      renderer.flyTo(cameraForOverview(this.selectedFloor()), 0);
    });
    // Neue Bubble-Elemente (Etagenwechsel, Raumansicht) sofort einordnen.
    this.bubbleChanges = this.bubbleRefs.changes.subscribe(() => this.outside(renderer => renderer.requestRender()));
    this.outside(renderer => renderer.requestRender());
  }

  ngOnDestroy(): void {
    this.followUps?.unsubscribe();
    this.bubbleChanges?.unsubscribe();
    this.renderer?.dispose();
    this.renderer = null;
  }

  selectFloor(floor: FloorId): void {
    this.dropDetail();
    this.selectedFloor.set(floor);
    this.focusedRoom.set(null);
    this.outside(renderer => renderer.setFloor(floor));
    this.fly(cameraForOverview(floor));
  }

  focusRoom(roomId: string): void {
    this.dropDetail();
    this.focusedRoom.set(roomId);
    this.fly(cameraForRoom(roomId));
    // Nur beim Betreten eines Raums laufen die Effekte kurz animiert; sonst steht alles still.
    this.outside(renderer => renderer.playEffects());
  }

  backToOverview(): void {
    this.dropDetail();
    this.focusedRoom.set(null);
    this.fly(cameraForOverview(this.selectedFloor()));
  }

  openDevice(bubble: DeviceBubble): void {
    const device = HOUSE_DEVICES.find(entry => entry.id === bubble.deviceId);
    const anchor = this.deviceBubbles().find(placed => placed.bubble.deviceId === bubble.deviceId)?.anchor;
    if (!device || !anchor || !opensDetail(device.kind)) {
      void this.router.navigateByUrl(bubble.target);
      return;
    }
    this.dropDetail();
    this.openDeviceId.set(device.id);
    this.extrasRequests.next();
    this.fly(cameraForDevice(anchor));
  }

  /** ✕ im Feld oder Tipp ins Leere: Feld zu, Kamera zurueck in die Raumansicht bzw. den Ueberblick. */
  closeDetail(): void {
    this.dropDetail();
    const room = this.focusedRoom();
    this.fly(room ? cameraForRoom(room) : cameraForOverview(this.selectedFloor()));
  }

  /**
   * Das Feld hat geschaltet oder gemerkt, dass sein Stand veraltet ist: Entitaeten und
   * Zusatzdaten sofort und nach {@link FOLLOW_UP_AFTER_ACTION_MS} erneut laden. Nur Lesen -
   * ein Fehler behaelt still den letzten Stand.
   */
  refreshAfterAction(): void {
    this.refreshRequests.next();
    this.followUps?.unsubscribe();
    this.followUps = merge(...FOLLOW_UP_AFTER_ACTION_MS.map(delayMs => timer(delayMs)))
      .subscribe(() => this.refreshRequests.next());
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
    if (this.openDeviceId()) {
      // Bei offenem Feld schliesst jeder Tipp in die Szene nur das Feld, nicht die Raumansicht.
      this.closeDetail();
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

  /** Schliesst das Feld ohne Kamerafahrt (der Aufrufer fliegt selbst) und bestellt spaete Antworten ab. */
  private dropDetail(): void {
    this.openDeviceId.set(null);
    this.openExtras.set({});
    this.extrasRequests.next();
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

function deviceById(id: string | null): HouseDevice | undefined {
  return id ? HOUSE_DEVICES.find(entry => entry.id === id) : undefined;
}

/**
 * Setzt jede Bubble auf den Platz ihres Geraets (`deviceAnchor`, Raster je Bereich) -
 * dieselbe Stelle, an der die Szene den Zustandseffekt des Geraets zeichnet.
 */
function placeByArea(bubbles: DeviceBubble[]): PlacedDeviceBubble[] {
  return bubbles.flatMap(bubble => {
    const anchor = deviceAnchor(bubble.deviceId);
    return anchor
      ? [{ bubble, anchor, stale: bubble.lines.length > 0 && bubble.lines.every(line => line.stale) }]
      : [];
  });
}
