import { Component, EventEmitter, Output, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FFlowModule, FCreateConnectionEvent, FMoveNodesEvent, FSelectionChangeEvent } from '@foblex/flow';
import { CanvasNode, CanvasConnection } from './flow-graph.mapper';

const OUT_CONNECTOR_MARKER = '::out::';
const IN_CONNECTOR_SUFFIX = '::in';

/**
 * Kapselt `@foblex/flow`: rendert Canvas-Nodes/-Verbindungen und übersetzt die
 * Lib-Events in einen lib-unabhängigen Output-Vertrag. Neben dem FlowGraphMapper
 * ist dies die einzige Stelle, die `@foblex/flow` kennt.
 */
@Component({
  selector: 'app-flow-canvas',
  standalone: true,
  imports: [CommonModule, FFlowModule],
  templateUrl: './flow-canvas.component.html',
  styleUrl: './flow-canvas.component.scss'
})
export class FlowCanvasComponent {
  readonly nodes = input<CanvasNode[]>([]);
  readonly connections = input<CanvasConnection[]>([]);
  /** Port-Labels je Node-Typ (aus node-types), für die Port-Beschriftung. */
  readonly portLabelsByType = input<Record<string, string[]>>({});

  @Output() nodeMoved = new EventEmitter<{ id: string; x: number; y: number }>();
  @Output() connectionCreated = new EventEmitter<CanvasConnection>();
  @Output() connectionDeleted = new EventEmitter<CanvasConnection>();
  @Output() nodeSelected = new EventEmitter<string>();
  @Output() nodeDeleted = new EventEmitter<string>();

  /** Fallback-Portbeschriftung, deckungsgleich mit dem Backend-Default (`NodeHandler.portLabels()`). */
  private static readonly DEFAULT_PORT_LABELS = ['Ausgang'];

  outPortLabels(type: string): string[] {
    const labels = this.portLabelsByType()[type];
    return labels && labels.length > 0 ? labels : FlowCanvasComponent.DEFAULT_PORT_LABELS;
  }

  inConnectorId(nodeId: string): string {
    return `${nodeId}${IN_CONNECTOR_SUFFIX}`;
  }

  outConnectorId(nodeId: string, port: number): string {
    return `${nodeId}${OUT_CONNECTOR_MARKER}${port}`;
  }

  connectionKey(connection: CanvasConnection): string {
    return `${connection.fromNode}${OUT_CONNECTOR_MARKER}${connection.fromPort}->${connection.toNode}`;
  }

  onCreateConnection(event: FCreateConnectionEvent): void {
    if (!event.targetId) {
      return;
    }
    const source = this.parseOutConnectorId(event.sourceId);
    const targetNodeId = this.parseInConnectorId(event.targetId);
    if (!source || !targetNodeId) {
      return;
    }
    this.connectionCreated.emit({ fromNode: source.nodeId, fromPort: source.port, toNode: targetNodeId });
  }

  onMoveNodes(event: FMoveNodesEvent): void {
    for (const node of event.nodes) {
      this.nodeMoved.emit({ id: node.id, x: node.position.x, y: node.position.y });
    }
  }

  onSelectionChange(event: FSelectionChangeEvent): void {
    if (event.nodeIds.length === 1) {
      this.nodeSelected.emit(event.nodeIds[0]);
    }
  }

  onDeleteNode(nodeId: string): void {
    this.nodeDeleted.emit(nodeId);
  }

  onDeleteConnection(connection: CanvasConnection): void {
    this.connectionDeleted.emit(connection);
  }

  private parseOutConnectorId(id: string): { nodeId: string; port: number } | null {
    const markerIndex = id.indexOf(OUT_CONNECTOR_MARKER);
    if (markerIndex === -1) {
      return null;
    }
    const port = Number(id.substring(markerIndex + OUT_CONNECTOR_MARKER.length));
    if (Number.isNaN(port)) {
      return null;
    }
    return { nodeId: id.substring(0, markerIndex), port };
  }

  private parseInConnectorId(id: string): string | null {
    return id.endsWith(IN_CONNECTOR_SUFFIX) ? id.substring(0, id.length - IN_CONNECTOR_SUFFIX.length) : null;
  }
}
