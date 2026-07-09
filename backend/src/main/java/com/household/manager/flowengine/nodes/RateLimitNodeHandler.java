package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Drossel: lässt höchstens eine Message pro Intervall durch, Überschuss wird
 * verworfen (gegen Ansage-Spam bei flatternden Werten). Zustand in-memory.
 */
@Component
public class RateLimitNodeHandler implements NodeHandler {

    static final String STATE_KEY_LAST_PASSED = "lastPassed";

    private final Clock clock;

    public RateLimitNodeHandler() {
        this(Clock.systemDefaultZone());
    }

    RateLimitNodeHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "rate-limit";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        Integer interval = config.integer("minIntervalSeconds").orElse(null);
        if (interval == null || interval <= 0) {
            return List.of("minIntervalSeconds fehlt oder ist nicht > 0");
        }
        return List.of();
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long intervalSeconds = config.integer("minIntervalSeconds").orElse(0);
        Instant now = clock.instant();
        synchronized (ctx.state()) {
            Instant lastPassed = (Instant) ctx.state().get(STATE_KEY_LAST_PASSED);
            if (lastPassed != null && lastPassed.plusSeconds(intervalSeconds).isAfter(now)) {
                return NodeResult.none();
            }
            ctx.state().put(STATE_KEY_LAST_PASSED, now);
        }
        return NodeResult.single(message);
    }
}
