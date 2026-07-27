package com.household.manager.repository;

import com.household.manager.model.entity.CalendarEventPerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Zuordnungen Termin↔Person. {@code deleteByCalendarEventId} ist eine abgeleitete
 * Loeschmethode und laeuft damit in der Transaktion des Aufrufers — alle Aufrufer im
 * {@code CalendarEventService} sind {@code @Transactional}.
 */
public interface CalendarEventPersonRepository
        extends JpaRepository<CalendarEventPerson, CalendarEventPerson.Key> {

    List<CalendarEventPerson> findByCalendarEventId(Long calendarEventId);

    void deleteByCalendarEventId(Long calendarEventId);
}
