package com.household.manager.systemlog;

import com.household.manager.repository.SystemLogEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** Loescht Protokollzeilen nach 30 Tagen. Der Bulk-Delete bringt seine eigene Transaktion mit. Wirft nie. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemLogRetentionJob {

    static final int RETENTION_DAYS = 30;

    private final SystemLogEntryRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        try {
            int deleted = repository.deleteOlderThan(LocalDateTime.now(clock).minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("Systemprotokoll-Retention: {} Zeilen geloescht", deleted);
            }
        } catch (Exception ex) {
            log.warn("Retention des Systemprotokolls fehlgeschlagen", ex);
        }
    }
}
