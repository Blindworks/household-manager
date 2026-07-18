package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Verbindet die Entity-Schicht mit der Flow-Engine: Entity-Events werden
 * asynchron (eigener Pool) an die passenden Trigger-Nodes verteilt.
 * Bewusst KEIN @TransactionalEventListener (verwirft Events ohne aktive TX —
 * der Polling-Normalfall; siehe Stufe-2-Spec).
 */
@Component
@Slf4j
public class FlowEngineListener {

    private final FlowRegistry registry;
    private final Executor executor;

    public FlowEngineListener(FlowRegistry registry, @Qualifier("flowEngineExecutor") Executor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    @EventListener
    public void onEntityStateChanged(EntityStateChangedEvent event) {
        dispatch(event.entityId(), (trigger, config, ctx) -> trigger.onEntityEvent(event, config, ctx));
    }

    @EventListener
    public void onEntityEventFired(EntityEventFired event) {
        dispatch(event.entityId(), (trigger, config, ctx) -> trigger.onEntityEventFired(event, config, ctx));
    }

    private void dispatch(String entityId, TriggerInvocation invocation) {
        for (FlowRegistry.TriggerRef ref : registry.triggersFor(entityId)) {
            executor.execute(() -> {
                try {
                    FlowGraph graph = registry.graph(ref.flowId()).orElse(null);
                    NodeContext ctx = registry.context(ref.flowId(), ref.nodeId());
                    if (graph == null || ctx == null) {
                        return;
                    }
                    FlowNode node = graph.node(ref.nodeId());
                    if (node == null) {
                        return;
                    }
                    if (registry.handler(node.type()) instanceof TriggerNodeHandler trigger) {
                        invocation.invoke(trigger, node.config(), ctx);
                    }
                } catch (Exception ex) {
                    log.warn("Flow {} trigger {} failed: {}", ref.flowId(), ref.nodeId(), ex.getMessage());
                }
            });
        }
    }

    @FunctionalInterface
    private interface TriggerInvocation {
        void invoke(TriggerNodeHandler trigger, NodeConfig config, NodeContext ctx);
    }
}
