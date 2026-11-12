import { InjectionToken } from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { FLOORS, FloorId, OUTDOOR_AREAS } from '../../shared/house/house-plan';
import {
  CameraView, Vec3, cameraForOverview, floorBase, insideCutout, isFloorVisible, roomBoxes,
  toPlanX, toPlanY, toWorldX, toWorldZ
} from '../../shared/house/house-geometry.util';
import { HouseEffect, HouseEffectKind } from '../../shared/house/house-effects.util';
import { OpeningShape } from '../../shared/house/house-openings.util';
import { ApplianceBody, ApplianceBodyState } from '../../shared/house/house-appliance-bodies.util';

/**
 * Three.js-Szene der 3D-Hausansicht. Einzige Datei, die `three` importiert -
 * sie haengt nur am Lazy-Chunk der Route `tablet/house`.
 *
 * Gezeichnet wird nur bei Bedarf (Steuerung, Kamerafahrt, Etagenwechsel,
 * Groessenaenderung, requestRender) - kein Dauer-Loop, damit das Wandtablet
 * im Ruhezustand nichts rechnet. Einzige Ausnahme ist `playEffects`: die
 * Zustandseffekte laufen beim Betreten eines Raums wenige Sekunden animiert.
 */
export interface ScreenPoint { x: number; y: number; visible: boolean }

const BACKGROUND = 0x05070f;
const NEON = 0x5b8cff;
const OUTDOOR = 0x3fbf7f;
const FILL_OPACITY = 0.08;
const DEFAULT_FLY_MS = 700;
const MIN_DISTANCE_M = 3;
const MAX_DISTANCE_M = 30;
/** Knapp ueber dem Boden, damit die Bodenplatte nicht mit den Kanten flimmert. */
const PLATE_LIFT_M = 0.01;
/** Effekte liegen knapp ueber der Bodenplatte. */
const EFFECT_LIFT_M = 0.03;
/** Dauer der kurzen Animation beim Betreten eines Raums; danach steht alles wieder still. */
export const EFFECT_PLAY_MS = 3000;

const EFFECT_COLORS: Record<HouseEffectKind, number> = {
  light: 0xffc46b,
  powered: 0x7fb4ff,
  heating: 0xff7a2f,
  open: 0xff3b5c,
  motion: 0x7dffb0,
  running: 0x3fe0ff
};

interface FloorLayer {
  group: THREE.Group;
  plates: THREE.Mesh[];
  /** Fenster und Tueren der Etage; Kind der Layer-Gruppe und folgt deren Sichtbarkeit. */
  openings: THREE.Group;
  /** Koerper der Grossgeraete der Etage; ebenfalls Kind der Layer-Gruppe. */
  bodies: THREE.Group;
}

/** Fenster: bläuliches Glas; Tuerblatt: heller; offen: rot. Rahmenfarbe je Zustand. */
const OPENING_COLORS = {
  windowPane: 0x8fd3ff,
  doorPane: 0xdff3ff,
  openPane: 0xff8f8f,
  frame: 0xb9e6ff,
  openFrame: 0xff8f8f
} as const;
const OPENING_PANE_OPACITY = { window: 0.22, door: 0.4, open: 0.35 } as const;
/** Versatz vor die Wandebene, damit die Flaeche nicht mit den Raumkanten flimmert. */
const OPENING_OFFSET_M = 0.015;

/** Grossgeraete (Stil wie Glas/Tuerblatt): Flaeche, Kante und Merkmal je Zustand. */
const BODY_COLORS = {
  fill: 0x8fd3ff, edge: 0xb9e6ff, active: 0x5ff2d6, screen: 0x8caaff, finished: 0xffd36b, alarm: 0xff8f8f
} as const;
const BODY_FILL_OPACITY = { normal: 0.10, alarm: 0.2 } as const;
/** Merkmal knapp vor der Vorderseite, damit es nicht mit ihr flimmert. */
const BODY_FEATURE_OFFSET_M = 0.006;
/** Laufflaeche des Laufbands; das Pult steht am Kopfende (an der Wand). */
const TREADMILL_DECK_M = 0.15;
const TREADMILL_CONSOLE_DEPTH_M = 0.12;

const easeInOut = (t: number): number => (t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2);

export class HouseSceneRenderer {
  private readonly renderer: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera = new THREE.PerspectiveCamera(45, 1, 0.1, 200);
  private readonly controls: OrbitControls;
  private readonly raycaster = new THREE.Raycaster();
  private readonly resizeObserver: ResizeObserver;
  private readonly layers = new Map<FloorId, FloorLayer>();
  private readonly renderedCallbacks: (() => void)[] = [];

  private readonly fillMaterial = new THREE.MeshBasicMaterial({
    color: NEON, transparent: true, opacity: FILL_OPACITY, depthWrite: false, side: THREE.DoubleSide
  });
  private readonly solidEdgeMaterial = new THREE.LineBasicMaterial({ color: NEON });
  private readonly outdoorMaterial = new THREE.LineDashedMaterial({ color: OUTDOOR, dashSize: 0.3, gapSize: 0.2 });

  /** Zustandseffekte; bei jedem `setEffects` neu befuellt, Materialien und Geometrien geteilt. */
  private readonly effectGroup = new THREE.Group();
  private readonly glowTextures = new Map<HouseEffectKind, THREE.Texture>();
  private readonly spriteMaterials = new Map<HouseEffectKind, THREE.SpriteMaterial>();
  private readonly floorGlowMaterials = new Map<HouseEffectKind, THREE.MeshBasicMaterial>();
  private readonly solidMaterials = new Map<HouseEffectKind, THREE.MeshBasicMaterial>();
  private readonly floorGlowGeometry = new THREE.PlaneGeometry(1, 1);
  private readonly bulbGeometry = new THREE.SphereGeometry(1, 16, 12);
  private readonly ringGeometry = new THREE.RingGeometry(0.3, 0.38, 40);
  private readonly arcGeometry = new THREE.TorusGeometry(0.4, 0.035, 8, 40, Math.PI * 1.4);
  /** Animierte Teile der aktuellen Effekte. */
  private motionRings: THREE.Mesh[] = [];
  private runningArcs: THREE.Mesh[] = [];
  private playback: { handle: number | null } | null = null;
  /** Fenster-/Tuermaterialien je Zustand, geteilt. */
  private readonly openingMaterials = new Map<string, THREE.Material>();
  /** Geraetekoerper-Materialien je Zustand, geteilt. */
  private readonly bodyMaterials = new Map<string, THREE.Material>();

  private selectedFloor: FloorId = 'ground';
  private frameHandle: number | null = null;
  private flight: { handle: number | null; finish: () => void } | null = null;
  private disposed = false;

  constructor(private readonly host: HTMLElement) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    this.renderer.setClearColor(BACKGROUND);
    this.renderer.domElement.style.display = 'block';
    this.scene.background = new THREE.Color(BACKGROUND);
    host.appendChild(this.renderer.domElement);

    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = false;
    this.controls.minDistance = MIN_DISTANCE_M;
    this.controls.maxDistance = MAX_DISTANCE_M;
    // Nie unter den Boden schauen.
    this.controls.maxPolarAngle = Math.PI / 2 - 0.05;
    this.controls.addEventListener('change', this.onControlsChange);

    this.buildFloors();
    this.buildOutdoorAreas();
    this.scene.add(this.effectGroup);
    this.applyView(cameraForOverview(this.selectedFloor));
    this.applyFloorVisibility();

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(host);
    this.resize();
  }

  setFloor(floor: FloorId): void {
    this.selectedFloor = floor;
    this.applyFloorVisibility();
    this.requestRender();
  }

  flyTo(view: CameraView, durationMs = DEFAULT_FLY_MS): Promise<void> {
    this.cancelFlight();
    if (this.disposed) {
      return Promise.resolve();
    }
    if (durationMs <= 0) {
      this.applyView(view);
      this.requestRender();
      return Promise.resolve();
    }

    const fromPosition = this.camera.position.clone();
    const fromTarget = this.controls.target.clone();
    const toPosition = new THREE.Vector3(view.position.x, view.position.y, view.position.z);
    const toTarget = new THREE.Vector3(view.target.x, view.target.y, view.target.z);
    const start = performance.now();

    return new Promise<void>(resolve => {
      const flight = {
        handle: null as number | null,
        finish: () => {
          if (this.flight === flight) {
            this.flight = null;
          }
          resolve();
        }
      };
      const step = (now: number) => {
        if (this.flight !== flight) {
          return;
        }
        const t = Math.min(1, (now - start) / durationMs);
        const eased = easeInOut(t);
        this.camera.position.lerpVectors(fromPosition, toPosition, eased);
        this.controls.target.lerpVectors(fromTarget, toTarget, eased);
        this.controls.update();
        this.requestRender();
        if (t < 1) {
          flight.handle = requestAnimationFrame(step);
        } else {
          flight.finish();
        }
      };
      this.flight = flight;
      flight.handle = requestAnimationFrame(step);
    });
  }

  pickRoom(clientX: number, clientY: number): string | null {
    const layer = this.layers.get(this.selectedFloor);
    const bounds = this.renderer.domElement.getBoundingClientRect();
    if (!layer || bounds.width === 0 || bounds.height === 0) {
      return null;
    }
    const ndc = new THREE.Vector2(
      ((clientX - bounds.left) / bounds.width) * 2 - 1,
      -((clientY - bounds.top) / bounds.height) * 2 + 1
    );
    this.raycaster.setFromCamera(ndc, this.camera);
    for (const hit of this.raycaster.intersectObjects(layer.plates, false)) {
      const roomId = hit.object.userData['roomId'] as string;
      // Der Flur traegt das Gaeste-WC als Ausschnitt: ein Treffer dort gehoert dem WC.
      if (!insideCutout(roomId, toPlanX(hit.point.x), toPlanY(hit.point.z))) {
        return roomId;
      }
    }
    return null;
  }

  project(points: Vec3[]): ScreenPoint[] {
    const width = this.host.clientWidth;
    const height = this.host.clientHeight;
    const vector = new THREE.Vector3();
    return points.map(point => {
      vector.set(point.x, point.y, point.z).project(this.camera);
      const visible = width > 0 && height > 0
        && vector.z > -1 && vector.z < 1
        && Math.abs(vector.x) <= 1 && Math.abs(vector.y) <= 1;
      return { x: ((vector.x + 1) / 2) * width, y: ((1 - vector.y) / 2) * height, visible };
    });
  }

  onRendered(cb: () => void): void {
    this.renderedCallbacks.push(cb);
  }

  requestRender(): void {
    if (this.disposed || this.frameHandle !== null) {
      return;
    }
    this.frameHandle = requestAnimationFrame(() => {
      this.frameHandle = null;
      this.renderFrame();
    });
  }

  /**
   * Ersetzt die Zustandseffekte. Laeuft gerade die kurze Animation, laeuft sie mit
   * den neuen Effekten weiter und endet wie geplant; sonst stehen sie still.
   */
  setEffects(effects: readonly HouseEffect[]): void {
    if (this.disposed) {
      return;
    }
    this.effectGroup.clear();
    this.motionRings = [];
    this.runningArcs = [];
    effects.forEach(effect => this.addEffect(effect));
    if (!this.playback) {
      this.applyStaticPose();
    }
    this.requestRender();
  }

  /**
   * Ersetzt die Fenster und Tueren aller Etagen (je Etage eine Gruppe in deren Layer-Gruppe).
   * Gezeichnet wird nur bei Bedarf wie sonst auch.
   */
  setOpenings(shapes: readonly OpeningShape[]): void {
    if (this.disposed) {
      return;
    }
    for (const layer of this.layers.values()) {
      this.clearGroup(layer.openings);
    }
    shapes.forEach(shape => this.addOpening(shape));
    this.requestRender();
  }

  /**
   * Ersetzt die Koerper der Grossgeraete aller Etagen (je Etage eine Gruppe in deren Layer-Gruppe).
   * Koerper sind statisch; gezeichnet wird nur bei Bedarf.
   */
  setBodies(bodies: readonly ApplianceBody[]): void {
    if (this.disposed) {
      return;
    }
    for (const layer of this.layers.values()) {
      this.clearGroup(layer.bodies);
    }
    bodies.forEach(body => this.addBody(body));
    this.requestRender();
  }

  /**
   * Spielt die Effekte kurz animiert ab (Pulsieren, Ringe, drehender Bogen) und kehrt
   * danach in die statische Pose zurueck - nur waehrend dieser Zeit wird dauernd gezeichnet.
   */
  playEffects(durationMs = EFFECT_PLAY_MS): void {
    this.stopPlayback();
    // Ohne Ring, Bogen oder Heizung bewegt sich nichts: dann gar nicht erst dauernd zeichnen.
    if (this.disposed || durationMs <= 0 || !this.hasAnimatedEffects()) {
      return;
    }
    const start = performance.now();
    const playback = { handle: null as number | null };
    const step = (now: number) => {
      if (this.playback !== playback) {
        return;
      }
      const elapsed = now - start;
      if (elapsed >= durationMs) {
        this.playback = null;
        this.applyStaticPose();
      } else {
        this.applyAnimatedPose(elapsed / 1000);
        playback.handle = requestAnimationFrame(step);
      }
      this.requestRender();
    };
    this.playback = playback;
    playback.handle = requestAnimationFrame(step);
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.cancelFlight();
    this.stopPlayback();
    if (this.frameHandle !== null) {
      cancelAnimationFrame(this.frameHandle);
      this.frameHandle = null;
    }
    this.resizeObserver.disconnect();
    this.controls.removeEventListener('change', this.onControlsChange);
    this.controls.dispose();
    this.scene.traverse(object => {
      if (object instanceof THREE.Mesh || object instanceof THREE.Line) {
        object.geometry.dispose();
      }
    });
    [this.fillMaterial, this.solidEdgeMaterial, this.outdoorMaterial, ...this.openingMaterials.values(), ...this.bodyMaterials.values(),
      ...this.spriteMaterials.values(), ...this.floorGlowMaterials.values(), ...this.solidMaterials.values()]
      .forEach(material => material.dispose());
    // Geteilte Effekt-Geometrien haengen nicht dauerhaft in der Szene, der traverse oben erreicht sie nicht sicher.
    [this.floorGlowGeometry, this.bulbGeometry, this.ringGeometry, this.arcGeometry]
      .forEach(geometry => geometry.dispose());
    this.glowTextures.forEach(texture => texture.dispose());
    // dispose() gibt den WebGL-Kontext nicht frei; ohne forceContextLoss stapeln sich bei
    // jedem Seitenwechsel Kontexte, bis der Browser die aeltesten verwirft.
    this.renderer.forceContextLoss();
    this.renderer.dispose();
    this.renderer.domElement.remove();
    this.renderedCallbacks.length = 0;
  }

  private readonly onControlsChange = (): void => this.requestRender();

  private renderFrame(): void {
    if (this.disposed) {
      return;
    }
    this.renderer.render(this.scene, this.camera);
    this.renderedCallbacks.forEach(cb => cb());
  }

  private resize(): void {
    const width = this.host.clientWidth;
    const height = this.host.clientHeight;
    if (width === 0 || height === 0) {
      return;
    }
    this.renderer.setSize(width, height);
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.requestRender();
  }

  private applyView(view: CameraView): void {
    this.camera.position.set(view.position.x, view.position.y, view.position.z);
    this.controls.target.set(view.target.x, view.target.y, view.target.z);
    this.controls.update();
  }

  private cancelFlight(): void {
    const flight = this.flight;
    if (!flight) {
      return;
    }
    if (flight.handle !== null) {
      cancelAnimationFrame(flight.handle);
    }
    flight.finish();
  }

  private buildFloors(): void {
    for (const floor of FLOORS) {
      const group = new THREE.Group();
      const openings = new THREE.Group();
      const bodies = new THREE.Group();
      group.add(openings, bodies);
      this.layers.set(floor.id, { group, plates: [], openings, bodies });
      this.scene.add(group);
    }
    for (const box of roomBoxes()) {
      const layer = this.layers.get(box.floor)!;

      const plate = new THREE.Mesh(new THREE.PlaneGeometry(box.size.x, box.size.z), this.fillMaterial);
      plate.rotation.x = -Math.PI / 2;
      plate.position.set(box.center.x, floorBase(box.floor) + PLATE_LIFT_M, box.center.z);
      plate.userData['roomId'] = box.roomId;
      layer.plates.push(plate);
      layer.group.add(plate);

      const boxGeometry = new THREE.BoxGeometry(box.size.x, box.size.y, box.size.z);
      const edges = new THREE.LineSegments(new THREE.EdgesGeometry(boxGeometry), this.solidEdgeMaterial);
      boxGeometry.dispose();
      edges.position.set(box.center.x, box.center.y, box.center.z);
      layer.group.add(edges);
    }
  }

  private buildOutdoorAreas(): void {
    for (const area of OUTDOOR_AREAS) {
      const { x1, y1, x2, y2 } = area.rect;
      const corners = [[x1, y1], [x2, y1], [x2, y2], [x1, y2], [x1, y1]]
        .map(([x, y]) => new THREE.Vector3(toWorldX(x), floorBase(area.floor) + PLATE_LIFT_M, toWorldZ(y)));
      const outline = new THREE.Line(new THREE.BufferGeometry().setFromPoints(corners), this.outdoorMaterial);
      outline.computeLineDistances();
      // Der Umriss gehoert zur Layer-Gruppe seiner Etage und folgt deren Sichtbarkeit.
      this.layers.get(area.floor)!.group.add(outline);
    }
  }

  /** Entfernt alle Kinder und gibt deren Geometrien frei, auch verschachtelte (Materialien sind geteilt und bleiben). */
  private clearGroup(group: THREE.Group): void {
    for (const child of [...group.children]) {
      child.traverse(object => {
        if (object instanceof THREE.Mesh || object instanceof THREE.Line) {
          object.geometry.dispose();
        }
      });
      group.remove(child);
    }
  }

  private addBody(body: ApplianceBody): void {
    const layer = this.layers.get(body.floor);
    if (!layer) {
      return;
    }
    const group = new THREE.Group();
    group.position.set(body.base.x, body.base.y, body.base.z);
    group.rotation.y = body.rotationY;
    const { widthM: w, depthM: d, heightM: h } = body;
    if (body.kind === 'treadmill') {
      this.addBox(group, body.state, w, TREADMILL_DECK_M, d, 0);
      const consoleZ = -d / 2 + TREADMILL_CONSOLE_DEPTH_M / 2;
      this.addBox(group, body.state, w, h, TREADMILL_CONSOLE_DEPTH_M, consoleZ);
      this.addFeature(group, body.state, false, 0.3, 0.15, h - 0.15, consoleZ + TREADMILL_CONSOLE_DEPTH_M / 2);
    } else {
      this.addBox(group, body.state, w, h, d, 0);
      this.addKindFeature(group, body, w, h, d / 2);
    }
    layer.bodies.add(group);
  }

  /** Quader mit Unterkante auf y = 0 der Gruppe, Mitte bei z. */
  private addBox(group: THREE.Group, state: ApplianceBodyState, w: number, h: number, d: number, z: number): void {
    const geometry = new THREE.BoxGeometry(w, h, d);
    const fill = new THREE.Mesh(geometry, this.bodyFillMaterial(state));
    fill.position.set(0, h / 2, z);
    const edges = new THREE.LineSegments(new THREE.EdgesGeometry(geometry), this.bodyEdgeMaterial(state));
    edges.position.copy(fill.position);
    group.add(fill, edges);
  }

  private addKindFeature(group: THREE.Group, body: ApplianceBody, w: number, h: number, front: number): void {
    const state = body.state;
    switch (body.kind) {
      case 'washer': {
        const geometry = new THREE.CircleGeometry(0.18, 32);
        const glass = new THREE.Mesh(geometry, this.bodyFeatureMaterial(state, false));
        glass.position.set(0, h * 0.45, front + BODY_FEATURE_OFFSET_M);
        const ring = new THREE.LineSegments(new THREE.EdgesGeometry(geometry), this.bodyEdgeMaterial(state));
        ring.position.copy(glass.position);
        group.add(glass, ring);
        break;
      }
      case 'dishwasher':
        this.addFeature(group, state, false, w * 0.9, 0.06, h - 0.06, front);
        break;
      case 'fridge':
        this.addFeature(group, state, false, w, 0.02, h * 0.65, front);
        break;
      case 'tv':
        this.addFeature(group, state, true, w * 0.94, h * 0.9, h / 2, front);
        break;
      case 'pc':
        this.addFeature(group, state, false, 0.03, h * 0.7, h / 2, front);
        break;
      case 'printer':
        this.addFeature(group, state, false, w * 0.7, 0.03, h * 0.7, front);
        break;
      case 'coffee':
        this.addFeature(group, state, false, 0.14, 0.08, 0.06, front);
        break;
      case 'treadmill':
        break;
    }
  }

  /** Flaches Merkmal auf der Vorderseite (lokale +z), Mitte auf Hoehe `centerY`. */
  private addFeature(group: THREE.Group, state: ApplianceBodyState, screen: boolean,
                     w: number, h: number, centerY: number, front: number): void {
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, h), this.bodyFeatureMaterial(state, screen));
    mesh.position.set(0, centerY, front + BODY_FEATURE_OFFSET_M);
    group.add(mesh);
  }

  private bodyFillMaterial(state: ApplianceBodyState): THREE.MeshBasicMaterial {
    const alarm = state === 'alarm';
    return this.bodyMaterial(`fill:${alarm}`, () => new THREE.MeshBasicMaterial({
      color: alarm ? BODY_COLORS.alarm : BODY_COLORS.fill,
      opacity: alarm ? BODY_FILL_OPACITY.alarm : BODY_FILL_OPACITY.normal,
      transparent: true, depthWrite: false, side: THREE.DoubleSide
    }));
  }

  private bodyEdgeMaterial(state: ApplianceBodyState): THREE.LineBasicMaterial {
    const color = state === 'active' ? BODY_COLORS.active : state === 'alarm' ? BODY_COLORS.alarm : BODY_COLORS.edge;
    return this.bodyMaterial(`edge:${color}`, () => new THREE.LineBasicMaterial({ color }));
  }

  /** Ruhend nur angedeutet; an leuchtet es (Bildschirm blau-hell, sonst tuerkis), fertig gelb, Alarm rot. */
  private bodyFeatureMaterial(state: ApplianceBodyState, screen: boolean): THREE.MeshBasicMaterial {
    const lit = state !== 'idle';
    const color = state === 'active' ? (screen ? BODY_COLORS.screen : BODY_COLORS.active)
      : state === 'finished' ? BODY_COLORS.finished : state === 'alarm' ? BODY_COLORS.alarm : BODY_COLORS.edge;
    const opacity = lit ? (screen && state === 'active' ? 0.55 : 0.75) : 0.18;
    return this.bodyMaterial(`feature:${color}:${opacity}`, () => new THREE.MeshBasicMaterial({
      color, opacity, transparent: true, depthWrite: false, side: THREE.DoubleSide
    }));
  }

  private bodyMaterial<T extends THREE.Material>(key: string, create: () => T): T {
    let material = this.bodyMaterials.get(key) as T | undefined;
    if (!material) {
      material = create();
      this.bodyMaterials.set(key, material);
    }
    return material;
  }


  private addOpening(shape: OpeningShape): void {
    const layer = this.layers.get(shape.floor);
    if (!layer) {
      return;
    }
    // Versatz senkrecht zur Wand; beide Seiten sind sichtbar (DoubleSide), die Richtung ist gleichgueltig.
    const [a, b] = shape.frame;
    const length = Math.hypot(b.x - a.x, b.z - a.z) || 1;
    const nx = -(b.z - a.z) / length * OPENING_OFFSET_M;
    const nz = (b.x - a.x) / length * OPENING_OFFSET_M;
    const toVector = (p: Vec3) => new THREE.Vector3(p.x + nx, p.y, p.z + nz);
    const open = shape.open;
    const door = shape.kind === 'DOOR';

    shape.panels.forEach(({ corners }) => {
      const points = corners.map(toVector);
      const geometry = new THREE.BufferGeometry().setFromPoints(
        [points[0], points[1], points[2], points[0], points[2], points[3]]);
      layer.openings.add(new THREE.Mesh(geometry, this.paneMaterial(door, open)));
      layer.openings.add(new THREE.LineLoop(new THREE.BufferGeometry().setFromPoints(points), this.frameMaterial(open)));
    });
    layer.openings.add(new THREE.LineLoop(
      new THREE.BufferGeometry().setFromPoints(shape.frame.map(toVector)), this.frameMaterial(open)));
  }

  private paneMaterial(door: boolean, open: boolean): THREE.MeshBasicMaterial {
    const key = `pane:${door ? 'door' : 'window'}:${open}`;
    let material = this.openingMaterials.get(key) as THREE.MeshBasicMaterial | undefined;
    if (!material) {
      material = new THREE.MeshBasicMaterial({
        color: open ? OPENING_COLORS.openPane : door ? OPENING_COLORS.doorPane : OPENING_COLORS.windowPane,
        opacity: open ? OPENING_PANE_OPACITY.open : door ? OPENING_PANE_OPACITY.door : OPENING_PANE_OPACITY.window,
        transparent: true, depthWrite: false, side: THREE.DoubleSide
      });
      this.openingMaterials.set(key, material);
    }
    return material;
  }

  private frameMaterial(open: boolean): THREE.LineBasicMaterial {
    const key = `frame:${open}`;
    let material = this.openingMaterials.get(key) as THREE.LineBasicMaterial | undefined;
    if (!material) {
      material = new THREE.LineBasicMaterial({ color: open ? OPENING_COLORS.openFrame : OPENING_COLORS.frame });
      this.openingMaterials.set(key, material);
    }
    return material;
  }

  private hasAnimatedEffects(): boolean {
    return this.motionRings.length > 0 || this.runningArcs.length > 0
      || this.effectGroup.children.some(child => child instanceof THREE.Sprite
        && child.material === this.spriteMaterials.get('heating'));
  }

  private stopPlayback(): void {
    if (this.playback?.handle != null) {
      cancelAnimationFrame(this.playback.handle);
    }
    this.playback = null;
  }

  private addEffect(effect: HouseEffect): void {
    const floor = effect.floorY + EFFECT_LIFT_M;
    const at = (height: number) => new THREE.Vector3(effect.anchor.x, floor + height, effect.anchor.z);
    // Der haengende Teil sitzt auf der Hoehe des Geraets, Schein und Ringe auf dem Boden.
    const hung = new THREE.Vector3(effect.anchor.x, effect.anchor.y, effect.anchor.z);
    const strength = effect.intensity;
    switch (effect.kind) {
      case 'light':
        this.addBulb(effect.kind, hung, 0.09);
        this.addSprite(effect.kind, hung, 0.6 + 0.9 * strength);
        this.addFloorGlow(effect.kind, at(0), 1.5 + 2.5 * strength);
        break;
      case 'powered':
        this.addBulb(effect.kind, hung, 0.05);
        this.addSprite(effect.kind, hung, 0.45);
        break;
      case 'heating':
        this.addSprite(effect.kind, hung, 1.3);
        this.addFloorGlow(effect.kind, at(0), 1.6);
        break;
      case 'open':
        this.addSprite(effect.kind, hung, 1.4);
        this.addFloorGlow(effect.kind, at(0), 1.8);
        break;
      case 'motion':
        this.motionRings.push(this.addFlat(this.ringGeometry, effect.kind, at(0)));
        break;
      case 'running':
        this.runningArcs.push(this.addFlat(this.arcGeometry, effect.kind, at(0.02)));
        this.addSprite(effect.kind, at(0.2), 0.6);
        break;
    }
  }

  private addBulb(kind: HouseEffectKind, position: THREE.Vector3, radius: number): void {
    const bulb = new THREE.Mesh(this.bulbGeometry, this.solidMaterial(kind));
    bulb.position.copy(position);
    bulb.scale.setScalar(radius);
    this.effectGroup.add(bulb);
  }

  private addSprite(kind: HouseEffectKind, position: THREE.Vector3, size: number): void {
    let material = this.spriteMaterials.get(kind);
    if (!material) {
      material = new THREE.SpriteMaterial({
        map: this.glowTexture(kind), blending: THREE.AdditiveBlending, depthWrite: false, transparent: true
      });
      this.spriteMaterials.set(kind, material);
    }
    const sprite = new THREE.Sprite(material);
    sprite.position.copy(position);
    sprite.scale.set(size, size, 1);
    this.effectGroup.add(sprite);
  }

  private addFloorGlow(kind: HouseEffectKind, position: THREE.Vector3, size: number): void {
    let material = this.floorGlowMaterials.get(kind);
    if (!material) {
      material = new THREE.MeshBasicMaterial({
        map: this.glowTexture(kind), blending: THREE.AdditiveBlending, depthWrite: false, transparent: true,
        opacity: 0.7, side: THREE.DoubleSide
      });
      this.floorGlowMaterials.set(kind, material);
    }
    const glow = new THREE.Mesh(this.floorGlowGeometry, material);
    glow.rotation.x = -Math.PI / 2;
    glow.position.copy(position);
    glow.scale.set(size, size, 1);
    this.effectGroup.add(glow);
  }

  private addFlat(geometry: THREE.BufferGeometry, kind: HouseEffectKind, position: THREE.Vector3): THREE.Mesh {
    const mesh = new THREE.Mesh(geometry, this.solidMaterial(kind));
    mesh.rotation.x = -Math.PI / 2;
    mesh.position.copy(position);
    this.effectGroup.add(mesh);
    return mesh;
  }

  private solidMaterial(kind: HouseEffectKind): THREE.MeshBasicMaterial {
    let material = this.solidMaterials.get(kind);
    if (!material) {
      material = new THREE.MeshBasicMaterial({ color: EFFECT_COLORS[kind], transparent: true, side: THREE.DoubleSide });
      this.solidMaterials.set(kind, material);
    }
    return material;
  }

  /** Radialer Verlauf als Leuchtschein; je Farbe einmal erzeugt. */
  private glowTexture(kind: HouseEffectKind): THREE.Texture {
    let texture = this.glowTextures.get(kind);
    if (!texture) {
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 128;
      const context = canvas.getContext('2d')!;
      const color = new THREE.Color(EFFECT_COLORS[kind]);
      const rgb = `${Math.round(color.r * 255)},${Math.round(color.g * 255)},${Math.round(color.b * 255)}`;
      const gradient = context.createRadialGradient(64, 64, 0, 64, 64, 64);
      gradient.addColorStop(0, `rgba(${rgb},1)`);
      gradient.addColorStop(0.35, `rgba(${rgb},0.45)`);
      gradient.addColorStop(1, `rgba(${rgb},0)`);
      context.fillStyle = gradient;
      context.fillRect(0, 0, 128, 128);
      texture = new THREE.CanvasTexture(canvas);
      this.glowTextures.set(kind, texture);
    }
    return texture;
  }

  /** Ruhezustand: Heizung glueht gleichmaessig, Ringe in fester Groesse, Boegen stehen. */
  private applyStaticPose(): void {
    this.spriteMaterials.get('heating')?.setValues({ opacity: 0.85 });
    this.solidMaterials.get('motion')?.setValues({ opacity: 1 });
    this.motionRings.forEach(ring => ring.scale.setScalar(1.6));
    this.runningArcs.forEach(arc => { arc.rotation.z = 0; });
  }

  private applyAnimatedPose(seconds: number): void {
    const pulse = 0.5 + 0.5 * Math.sin(seconds * 4);
    this.spriteMaterials.get('heating')?.setValues({ opacity: 0.5 + 0.5 * pulse });
    // Ring breitet sich vom Melder aus und verblasst, alle 0,8 s ein neuer.
    const wave = (seconds / 0.8) % 1;
    this.solidMaterials.get('motion')?.setValues({ opacity: 1 - wave });
    this.motionRings.forEach(ring => ring.scale.setScalar(1 + wave * 5));
    this.runningArcs.forEach(arc => { arc.rotation.z = seconds * 2.5; });
  }

  private applyFloorVisibility(): void {
    for (const floor of FLOORS) {
      this.layers.get(floor.id)!.group.visible = isFloorVisible(floor.id, this.selectedFloor);
    }
  }

}

/**
 * Erzeugt den Renderer einer Seite. Die Komponente holt ihn ueber dieses
 * Token, damit Tests ohne WebGL einen Fake einsetzen koennen.
 */
export const HOUSE_RENDERER_FACTORY = new InjectionToken<(host: HTMLElement) => HouseSceneRenderer>(
  'HOUSE_RENDERER_FACTORY',
  { providedIn: 'root', factory: () => (host: HTMLElement) => new HouseSceneRenderer(host) }
);
