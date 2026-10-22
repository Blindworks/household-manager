package com.household.manager.systemlog;

import com.household.manager.model.entity.SystemLogEntry;
import com.household.manager.repository.SystemLogEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SystemLogWriterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    private final SystemLogEntryRepository repository = mock(SystemLogEntryRepository.class);
    private SystemLogWriter writer;

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown();
        }
    }

    private SystemLogEvent event(String message) {
        return SystemLogEvent.builder().kind(SystemLogKind.FLOW_RUN).status(SystemLogStatus.OK)
                .source("flow:1").title("Titel").message(message).build();
    }

    @Test
    void write_persistsOnWriterThread() throws Exception {
        writer = new SystemLogWriter(CLOCK, repository, 10);

        writer.write(event("hallo"));
        writer.flushForTest();

        verify(repository).save(argThat((SystemLogEntry e) ->
                e.getKind() == SystemLogKind.FLOW_RUN && e.getRepeatCount() == 1
                        && "hallo".equals(e.getMessage())
                        && e.getOccurredAt().equals(LocalDateTime.now(CLOCK))));
    }

    @Test
    void write_neverThrows_whenRepositoryFails() throws Exception {
        when(repository.save(any())).thenThrow(new RuntimeException("db down")).thenAnswer(i -> i.getArgument(0));
        writer = new SystemLogWriter(CLOCK, repository, 10);

        writer.write(event("eins"));
        writer.write(event("zwei"));
        writer.flushForTest();

        verify(repository, times(2)).save(any());
    }

    @Test
    void write_doesNotBlock_whenQueueFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(repository.save(any())).thenAnswer(i -> {
            release.await();
            return i.getArgument(0);
        });
        writer = new SystemLogWriter(CLOCK, repository, 2);

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            for (int i = 0; i < 10; i++) {
                writer.write(event("m" + i));
            }
        });
        assertThat(writer.droppedCount()).isBetween(7L, 8L);

        release.countDown();
        writer.flushForTest();

        ArgumentCaptor<SystemLogEntry> saved = ArgumentCaptor.forClass(SystemLogEntry.class);
        verify(repository, atLeast(2)).save(saved.capture());
        // Der erste Eintrag kann die Zaehlung schon mitten im Verwerfen beanspruchen; die Summe aller Suffixe stimmt trotzdem.
        Pattern suffix = Pattern.compile("\\((\\d+) Einträge verworfen\\)$");
        long reported = saved.getAllValues().stream()
                .map(e -> suffix.matcher(e.getMessage()))
                .filter(Matcher::find)
                .mapToLong(m -> Long.parseLong(m.group(1)))
                .sum();
        assertThat(reported).isEqualTo(writer.droppedCount());
    }

    @Test
    void write_truncatesMessageTo2000() throws Exception {
        writer = new SystemLogWriter(CLOCK, repository, 10);

        writer.write(event("x".repeat(3000)));
        writer.flushForTest();

        verify(repository).save(argThat((SystemLogEntry e) -> e.getMessage().length() == 2000));
    }

    @Test
    void writeOrIncrement_incrementsWithinWindow() throws Exception {
        SystemLogEntry existing = new SystemLogEntry();
        existing.setRepeatCount(3);
        when(repository.findTopByKindAndSourceAndMessageAndOccurredAtAfterOrderByOccurredAtDesc(
                eq(SystemLogKind.FLOW_RUN), eq("flow:1"), eq("boom"),
                eq(LocalDateTime.now(CLOCK).minusMinutes(10)))).thenReturn(Optional.of(existing));
        writer = new SystemLogWriter(CLOCK, repository, 10);

        writer.writeOrIncrement(event("boom"), Duration.ofMinutes(10));
        writer.flushForTest();

        verify(repository).save(existing);
        assertThat(existing.getRepeatCount()).isEqualTo(4);
    }

    @Test
    void writeOrIncrement_createsNewRowAfterWindow() throws Exception {
        when(repository.findTopByKindAndSourceAndMessageAndOccurredAtAfterOrderByOccurredAtDesc(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        writer = new SystemLogWriter(CLOCK, repository, 10);

        writer.writeOrIncrement(event("boom"), Duration.ofMinutes(10));
        writer.flushForTest();

        verify(repository).save(argThat((SystemLogEntry e) -> e.getRepeatCount() == 1 && "boom".equals(e.getMessage())));
    }

    @Test
    void isWriterThread_isFalseOnCallerThread() {
        writer = new SystemLogWriter(CLOCK, repository, 10);

        assertThat(writer.isWriterThread()).isFalse();
    }
}
