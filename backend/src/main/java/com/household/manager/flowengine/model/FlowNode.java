package com.household.manager.flowengine.model;

/**
 * Eine Node im Flow-Graphen. position gehört dem Canvas-Editor; die Engine ignoriert sie.
 */
public record FlowNode(String id, String type, String name, Position position, NodeConfig config) {

    public record Position(double x, double y) {
    }
}
