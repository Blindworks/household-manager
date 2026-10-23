package com.household.manager.flowengine;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.systemlog.FlowPendingRunTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
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
    static final String HOP_LIMIT_REACHED = "Hop-Limit erreicht";

    private final FlowRegistry registry;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final DebugBuffer debugBuffer;
    private final FlowTriggerRecorder triggerRecorder;
    private final FlowRunRecorder runRecorder;
    private final FlowPendingRunTracker pendingRuns;

    public FlowEngine(FlowRegistry registry,
                      @Qualifier("flowEngineExecutor") Executor executor,
                      @Qualifier("flowTaskScheduler") TaskScheduler scheduler,
                      DebugBuffer debugBuffer,
                      FlowTriggerRecorder triggerRecorder,
                      FlowRunRecorder runRecorder,
                      FlowPendingRunTracker pendingRuns) {
        this.registry = registry;
        this.executor = executor;
        this.scheduler = scheduler;
        this.debugBuffer = debugBuffer;
        this.triggerRecorder = triggerRecorder;
        this.runRecorder = runRecorder;
        this.pendingRuns = pendingRuns;
    }

    /** Asynchrone Fortsetzung ab einem Node-Ausgang (von NodeContext.emit gerufen). */
    public void emitAsync(long flowId, String nodeId, int port, FlowMessage message) {
        executor.execute(() -> runFrom(flowId, nodeId, port, message));
    }

    /**
     * Traversiert ab dem Ausgangsport einer Node (typisch: gefeuerter Trigger).
     * Läuft im Aufrufer-Thread — Aufrufer legen dies auf den flowEngineExecutor.
     *
     * <p>Einziger Ausführungspfad jeder Node (siehe {@link #emitAsync}): jede
     * Fortsetzung — auch verzögerte via Delay-Node oder ein weiterer Trigger —
     * kommt als frischer {@code executor.execute}-Aufruf hier an. Deshalb setzt
     * genau diese Methode den Audit-Aktor {@code FLOW:<id>} für die Dauer des
     * Laufs, statt einzelne Node-Handler damit zu belasten. Aus demselben Grund
     * entsteht hier je Aufruf genau ein Systemprotokoll-Eintrag; Fortsetzungen
     * tragen über {@link FlowRunTrace#RUN_ID_KEY} die runId des ursprünglichen Laufs.
     */
    public void runFrom(long flowId, String nodeId, int port, FlowMessage message) {
        AuditActorContext.set(AuditActor.flow(flowId));
        try {
            FlowGraph graph = registry.graph(flowId).orElse(null);
            if (graph == null) {
                return;
            }
            FlowNode startNode = graph.node(nodeId);
            boolean continuation = !isTrigger(startNode);
            recordIfTrigger(flowId, startNode);
            Object givenRunId = message.get(FlowRunTrace.RUN_ID_KEY);
            String runId = givenRunId != null ? givenRunId.toString() : UUID.randomUUID().toString();
            if (givenRunId != null) {
                completePendingRun(runId);
            }
            FlowRunTrace trace = FlowRunTrace.start(runId);
            try {
                traverse(flowId, graph, startNode, nodeId, port, message.with(FlowRunTrace.RUN_ID_KEY, runId), trace);
                recordRun(flowId, trace, continuation, Boolean.TRUE.equals(message.get(FlowRunTrace.INJECTED_KEY)));
            } finally {
                trace.end();
            }
        } finally {
            AuditActorContext.clear();
        }
    }

    private record Work(String nodeId, FlowMessage message) {
    }

    private void traverse(long flowId, FlowGraph graph, FlowNode startNode, String nodeId, int port,
                          FlowMessage startMessage, FlowRunTrace trace) {
        Deque<Work> queue = new ArrayDeque<>();
        List<String> startTargets = graph.targetsOf(nodeId, port);
        for (String target : startTargets) {
            queue.add(new Work(target, startMessage));
        }
        if (startNode != null) {
            String outcome = startTargets.isEmpty() ? FlowRunTrace.NO_TARGET : FlowRunTrace.PORT_PREFIX + port;
            trace.add(step(startNode, outcome, null));
        }

        int hops = 0;
        while (!queue.isEmpty()) {
            if (++hops > HOP_LIMIT) {
                log.warn("Flow {}: hop limit {} reached, aborting execution (cycle?)", flowId, HOP_LIMIT);
                trace.add(hopLimitStep(graph, queue.peek().nodeId()));
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
                Integer firstPort = null;
                Integer forwardingPort = null;
                for (Map.Entry<Integer, List<FlowMessage>> output : new TreeMap<>(result.outputs()).entrySet()) {
                    List<String> targets = graph.targetsOf(node.id(), output.getKey());
                    if (firstPort == null) {
                        firstPort = output.getKey();
                    }
                    if (forwardingPort == null && !targets.isEmpty() && !output.getValue().isEmpty()) {
                        forwardingPort = output.getKey();
                    }
                    for (FlowMessage outMessage : output.getValue()) {
                        for (String target : targets) {
                            queue.add(new Work(target, outMessage));
                        }
                    }
                }
                String reported = trace.takeReportedFailure(node.id());
                trace.add(reported != null
                        ? step(node, FlowRunTrace.REPORTED_FAILURE, reported)
                        : step(node, outcome(handler, firstPort, forwardingPort), null));
            } catch (Exception ex) {
                trace.takeReportedFailure(node.id());
                log.warn("Flow {} node {} failed, aborting branch: {}", flowId, node.id(), ex.getMessage());
                debugBuffer.add(flowId, node.id(), "ERROR: " + ex.getMessage(), work.message());
                trace.add(step(node, FlowRunTrace.FAILED, ex.getMessage()));
            }
        }
    }

    /**
     * Ergebnis einer Node ohne Fehler. {@code firstPort}: kleinster Port mit Ausgabe (null =
     * keine Ausgabe), {@code forwardingPort}: kleinster Port, über den tatsächlich weitergereicht wurde.
     */
    private static String outcome(NodeHandler handler, Integer firstPort, Integer forwardingPort) {
        if (forwardingPort != null) {
            return FlowRunTrace.PORT_PREFIX + forwardingPort;
        }
        if (firstPort != null) {
            return handler instanceof ConditionNodeHandler ? FlowRunTrace.STOPPED : FlowRunTrace.NO_TARGET;
        }
        if (handler instanceof GateNodeHandler) {
            return FlowRunTrace.DROPPED;
        }
        if (handler instanceof DeferringNodeHandler) {
            return FlowRunTrace.DEFERRED;
        }
        return FlowRunTrace.NO_TARGET;
    }

    private static FlowRunTrace.Step step(FlowNode node, String outcome, String error) {
        return new FlowRunTrace.Step(node.id(), node.type(), node.name(), outcome, error);
    }

    private static FlowRunTrace.Step hopLimitStep(FlowGraph graph, String nextNodeId) {
        FlowNode next = graph.node(nextNodeId);
        return new FlowRunTrace.Step(nextNodeId, next != null ? next.type() : null, next != null ? next.name() : null,
                FlowRunTrace.FAILED, HOP_LIMIT_REACHED);
    }

    /** Der Recorder wirft per Vertrag nie; der Fang schützt den Lauf trotzdem gegen eine Regression dort. */
    private void recordRun(long flowId, FlowRunTrace trace, boolean continuation, boolean injected) {
        try {
            runRecorder.record(flowId, trace.runId(), trace.status(), trace.steps(), continuation, injected);
        } catch (Exception ex) {
            log.warn("Flow {}: Lauf nicht protokolliert: {}", flowId, ex.getMessage());
        }
    }

    /**
     * Eine mitgebrachte runId heißt: dieser Lauf hat gewartet (Delay-Fortsetzung oder abgelaufener
     * Verweildauer-Timer an der Trigger-Node) und wird jetzt fortgesetzt. Abgeschlossen wird VOR der
     * Traversierung, nicht danach: trifft derselbe Lauf erneut auf ein Delay, registriert er dieselbe
     * runId neu, und ein Abschluss am Ende löschte genau diese neue Registrierung.
     */
    private void completePendingRun(String runId) {
        try {
            pendingRuns.complete(runId);
        } catch (Exception ex) {
            log.warn("Wartender Lauf {} nicht abgeschlossen: {}", runId, ex.getMessage());
        }
    }

    private boolean isTrigger(FlowNode node) {
        return node != null && registry.handler(node.type()) instanceof TriggerNodeHandler;
    }

    /**
     * Nur der Start an einer Trigger-Node zählt als „ausgelöst“ (auch ein Test-Inject);
     * Fortsetzungen aus Delay- oder Timer-Nodes laufen ebenfalls hier durch und
     * dürfen den Zeitpunkt nicht nachträglich verschieben.
     */
    private void recordIfTrigger(long flowId, FlowNode startNode) {
        if (isTrigger(startNode)) {
            triggerRecorder.recordTriggered(flowId);
        }
    }

    TaskScheduler scheduler() {
        return scheduler;
    }

    DebugBuffer debugBuffer() {
        return debugBuffer;
    }
}
