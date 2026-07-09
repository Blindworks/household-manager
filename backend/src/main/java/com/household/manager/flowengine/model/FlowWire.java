package com.household.manager.flowengine.model;

/**
 * Verbindung von einem Ausgangsport einer Node zum Eingang einer anderen.
 */
public record FlowWire(Endpoint from, Target to) {

    public record Endpoint(String node, int port) {
    }

    public record Target(String node) {
    }
}
