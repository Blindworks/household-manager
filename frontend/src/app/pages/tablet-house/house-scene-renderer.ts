import { InjectionToken } from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { FLOORS, FloorId, OUTDOOR_AREAS } from '../../shared/house/house-plan';
import {
  CameraView, Vec3, cameraForOverview, floorBase, floorOpacity, insideCutout, roomBoxes,
  toPlanX, toPlanY, toWorldX, toWorldZ
} from '../../shared/house/house-geometry.util';

/**
 * Three.js-Szene der 3D-Hausansicht. Einzige Datei, die `three` importiert -
 * sie haengt nur am Lazy-Chunk der Route `tablet/house`.
 *
 * Gezeichnet wird nur bei Bedarf (Steuerung, Kamerafahrt, Etagenwechsel,
 * Groessenaenderung, requestRender) - kein Dauer-Loop, damit das Wandtablet
 * im Ruhezustand nichts rechnet.
 */
export interface ScreenPoint { x: number; y: number; visible: boolean }

const BACKGROUND = 0x05070f;
const NEON = 0x5b8cff;
const OUTDOOR = 0x3fbf7f;
const FILL_OPACITY = 0.08;
const GLASS_OPACITY = 0.15;
const DEFAULT_FLY_MS = 700;
const MIN_DISTANCE_M = 3;
const MAX_DISTANCE_M = 30;
/** Knapp ueber dem Boden, damit die Bodenplatte nicht mit den Kanten flimmert. */
const PLATE_LIFT_M = 0.01;

interface FloorLayer {
  group: THREE.Group;
  plates: THREE.Mesh[];
  edges: THREE.LineSegments[];
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
  private readonly glassEdgeMaterial = new THREE.LineBasicMaterial({
    color: NEON, transparent: true, opacity: GLASS_OPACITY, depthWrite: false
  });
  private readonly outdoorMaterial = new THREE.LineDashedMaterial({ color: OUTDOOR, dashSize: 0.3, gapSize: 0.2 });

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

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.cancelFlight();
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
    [this.fillMaterial, this.solidEdgeMaterial, this.glassEdgeMaterial, this.outdoorMaterial]
      .forEach(material => material.dispose());
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
      this.layers.set(floor.id, { group, plates: [], edges: [] });
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
      layer.edges.push(edges);
      layer.group.add(edges);
    }
  }

  private buildOutdoorAreas(): void {
    for (const area of OUTDOOR_AREAS) {
      const { x1, y1, x2, y2 } = area.rect;
      const corners = [[x1, y1], [x2, y1], [x2, y2], [x1, y2], [x1, y1]]
        .map(([x, y]) => new THREE.Vector3(toWorldX(x), PLATE_LIFT_M, toWorldZ(y)));
      const outline = new THREE.Line(new THREE.BufferGeometry().setFromPoints(corners), this.outdoorMaterial);
      outline.computeLineDistances();
      this.scene.add(outline);
    }
  }

  private applyFloorVisibility(): void {
    for (const floor of FLOORS) {
      const layer = this.layers.get(floor.id)!;
      const opacity = floorOpacity(floor.id, this.selectedFloor);
      layer.group.visible = opacity !== 'hidden';
      layer.plates.forEach(plate => (plate.visible = opacity === 'solid'));
      layer.edges.forEach(edges => {
        edges.material = opacity === 'solid' ? this.solidEdgeMaterial : this.glassEdgeMaterial;
      });
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
