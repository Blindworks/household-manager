import { InjectionToken } from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { FLOORS, FloorId, OUTDOOR_AREAS } from '../../shared/house/house-plan';
import {
  CameraView, Vec3, cameraForOverview, floorBase, insideCutout, isFloorVisible, roomBoxes,
  toPlanX, toPlanY, toWorldX, toWorldZ
} from '../../shared/house/house-geometry.util';
import { HouseEffect, HouseEffectKind } from '../../shared/house/house-effects.util';

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
}

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
    [this.fillMaterial, this.solidEdgeMaterial, this.outdoorMaterial,
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
      this.layers.set(floor.id, { group, plates: [] });
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
