import { FlowDefinition, FlowNode } from '../../models/flow.model';

/** Lib-unabhängiges Zwischenmodell für den Canvas. */
export interface CanvasNode {
  id: string;
  type: string;
  name?: string;
  x: number;
  y: number;
  config: Record<string, unknown>;
}

export interface CanvasConnection {
  fromNode: string;
  fromPort: number;
  toNode: string;
}

/** Übersetzt bidirektional zwischen Backend-Flow-Format und Canvas-Zwischenmodell. */
export class FlowGraphMapper {
  toCanvas(def: FlowDefinition): { nodes: CanvasNode[]; connections: CanvasConnection[] } {
    const nodes: CanvasNode[] = def.nodes.map(n => {
      const node: CanvasNode = {
        id: n.id,
        type: n.type,
        x: n.position?.x ?? 0,
        y: n.position?.y ?? 0,
        config: { ...(n.config ?? {}) }
      };
      if (n.name !== undefined) {
        node.name = n.name;
      }
      return node;
    });
    const connections: CanvasConnection[] = def.wires.map(w => ({
      fromNode: w.from.node,
      fromPort: w.from.port,
      toNode: w.to.node
    }));
    return { nodes, connections };
  }

  toDefinition(nodes: CanvasNode[], connections: CanvasConnection[]): FlowDefinition {
    const ids = new Set(nodes.map(n => n.id));
    const outNodes: FlowNode[] = nodes.map(n => {
      const node: FlowNode = { id: n.id, type: n.type, position: { x: n.x, y: n.y }, config: { ...(n.config ?? {}) } };
      if (n.name !== undefined) {
        node.name = n.name;
      }
      return node;
    });
    const wires = connections
      .filter(c => ids.has(c.fromNode) && ids.has(c.toNode))
      .map(c => ({ from: { node: c.fromNode, port: c.fromPort }, to: { node: c.toNode } }));
    return { nodes: outNodes, wires };
  }
}
