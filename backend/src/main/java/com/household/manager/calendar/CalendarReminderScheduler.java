package com.household.manager.calendar;

import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Feuert fuer faellige Kalender-Vorkommen das Flow-Event {@code event.calendar_reminder}
 * (action = Kategorie kleingeschrieben) — Uhrzeit-Termine zum Start, ganztaegige um 08:00.
 */
@Service
@Slf4j
public class CalendarReminderScheduler {

    static final String ENTITY_ID = "event.calendar_reminder";
    /** Ganztaegige Termine erinnern morgens um diese Zeit (bewusst Konstante, keine Settings-UI in v1). */
    static final LocalTime ALL_DAY_REMINDER_TIME = LocalTime.of(8, 0);

    private final CalendarEventService calendarService;
    private final EntityStateService entityStateService;
    private final Clock clock;

    /**
     * Obergrenze des zuletzt geprueften Fensters. Startwert "jetzt": Nach einem Neustart
     * werden verpasste Erinnerungen bewusst NICHT nachgefeuert — eine verspaetete
     * Erinnerung waere irrefuehrender als keine. In-memory reicht damit aus.
     */
    private LocalDateTime lastChecked;

    public CalendarReminderScheduler(CalendarEventService calendarService,
                                     EntityStateService entityStateService,
                                     Clock clock) {
        this.calendarService = calendarService;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.lastChecked = LocalDateTime.now(clock);
    }

    @Scheduled(fixedDelayString = "${calendar.reminder.check-interval-ms:60000}")
    public void checkDueReminders() {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            fireRemindersBetween(lastChecked, now);
        } catch (Exception ex) {
            // Kalenderfehler duerfen den Scheduler-Thread nie reissen (Hook-Muster des Entity-Layers).
            log.error("Kalender-Erinnerungen konnten nicht geprueft werden", ex);
        }
        lastChecked = now;
    }

    /** Feuert alle Vorkommen, deren Erinnerungszeitpunkt in (since, until] liegt. */
    void fireRemindersBetween(LocalDateTime since, LocalDateTime until) {
        for (CalendarOccurrenceResponse occ :
                calendarService.getOccurrences(since.toLocalDate(), until.toLocalDate())) {
            LocalDateTime reminderAt = reminderTime(occ);
            if (reminderAt.isAfter(since) && !reminderAt.isAfter(until)) {
                fire(occ);
            }
        }
    }

    private LocalDateTime reminderTime(CalendarOccurrenceResponse occ) {
        LocalTime time = occ.isAllDay() ? ALL_DAY_REMINDER_TIME : occ.getStartTime();
        return occ.getOccurrenceDate().atTime(time);
    }

    private void fire(CalendarOccurrenceResponse occ) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("title", occ.getTitle());
        attributes.put("date", occ.getOccurrenceDate().toString());
        attributes.put("time", occ.getStartTime() != null ? occ.getStartTime().toString() : null);
        attributes.put("allDay", occ.isAllDay());
        attributes.put("eventId", occ.getEventId());
        entityStateService.reportEvent(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.EVENT)
                .source(EntitySource.CALENDAR)
                .sourceRef("calendar")
                .friendlyName("Kalender-Erinnerung")
                .state(occ.getCategory().name().toLowerCase(Locale.ROOT))
                .attributes(attributes)
                .build());
        log.info("Kalender-Erinnerung gefeuert: {} am {}", occ.getTitle(), occ.getOccurrenceDate());
    }
}
