package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowNode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hält die deployten Flows als In-Memory-Graphen inkl. per-Node-State,
 * NodeContexts, Trigger-Cleanups und Trigger-Index. Atomarer Swap pro Flow.
 */
@Component
@Slf4j
public class FlowRegistry {

    public record TriggerRef(long flowId, String nodeId) {
    }

    static class DeployedFlow {
        final FlowGraph graph;
        final Map<String, NodeContext> contexts = new HashMap<>();
        final List<Runnable> cleanups = new ArrayList<>();

        DeployedFlow(FlowGraph graph) {
            this.graph = graph;
        }
    }

    private final Map<String, NodeHandler> handlersByType = new HashMap<>();
    private final ConcurrentMap<Long, DeployedFlow> deployed = new ConcurrentHashMap<>();
    /** entityId -> Trigger, die darauf lauschen. Wird bei jedem Deploy/Undeploy neu aufgebaut. */
    private volatile Map<String, List<TriggerRef>> triggerIndex = Map.of();

    /** Zirkularität Engine<->Registry wird per Setter aufgelöst (Engine braucht Registry, Contexts brauchen Engine). */
    @Setter
    private FlowEngine engine;

    public FlowRegistry(List<NodeHandler> handlers) {
        for (NodeHandler handler : handlers) {
            handlersByType.put(handler.type(), handler);
        }
    }

    public NodeHandler handler(String type) {
        return handlersByType.get(type);
    }

    public java.util.Collection<NodeHandler> handlers() {
        return handlersByType.values();
    }

    public void deploy(long flowId, FlowDefinition definition) {
        DeployedFlow flow = new DeployedFlow(new FlowGraph(definition));
        for (FlowNode node : flow.graph.nodes()) {
            flow.contexts.put(node.id(), new EngineNodeContext(flowId, node.id()));
        }
        // Trigger registrieren; falls eine Registrierung wirft, die bereits
        // gesammelten Cleanups laufen lassen, damit nichts leakt (z. B. Cron-Job).
        try {
            for (FlowNode node : flow.graph.nodes()) {
                if (handler(node.type()) instanceof TriggerNodeHandler trigger) {
                    flow.cleanups.add(trigger.register(node.config(), flow.contexts.get(node.id())));
                }
            }
        } catch (RuntimeException ex) {
            runCleanups(flowId, flow.cleanups);
            throw ex;
        }
        DeployedFlow old = deployed.put(flowId, flow); // atomarer Swap
        rebuildTriggerIndex();
        if (old != null) {
            runCleanups(flowId, old.cleanups);
            cancelScheduledFutures(old);
        }
        log.info("Flow {} deployed ({} nodes)", flowId, flow.graph.nodes().size());
    }

    public void undeploy(long flowId) {
        DeployedFlow old = deployed.remove(flowId);
        if (old != null) {
            rebuildTriggerIndex();
            runCleanups(flowId, old.cleanups);
            cancelScheduledFutures(old);
            if (engine != null) {
                engine.debugBuffer().clearFlow(flowId);
            }
            log.info("Flow {} undeployed", flowId);
        }
    }

    private void runCleanups(long flowId, List<Runnable> cleanups) {
        cleanups.forEach(cleanup -> {
            try {
                cleanup.run();
            } catch (Exception ex) {
                log.warn("Flow {} cleanup failed: {}", flowId, ex.getMessage());
            }
        });
    }

    /** Storniert alle noch offenen ScheduledFutures, die Nodes im state() geparkt haben
     *  (Delay-Timer, Verweildauer-Timer). Ergänzt die register()-Cleanups generisch, damit
     *  die Spec-Zusage "offene Delays verfallen bei Re-Deploy" auch für Nodes gilt, die keinen
     *  eigenen register()-Cleanup schreiben. cancel(false) ist idempotent, doppeltes Stornieren
     *  (z. B. zusätzlich zum EntityStateTrigger-eigenen register()-Cleanup) ist harmlos. */
    private void cancelScheduledFutures(DeployedFlow flow) {
        for (NodeContext ctx : flow.contexts.values()) {
            for (Object value : ctx.state().values()) {
                cancelIfFuture(value);
                if (value instanceof java.util.Collection<?> collection) {
                    collection.forEach(this::cancelIfFuture);
                }
            }
        }
    }

    private void cancelIfFuture(Object value) {
        if (value instanceof java.util.concurrent.ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    public Optional<FlowGraph> graph(long flowId) {
        DeployedFlow flow = deployed.get(flowId);
        return flow != null ? Optional.of(flow.graph) : Optional.empty();
    }

    public NodeContext context(long flowId, String nodeId) {
        DeployedFlow flow = deployed.get(flowId);
        return flow != null ? flow.contexts.get(nodeId) : null;
    }

    public List<TriggerRef> triggersFor(String entityId) {
        return triggerIndex.getOrDefault(entityId, List.of());
    }

    private void rebuildTriggerIndex() {
        Map<String, List<TriggerRef>> index = new HashMap<>();
        deployed.forEach((flowId, flow) -> {
            for (FlowNode node : flow.graph.nodes()) {
                if (handler(node.type()) instanceof TriggerNodeHandler trigger) {
                    trigger.watchedEntityId(node.config()).ifPresent(entityId ->
                            index.computeIfAbsent(entityId, k -> new ArrayList<>())
                                    .add(new TriggerRef(flowId, node.id())));
                }
            }
        });
        index.replaceAll((k, v) -> List.copyOf(v));
        triggerIndex = Map.copyOf(index);
    }

    /** NodeContext-Implementierung; delegiert emit/debug an die Engine. */
    private class EngineNodeContext implements NodeContext {
        private final long flowId;
        private final String nodeId;
        private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();

        EngineNodeContext(long flowId, String nodeId) {
            this.flowId = flowId;
            this.nodeId = nodeId;
        }

        public long flowId() {
            return flowId;
        }

        public String nodeId() {
            return nodeId;
        }

        public ConcurrentMap<String, Object> state() {
            return state;
        }

        public void emit(int port, FlowMessage message) {
            engine.emitAsync(flowId, nodeId, port, message);
        }

        public org.springframework.scheduling.TaskScheduler scheduler() {
            return engine.scheduler();
        }

        public void debug(String label, FlowMessage message) {
            engine.debugBuffer().add(flowId, nodeId, label, message);
        }
    }
}
