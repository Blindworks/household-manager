import { FlowGraphMapper, CanvasNode, CanvasConnection } from './flow-graph.mapper';
import { FlowDefinition } from '../../models/flow.model';

describe('FlowGraphMapper', () => {
  const mapper = new FlowGraphMapper();

  const def: FlowDefinition = {
    nodes: [
      { id: 'n1', type: 'entity-state-trigger', name: 'T', position: { x: 80, y: 120 }, config: { operator: '<' } },
      { id: 'n2', type: 'alexa-announce', position: { x: 400, y: 120 }, config: {} }
    ],
    wires: [{ from: { node: 'n1', port: 0 }, to: { node: 'n2' } }]
  };

  it('maps definition to canvas nodes and connections', () => {
    const { nodes, connections } = mapper.toCanvas(def);
    expect(nodes.length).toBe(2);
    expect(nodes[0]).toEqual(jasmine.objectContaining({ id: 'n1', type: 'entity-state-trigger', x: 80, y: 120 }));
    expect(connections.length).toBe(1);
    expect(connections[0]).toEqual(jasmine.objectContaining({ fromNode: 'n1', fromPort: 0, toNode: 'n2' }));
  });

  it('round-trips definition -> canvas -> definition preserving nodes, wires, positions, config', () => {
    const { nodes, connections } = mapper.toCanvas(def);
    const back = mapper.toDefinition(nodes, connections);
    expect(back).toEqual(def);
  });

  it('preserves node position updates on the way back', () => {
    const { nodes, connections } = mapper.toCanvas(def);
    const moved: CanvasNode[] = nodes.map(n => n.id === 'n1' ? { ...n, x: 200, y: 300 } : n);
    const back = mapper.toDefinition(moved, connections);
    expect(back.nodes.find(n => n.id === 'n1')!.position).toEqual({ x: 200, y: 300 });
  });

  it('drops connections whose endpoints no longer exist', () => {
    const { nodes } = mapper.toCanvas(def);
    const orphan: CanvasConnection[] = [{ fromNode: 'ghost', fromPort: 0, toNode: 'n2' }];
    const back = mapper.toDefinition(nodes, orphan);
    expect(back.wires.length).toBe(0);
  });
});
