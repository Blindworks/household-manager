package com.household.manager.repository;

import com.household.manager.model.entity.CalendarEventPerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Zuordnungen Termin↔Person. Es gibt bewusst keine Loeschmethode nach Termin: das
 * Abraeumen beim Loeschen eines Termins uebernimmt vollstaendig die Kaskade des
 * Fremdschluessels (siehe {@code CalendarEventService#delete}).
 */
public interface CalendarEventPersonRepository
        extends JpaRepository<CalendarEventPerson, CalendarEventPerson.Key> {

    List<CalendarEventPerson> findByCalendarEventId(Long calendarEventId);
}
