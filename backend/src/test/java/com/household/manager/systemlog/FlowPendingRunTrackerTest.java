package com.household.manager.systemlog;

import com.household.manager.flowengine.FlowRunRecorder;
import com.household.manager.model.entity.SystemFlowPendingRun;
import com.household.manager.repository.SystemFlowPendingRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class FlowPendingRunTrackerTest {

    /** Fuehrt jede Aufgabe sofort im Aufrufer-Thread aus, damit die Tests deterministisch sind. */
    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;
        public void execute(Runnable command) {
            if (shutdown) {
                throw new java.util.concurrent.RejectedExecutionException("shut down");
            }
            command.run();
        }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private final SystemFlowPendingRunRepository repository = mock(SystemFlowPendingRunRepository.class);
    private final SystemLogWriter writer = mock(SystemLogWriter.class);
    private final FlowRunRecorder runRecorder = mock(FlowRunRecorder.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private final FlowPendingRunTracker tracker =
            new FlowPendingRunTracker(repository, writer, runRecorder, clock, new DirectExecutorService());

    @AfterEach
    void tearDown() {
        tracker.shutdown();
    }

    private static SystemFlowPendingRun row(String runId, long flowId, String flowName) {
        SystemFlowPendingRun row = new SystemFlowPendingRun();
        row.setRunId(runId);
        row.setFlowId(flowId);
        row.setNodeId("d");
        row.setFlowName(flowName);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 22, 11, 0));
        return row;
    }

    @Test
    void register_savesRowWithFlowNameAndNode() {
        when(runRecorder.flowName(5L)).thenReturn("Flurlicht");

        tracker.register(5L, "d", "run-1");

        ArgumentCaptor<SystemFlowPendingRun> saved = ArgumentCaptor.forClass(SystemFlowPendingRun.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRunId()).isEqualTo("run-1");
        assertThat(saved.getValue().getFlowId()).isEqualTo(5L);
        assertThat(saved.getValue().getNodeId()).isEqualTo("d");
        assertThat(saved.getValue().getFlowName()).isEqualTo("Flurlicht");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0));
    }

    @Test
    void abortAll_writesOneAbortedPerPendingRun_andDeletes() {
        when(repository.findByFlowId(5L)).thenReturn(List.of(row("run-1", 5L, "Flurlicht"), row("run-2", 5L, "Flurlicht")));

        tracker.abortAll(5L, FlowPendingRunTracker.REASON_REDEPLOY);

        ArgumentCaptor<SystemLogEvent> events = ArgumentCaptor.forClass(SystemLogEvent.class);
        InOrder order = inOrder(writer, repository);
        order.verify(writer, times(2)).write(events.capture());
        order.verify(repository).deleteByFlowId(5L);
        assertThat(events.getAllValues()).extracting(SystemLogEvent::runId).containsExactly("run-1", "run-2");
        SystemLogEvent first = events.getAllValues().get(0);
        assertThat(first.kind()).isEqualTo(SystemLogKind.FLOW_RUN);
        assertThat(first.status()).isEqualTo(SystemLogStatus.ABORTED);
        assertThat(first.flowId()).isEqualTo(5L);
        assertThat(first.title()).isEqualTo("Flurlicht");
        assertThat(first.source()).isEqualTo("5");
        assertThat(first.message()).isEqualTo("durch Re-Deploy abgebrochen");
        assertThat(first.occurredAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0));
    }

    @Test
    void abortAll_withoutPendingRuns_writesNothing() {
        when(repository.findByFlowId(5L)).thenReturn(List.of());

        tracker.abortAll(5L, FlowPendingRunTracker.REASON_UNDEPLOY);

        verifyNoInteractions(writer);
    }

    @Test
    void abortAll_rowWithoutStoredName_fallsBackToRecorderName() {
        when(repository.findByFlowId(5L)).thenReturn(List.of(row("run-1", 5L, null)));
        when(runRecorder.flowName(5L)).thenReturn("Flow 5");

        tracker.abortAll(5L, FlowPendingRunTracker.REASON_UNDEPLOY);

        ArgumentCaptor<SystemLogEvent> event = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).write(event.capture());
        assertThat(event.getValue().title()).isEqualTo("Flow 5");
        assertThat(event.getValue().message()).isEqualTo("Flow deaktiviert oder gelöscht");
    }

    @Test
    void complete_deletesWithoutLogEntry() {
        tracker.complete("run-1");

        verify(repository).deleteByRunId("run-1");
        verifyNoInteractions(writer);
    }

    @Test
    void abortAllOnStartup_usesNeustartReason() {
        when(repository.findAll()).thenReturn(List.of(row("run-1", 5L, "Flurlicht"), row("run-9", 9L, "Bad")));

        tracker.abortAllOnStartup();

        ArgumentCaptor<SystemLogEvent> events = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer, times(2)).write(events.capture());
        assertThat(events.getAllValues()).extracting(SystemLogEvent::message)
                .containsOnly("Backend-Neustart");
        assertThat(events.getAllValues()).extracting(SystemLogEvent::flowId).containsExactly(5L, 9L);
        assertThat(events.getAllValues()).extracting(SystemLogEvent::status).containsOnly(SystemLogStatus.ABORTED);
        verify(repository).deleteAllInBatch();
    }

    @Test
    void repositoryFailure_neverThrows() {
        when(runRecorder.flowName(anyLong())).thenReturn("Flow 5");
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));
        when(repository.deleteByRunId(any())).thenThrow(new IllegalStateException("db down"));
        when(repository.findByFlowId(anyLong())).thenThrow(new IllegalStateException("db down"));
        when(repository.findAll()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> {
            tracker.register(5L, "d", "run-1");
            tracker.complete("run-1");
            tracker.abortAll(5L, FlowPendingRunTracker.REASON_REDEPLOY);
            tracker.abortAllOnStartup();
        }).doesNotThrowAnyException();
    }

    @Test
    void abortAll_onRealExecutor_runsAfterPreviouslyQueuedRegistration() {
        FlowPendingRunTracker async = new FlowPendingRunTracker(repository, writer, runRecorder, clock,
                java.util.concurrent.Executors.newSingleThreadExecutor());
        try {
            when(repository.save(any())).thenAnswer(inv -> {
                Thread.sleep(100);
                return inv.getArgument(0);
            });
            when(repository.findByFlowId(5L)).thenReturn(List.of(row("run-1", 5L, "Flurlicht")));

            async.register(5L, "d", "run-1");
            async.abortAll(5L, FlowPendingRunTracker.REASON_REDEPLOY);

            // abortAll kehrt erst zurueck, wenn seine Arbeit (nach dem register) gelaufen ist
            InOrder order = inOrder(repository, writer);
            order.verify(repository).save(any());
            order.verify(repository).findByFlowId(5L);
            order.verify(writer).write(any());
            order.verify(repository).deleteByFlowId(5L);
        } finally {
            async.shutdown();
        }
    }

    @Test
    void afterShutdown_callsAreIgnoredWithoutThrowing() {
        tracker.shutdown();

        assertThatCode(() -> {
            tracker.register(5L, "d", "run-1");
            tracker.abortAll(5L, FlowPendingRunTracker.REASON_REDEPLOY);
        }).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }
}
