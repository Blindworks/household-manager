package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reicht die Message nach konfigurierten Sekunden weiter (nicht-blockierend
 * über den TaskScheduler). Offene Delays verfallen bei Neustart/Re-Deploy.
 */
@Component
public class DelayNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        Integer seconds = config.integer("seconds").orElse(null);
        if (seconds == null || seconds <= 0) {
            return List.of("seconds fehlt oder ist nicht > 0");
        }
        return List.of();
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of("seconds", "Verzögerung in Sekunden");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        int seconds = config.integer("seconds").orElse(0);
        ctx.scheduler().schedule(() -> ctx.emit(0, message), Instant.now().plusSeconds(seconds));
        return NodeResult.none();
    }
}
