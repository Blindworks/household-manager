import { ChangeDetectionStrategy, Component, ElementRef, HostListener, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { HouseDevicePositionService } from '../../services/house-device-position.service';
import { HOUSE_DEVICES, HouseDevice } from '../../shared/house/house-devices';
import { FLOORS, FloorId, HOUSE_ROOMS, OUTDOOR_AREAS, PlanRect, areaFloor, outdoorAreasOn, roomById } from '../../shared/house/house-plan';
import { HouseOpeningsEditorComponent } from './house-openings-editor/house-openings-editor.component';
import { DevicePositions, PlanPoint, isPointInArea, toPositionMap, validPlacement } from '../../shared/house/house-placement.util';

/** Ab dieser Strecke (Pixel) ist eine Geste ein Ziehen, darunter ein Tipp. */
const TAP_TOLERANCE_PX = 8;

const FLOOR_TABS: Record<FloorId, string> = { basement: 'Keller', ground: 'Erdgeschoss', upper: 'Obergeschoss' };

/** Farbe des Punkts nach Art des Geraets. */
export function deviceDotColor(device: HouseDevice): string {
  switch (device.kind) {
    case 'switch':
      return device.lamp ? '#ffd36b' : '#8fb4ff';
    case 'thermostat':
      return '#ff9a4d';
    case 'climate':
      return '#6be0a0';
    case 'contact':
    case 'door':
      return '#ff6b6b';
    case 'appliance':
      return '#8fb4ff';
    default:
      return '#c9a6ff';
  }
}

function areaLabel(areaId: string): string {
  return roomById(areaId)?.label ?? OUTDOOR_AREAS.find(area => area.id === areaId)?.label ?? areaId;
}

function roundPoint(point: PlanPoint): PlanPoint {
  return { x: Math.round(point.x * 10) / 10, y: Math.round(point.y * 10) / 10 };
}

const PLAN_VIEW_WIDTH = 440;

export interface PlanShape { id: string; label: string; rects: PlanRect[]; outdoor: boolean; nameX: number; nameY: number }
export interface PlacedDot { device: HouseDevice; label: string; color: string; point: PlanPoint; transform: string; labelLeft: boolean }
export interface UnplacedEntry { device: HouseDevice; label: string; invalid: boolean }
export interface UnplacedGroup { area: string; entries: UnplacedEntry[] }

/** Laufende Zeigergeste: Listeneintrag, platzierter Punkt oder leerer Plan. */
interface Gesture {
  source: 'list' | 'dot' | 'plan';
  deviceId: string | null;
  startX: number;
  startY: number;
  moved: boolean;
  point: PlanPoint | null;
}

function shapeFor(id: string, label: string, rects: PlanRect[], outdoor: boolean): PlanShape {
  const first = rects[0];
  return {
    id, label, rects, outdoor,
    nameX: (first.x1 + first.x2) / 2,
    nameY: Math.min(first.y1, first.y2) + 14
  };
}

/**
 * Admin-Seite „Geräteplätze“: Draufsicht je Etage, Geraete per Ziehen oder Tippen an ihren
 * echten Platz setzen. Gespeichert wird nur beim Loslassen und nur innerhalb des eigenen
 * Bereichs (`isPointInArea`); die Regeln stehen in `house-placement.util.ts`.
 */
@Component({
  selector: 'app-admin-house-devices',
  standalone: true,
  imports: [NgTemplateOutlet, HouseOpeningsEditorComponent],
  templateUrl: './admin-house-devices.component.html',
  styleUrl: './admin-house-devices.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AdminHouseDevicesComponent implements OnInit {
  private readonly api = inject(HouseDevicePositionService);

  @ViewChild('plan') private planRef?: ElementRef<SVGSVGElement>;

  readonly tabs = FLOORS.map(floor => ({ id: floor.id, label: FLOOR_TABS[floor.id] }));
  readonly floor = signal<FloorId>('ground');
  readonly loadState = signal<'loading' | 'ok' | 'failed'>('loading');
  readonly positions = signal<DevicePositions>(new Map());
  /** Per Tipp markiertes Listengeraet, das der naechste Tipp in den Plan setzt. */
  readonly selectedListId = signal<string | null>(null);
  /** Per Tipp markierter Punkt, fuer „Platz zurücksetzen“. */
  readonly selectedDotId = signal<string | null>(null);
  readonly hint = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  private readonly gesture = signal<Gesture | null>(null);

  readonly floorLabel = computed(() => FLOOR_TABS[this.floor()]);

  readonly shapes = computed<PlanShape[]>(() => {
    const floor = this.floor();
    return [
      ...outdoorAreasOn(floor).map(area => shapeFor(area.id, area.label, [area.rect], true)),
      ...HOUSE_ROOMS.filter(room => room.floor === floor).map(room => shapeFor(room.id, room.label, room.rects, false))
    ];
  });

  private readonly floorDevices = computed(() => {
    const floor = this.floor();
    return HOUSE_DEVICES.filter(device => areaFloor(device.area) === floor);
  });

  private readonly labels = computed(() => {
    const devices = this.floorDevices();
    const counts = new Map<string, number>();
    devices.forEach(device => counts.set(device.label, (counts.get(device.label) ?? 0) + 1));
    return new Map(devices.map(device => [
      device.id,
      (counts.get(device.label) ?? 0) > 1 ? `${device.label} · ${areaLabel(device.area)}` : device.label
    ]));
  });

  readonly selectedDotLabel = computed(() => {
    const id = this.selectedDotId();
    return id ? this.labels().get(id) ?? null : null;
  });

  readonly dots = computed<PlacedDot[]>(() => {
    const positions = this.positions();
    const labels = this.labels();
    const gesture = this.gesture();
    const result: PlacedDot[] = [];
    for (const device of this.floorDevices()) {
      const placed = validPlacement(device, positions);
      const dragged = gesture?.moved && gesture.deviceId === device.id && gesture.source !== 'plan' ? gesture.point : null;
      const point = dragged ?? placed;
      if (point) {
        result.push({
          device, label: labels.get(device.id) ?? device.label, color: deviceDotColor(device), point,
          transform: `translate(${point.x} ${point.y})`,
          labelLeft: point.x + 14 + (labels.get(device.id) ?? device.label).length * 5.5 > PLAN_VIEW_WIDTH
        });
      }
    }
    return result;
  });

  readonly unplacedGroups = computed<UnplacedGroup[]>(() => {
    const positions = this.positions();
    const labels = this.labels();
    const groups = new Map<string, UnplacedEntry[]>();
    for (const device of this.floorDevices()) {
      if (validPlacement(device, positions)) {
        continue;
      }
      const area = areaLabel(device.area);
      const entries = groups.get(area) ?? [];
      entries.push({ device, label: labels.get(device.id) ?? device.label, invalid: positions.has(device.id) });
      groups.set(area, entries);
    }
    return Array.from(groups, ([area, entries]) => ({ area, entries }));
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loadState.set('loading');
    this.api.list().subscribe({
      next: list => {
        this.positions.set(toPositionMap(list));
        this.loadState.set('ok');
      },
      error: () => this.loadState.set('failed')
    });
  }

  selectFloor(floor: FloorId): void {
    this.floor.set(floor);
    this.gesture.set(null);
    this.selectedListId.set(null);
    this.selectedDotId.set(null);
    this.hint.set(null);
  }

  onListPointerDown(device: HouseDevice, event: PointerEvent): void {
    event.preventDefault();
    this.startGesture('list', device.id, event);
  }

  onDotPointerDown(device: HouseDevice, event: PointerEvent): void {
    event.stopPropagation();
    event.preventDefault();
    this.startGesture('dot', device.id, event);
  }

  onPlanPointerDown(event: PointerEvent): void {
    this.startGesture('plan', null, event);
  }

  @HostListener('document:pointermove', ['$event'])
  onPointerMove(event: PointerEvent): void {
    const gesture = this.gesture();
    if (!gesture) {
      return;
    }
    const moved = gesture.moved || this.distance(gesture, event) >= TAP_TOLERANCE_PX;
    this.gesture.set({ ...gesture, moved, point: this.toPlan(event) });
  }

  @HostListener('document:pointerup', ['$event'])
  onPointerUp(event: PointerEvent): void {
    const gesture = this.gesture();
    if (!gesture) {
      return;
    }
    this.gesture.set(null);
    const moved = gesture.moved || this.distance(gesture, event) >= TAP_TOLERANCE_PX;
    const point = this.toPlan(event);
    if (gesture.source === 'plan') {
      this.handlePlanTap(moved, point);
      return;
    }
    const device = HOUSE_DEVICES.find(entry => entry.id === gesture.deviceId);
    if (!device) {
      return;
    }
    if (moved) {
      if (point) {
        this.place(device, point);
      }
    } else if (gesture.source === 'list') {
      this.selectedDotId.set(null);
      this.selectedListId.set(this.selectedListId() === device.id ? null : device.id);
    } else {
      this.selectedListId.set(null);
      this.selectedDotId.set(this.selectedDotId() === device.id ? null : device.id);
    }
  }

  @HostListener('document:pointercancel')
  onPointerCancel(): void {
    this.gesture.set(null);
  }

  resetSelected(): void {
    const deviceId = this.selectedDotId();
    if (!deviceId) {
      return;
    }
    this.errorMessage.set(null);
    this.api.remove(deviceId).subscribe({
      next: () => {
        const next = new Map(this.positions());
        next.delete(deviceId);
        this.positions.set(next);
        this.selectedDotId.set(null);
      },
      error: () => this.errorMessage.set('Speichern fehlgeschlagen.')
    });
  }


  isDragging(deviceId: string): boolean {
    const gesture = this.gesture();
    return !!gesture?.moved && gesture.deviceId === deviceId;
  }

  private handlePlanTap(moved: boolean, point: PlanPoint | null): void {
    const deviceId = this.selectedListId();
    if (moved || !point) {
      return;
    }
    if (!deviceId) {
      this.selectedDotId.set(null);
      return;
    }
    const device = HOUSE_DEVICES.find(entry => entry.id === deviceId);
    if (device) {
      this.place(device, point);
    }
  }

  private place(device: HouseDevice, rawPoint: PlanPoint): void {
    const point = roundPoint(rawPoint);
    this.errorMessage.set(null);
    if (!isPointInArea(device.area, point)) {
      this.hint.set(`„${device.label}“ gehört in: ${areaLabel(device.area)}`);
      return;
    }
    this.hint.set(null);
    this.api.set(device.id, point).subscribe({
      next: saved => {
        const next = new Map(this.positions());
        next.set(saved.deviceId, { x: saved.x, y: saved.y });
        this.positions.set(next);
        this.selectedListId.set(null);
      },
      error: () => this.errorMessage.set('Speichern fehlgeschlagen.')
    });
  }

  private startGesture(source: Gesture['source'], deviceId: string | null, event: PointerEvent): void {
    try {
      (event.target as Element | null)?.setPointerCapture?.(event.pointerId);
    } catch {
      // Ohne aktiven Zeiger (z. B. synthetische Ereignisse) gibt es nichts einzufangen.
    }
    this.gesture.set({ source, deviceId, startX: event.clientX, startY: event.clientY, moved: false, point: this.toPlan(event) });
  }

  private distance(gesture: Gesture, event: PointerEvent): number {
    return Math.hypot(event.clientX - gesture.startX, event.clientY - gesture.startY);
  }

  /** Client-Koordinaten in Plan-Einheiten (viewBox) umrechnen. */
  private toPlan(event: PointerEvent): PlanPoint | null {
    const matrix = this.planRef?.nativeElement.getScreenCTM();
    if (!matrix) {
      return null;
    }
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse());
    return { x: point.x, y: point.y };
  }
}
