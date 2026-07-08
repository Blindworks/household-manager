package com.household.manager.service;

import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import com.household.manager.repository.AlexaScheduledAnnouncementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Verwaltung (CRUD) und minuetliche Ausloesung zeitgeplanter Ansagen. */
@Service
@Slf4j
public class AlexaScheduledAnnouncementService {

    private final AlexaScheduledAnnouncementRepository repository;
    private final AlexaAnnouncementService announcementService;

    public AlexaScheduledAnnouncementService(AlexaScheduledAnnouncementRepository repository,
                                             AlexaAnnouncementService announcementService) {
        this.repository = repository;
        this.announcementService = announcementService;
    }

    public List<AlexaScheduledAnnouncement> getAll() {
        return repository.findAll();
    }

    public AlexaScheduledAnnouncement create(AlexaScheduledAnnouncement announcement) {
        announcement.setId(null);
        return repository.save(announcement);
    }

    public AlexaScheduledAnnouncement update(Long id, AlexaScheduledAnnouncement update) {
        AlexaScheduledAnnouncement existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ansage nicht gefunden: " + id));
        existing.setText(update.getText());
        existing.setTimeOfDay(update.getTimeOfDay());
        existing.setWeekdays(update.getWeekdays());
        existing.setMode(update.getMode());
        existing.setEnabled(update.isEnabled());
        existing.setTargetSerialNumbers(update.getTargetSerialNumbers());
        return repository.save(existing);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * Reine Faelligkeitslogik: wird die Ansage zum Zeitpunkt {@code now} ausgeloest?
     * Faellig, wenn aktiviert, der Wochentag passt, die Minute mit timeOfDay uebereinstimmt
     * und sie nicht bereits in dieser Minute lief. Verpasste Slots werden nicht nachgeholt.
     */
    boolean isDue(AlexaScheduledAnnouncement a, LocalDateTime now) {
        if (!a.isEnabled()) {
            return false;
        }
        Set<DayOfWeek> days = parseWeekdays(a.getWeekdays());
        if (!days.contains(now.getDayOfWeek())) {
            return false;
        }
        if (now.getHour() != a.getTimeOfDay().getHour()
                || now.getMinute() != a.getTimeOfDay().getMinute()) {
            return false;
        }
        if (a.getLastRun() != null
                && a.getLastRun().getYear() == now.getYear()
                && a.getLastRun().getDayOfYear() == now.getDayOfYear()
                && a.getLastRun().getHour() == now.getHour()
                && a.getLastRun().getMinute() == now.getMinute()) {
            return false;
        }
        return true;
    }

    private Set<DayOfWeek> parseWeekdays(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> DayOfWeek.valueOf(s.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toSet());
    }

    /** Minuetliche Pruefung; feuert faellige Ansagen und protokolliert Fehler pro Ansage. */
    @Scheduled(fixedDelayString = "${alexa.scheduled.check-interval-ms:60000}")
    public void runDueAnnouncements() {
        LocalDateTime now = LocalDateTime.now();
        for (AlexaScheduledAnnouncement a : repository.findByEnabledTrue()) {
            if (!isDue(a, now)) {
                continue;
            }
            try {
                announcementService.announce(
                        a.getText(),
                        List.copyOf(a.getTargetSerialNumbers()),
                        a.getMode());
                a.setLastRun(now);
                a.setLastError(null);
            } catch (Exception ex) {
                a.setLastError(ex.getMessage());
                log.warn("Geplante Ansage {} fehlgeschlagen: {}", a.getId(), ex.getMessage());
            }
            repository.save(a);
        }
    }
}
