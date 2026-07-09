package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.flowengine.model.FlowNode;
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
        for (FlowRegistry.TriggerRef ref : registry.triggersFor(event.entityId())) {
            executor.execute(() -> {
                try {
                    FlowGraph graph = registry.graph(ref.flowId()).orElse(null);
                    NodeContext ctx = registry.context(ref.flowId(), ref.nodeId());
                    if (graph == null || ctx == null) {
                        return;
                    }
                    FlowNode node = graph.node(ref.nodeId());
                    if (registry.handler(node.type()) instanceof TriggerNodeHandler trigger) {
                        trigger.onEntityEvent(event, node.config(), ctx);
                    }
                } catch (Exception ex) {
                    log.warn("Flow {} trigger {} failed: {}", ref.flowId(), ref.nodeId(), ex.getMessage());
                }
            });
        }
    }
}
