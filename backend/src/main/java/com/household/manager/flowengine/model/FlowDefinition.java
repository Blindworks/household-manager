package com.household.manager.flowengine.model;

import java.util.List;

/** Kompletter Flow-Graph, wie er als JSON in der flows-Tabelle liegt. */
public record FlowDefinition(List<FlowNode> nodes, List<FlowWire> wires) {
}
