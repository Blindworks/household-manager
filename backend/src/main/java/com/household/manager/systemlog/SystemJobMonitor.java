package com.household.manager.systemlog;

import com.household.manager.model.entity.SystemJobStatus;
import com.household.manager.repository.SystemJobStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statustafel der Hintergrund-Jobs. Protokolliert nur Uebergaenge OK -> FAILING ({@code ERROR}) und
 * FAILING -> OK ({@code RECOVERED}); ein dauerhaft gestoerter Job erzeugt also genau einen Eintrag statt
 * einen je Lauf. INACTIVE (z. B. „nicht konfiguriert“) erzeugt nie einen Eintrag. Wirft nie.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemJobMonitor {

    /** Unveraenderte Tafel nicht oefter als so schreiben - sonst ein DB-Update je Lauf und Job. */
    private static final Duration PERSIST_INTERVAL = Duration.ofMinutes(5);
    private static final Duration LOAD_RETRY = Duration.ofMinutes(1);
    private static final int MAX_MESSAGE = 2000;
    private static final DateTimeFormatter SINCE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public record JobStatusView(String jobKey, JobState state, LocalDateTime lastRunAt,
                                LocalDateTime lastSuccessAt, LocalDateTime lastFailureAt,
                                LocalDateTime failingSince, String lastMessage) { }

    /** Laufender Job des Threads; {@code previous} stellt einen verschachtelten Aufruf wieder her. */
    static final class RunContext {
        private final String jobKey;
        private final RunContext previous;
        private String failureReason;
        private String inactiveReason;

        private RunContext(String jobKey, RunContext previous) {
            this.jobKey = jobKey;
            this.previous = previous;
        }
    }

    private record Entry(JobStatusView view, LocalDateTime persistedRunAt) { }

    private record Transition(Entry before, Entry after, boolean persist) { }

    private final SystemJobStatusRepository repository;
    private final SystemLogWriter writer;
    private final Clock clock;

    private final Map<String, Entry> board = new ConcurrentHashMap<>();
    private final ThreadLocal<RunContext> current = new ThreadLocal<>();
    private volatile boolean loaded;
    private volatile LocalDateTime lastLoadAttempt;

    /** Meldet fuer den laufenden Job einen Fehler, ohne dass er eine Exception werfen muss. */
    public void reportFailure(String reason) {
        RunContext run = current.get();
        if (run == null) {
            log.debug("Job-Monitor: reportFailure ohne laufenden Job ignoriert ({})", reason);
            return;
        }
        run.failureReason = reason;
    }

    /** Meldet fuer den laufenden Job „nichts zu tun“ (z. B. nicht konfiguriert). */
    public void reportInactive(String reason) {
        RunContext run = current.get();
        if (run == null) {
            log.debug("Job-Monitor: reportInactive ohne laufenden Job ignoriert ({})", reason);
            return;
        }
        run.inactiveReason = reason;
    }

    RunContext beginRun(String jobKey) {
        RunContext run = new RunContext(jobKey, current.get());
        current.set(run);
        return run;
    }

    void endRun(RunContext run, Throwable failure) {
        if (run == null) {
            return;
        }
        try {
            if (failure != null) {
                recordOutcome(run.jobKey, JobState.FAILING, describe(failure));
            } else if (run.failureReason != null) {
                recordOutcome(run.jobKey, JobState.FAILING, run.failureReason);
            } else if (run.inactiveReason != null) {
                recordOutcome(run.jobKey, JobState.INACTIVE, run.inactiveReason);
            } else {
                recordOutcome(run.jobKey, JobState.OK, null);
            }
        } finally {
            if (run.previous != null) {
                current.set(run.previous);
            } else {
                current.remove();
            }
        }
    }

    /** Ausgang eines Laufs; auch fuer Jobs ohne {@code @Scheduled} (Telegram). Wirft nie. */
    public void recordOutcome(String jobKey, JobState state, String message) {
        try {
            ensureLoaded();
            LocalDateTime now = LocalDateTime.now(clock);
            String text = truncate(message);
            Transition[] result = new Transition[1];
            board.compute(jobKey, (key, before) -> {
                Entry after = apply(key, before, state, text, now);
                result[0] = new Transition(before, after, shouldPersist(before, after, now));
                return result[0].persist() ? new Entry(after.view(), now) : after;
            });
            logTransition(result[0], now);
            if (result[0].persist()) {
                persist(result[0].after().view());
            }
        } catch (Exception ex) {
            log.debug("Job-Monitor: Ausgang von {} nicht erfasst", jobKey, ex);
        }
    }

    public List<JobStatusView> statuses() {
        try {
            ensureLoaded();
            return board.values().stream().map(Entry::view)
                    .sorted(Comparator.comparing(JobStatusView::jobKey)).toList();
        } catch (Exception ex) {
            log.debug("Job-Monitor: Statustafel nicht lesbar", ex);
            return List.of();
        }
    }

    private Entry apply(String key, Entry before, JobState state, String message, LocalDateTime now) {
        JobStatusView old = before != null ? before.view() : null;
        boolean wasFailing = old != null && old.state() == JobState.FAILING;
        LocalDateTime failingSince = state != JobState.FAILING ? null
                : wasFailing && old.failingSince() != null ? old.failingSince() : now;
        JobStatusView view = new JobStatusView(key, state, now,
                state == JobState.OK ? now : old != null ? old.lastSuccessAt() : null,
                state == JobState.FAILING ? now : old != null ? old.lastFailureAt() : null,
                failingSince, message);
        return new Entry(view, before != null ? before.persistedRunAt() : null);
    }

    private static boolean shouldPersist(Entry before, Entry after, LocalDateTime now) {
        if (before == null || before.view().state() != after.view().state()) {
            return true;
        }
        LocalDateTime persisted = before.persistedRunAt();
        return persisted == null || !persisted.plus(PERSIST_INTERVAL).isAfter(now);
    }

    private void logTransition(Transition transition, LocalDateTime now) {
        JobState from = transition.before() != null ? transition.before().view().state() : null;
        JobStatusView to = transition.after().view();
        if (to.state() == JobState.FAILING && from != JobState.FAILING) {
            writer.write(event(to.jobKey(), SystemLogStatus.ERROR, to.jobKey() + " gestört", to.lastMessage(), now));
        } else if (to.state() == JobState.OK && from == JobState.FAILING) {
            writer.write(event(to.jobKey(), SystemLogStatus.RECOVERED, to.jobKey() + " wieder in Ordnung",
                    recoveredMessage(transition.before().view().failingSince(), now), now));
        }
    }

    private static String recoveredMessage(LocalDateTime failingSince, LocalDateTime now) {
        if (failingSince == null) {
            return "wieder in Ordnung";
        }
        long minutes = Duration.between(failingSince, now).toMinutes();
        return "gestört seit " + SINCE_FORMAT.format(failingSince) + " (" + minutes + " min)";
    }

    private static SystemLogEvent event(String key, SystemLogStatus status, String title, String message,
                                        LocalDateTime now) {
        return SystemLogEvent.builder().occurredAt(now).kind(SystemLogKind.JOB).status(status)
                .source(key).title(title).message(message).build();
    }

    private void persist(JobStatusView view) {
        try {
            SystemJobStatus row = new SystemJobStatus();
            row.setJobKey(view.jobKey());
            row.setState(view.state().name());
            row.setLastRunAt(view.lastRunAt());
            row.setLastSuccessAt(view.lastSuccessAt());
            row.setLastFailureAt(view.lastFailureAt());
            row.setFailingSince(view.failingSince());
            row.setLastMessage(view.lastMessage());
            repository.save(row);
        } catch (Exception ex) {
            log.debug("Job-Monitor: Stand von {} nicht gespeichert", view.jobKey(), ex);
        }
    }

    /**
     * Laedt die gespeicherte Tafel beim ersten Gebrauch, damit ein nach dem Neustart weiter gestoerter
     * Job nicht erneut als „gestört“ protokolliert wird. Scheitert die DB, wird hoechstens minuetlich
     * erneut versucht; bereits im Speicher stehende Eintraege sind frischer und gewinnen.
     */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            LocalDateTime now = LocalDateTime.now(clock);
            if (loaded || (lastLoadAttempt != null && lastLoadAttempt.plus(LOAD_RETRY).isAfter(now))) {
                return;
            }
            lastLoadAttempt = now;
            try {
                for (SystemJobStatus row : repository.findAll()) {
                    toEntry(row).ifPresent(entry -> board.putIfAbsent(row.getJobKey(), entry));
                }
                loaded = true;
            } catch (Exception ex) {
                log.debug("Job-Monitor: gespeicherte Statustafel nicht lesbar", ex);
            }
        }
    }

    private static Optional<Entry> toEntry(SystemJobStatus row) {
        try {
            JobState state = JobState.valueOf(row.getState());
            JobStatusView view = new JobStatusView(row.getJobKey(), state, row.getLastRunAt(),
                    row.getLastSuccessAt(), row.getLastFailureAt(), row.getFailingSince(), row.getLastMessage());
            return Optional.of(new Entry(view, row.getLastRunAt()));
        } catch (Exception ex) {
            log.debug("Job-Monitor: unlesbare Zeile {} uebersprungen", row.getJobKey(), ex);
            return Optional.empty();
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private static String truncate(String value) {
        return value != null && value.length() > MAX_MESSAGE ? value.substring(0, MAX_MESSAGE) : value;
    }
}
