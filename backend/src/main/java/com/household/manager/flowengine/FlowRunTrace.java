package com.household.manager.flowengine;

import com.household.manager.systemlog.SystemLogStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Node-Weg eines einzelnen {@link FlowEngine#runFrom}-Aufrufs. Lebt als ThreadLocal genau
 * fuer die Dauer dieses Aufrufs: asynchrone Fortsetzungen (emit, Delay) laufen ueber den
 * Executor auf anderen Threads und bekommen dort einen eigenen Trace mit derselben runId.
 */
public final class FlowRunTrace {

    /** Reserviertes Message-Feld, das die runId an Nachfolger und Fortsetzungen weiterreicht. */
    public static final String RUN_ID_KEY = "_runId";
    /** Reserviertes Message-Feld fuer Test-Injects. */
    public static final String INJECTED_KEY = "_injected";

    public static final String PORT_PREFIX = "port:";
    public static final String NO_TARGET = "no-target";
    public static final String STOPPED = "stopped";
    public static final String DROPPED = "dropped";
    public static final String DEFERRED = "deferred";
    public static final String FAILED = "failed";
    public static final String REPORTED_FAILURE = "reported-failure";

    /** Ein besuchter Node; {@code error} nur bei {@code failed}/{@code reported-failure}. */
    public record Step(String nodeId, String type, String label, String outcome, String error) {
    }

    private static final ThreadLocal<FlowRunTrace> CURRENT = new ThreadLocal<>();

    private final String runId;
    private final List<Step> steps = new ArrayList<>();
    /** Per reportFailure gemeldete Gruende je Node, bis die Engine den Step der Node abschliesst. */
    private final Map<String, String> reportedFailures = new HashMap<>();

    private FlowRunTrace(String runId) {
        this.runId = runId;
    }

    static FlowRunTrace start(String runId) {
        FlowRunTrace trace = new FlowRunTrace(runId);
        CURRENT.set(trace);
        return trace;
    }

    static void clear() {
        CURRENT.remove();
    }

    /** runId des auf diesem Thread laufenden Flow-Laufs. */
    public static Optional<String> currentRunId() {
        FlowRunTrace trace = CURRENT.get();
        return trace != null ? Optional.of(trace.runId) : Optional.empty();
    }

    /** Merkt einen gemeldeten Fehler fuer die gerade ausgefuehrte Node; ohne laufenden Trace ein No-op. */
    static void failCurrent(String nodeId, String reason) {
        FlowRunTrace trace = CURRENT.get();
        if (trace != null) {
            trace.reportedFailures.put(nodeId, reason);
        }
    }

    String runId() {
        return runId;
    }

    void add(Step step) {
        steps.add(step);
    }

    /** Liefert und entfernt den gemeldeten Fehler einer Node (null, wenn keiner gemeldet wurde). */
    String takeReportedFailure(String nodeId) {
        return reportedFailures.remove(nodeId);
    }

    List<Step> steps() {
        return List.copyOf(steps);
    }

    /** Laufstatus nach der Prioritaetsregel ERROR > STOPPED/RATE_LIMITED > WAITING > OK. */
    SystemLogStatus status() {
        SystemLogStatus result = SystemLogStatus.OK;
        for (Step step : steps) {
            SystemLogStatus candidate = statusOf(step.outcome());
            if (rank(candidate) > rank(result)) {
                result = candidate;
            }
        }
        return result;
    }

    /** Einzige Abbildung Step-Ergebnis auf Laufstatus. */
    public static SystemLogStatus statusOf(String outcome) {
        return switch (outcome) {
            case FAILED, REPORTED_FAILURE -> SystemLogStatus.ERROR;
            case STOPPED -> SystemLogStatus.STOPPED;
            case DROPPED -> SystemLogStatus.RATE_LIMITED;
            case DEFERRED -> SystemLogStatus.WAITING;
            default -> SystemLogStatus.OK;
        };
    }

    private static int rank(SystemLogStatus status) {
        return switch (status) {
            case ERROR -> 3;
            case STOPPED, RATE_LIMITED -> 2;
            case WAITING -> 1;
            default -> 0;
        };
    }
}
