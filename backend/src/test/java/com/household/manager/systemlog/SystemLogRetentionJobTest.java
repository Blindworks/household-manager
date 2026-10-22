package com.household.manager.systemlog;

import com.household.manager.repository.SystemLogEntryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class SystemLogRetentionJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T01:30:00Z"), ZoneId.of("Europe/Berlin"));

    private final SystemLogEntryRepository repository = mock(SystemLogEntryRepository.class);
    private final SystemLogRetentionJob job = new SystemLogRetentionJob(repository, CLOCK);

    @Test
    void purge_deletesOlderThan30Days() {
        job.purge();

        verify(repository).deleteOlderThan(LocalDateTime.now(CLOCK).minusDays(30));
    }

    @Test
    void purge_doesNotThrow_whenRepositoryFails() {
        when(repository.deleteOlderThan(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(job::purge);
    }
}
