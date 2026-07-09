package com.household.manager.flowengine;

import java.util.List;
import java.util.Map;

/**
 * Ergebnis eines Node-Aufrufs: Messages je Ausgangsport.
 */
public record NodeResult(Map<Integer, List<FlowMessage>> outputs) {

    public static NodeResult none() {
        return new NodeResult(Map.of());
    }

    /** Eine Message auf Port 0. */
    public static NodeResult single(FlowMessage message) {
        return port(0, message);
    }

    public static NodeResult port(int port, FlowMessage message) {
        return new NodeResult(Map.of(port, List.of(message)));
    }
}
