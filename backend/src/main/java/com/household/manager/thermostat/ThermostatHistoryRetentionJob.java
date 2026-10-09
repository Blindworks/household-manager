package com.household.manager.thermostat;

import com.household.manager.repository.ThermostatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Loescht Verlaufspunkte nach 30 Tagen, nachts um 03:40. Keine Verdichtung: bei hoechstens einem
 * Punkt pro Minute und Thermostat bleibt die Tabelle klein. Der Bulk-Delete bringt seine eigene
 * Transaktion mit (siehe Repository), deshalb hier bewusst kein {@code @Transactional}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThermostatHistoryRetentionJob {

    static final int RETENTION_DAYS = 30;

    private final ThermostatHistoryRepository repository;
    private final Clock clock;

    @Scheduled(cron = "0 40 3 * * *")
    public void retain() {
        try {
            int deleted = repository.deleteByRecordedAtBefore(LocalDateTime.now(clock).minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("Thermostat-Retention: {} Verlaufspunkte geloescht", deleted);
            }
        } catch (Exception ex) {
            log.warn("Retention des Thermostat-Verlaufs fehlgeschlagen", ex);
        }
    }
}
