package com.household.manager.agent;

import com.household.manager.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Löscht alte Laufprotokolle nachts um 03:50. Der Bulk-Delete bringt seine eigene Transaktion mit
 * (siehe Repository), deshalb hier bewusst kein {@code @Transactional}. Wirft nie.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunRetentionJob {

    private final AgentRunRepository repository;
    private final AgentProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 50 3 * * *")
    public void retain() {
        try {
            int deleted = repository.deleteByStartedAtBefore(
                    LocalDateTime.now(clock).minusDays(properties.getRunRetentionDays()));
            if (deleted > 0) {
                log.info("Hausverstand-Retention: {} Laufprotokolle geloescht", deleted);
            }
        } catch (Exception ex) {
            log.warn("Retention der Hausverstand-Laufprotokolle fehlgeschlagen", ex);
        }
    }
}
