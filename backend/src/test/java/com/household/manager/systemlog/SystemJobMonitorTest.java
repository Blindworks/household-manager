package com.household.manager.systemlog;

import com.household.manager.model.entity.SystemJobStatus;
import com.household.manager.repository.SystemJobStatusRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SystemJobMonitorTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final String KEY = "NukiPollingService.poll";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-22T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final SystemJobStatusRepository repository = mock(SystemJobStatusRepository.class);
    private final SystemLogWriter writer = mock(SystemLogWriter.class);
    private final SystemJobMonitor monitor = new SystemJobMonitor(repository, writer, clock, Runnable::run);

    @Test
    void okToFailing_writesErrorOnce() {
        monitor.recordOutcome(KEY, JobState.OK, null);

        monitor.recordOutcome(KEY, JobState.FAILING, "Cloud weg");
        monitor.recordOutcome(KEY, JobState.FAILING, "Cloud weg");

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer, times(1)).write(captor.capture());
        SystemLogEvent event = captor.getValue();
        assertThat(event.kind()).isEqualTo(SystemLogKind.JOB);
        assertThat(event.status()).isEqualTo(SystemLogStatus.ERROR);
        assertThat(event.title()).isEqualTo(KEY + " gestört");
        assertThat(event.message()).isEqualTo("Cloud weg");
        assertThat(event.source()).isEqualTo(KEY);
        assertThat(view().state()).isEqualTo(JobState.FAILING);
        assertThat(view().failingSince()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0));
    }

    @Test
    void failingToOk_writesRecoveredWithDuration() {
        monitor.recordOutcome(KEY, JobState.FAILING, "Cloud weg");
        now.set(now.get().plus(Duration.ofMinutes(42)));

        monitor.recordOutcome(KEY, JobState.OK, null);

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer, times(2)).write(captor.capture());
        SystemLogEvent recovered = captor.getAllValues().get(1);
        assertThat(recovered.status()).isEqualTo(SystemLogStatus.RECOVERED);
        assertThat(recovered.kind()).isEqualTo(SystemLogKind.JOB);
        assertThat(recovered.message()).isEqualTo("gestört seit 22.10.2026 12:00 (42 min)");
        assertThat(view().state()).isEqualTo(JobState.OK);
        assertThat(view().failingSince()).isNull();
        assertThat(view().lastSuccessAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 42));
    }

    @Test
    void inactive_writesNothing_andStaysInactive() {
        monitor.recordOutcome(KEY, JobState.INACTIVE, "nicht konfiguriert");
        monitor.recordOutcome(KEY, JobState.INACTIVE, "nicht konfiguriert");

        verifyNoInteractions(writer);
        assertThat(view().state()).isEqualTo(JobState.INACTIVE);
    }

    @Test
    void inactiveToFailing_writesError() {
        monitor.recordOutcome(KEY, JobState.INACTIVE, "nicht konfiguriert");

        monitor.recordOutcome(KEY, JobState.FAILING, "kaputt");

        ArgumentCaptor<SystemLogEvent> captor = ArgumentCaptor.forClass(SystemLogEvent.class);
        verify(writer).write(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(SystemLogStatus.ERROR);
    }

    @Test
    void failingToInactive_writesNothing() {
        monitor.recordOutcome(KEY, JobState.FAILING, "kaputt");
        clearInvocations(writer);

        monitor.recordOutcome(KEY, JobState.INACTIVE, "abgemeldet");

        verifyNoInteractions(writer);
        assertThat(view().failingSince()).isNull();
    }

    @Test
    void reportFailure_withoutRunningJob_isNoop() {
        assertDoesNotThrow(() -> monitor.reportFailure("egal"));
        assertDoesNotThrow(() -> monitor.reportInactive("egal"));

        verifyNoInteractions(writer);
        assertThat(monitor.statuses()).isEmpty();
    }

    @Test
    void repositoryFailure_neverThrows() {
        when(repository.findAll()).thenThrow(new RuntimeException("db down"));
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> monitor.recordOutcome(KEY, JobState.FAILING, "kaputt"));
        assertDoesNotThrow(monitor::statuses);
        assertThat(view().state()).isEqualTo(JobState.FAILING);
    }

    @Test
    void persistedBoard_isLoaded_soKnownFailureIsNotReportedAgain() {
        SystemJobStatus stored = new SystemJobStatus();
        stored.setJobKey(KEY);
        stored.setState("FAILING");
        stored.setFailingSince(LocalDateTime.of(2026, 10, 22, 11, 0));
        when(repository.findAll()).thenReturn(List.of(stored));

        monitor.recordOutcome(KEY, JobState.FAILING, "immer noch kaputt");

        verifyNoInteractions(writer);
        assertThat(view().failingSince()).isEqualTo(LocalDateTime.of(2026, 10, 22, 11, 0));
    }

    @Test
    void upsert_onlyOnStateChange_orWhenLastRunOlderThanFiveMinutes() {
        monitor.recordOutcome(KEY, JobState.OK, null);
        now.set(now.get().plus(Duration.ofMinutes(1)));
        monitor.recordOutcome(KEY, JobState.OK, null);
        verify(repository, times(1)).save(any());

        now.set(now.get().plus(Duration.ofMinutes(5)));
        monitor.recordOutcome(KEY, JobState.OK, null);
        verify(repository, times(2)).save(any());

        monitor.recordOutcome(KEY, JobState.FAILING, "kaputt");
        ArgumentCaptor<SystemJobStatus> captor = ArgumentCaptor.forClass(SystemJobStatus.class);
        verify(repository, times(3)).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo("FAILING");
        assertThat(captor.getValue().getLastMessage()).isEqualTo("kaputt");
    }

    @Test
    void sideEffects_runOnExecutor_inDecisionOrder() {
        List<Runnable> queued = new ArrayList<>();
        SystemJobMonitor queuedMonitor = new SystemJobMonitor(repository, writer, clock, queued::add);

        queuedMonitor.recordOutcome(KEY, JobState.FAILING, "kaputt");
        now.set(now.get().plus(Duration.ofMinutes(3)));
        queuedMonitor.recordOutcome(KEY, JobState.OK, null);

        verifyNoInteractions(writer, repository);
        queued.forEach(Runnable::run);
        ArgumentCaptor<SystemLogEvent> events = ArgumentCaptor.forClass(SystemLogEvent.class);
        ArgumentCaptor<SystemJobStatus> rows = ArgumentCaptor.forClass(SystemJobStatus.class);
        InOrder order = inOrder(repository, writer);
        order.verify(repository).findAll();
        order.verify(writer).write(events.capture());
        order.verify(repository).save(rows.capture());
        order.verify(writer).write(events.capture());
        order.verify(repository).save(rows.capture());
        assertThat(events.getAllValues()).extracting(SystemLogEvent::status)
                .containsExactly(SystemLogStatus.ERROR, SystemLogStatus.RECOVERED);
        assertThat(rows.getAllValues()).extracting(SystemJobStatus::getState).containsExactly("FAILING", "OK");
    }

    @Test
    void load_neverBlocksDecision_andOnlyFillsKeysNotSeenInMemory() {
        SystemJobStatus storedSameKey = new SystemJobStatus();
        storedSameKey.setJobKey(KEY);
        storedSameKey.setState("OK");
        SystemJobStatus storedOther = new SystemJobStatus();
        storedOther.setJobKey("Other.job");
        storedOther.setState("INACTIVE");
        when(repository.findAll()).thenReturn(List.of(storedSameKey, storedOther));
        List<Runnable> queued = new ArrayList<>();
        SystemJobMonitor queuedMonitor = new SystemJobMonitor(repository, writer, clock, queued::add);

        queuedMonitor.recordOutcome(KEY, JobState.FAILING, "kaputt");
        queued.forEach(Runnable::run);

        assertThat(queuedMonitor.statuses()).extracting(SystemJobMonitor.JobStatusView::jobKey,
                SystemJobMonitor.JobStatusView::state)
                .containsExactly(tuple(KEY, JobState.FAILING), tuple("Other.job", JobState.INACTIVE));
    }

    private SystemJobMonitor.JobStatusView view() {
        return monitor.statuses().stream().filter(v -> v.jobKey().equals(KEY)).findFirst().orElseThrow();
    }
}
