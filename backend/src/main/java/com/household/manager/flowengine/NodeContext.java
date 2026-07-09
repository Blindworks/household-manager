package com.household.manager.flowengine;

import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.ConcurrentMap;

/**
 * Laufzeitkontext einer deployten Node: per-Node-Zustand, asynchrone
 * Fortsetzung und Debug-Ausgabe. Wird von der Engine bereitgestellt.
 */
public interface NodeContext {

    long flowId();

    String nodeId();

    /**
     * Per-Node-Zustand (Timer, lastFired, ...). Lebt bis zum Re-Deploy/Neustart.
     * ACHTUNG: Mehrere Executor-Threads können handle() derselben Node parallel
     * ausführen. Zusammengesetzte Updates (get-dann-put) müssen atomar über
     * compute/merge/putIfAbsent erfolgen, nicht als getrennte get/put-Schritte.
     */
    ConcurrentMap<String, Object> state();

    /** Setzt die Traversierung asynchron ab diesem Node-Ausgang fort (Delay, Trigger-Feuern). */
    void emit(int port, FlowMessage message);

    TaskScheduler scheduler();

    /** Schreibt in den Debug-Ringpuffer dieser Node (genutzt von debug-Node und Fehlerpfaden). */
    void debug(String label, FlowMessage message);
}
