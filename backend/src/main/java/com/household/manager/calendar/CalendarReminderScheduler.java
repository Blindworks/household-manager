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
import java.time.Instant;
import java.time.LocalDate;
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
    /**
     * Ganztaegige Termine erinnern morgens um diese Zeit (bewusst Konstante, keine Settings-UI
     * in v1). Dient auch als defensiver Fallback fuer Uhrzeit-Termine ohne Startzeit (siehe
     * {@link #reminderInstant}) — ueber die API heute durch Validierung ausgeschlossen, aber bei
     * fehlerhaften Altdaten/manuellem DB-Eingriff darf das den Lauf nicht abreissen lassen.
     */
    static final LocalTime ALL_DAY_REMINDER_TIME = LocalTime.of(8, 0);

    private final CalendarEventService calendarService;
    private final EntityStateService entityStateService;
    private final Clock clock;

    /**
     * Obergrenze des zuletzt geprueften Fensters, bewusst als {@code Instant} statt als
     * {@code LocalDateTime} in Wandzeit gehalten: Die Berliner Wandzeit ist bei der
     * Zeitumstellung im Oktober NICHT monoton — die Stunde 02:00-03:00 laeuft zweimal. Mit einer
     * Wandzeit-Hochwassermarke wuerde der ueber den Sprung laufende Check ein leeres/rueckwaerts
     * laufendes Fenster sehen und Erinnerungen der wiederholten Stunde ein zweites Mal feuern
     * (fuer ein Smart-Home: dieselbe Ansage/Nachricht doppelt). Ein {@code Instant} ist in realer
     * Zeit garantiert monoton, daher wird ausschliesslich damit verglichen; die Wandzeit eines
     * Vorkommens wird je Aufruf frisch ueber die Zone der {@code Clock} in einen Instant
     * umgerechnet (bei der ueberlappenden Stunde loest Java dabei bewusst auf die fruehere,
     * Sommerzeit-Instant auf — die dann bereits "verbraucht" ist und beim zweiten Durchlauf durch
     * die Stunde nicht erneut vor dem neuen Fensteranfang liegt).
     *
     * Startwert "jetzt": Nach einem Neustart werden verpasste Erinnerungen bewusst NICHT
     * nachgefeuert — eine verspaetete Erinnerung waere irrefuehrender als keine. In-memory
     * reicht damit aus.
     */
    private Instant lastChecked;

    public CalendarReminderScheduler(CalendarEventService calendarService,
                                     EntityStateService entityStateService,
                                     Clock clock) {
        this.calendarService = calendarService;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.lastChecked = clock.instant();
    }

    /**
     * {@code fixedDelay} (nicht {@code fixedRate}, keine {@code @Async}-Ausfuehrung): Spring
     * startet den naechsten Lauf erst nach Abschluss des vorigen, daher ueberlappen sich zwei
     * Aufrufe nie. Ohne diese Garantie waere der Zugriff auf {@code lastChecked} ein Datenrennen
     * zwischen Scheduler-Threads (der Pool dieses Projekts hat vier Threads) und muesste
     * synchronisiert werden.
     */
    @Scheduled(fixedDelayString = "${calendar.reminder.check-interval-ms:60000}")
    public void checkDueReminders() {
        Instant now = clock.instant();
        try {
            fireRemindersBetween(lastChecked, now);
        } catch (Exception ex) {
            // Kalenderfehler duerfen den Scheduler-Thread nie reissen (Hook-Muster des Entity-Layers).
            log.error("Kalender-Erinnerungen konnten nicht geprueft werden", ex);
        }
        lastChecked = now;
    }

    /** Feuert alle Vorkommen, deren Erinnerungszeitpunkt in (since, until] liegt. */
    void fireRemindersBetween(Instant since, Instant until) {
        LocalDate from = LocalDateTime.ofInstant(since, clock.getZone()).toLocalDate();
        LocalDate to = LocalDateTime.ofInstant(until, clock.getZone()).toLocalDate();
        // Bewusste Wiederverwendung von getOccurrences statt einer engeren, fensterspezifischen
        // Abfrage: der Service laedt intern die gesamte Tabelle und filtert in Java. Fuer den
        // ueberschaubaren Haushaltskalender unkritisch — bei sehr vielen Terminen waere eine
        // gezielte DB-Abfrage die naechste Ausbaustufe.
        for (CalendarOccurrenceResponse occ : calendarService.getOccurrences(from, to)) {
            Instant reminderAt = reminderInstant(occ);
            if (reminderAt.isAfter(since) && !reminderAt.isAfter(until)) {
                fire(occ);
            }
        }
    }

    private Instant reminderInstant(CalendarOccurrenceResponse occ) {
        // Fehlende Startzeit bei einem Nicht-Ganztagstermin ist ueber die API ausgeschlossen,
        // wird hier aber wie ein ganztaegiger Termin behandelt (statt eine NPE zu werfen) —
        // derselbe defensive Fallback wie CalendarEventService#isAlreadyOver.
        LocalTime time = (occ.isAllDay() || occ.getStartTime() == null)
                ? ALL_DAY_REMINDER_TIME
                : occ.getStartTime();
        return occ.getOccurrenceDate().atTime(time).atZone(clock.getZone()).toInstant();
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
