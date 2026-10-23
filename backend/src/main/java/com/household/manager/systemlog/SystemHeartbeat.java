package com.household.manager.systemlog;

import com.household.manager.service.ApplicationSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Minütliches Lebenszeichen des Backends in {@code application_settings} (Kategorie SYSTEM_LOG).
 * Aus dem letzten Eintrag vor dem Start erkennt der {@link MissedTriggerDetector}, wie lange das
 * Backend nicht lief. Den liest er aus einem Schnappschuss, der beim Anlegen dieser Bean entsteht - vor
 * jedem Schlag, also unabhängig davon, ob der erste Schlag vor oder nach {@code ApplicationReadyEvent}
 * kommt. Beim Beenden schlägt es ein letztes Mal: sonst wäre der Wert bis zu einer Minute alt, und ein
 * minütlicher Cron meldete nach jedem Neustart einen verpassten Termin, der gar nicht verpasst war.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SystemHeartbeat {

    static final String CATEGORY = "SYSTEM_LOG";
    static final String KEY_LAST_ALIVE = "last_alive_at";

    private final ApplicationSettingsService settings;
    private final Clock clock;

    /** Lebenszeichen vor diesem Start; null, solange der Schnappschuss nicht genommen ist. */
    private volatile Optional<Instant> beforeStart;

    @PostConstruct
    void snapshotLastAlive() {
        beforeStart = lastAlive();
    }

    @PreDestroy
    void beatOnShutdown() {
        beat();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void beat() {
        try {
            settings.saveSetting(CATEGORY, KEY_LAST_ALIVE, Instant.now(clock).toString());
        } catch (Exception ex) {
            log.warn("Lebenszeichen konnte nicht gespeichert werden: {}", ex.getMessage());
        }
    }

    /** Lebenszeichen vor diesem Start (Schnappschuss, siehe Klassenkommentar). Wirft nie. */
    public Optional<Instant> lastAliveBeforeStart() {
        Optional<Instant> snapshot = beforeStart;
        return snapshot != null ? snapshot : lastAlive();
    }

    /** Letztes gespeichertes Lebenszeichen; unlesbar oder nie geschrieben ergibt leer. Wirft nie. */
    public Optional<Instant> lastAlive() {
        try {
            String raw = settings.getSettingsByCategory(CATEGORY).get(KEY_LAST_ALIVE);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Instant.parse(raw.trim()));
        } catch (Exception ex) {
            log.warn("Lebenszeichen nicht lesbar: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
