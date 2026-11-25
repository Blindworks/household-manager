package com.household.manager.blaulicht;

import com.household.manager.repository.BlaulichtEventRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** Nachts 03:25: Fälle nach 365 Tagen (Veröffentlichung) löschen. Wirft nie. */
@Component
@Slf4j
public class BlaulichtRetentionJob {

    static final int RETENTION_DAYS = 365;

    private final BlaulichtEventRepository repository;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;

    public BlaulichtRetentionJob(BlaulichtEventRepository repository, SystemJobMonitor jobMonitor, Clock clock) {
        this.repository = repository;
        this.jobMonitor = jobMonitor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "Europe/Berlin")
    public void run() {
        try {
            int deleted = repository.deletePublishedBefore(LocalDateTime.now(clock).minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("Blaulicht: {} alte Fälle gelöscht", deleted);
            }
        } catch (RuntimeException e) {
            jobMonitor.reportFailure("Aufräumen: " + e.getMessage());
            log.warn("Blaulicht-Aufräumen fehlgeschlagen", e);
        }
    }
}
