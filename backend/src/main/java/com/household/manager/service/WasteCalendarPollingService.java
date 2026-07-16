package com.household.manager.service;

import com.household.manager.dto.WastePollingStatusResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.repository.WasteCollectionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spiegelt den ICS-Kalender taeglich in die Tabelle {@code waste_collection_events}.
 *
 * <p>Resync-Strategie: Bei Erfolg wird das Zukunftsfenster (ab heute) geloescht und neu
 * geschrieben — das uebernimmt {@link WasteCollectionResyncService} in einer eigenen
 * Transaktion. Verschobene und abgesagte Termine bilden sich dadurch korrekt ab, ohne sich
 * auf instabile ICS-UIDs zu stuetzen. Bei jedem Fehler bleibt die Tabelle unangetastet, damit
 * ein Ausfall der Quelle nicht die Dashboard-Kachel leert.
 */
@Service
@Slf4j
public class WasteCalendarPollingService {

    private static final String SCHEDULE = "Taeglich";
    /** Wie weit in die Zukunft der Kalender gespiegelt wird. */
    private static final int SYNC_WINDOW_MONTHS = 12;

    private final WasteCalendarIcsClient icsClient;
    private final WasteCalendarIcsParser icsParser;
    private final WasteCollectionResyncService resyncService;
    private final WasteCollectionEventRepository repository;
    private final WasteCollectionSettingsService settingsService;
    private final EntityStateService entityStateService;
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public WasteCalendarPollingService(WasteCalendarIcsClient icsClient,
                                       WasteCalendarIcsParser icsParser,
                                       WasteCollectionResyncService resyncService,
                                       WasteCollectionEventRepository repository,
                                       WasteCollectionSettingsService settingsService,
                                       EntityStateService entityStateService,
                                       TaskScheduler taskScheduler,
                                       Clock clock) {
        this.icsClient = icsClient;
        this.icsParser = icsParser;
        this.resyncService = resyncService;
        this.repository = repository;
        this.settingsService = settingsService;
        this.entityStateService = entityStateService;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;
    /**
     * Verhindert, dass ein manueller Trigger (spaeterer "Jetzt abrufen"-Button) einen
     * laufenden Scheduler-Durchlauf ueberlappt: Beide landen auf demselben
     * {@code ThreadPoolTaskScheduler} (siehe SchedulingConfig) und wuerden sonst zwei
     * gleichzeitige Delete-dann-Insert-Resyncs ueber demselben Datumsfenster ausloesen.
     */
    private final AtomicBoolean pollInProgress = new AtomicBoolean(false);

    public WastePollingStatusResponse getStatus() {
        return WastePollingStatusResponse.builder()
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .knownEventCount(countKnown())
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::scheduledPoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${waste.polling.interval-ms:86400000}",
            initialDelayString = "${waste.polling.initial-delay-ms:30000}"
    )
    public void scheduledPoll() {
        if (!settingsService.isEnabled()) {
            log.debug("Muellabfuhr-Abruf uebersprungen: Feature ist deaktiviert");
            return;
        }
        String icsUrl = settingsService.getIcsUrl();
        if (icsUrl == null || icsUrl.isBlank()) {
            log.info("Muellabfuhr-Abruf uebersprungen: keine Kalender-URL hinterlegt");
            return;
        }
        if (!pollInProgress.compareAndSet(false, true)) {
            log.info("Muellabfuhr-Abruf laeuft bereits; dieser Lauf wird uebersprungen");
            return;
        }
        try {
            safePoll(icsUrl);
        } finally {
            pollInProgress.set(false);
        }
    }

    private void safePoll(String icsUrl) {
        try {
            lastPollTime = LocalDateTime.now(clock);
            LocalDate from = LocalDate.now(clock);
            LocalDate to = from.plusMonths(SYNC_WINDOW_MONTHS);

            String ics = icsClient.fetch(icsUrl);
            List<ParsedWasteEvent> parsed = icsParser.parse(ics, from, to);

            if (parsed.isEmpty()) {
                // Bewusst kein Resync: ein leeres Ergebnis ist mehrdeutig (echt leerer Kalender
                // oder stiller Fehler). Alte Termine stehen zu lassen ist das harmlosere Risiko.
                log.warn("Kalender lieferte keine Termine; bestehende Daten bleiben unveraendert");
                lastError = null;
                return;
            }

            resyncService.resync(from, parsed);
            reportNextCollection(parsed, from);
            lastError = null;
            log.info("Muellabfuhr-Kalender aktualisiert: {} Termine", parsed.size());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Muellabfuhr-Kalender konnte nicht abgerufen werden", ex);
        }
    }

    /**
     * Leitet den naechsten Termin aus {@code parsed} ab, statt die soeben von
     * {@link WasteCollectionResyncService} geschriebenen Zeilen erneut abzufragen: Eine
     * Re-Query wuere nicht nur eine unnoetige Zusatzabfrage, sondern koennte bei einem
     * interleavenden zweiten Resync eine Zeile aus einer anderen Sync-Generation als der
     * dieses Durchlaufs melden.
     */
    private void reportNextCollection(List<ParsedWasteEvent> parsed, LocalDate today) {
        try {
            ParsedWasteEvent next = parsed.stream()
                    .filter(event -> !event.date().isBefore(today))
                    .min(Comparator.comparing(ParsedWasteEvent::date)
                            .thenComparing(ParsedWasteEvent::label))
                    .orElse(null);

            String state = next == null ? "unknown" : next.date().toString();
            Map<String, Object> attributes = next == null ? Map.of() : Map.of(
                    "label", next.label(),
                    "daysUntil", ChronoUnit.DAYS.between(today, next.date()));

            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.WASTE,
                            "calendar", "next_collection"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.WASTE)
                    .sourceRef("calendar")
                    .friendlyName("Naechste Muellabfuhr")
                    .state(state)
                    .attributes(attributes)
                    .build());
        } catch (Exception ex) {
            // Die Entity-Schicht darf den Abruf nicht scheitern lassen (Muster wie beim Wetter).
            log.warn("Entity-State der Muellabfuhr konnte nicht gemeldet werden: {}", ex.getMessage());
        }
    }

    private long countKnown() {
        try {
            return repository.countByCollectionDateGreaterThanEqual(LocalDate.now(clock));
        } catch (Exception ex) {
            log.warn("Anzahl bekannter Termine nicht ermittelbar: {}", ex.getMessage());
            return 0L;
        }
    }
}
