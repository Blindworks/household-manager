package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.StateComparator;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Trigger auf Entity-Zustandsänderungen. Edge-getriggert: feuert beim Übergang
 * IN den passenden Bereich (nicht bei jeder Änderung innerhalb). Mit forSeconds
 * startet stattdessen ein Timer; bei Ablauf wird der aktuelle Zustand erneut
 * geprüft; Verlassen des Bereichs storniert den Timer.
 */
@Component
@RequiredArgsConstructor
public class EntityStateTriggerHandler implements TriggerNodeHandler {

    static final String STATE_KEY_TIMER = "pendingTimer";
    private static final String OP_CHANGED = "changed";

    private final EntityStateService entityStateService;

    @Override
    public String type() {
        return "entity-state-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return config.string("entityId");
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").isEmpty()) {
            errors.add("entityId fehlt");
        }
        String operator = config.string("operator").orElse(null);
        if (operator == null) {
            errors.add("operator fehlt");
        } else if (!OP_CHANGED.equals(operator) && config.string("value").isEmpty()) {
            errors.add("value fehlt (nur bei operator 'changed' optional)");
        }
        return errors;
    }

    @Override
    public Runnable register(NodeConfig config, NodeContext ctx) {
        // Cleanup beim Undeploy/Re-Deploy: laufenden Verweildauer-Timer stornieren,
        // damit er nicht verwaist in einen neuen Graphen feuert.
        return () -> cancelTimer(ctx);
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "entityId", "Entity-ID, auf die gelauscht wird",
                "operator", "<, <=, >, >=, ==, != oder changed",
                "value", "Vergleichswert (entfällt bei changed)",
                "forSeconds", "optional: Bedingung muss so lange ununterbrochen gelten");
    }

    @Override
    public void onEntityEvent(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx) {
        String operator = config.string("operator").orElse(OP_CHANGED);

        if (OP_CHANGED.equals(operator)) {
            ctx.emit(0, toMessage(event, ctx));
            return;
        }

        String value = config.string("value").orElse(null);
        boolean nowMatches = StateComparator.matches(event.newState(), operator, value);
        boolean beforeMatched = StateComparator.matches(event.oldState(), operator, value);
        Integer forSeconds = config.integer("forSeconds").orElse(null);

        if (nowMatches && !beforeMatched) {
            if (forSeconds == null || forSeconds <= 0) {
                ctx.emit(0, toMessage(event, ctx));
            } else {
                startTimer(event, config, ctx, operator, value, forSeconds);
            }
        } else if (!nowMatches) {
            cancelTimer(ctx);
        }
    }

    private void startTimer(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx,
                            String operator, String value, int forSeconds) {
        synchronized (ctx.state()) {
            cancelTimerLocked(ctx);
            String entityId = config.string("entityId").orElseThrow();
            ScheduledFuture<?> future = ctx.scheduler().schedule(() -> {
                ctx.state().remove(STATE_KEY_TIMER);
                String currentState = entityStateService.getByEntityId(entityId)
                        .map(e -> e.getState()).orElse(null);
                if (StateComparator.matches(currentState, operator, value)) {
                    ctx.emit(0, toMessage(event, ctx).with("newState", currentState));
                }
            }, Instant.now().plusSeconds(forSeconds));
            ctx.state().put(STATE_KEY_TIMER, future);
        }
    }

    private void cancelTimer(NodeContext ctx) {
        synchronized (ctx.state()) {
            cancelTimerLocked(ctx);
        }
    }

    private void cancelTimerLocked(NodeContext ctx) {
        Object pending = ctx.state().remove(STATE_KEY_TIMER);
        if (pending instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private FlowMessage toMessage(EntityStateChangedEvent event, NodeContext ctx) {
        Map<String, Object> values = new HashMap<>();
        values.put("entityId", event.entityId());
        values.put("oldState", event.oldState());
        values.put("newState", event.newState());
        values.put("attributes", event.attributes());
        values.put("timestamp", event.timestamp());
        values.put("triggerNodeId", ctx.nodeId());
        return FlowMessage.of(values);
    }
}
