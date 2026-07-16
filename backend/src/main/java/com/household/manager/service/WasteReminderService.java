package com.household.manager.service;

import com.household.manager.alexa.AlexaTtsMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * Loest am Vorabend die Alexa-Durchsage aus.
 *
 * <p>Geprueft wird minuetlich statt per Cron-Ausdruck, weil die Ansagezeit zur Laufzeit in den
 * Settings aenderbar ist und ein statisches {@code @Scheduled(cron=...)} sie nicht lesen kann.
 * Die Pruefung kostet einen Settings-Read und eine indizierte Datumsabfrage.
 */
@Service
@Slf4j
public class WasteReminderService {

    /**
     * Laenge des Ansage-Fensters ab der konfigurierten Uhrzeit. Ohne diese Obergrenze wuerde
     * ein Neustart spaet am Abend noch eine Durchsage ausloesen.
     */
    private static final Duration ANNOUNCE_WINDOW = Duration.ofMinutes(60);

    private final WasteCollectionService collectionService;
    private final WasteCollectionSettingsService settingsService;
    private final AlexaAnnouncementService announcementService;
    private final WasteAnnouncementTextBuilder textBuilder;
    private final Clock clock;

    public WasteReminderService(WasteCollectionService collectionService,
                                WasteCollectionSettingsService settingsService,
                                AlexaAnnouncementService announcementService,
                                WasteAnnouncementTextBuilder textBuilder,
                                Clock clock) {
        this.collectionService = collectionService;
        this.settingsService = settingsService;
        this.announcementService = announcementService;
        this.textBuilder = textBuilder;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${waste.reminder.check-interval-ms:60000}")
    public void checkAndAnnounce() {
        try {
            if (!shouldAnnounceNow()) {
                return;
            }
            List<String> labels = collectionService.getLabelsForTomorrow();
            if (labels.isEmpty()) {
                return;
            }
            List<String> serials = settingsService.getReminderAlexaSerials();

            announcementService.announce(
                    textBuilder.buildTomorrowText(labels), serials, AlexaTtsMode.ANNOUNCE);

            // Erst nach erfolgreicher Ansage merken: schlaegt sie fehl, versucht es der
            // naechste Lauf im Fenster erneut.
            settingsService.markAnnounced(collectionService.today());
            log.info("Muellabfuhr-Erinnerung angesagt: {}", labels);
        } catch (Exception ex) {
            log.error("Muellabfuhr-Erinnerung konnte nicht angesagt werden", ex);
        }
    }

    private boolean shouldAnnounceNow() {
        if (!settingsService.isEnabled() || !settingsService.isReminderEnabled()) {
            return false;
        }
        if (settingsService.getReminderAlexaSerials().isEmpty()) {
            return false;
        }
        if (!isWithinAnnounceWindow()) {
            return false;
        }
        return !settingsService.wasAnnouncedOn(collectionService.today());
    }

    private boolean isWithinAnnounceWindow() {
        LocalTime now = LocalTime.now(clock);
        LocalTime start = settingsService.getReminderTime();
        LocalTime end = start.plus(ANNOUNCE_WINDOW);

        if (end.isAfter(start)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Fenster laeuft ueber Mitternacht (z. B. Ansagezeit 23:30).
        return !now.isBefore(start) || now.isBefore(end);
    }
}
