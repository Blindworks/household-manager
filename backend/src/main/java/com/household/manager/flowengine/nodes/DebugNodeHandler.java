package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Schreibt jede eingehende Message in den Debug-Ringpuffer (via NodeContext).
 * Kein Ausgang — reine Senke, Node-REDs wichtigstes Entwicklungswerkzeug.
 */
@Component
public class DebugNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "debug";
    }

    @Override
    public int outputPorts() {
        return 0;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        return List.of();
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of("label", "optionale Beschriftung des Eintrags");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        ctx.debug(config.string("label").orElse(null), message);
        return NodeResult.none();
    }
}
