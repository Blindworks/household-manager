package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Traversiert deployte Flow-Graphen. Hop-Limit 100 pro Ausführung
 * (asynchrone Fortsetzungen via emit starten ein frisches Budget);
 * Fehler in einer Node brechen nur deren Zweig ab.
 */
@Component
@Slf4j
public class FlowEngine {

    static final int HOP_LIMIT = 100;

    private final FlowRegistry registry;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final DebugBuffer debugBuffer;

    public FlowEngine(FlowRegistry registry,
                      @Qualifier("flowEngineExecutor") Executor executor,
                      TaskScheduler scheduler,
                      DebugBuffer debugBuffer) {
        this.registry = registry;
        this.executor = executor;
        this.scheduler = scheduler;
        this.debugBuffer = debugBuffer;
    }

    /** Asynchrone Fortsetzung ab einem Node-Ausgang (von NodeContext.emit gerufen). */
    public void emitAsync(long flowId, String nodeId, int port, FlowMessage message) {
        executor.execute(() -> runFrom(flowId, nodeId, port, message));
    }

    /**
     * Traversiert ab dem Ausgangsport einer Node (typisch: gefeuerter Trigger).
     * Läuft im Aufrufer-Thread — Aufrufer legen dies auf den flowEngineExecutor.
     */
    public void runFrom(long flowId, String nodeId, int port, FlowMessage message) {
        FlowGraph graph = registry.graph(flowId).orElse(null);
        if (graph == null) {
            return;
        }
        record Work(String nodeId, FlowMessage message) {
        }
        Deque<Work> queue = new ArrayDeque<>();
        for (String target : graph.targetsOf(nodeId, port)) {
            queue.add(new Work(target, message));
        }

        int hops = 0;
        while (!queue.isEmpty()) {
            if (++hops > HOP_LIMIT) {
                log.warn("Flow {}: hop limit {} reached, aborting execution (cycle?)", flowId, HOP_LIMIT);
                return;
            }
            Work work = queue.poll();
            FlowNode node = graph.node(work.nodeId());
            if (node == null) {
                continue;
            }
            NodeHandler handler = registry.handler(node.type());
            NodeContext ctx = registry.context(flowId, node.id());
            if (handler == null || ctx == null) {
                continue;
            }
            try {
                NodeResult result = handler.handle(work.message(), node.config(), ctx);
                for (Map.Entry<Integer, List<FlowMessage>> output : result.outputs().entrySet()) {
                    for (FlowMessage outMessage : output.getValue()) {
                        for (String target : graph.targetsOf(node.id(), output.getKey())) {
                            queue.add(new Work(target, outMessage));
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Flow {} node {} failed, aborting branch: {}", flowId, node.id(), ex.getMessage());
                debugBuffer.add(flowId, node.id(), "ERROR: " + ex.getMessage(), work.message());
            }
        }
    }

    TaskScheduler scheduler() {
        return scheduler;
    }

    DebugBuffer debugBuffer() {
        return debugBuffer;
    }
}
