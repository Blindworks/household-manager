package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.flowengine.model.FlowWire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kompilierte, unveränderliche Sicht auf eine Flow-Definition für die Ausführung:
 * Nodes per id, Wires als Adjazenz (nodeId+port -> Ziel-Nodes).
 */
public class FlowGraph {

    private final Map<String, FlowNode> nodesById = new HashMap<>();
    private final Map<String, List<String>> targets = new HashMap<>();

    public FlowGraph(FlowDefinition definition) {
        for (FlowNode node : definition.nodes()) {
            nodesById.put(node.id(), node);
        }
        for (FlowWire wire : definition.wires()) {
            targets.computeIfAbsent(portKey(wire.from().node(), wire.from().port()), k -> new ArrayList<>())
                    .add(wire.to().node());
        }
    }

    public FlowNode node(String nodeId) {
        return nodesById.get(nodeId);
    }

    public java.util.Collection<FlowNode> nodes() {
        return nodesById.values();
    }

    public List<String> targetsOf(String nodeId, int port) {
        return targets.getOrDefault(portKey(nodeId, port), List.of());
    }

    private String portKey(String nodeId, int port) {
        return nodeId + "#" + port;
    }
}
