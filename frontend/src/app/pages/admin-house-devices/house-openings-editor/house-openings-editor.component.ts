import {
  ChangeDetectionStrategy, Component, HostListener, OnInit, TemplateRef, ViewChild, computed, inject, input, output, signal
} from '@angular/core';
import { HouseOpening, HouseOpeningRequest, OpeningKind, OpeningSize, OpeningSwing } from '../../../models/house-opening.model';
import { HouseOpeningService } from '../../../services/house-opening.service';
import { FloorId, OUTDOOR_AREAS, roomById } from '../../../shared/house/house-plan';
import { PlanPoint } from '../../../shared/house/house-placement.util';
import {
  DEFAULT_SIZE, HingeSide, SIZES_BY_KIND, Segment, defaultSwing, hingeForSide, isInnerWall, isOnWall, linkableDevices,
  normalizeSegment, overlapsAny, sideForHinge, snapToWall, wallSides, widthUnits
} from '../../../shared/house/house-openings.util';

/** Ab dieser Strecke (Pixel) ist eine Geste ein Ziehen, darunter ein Tipp (wie bei den Geraeten). */
const TAP_TOLERANCE_PX = 8;

const WINDOW_COLOR = '#5b8cff';
const DOOR_COLOR = '#c9d6f0';

const NO_WALL = 'Hier ist keine Wand, die lang genug ist.';
const OCCUPIED = 'Dort ist schon ein Fenster oder eine Tür.';
const SIZE_DOES_NOT_FIT = 'Diese Größe passt hier nicht hin.';
const SAVE_FAILED = 'Speichern fehlgeschlagen.';
const PLACE_HINT = 'Auf eine Wand tippen';

const KIND_LABELS: Record<OpeningKind, string> = { WINDOW: 'Fenster', DOOR: 'Tür' };

const SIZE_LABELS: Record<OpeningKind, Partial<Record<OpeningSize, string>>> = {
  WINDOW: { SMALL: 'schmal', NORMAL: 'normal', WIDE: 'breit' },
  DOOR: { NORMAL: 'Tür', TERRACE: 'Terrassentür' }
};

export interface OpeningLine {
  id: number;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  color: string;
  /** Viertelbogen(e) der Tuer vom Anschlag zur `swing`-Seite, sonst leer. */
  arcs: string[];
  linked: PlanPoint | null;
  selected: boolean;
}

export interface SensorOption { value: string; label: string }

interface OpeningPanel {
  opening: HouseOpening;
  title: string;
  sizes: { size: OpeningSize; label: string; active: boolean }[];
  hingeSide: HingeSide | null;
  opensInto: { swing: OpeningSwing; label: string; active: boolean }[] | null;
  sensorOptions: SensorOption[];
  sensorMissing: boolean;
}

interface OrphanEntry { id: number; label: string }

/** Laufende Geste: Tipp beim Setzen auf den Plan oder Ziehen eines Elements. */
interface Gesture {
  source: 'place' | 'opening';
  openingId: number | null;
  startX: number;
  startY: number;
  moved: boolean;
  segment: Segment | null;
}

function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

/**
 * Auf eine Nachkommastelle runden, ohne die Laenge zu veraendern: Mitte runden, Endpunkte
 * daraus mit der (ganzzahligen) Breite neu bilden. Das Backend rundet sonst selbst und prueft
 * die Laenge mit Toleranz.
 */
function roundSegment(s: Segment): Segment {
  const n = normalizeSegment(s);
  if (n.y1 === n.y2) {
    const width = n.x2 - n.x1;
    const from = round1(round1((n.x1 + n.x2) / 2) - width / 2);
    return { x1: from, y1: round1(n.y1), x2: round1(from + width), y2: round1(n.y1) };
  }
  const height = n.y2 - n.y1;
  const from = round1(round1((n.y1 + n.y2) / 2) - height / 2);
  return { x1: round1(n.x1), y1: from, x2: round1(n.x1), y2: round1(from + height) };
}

/** Gleiche Wandlinie: beide waagerecht auf gleichem y oder beide senkrecht auf gleichem x. */
function sameWallLine(a: Segment, b: Segment): boolean {
  const horizontalA = a.y1 === a.y2;
  const horizontalB = b.y1 === b.y2;
  return horizontalA === horizontalB && (horizontalA ? a.y1 === b.y1 : a.x1 === b.x1);
}

/** Strecke gleicher Lage mit neuer Breite um dieselbe Mitte. */
function resized(s: Segment, width: number): Segment {
  const n = normalizeSegment(s);
  if (n.y1 === n.y2) {
    const from = (n.x1 + n.x2) / 2 - width / 2;
    return { x1: from, y1: n.y1, x2: from + width, y2: n.y1 };
  }
  const from = (n.y1 + n.y2) / 2 - width / 2;
  return { x1: n.x1, y1: from, x2: n.x1, y2: from + width };
}

function areaLabel(areaId: string): string {
  return roomById(areaId)?.label ?? OUTDOOR_AREAS.find(area => area.id === areaId)?.label ?? areaId;
}

function toRequest(o: HouseOpening): HouseOpeningRequest {
  return {
    floor: o.floor, kind: o.kind, size: o.size, x1: o.x1, y1: o.y1, x2: o.x2, y2: o.y2,
    hinge: o.hinge, swing: o.swing, deviceId: o.deviceId
  };
}

/** Viertelbogen um `hinge` von `free` (geschlossen) bis zur offenen Lage auf der `swing`-Seite. */
function quarterArc(hinge: PlanPoint, free: PlanPoint, normal: PlanPoint): string {
  const radius = Math.hypot(free.x - hinge.x, free.y - hinge.y);
  const open = { x: hinge.x + normal.x * radius, y: hinge.y + normal.y * radius };
  const cross = (free.x - hinge.x) * (open.y - hinge.y) - (free.y - hinge.y) * (open.x - hinge.x);
  // y zeigt nach unten: positives Kreuzprodukt = im Uhrzeigersinn = SVG-sweep-flag 1.
  const sweep = cross > 0 ? 1 : 0;
  return `M ${hinge.x} ${hinge.y} L ${open.x} ${open.y} M ${free.x} ${free.y} A ${radius} ${radius} 0 0 ${sweep} ${open.x} ${open.y}`;
}

function doorArcs(o: HouseOpening, s: Segment): string[] {
  if (o.kind !== 'DOOR') {
    return [];
  }
  const n = normalizeSegment(s);
  const sign = o.swing === 'POSITIVE' ? 1 : -1;
  const normal = n.y1 === n.y2 ? { x: 0, y: sign } : { x: sign, y: 0 };
  const start = { x: n.x1, y: n.y1 };
  const end = { x: n.x2, y: n.y2 };
  if (o.size === 'TERRACE') {
    const middle = { x: (n.x1 + n.x2) / 2, y: (n.y1 + n.y2) / 2 };
    return [quarterArc(start, middle, normal), quarterArc(end, middle, normal)];
  }
  return o.hinge === 'START' ? [quarterArc(start, end, normal)] : [quarterArc(end, start, normal)];
}

/**
 * Fenster und Tueren auf der Admin-Seite „Geräteplätze“: Anlegen per Tipp auf eine Wand, Ziehen
 * entlang der Waende, Feld fuer Groesse, Anschlag, Oeffnungsseite, Sensor und Loeschen. Die
 * Darstellung im Plan (`layer`, `catcher`) und das Feld (`panel`) rendert die Seite per
 * Template, damit alles im selben SVG bzw. in der Seitenspalte steht. Meldungen gehen ueber
 * `hintChange`/`errorChange` in die feste Meldungszeile der Seite. Alle Regeln stehen in
 * `house-openings.util.ts`.
 */
@Component({
  selector: 'app-house-openings-editor',
  standalone: true,
  templateUrl: './house-openings-editor.component.html',
  styleUrl: './house-openings-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HouseOpeningsEditorComponent implements OnInit {
  private readonly api = inject(HouseOpeningService);

  readonly floor = input.required<FloorId>();
  /** SVG des Plans, fuer die Umrechnung von Client- in Plan-Koordinaten (Template-Referenz der Seite). */
  readonly plan = input<Element | undefined>(undefined);

  readonly hintChange = output<string | null>();
  readonly errorChange = output<string | null>();

  @ViewChild('layer', { static: true }) layer!: TemplateRef<unknown>;
  @ViewChild('catcher', { static: true }) catcher!: TemplateRef<unknown>;
  @ViewChild('panel', { static: true }) panel!: TemplateRef<unknown>;

  readonly loadState = signal<'loading' | 'ok' | 'failed'>('loading');
  readonly openings = signal<HouseOpening[]>([]);
  private readonly placing = signal<{ kind: OpeningKind; floor: FloorId } | null>(null);
  private readonly selected = signal<number | null>(null);
  readonly confirmDeleteId = signal<number | null>(null);
  private readonly gesture = signal<Gesture | null>(null);

  readonly placingKind = computed(() => {
    const placing = this.placing();
    return placing && placing.floor === this.floor() ? placing.kind : null;
  });

  private readonly floorOpenings = computed(() => {
    const floor = this.floor();
    return this.openings().filter(o => o.floor === floor);
  });

  readonly lines = computed<OpeningLine[]>(() => {
    const floor = this.floor();
    const gesture = this.gesture();
    const selectedId = this.selected();
    return this.floorOpenings()
      .filter(o => isOnWall(floor, o))
      .map(o => {
        const dragged = gesture?.source === 'opening' && gesture.moved && gesture.openingId === o.id ? gesture.segment : null;
        const s = normalizeSegment(dragged ?? o);
        return {
          id: o.id, ...s,
          color: o.kind === 'WINDOW' ? WINDOW_COLOR : DOOR_COLOR,
          arcs: doorArcs(o, s),
          linked: o.deviceId ? { x: (s.x1 + s.x2) / 2, y: (s.y1 + s.y2) / 2 } : null,
          selected: o.id === selectedId
        };
      });
  });

  readonly orphans = computed<OrphanEntry[]>(() => {
    const floor = this.floor();
    return this.floorOpenings()
      .filter(o => !isOnWall(floor, o))
      .map(o => ({ id: o.id, label: `${KIND_LABELS[o.kind]} (${SIZE_LABELS[o.kind][o.size] ?? o.size})` }));
  });

  readonly selectedPanel = computed<OpeningPanel | null>(() => {
    const id = this.selected();
    const o = this.floorOpenings().find(entry => entry.id === id);
    if (!o || !isOnWall(o.floor, o)) {
      return null;
    }
    return {
      opening: o,
      title: `${KIND_LABELS[o.kind]} (${SIZE_LABELS[o.kind][o.size] ?? o.size})`,
      sizes: SIZES_BY_KIND[o.kind].map(size => ({ size, label: SIZE_LABELS[o.kind][size] ?? size, active: size === o.size })),
      hingeSide: o.size === 'TERRACE' ? null : sideForHinge(o, o.swing, o.hinge),
      opensInto: this.opensInto(o),
      sensorOptions: this.sensorOptions(o),
      sensorMissing: !!o.deviceId && !linkableDevices().some(device => device.id === o.deviceId)
    };
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loadState.set('loading');
    this.api.list().subscribe({
      next: list => {
        this.openings.set(list);
        this.loadState.set('ok');
      },
      error: () => this.loadState.set('failed')
    });
  }

  startPlacing(kind: OpeningKind): void {
    if (this.placingKind() === kind) {
      this.stopPlacing();
      return;
    }
    this.placing.set({ kind, floor: this.floor() });
    this.selected.set(null);
    this.confirmDeleteId.set(null);
    this.hintChange.emit(PLACE_HINT);
  }

  stopPlacing(): void {
    this.placing.set(null);
    this.hintChange.emit(null);
  }

  onCatcherPointerDown(event: PointerEvent): void {
    event.stopPropagation();
    event.preventDefault();
    this.startGesture('place', null, event);
  }

  onOpeningPointerDown(id: number, event: PointerEvent): void {
    if (this.placingKind()) {
      return;
    }
    event.stopPropagation();
    event.preventDefault();
    this.startGesture('opening', id, event);
  }

  @HostListener('document:pointermove', ['$event'])
  onPointerMove(event: PointerEvent): void {
    const gesture = this.gesture();
    if (!gesture) {
      return;
    }
    const moved = gesture.moved || this.distance(gesture, event) >= TAP_TOLERANCE_PX;
    const o = this.openingById(gesture.openingId);
    const point = this.toPlan(event);
    const segment = moved && o && point ? snapToWall(this.floor(), point, o.kind, o.size) : null;
    this.gesture.set({ ...gesture, moved, segment });
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
    if (gesture.source === 'place') {
      if (!moved && point) {
        this.create(point);
      }
      return;
    }
    const o = this.openingById(gesture.openingId);
    if (!o) {
      return;
    }
    if (!moved) {
      this.confirmDeleteId.set(null);
      this.selected.set(this.selected() === o.id ? null : o.id);
      return;
    }
    if (point) {
      this.move(o, point);
    }
  }

  @HostListener('document:pointercancel')
  onPointerCancel(): void {
    this.gesture.set(null);
  }

  selectSize(o: HouseOpening, size: OpeningSize): void {
    if (size === o.size) {
      return;
    }
    const segment = resized(o, widthUnits(o.kind, size));
    if (!isOnWall(o.floor, segment) || overlapsAny(segment, this.others(o))) {
      this.hintChange.emit(SIZE_DOES_NOT_FIT);
      return;
    }
    this.save({ ...o, ...roundSegment(segment), size });
  }

  selectHingeSide(o: HouseOpening, side: HingeSide): void {
    this.save({ ...o, hinge: hingeForSide(o, o.swing, side) });
  }

  selectSwing(o: HouseOpening, swing: OpeningSwing): void {
    if (swing !== o.swing) {
      this.save({ ...o, swing });
    }
  }

  onSensorChange(o: HouseOpening, event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.save({ ...o, deviceId: value === '' ? null : value });
  }

  askDelete(id: number): void {
    this.confirmDeleteId.set(id);
  }

  cancelDelete(): void {
    this.confirmDeleteId.set(null);
  }

  confirmDelete(id: number): void {
    this.errorChange.emit(null);
    this.api.remove(id).subscribe({
      next: () => {
        this.openings.set(this.openings().filter(o => o.id !== id));
        this.confirmDeleteId.set(null);
        if (this.selected() === id) {
          this.selected.set(null);
        }
      },
      error: () => this.errorChange.emit(SAVE_FAILED)
    });
  }

  isDragging(id: number): boolean {
    const gesture = this.gesture();
    return gesture?.source === 'opening' && gesture.moved && gesture.openingId === id;
  }

  private create(point: PlanPoint): void {
    const kind = this.placingKind();
    if (!kind) {
      return;
    }
    const floor = this.floor();
    const size = DEFAULT_SIZE[kind];
    const snapped = snapToWall(floor, point, kind, size);
    if (!snapped) {
      this.hintChange.emit(NO_WALL);
      return;
    }
    const segment = roundSegment(snapped);
    if (overlapsAny(segment, this.floorOpenings())) {
      this.hintChange.emit(OCCUPIED);
      return;
    }
    this.errorChange.emit(null);
    const request: HouseOpeningRequest = {
      floor, kind, size, ...segment, hinge: 'START', swing: defaultSwing(floor, segment), deviceId: null
    };
    this.api.create(request).subscribe({
      next: created => {
        this.openings.set([...this.openings(), created]);
        this.placing.set(null);
        this.selected.set(created.id);
        this.hintChange.emit(null);
      },
      error: () => this.errorChange.emit(SAVE_FAILED)
    });
  }

  private move(o: HouseOpening, point: PlanPoint): void {
    const snapped = snapToWall(o.floor, point, o.kind, o.size);
    if (!snapped) {
      this.hintChange.emit(NO_WALL);
      return;
    }
    const segment = roundSegment(snapped);
    if (overlapsAny(segment, this.others(o))) {
      this.hintChange.emit(OCCUPIED);
      return;
    }
    const swing = sameWallLine(segment, normalizeSegment(o)) ? o.swing : defaultSwing(o.floor, segment);
    this.hintChange.emit(null);
    this.save({ ...o, ...segment, swing });
  }

  /** Speichert per `update`; bei einem Fehler (auch 409) bleibt der letzte gespeicherte Stand. */
  private save(next: HouseOpening): void {
    this.errorChange.emit(null);
    this.api.update(next.id, toRequest(next)).subscribe({
      next: saved => this.openings.set(this.openings().map(o => o.id === saved.id ? saved : o)),
      error: () => this.errorChange.emit(SAVE_FAILED)
    });
  }

  private opensInto(o: HouseOpening): OpeningPanel['opensInto'] {
    if (!isInnerWall(o.floor, o)) {
      return null;
    }
    const sides = wallSides(o.floor, o);
    return (['NEGATIVE', 'POSITIVE'] as const).map(swing => {
      const roomId = swing === 'POSITIVE' ? sides.positive : sides.negative;
      return { swing, label: areaLabel(roomId ?? ''), active: swing === o.swing };
    });
  }

  /** „kein Sensor“ plus alle verknuepfbaren Sensoren, die kein anderes Element belegt. */
  private sensorOptions(o: HouseOpening): SensorOption[] {
    const used = new Set(this.openings().filter(other => other.id !== o.id && other.deviceId).map(other => other.deviceId));
    const options: SensorOption[] = [{ value: '', label: 'kein Sensor' }];
    if (o.deviceId && !linkableDevices().some(device => device.id === o.deviceId)) {
      options.push({ value: o.deviceId, label: 'Sensor nicht gefunden' });
    }
    for (const device of linkableDevices()) {
      if (!used.has(device.id)) {
        options.push({ value: device.id, label: `${device.label} · ${areaLabel(device.area)}` });
      }
    }
    return options;
  }

  private others(o: HouseOpening): HouseOpening[] {
    return this.floorOpenings().filter(other => other.id !== o.id);
  }

  private openingById(id: number | null): HouseOpening | undefined {
    return id === null ? undefined : this.openings().find(o => o.id === id);
  }

  private startGesture(source: Gesture['source'], openingId: number | null, event: PointerEvent): void {
    try {
      (event.target as Element | null)?.setPointerCapture?.(event.pointerId);
    } catch {
      // Ohne aktiven Zeiger (z. B. synthetische Ereignisse) gibt es nichts einzufangen.
    }
    this.gesture.set({ source, openingId, startX: event.clientX, startY: event.clientY, moved: false, segment: null });
  }

  private distance(gesture: Gesture, event: PointerEvent): number {
    return Math.hypot(event.clientX - gesture.startX, event.clientY - gesture.startY);
  }

  /** Client-Koordinaten in Plan-Einheiten (viewBox) umrechnen. */
  private toPlan(event: PointerEvent): PlanPoint | null {
    const plan = this.plan();
    const matrix = plan instanceof SVGGraphicsElement ? plan.getScreenCTM() : null;
    if (!matrix) {
      return null;
    }
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse());
    return { x: point.x, y: point.y };
  }
}
