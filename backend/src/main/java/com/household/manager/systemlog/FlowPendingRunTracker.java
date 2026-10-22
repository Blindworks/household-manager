package com.household.manager.systemlog;

import com.household.manager.flowengine.FlowRunRecorder;
import com.household.manager.model.entity.SystemFlowPendingRun;
import com.household.manager.repository.SystemFlowPendingRunRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Merkt sich Flow-Laeufe, die an einem Delay- oder Verweildauer-Timer warten, in der DB. Werden sie durch
 * Re-Deploy, Undeploy oder einen Neustart nie fortgesetzt, entsteht je Lauf ein {@code ABORTED}-Eintrag.
 *
 * <p>Alle Zugriffe laufen auf einem eigenen Ein-Thread-Executor: register/complete kommen vom Flow-Executor
 * bzw. Timer-Thread und duerfen dort nicht auf die DB warten. Weil der Executor die Reihenfolge erhaelt, sieht
 * ein {@code abortAll} jede vorher eingereichte Registrierung; es wartet deshalb auf seine eigene Ausfuehrung.
 * Wirft nie.
 */
@Component
@Slf4j
public class FlowPendingRunTracker {

    public static final String REASON_REDEPLOY = "durch Re-Deploy abgebrochen";
    public static final String REASON_UNDEPLOY = "Flow deaktiviert oder gelöscht";
    public static final String REASON_RESTART = "Backend-Neustart";

    private static final long AWAIT_SECONDS = 5;
    private static final String THREAD_NAME = "flow-pending-runs";

    private final SystemFlowPendingRunRepository repository;
    private final SystemLogWriter writer;
    private final FlowRunRecorder runRecorder;
    private final Clock clock;
    private final ExecutorService executor;

    @Autowired
    public FlowPendingRunTracker(SystemFlowPendingRunRepository repository, SystemLogWriter writer,
                                 FlowRunRecorder runRecorder, Clock clock) {
        this(repository, writer, runRecorder, clock, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Fuer Tests: ein synchroner Executor macht die Ablaeufe deterministisch. */
    FlowPendingRunTracker(SystemFlowPendingRunRepository repository, SystemLogWriter writer,
                          FlowRunRecorder runRecorder, Clock clock, ExecutorService executor) {
        this.repository = repository;
        this.writer = writer;
        this.runRecorder = runRecorder;
        this.clock = clock;
        this.executor = executor;
    }

    public void register(long flowId, String nodeId, String runId) {
        submit("register " + runId, () -> {
            SystemFlowPendingRun row = new SystemFlowPendingRun();
            row.setRunId(runId);
            row.setFlowId(flowId);
            row.setNodeId(nodeId);
            row.setFlowName(runRecorder.flowName(flowId));
            row.setCreatedAt(LocalDateTime.now(clock));
            repository.save(row);
        });
    }

    /** Der Lauf wurde fortgesetzt oder regulaer verworfen: still loeschen, kein Protokolleintrag. */
    public void complete(String runId) {
        submit("complete " + runId, () -> repository.deleteByRunId(runId));
    }

    public void abortAll(long flowId, String reason) {
        submitAndWait("abortAll " + flowId, () -> {
            abort(repository.findByFlowId(flowId), reason);
            repository.deleteByFlowId(flowId);
        });
    }

    /** Vor dem Flow-Bootstrap: nach einem Neustart setzt kein wartender Lauf mehr fort. */
    public void abortAllOnStartup() {
        submitAndWait("abortAllOnStartup", () -> {
            abort(repository.findAll(), REASON_RESTART);
            repository.deleteAllInBatch();
        });
    }

    private void abort(List<SystemFlowPendingRun> rows, String reason) {
        for (SystemFlowPendingRun row : rows) {
            writer.write(SystemLogEvent.builder()
                    .occurredAt(LocalDateTime.now(clock))
                    .kind(SystemLogKind.FLOW_RUN)
                    .status(SystemLogStatus.ABORTED)
                    .source(String.valueOf(row.getFlowId()))
                    .title(titleOf(row))
                    .message(reason)
                    .flowId(row.getFlowId())
                    .runId(row.getRunId())
                    .build());
        }
    }

    /** Der gespeicherte Name ueberlebt das Loeschen des Flows; nur ohne ihn wird nachgeschlagen. */
    private String titleOf(SystemFlowPendingRun row) {
        String stored = row.getFlowName();
        return stored != null && !stored.isBlank() ? stored : runRecorder.flowName(row.getFlowId());
    }

    private void submit(String action, Runnable work) {
        try {
            executor.execute(() -> runSafely(action, work));
        } catch (Exception ex) {
            log.debug("Wartende Flow-Laeufe: {} nicht eingereiht: {}", action, ex.getMessage());
        }
    }

    private void submitAndWait(String action, Runnable work) {
        try {
            Future<?> future = executor.submit(() -> runSafely(action, work));
            future.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            log.warn("Wartende Flow-Laeufe: {} nach {} s noch nicht fertig", action, AWAIT_SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Wartende Flow-Laeufe: {} unterbrochen", action);
        } catch (Exception ex) {
            log.debug("Wartende Flow-Laeufe: {} nicht ausgefuehrt: {}", action, ex.getMessage());
        }
    }

    private static void runSafely(String action, Runnable work) {
        try {
            work.run();
        } catch (Exception ex) {
            log.warn("Wartende Flow-Laeufe: {} fehlgeschlagen: {}", action, ex.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
