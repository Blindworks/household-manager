package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Reicht die Message nach konfigurierten Sekunden weiter (nicht-blockierend
 * über den TaskScheduler). Offene Delays werden bei Re-Deploy/Undeploy von
 * {@code FlowRegistry} storniert (siehe dort {@code cancelScheduledFutures}) —
 * dafür parkt diese Node ihre {@link ScheduledFuture}s im Node-State.
 */
@Component
public class DelayNodeHandler implements NodeHandler {

    static final String STATE_KEY_PENDING = "pendingDelays";

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
    @SuppressWarnings("unchecked")
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        int seconds = config.integer("seconds").orElse(0);
        Set<ScheduledFuture<?>> pending = (Set<ScheduledFuture<?>>) ctx.state()
                .computeIfAbsent(STATE_KEY_PENDING, k -> ConcurrentHashMap.newKeySet());
        // holder[0] wird im Lambda selbst referenziert, um sich beim Feuern
        // aus dem pending-Set zu entfernen (das Future kennt sich selbst erst
        // nach dem Aufruf von schedule()).
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        holder[0] = ctx.scheduler().schedule(() -> {
            pending.remove(holder[0]);
            ctx.emit(0, message);
        }, Instant.now().plusSeconds(seconds));
        pending.add(holder[0]);
        return NodeResult.none();
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(NodeFieldDescriptor.field("seconds", "Verzögerung (Sek.)", NodeFieldType.NUMBER, true));
    }
}
