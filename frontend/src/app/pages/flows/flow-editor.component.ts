import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, forkJoin, timer } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { FlowService } from '../../services/flow.service';
import { EntityStateService } from '../../services/entity-state.service';
import { SmartDeviceService } from '../../services/smart-device.service';
import { FlowDefinition, NodeType } from '../../models/flow.model';
import { EntityState } from '../../models/entity-state.model';
import { SmartDevice } from '../../models/smart-device.model';
import { NodeStatus, nodeStatus } from './node-status.util';
import { CanvasConnection, CanvasNode, FlowGraphMapper } from './flow-graph.mapper';
import { NodePaletteComponent } from './node-palette.component';
import { NodeCategory, nodeCategory } from './node-catalog';
import { NodeConfigPanelComponent } from './node-config-panel.component';
import { FlowCanvasComponent } from './flow-canvas.component';
import { DebugPanelComponent } from './debug-panel.component';

/** Der Flow-Editor: orchestriert Palette, Canvas, Konfig-Panel und Debug. */
@Component({
  selector: 'app-flow-editor',
  standalone: true,
  imports: [CommonModule, NodePaletteComponent, NodeConfigPanelComponent, FlowCanvasComponent, DebugPanelComponent],
  templateUrl: './flow-editor.component.html',
  styleUrl: './flow-editor.component.scss'
})
export class FlowEditorComponent implements OnInit {
  private readonly flowService = inject(FlowService);
  private readonly entityService = inject(EntityStateService);
  private readonly deviceService = inject(SmartDeviceService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly mapper = new FlowGraphMapper();

  /**
   * Abstand der Live-Status-Abfrage. Es gibt keinen Entitäten-Stream (SSE nur für
   * Zigbee); 10 s reichen, um „Flur an → aus" am Kästchen mitzuverfolgen, ohne den
   * Server mit einem offenen Editor zu beschäftigen.
   */
  static readonly STATUS_POLL_MS = 10_000;

  readonly flowId = Number(this.route.snapshot.paramMap.get('id'));
  readonly name = signal('');
  readonly description = signal('');
  readonly enabled = signal(false);
  readonly deployed = signal(false);
  readonly nodeTypes = signal<NodeType[]>([]);
  readonly canvasNodes = signal<CanvasNode[]>([]);
  readonly canvasConnections = signal<CanvasConnection[]>([]);
  readonly selectedNodeId = signal<string | null>(null);
  readonly dirty = signal(false);
  readonly deployErrors = signal<string[]>([]);
  readonly deployWarnings = signal<string[]>([]);
  readonly activeTab = signal<'config' | 'debug'>('config');

  /** Letzter erfolgreich geladener Stand; ein fehlgeschlagener Refresh behält ihn. */
  private readonly entities = signal<EntityState[]>([]);
  private readonly devices = signal<SmartDevice[]>([]);
  private readonly statusClock = signal(Date.now());

  /** Breite des rechten Panels (Konfig/Debug) in px — per Zieh-Griff verstellbar. */
  readonly sideWidth = signal(320);
  private static readonly MIN_SIDE_WIDTH = 220;
  private static readonly MAX_SIDE_WIDTH = 720;

  private savedSnapshot = '';
  private nodeCounter = 0;

  readonly selectedNode = computed(() => this.canvasNodes().find(n => n.id === this.selectedNodeId()) ?? undefined);
  readonly selectedNodeType = computed(() =>
    this.nodeTypes().find(t => t.type === this.selectedNode()?.type) ?? undefined);
  readonly portLabelsByType = computed<Record<string, string[]>>(() =>
    Object.fromEntries(this.nodeTypes().map(t => [t.type, t.portLabels])));
  readonly categoryByType = computed<Record<string, NodeCategory>>(() =>
    Object.fromEntries(this.nodeTypes().map(t => [t.type, nodeCategory(t.type, t.trigger)])));

  /** Live-Status je Kästchen (nur Nodes mit Entitäts- oder Gerätebezug haben einen Eintrag). */
  readonly statusByNodeId = computed<Record<string, NodeStatus>>(() => {
    const sources = { entities: this.entities(), devices: this.devices() };
    const now = this.statusClock();
    const result: Record<string, NodeStatus> = {};
    for (const node of this.canvasNodes()) {
      const status = nodeStatus(node, sources, now);
      if (status) {
        result[node.id] = status;
      }
    }
    return result;
  });

  ngOnInit(): void {
    forkJoin({ flow: this.flowService.getFlow(this.flowId), types: this.flowService.getNodeTypes() })
      .subscribe(({ flow, types }) => {
        this.name.set(flow.name);
        this.description.set(flow.description ?? '');
        this.enabled.set(flow.enabled);
        this.deployed.set(flow.deployed);
        this.nodeTypes.set(types);
        const def: FlowDefinition = flow.draftDefinition
          ? JSON.parse(flow.draftDefinition) : { nodes: [], wires: [] };
        const { nodes, connections } = this.mapper.toCanvas(def);
        this.canvasNodes.set(nodes);
        this.canvasConnections.set(connections);
        this.savedSnapshot = this.serialize();
        this.dirty.set(false);
      });
    this.startStatusPolling();
  }

  /**
   * Zieht Entitäten und Geräte sofort und dann alle {@link STATUS_POLL_MS} nach. Ein
   * Fehlschlag behält den letzten Stand und beendet den Poller nicht — sonst stünde
   * nach einem Backend-Schluckauf bis zum Neuladen ein eingefrorener Status da.
   */
  private startStatusPolling(): void {
    timer(0, FlowEditorComponent.STATUS_POLL_MS).pipe(
      switchMap(() => forkJoin({
        entities: this.entityService.getEntities(),
        devices: this.deviceService.getAllDevices()
      }).pipe(catchError(() => EMPTY))),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(({ entities, devices }) => {
      this.entities.set(entities);
      this.devices.set(devices);
      this.statusClock.set(Date.now());
    });
  }

  private serialize(): string {
    return JSON.stringify(this.mapper.toDefinition(this.canvasNodes(), this.canvasConnections()));
  }

  private markDirty(): void {
    this.dirty.set(this.serialize() !== this.savedSnapshot);
  }

  addNode(type: string): void {
    const id = `${type}-${Date.now()}-${this.nodeCounter++}`;
    this.canvasNodes.update(ns => [...ns, { id, type, x: 120, y: 80, config: {} }]);
    this.markDirty();
  }

  onNodeMoved(e: { id: string; x: number; y: number }): void {
    this.canvasNodes.update(ns => ns.map(n => n.id === e.id ? { ...n, x: e.x, y: e.y } : n));
    this.markDirty();
  }

  onConnectionCreated(c: CanvasConnection): void {
    this.canvasConnections.update(cs => [...cs, c]);
    this.markDirty();
  }

  onConnectionDeleted(c: CanvasConnection): void {
    this.canvasConnections.update(cs => cs.filter(x =>
      !(x.fromNode === c.fromNode && x.fromPort === c.fromPort && x.toNode === c.toNode)));
    this.markDirty();
  }

  onNodeSelected(id: string): void {
    this.selectedNodeId.set(id);
    this.activeTab.set('config');
  }

  onNodeDeleted(id: string): void {
    this.canvasNodes.update(ns => ns.filter(n => n.id !== id));
    this.canvasConnections.update(cs => cs.filter(c => c.fromNode !== id && c.toNode !== id));
    if (this.selectedNodeId() === id) { this.selectedNodeId.set(null); }
    this.markDirty();
  }

  onConfigChange(config: Record<string, unknown>): void {
    const id = this.selectedNodeId();
    if (!id) { return; }
    this.canvasNodes.update(ns => ns.map(n => n.id === id ? { ...n, config } : n));
    this.markDirty();
  }

  save(): void {
    const draft = this.serialize();
    this.flowService.saveDraft(this.flowId, this.name(), this.description(), draft).subscribe({
      next: () => {
        this.savedSnapshot = draft;
        this.dirty.set(false);
      },
      error: () => this.deployErrors.set(['Speichern fehlgeschlagen.'])
    });
  }

  deploy(): void {
    this.deployErrors.set([]);
    this.deployWarnings.set([]);
    const draft = this.serialize();
    this.flowService.saveDraft(this.flowId, this.name(), this.description(), draft).subscribe({
      next: () => {
        this.savedSnapshot = draft;
        this.dirty.set(false);
        this.flowService.deploy(this.flowId).subscribe(result => {
          this.deployErrors.set(result.errors);
          this.deployWarnings.set(result.warnings);
          if (result.errors.length === 0) { this.deployed.set(true); }
        });
      },
      error: () => this.deployErrors.set(['Speichern fehlgeschlagen.'])
    });
  }

  toggleEnabled(): void {
    this.flowService.setEnabled(this.flowId, !this.enabled()).subscribe({
      next: () => this.enabled.set(!this.enabled()),
      error: () => this.deployErrors.set(['Aktivierung/Deaktivierung fehlgeschlagen.'])
    });
  }

  testTrigger(nodeId: string): void {
    this.flowService.inject(this.flowId, nodeId, {}).subscribe();
  }

  /** Beschränkt die Panel-Breite auf den erlaubten Bereich. */
  clampSideWidth(width: number): number {
    return Math.max(FlowEditorComponent.MIN_SIDE_WIDTH,
      Math.min(FlowEditorComponent.MAX_SIDE_WIDTH, width));
  }

  /** Startet das Ziehen des Griffs zwischen Canvas und rechtem Panel. */
  startResize(event: PointerEvent): void {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = this.sideWidth();
    // Das Panel liegt rechts: Griff nach links ziehen (clientX sinkt) → breiter.
    const onMove = (e: PointerEvent) => this.sideWidth.set(this.clampSideWidth(startWidth + (startX - e.clientX)));
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  }
}
