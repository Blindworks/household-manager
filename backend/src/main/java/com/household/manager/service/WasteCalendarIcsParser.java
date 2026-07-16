package com.household.manager.service;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.io.TimezoneAssignment;
import biweekly.io.TimezoneInfo;
import biweekly.property.DateStart;
import biweekly.util.ICalDate;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Parst ICS-Text zu Abholterminen. Rein funktional: kein Netz, keine Datenbank.
 *
 * <p>Serientermine werden über {@link VEvent#getDateIterator(TimeZone)} aufgelöst; für
 * Einzeltermine liefert derselbe Iterator genau ein Datum, sodass beide Fälle einen Codepfad
 * teilen.
 */
@Component
@Slf4j
public class WasteCalendarIcsParser {

    /**
     * Obergrenze für Iterationen je Termin. Eine Serie ohne UNTIL/COUNT ist unendlich; das
     * Fensterende bricht regulär ab, diese Grenze ist der Notausstieg gegen fehlerhafte Regeln.
     */
    private static final int MAX_OCCURRENCES_PER_EVENT = 1000;

    /**
     * @param icsContent Roher ICS-Text
     * @param from       erster Tag des Fensters (einschließlich)
     * @param to         letzter Tag des Fensters (einschließlich)
     * @return Termine im Fenster, Duplikate möglich (mehrere Tonnen an einem Tag)
     * @throws WasteCalendarException wenn der Text kein verwertbarer Kalender ist
     */
    public List<ParsedWasteEvent> parse(String icsContent, LocalDate from, LocalDate to) {
        ICalendar ical = parseCalendar(icsContent);

        List<ParsedWasteEvent> result = new ArrayList<>();
        for (VEvent event : ical.getEvents()) {
            collectOccurrences(ical, event, from, to, result);
        }
        log.debug("ICS geparst: {} Termine im Fenster {} bis {}", result.size(), from, to);
        return result;
    }

    private ICalendar parseCalendar(String icsContent) {
        ICalendar ical;
        try {
            ical = Biweekly.parse(icsContent).first();
        } catch (Exception ex) {
            throw new WasteCalendarException("Kalender konnte nicht gelesen werden.", ex);
        }
        if (ical == null) {
            throw new WasteCalendarException(
                    "Kalender konnte nicht gelesen werden: kein VCALENDAR im Inhalt gefunden.");
        }
        return ical;
    }

    private void collectOccurrences(ICalendar ical, VEvent event,
                                    LocalDate from, LocalDate to,
                                    List<ParsedWasteEvent> result) {
        String label = readLabel(event);
        if (label == null) {
            log.warn("Termin ohne SUMMARY wird uebersprungen");
            return;
        }
        DateStart dtstart = event.getDateStart();
        if (dtstart == null || dtstart.getValue() == null) {
            log.warn("Termin '{}' ohne DTSTART wird uebersprungen", label);
            return;
        }

        TimeZone timezone = resolveTimezone(ical, dtstart);
        ZoneId zoneId = timezone.toZoneId();

        DateIterator it = event.getDateIterator(timezone);
        it.advanceTo(Date.from(from.atStartOfDay(zoneId).toInstant()));

        int guard = 0;
        while (it.hasNext() && guard++ < MAX_OCCURRENCES_PER_EVENT) {
            LocalDate occurrence = it.next().toInstant().atZone(zoneId).toLocalDate();
            if (occurrence.isAfter(to)) {
                return;
            }
            if (!occurrence.isBefore(from)) {
                result.add(new ParsedWasteEvent(occurrence, label));
            }
        }
        if (guard >= MAX_OCCURRENCES_PER_EVENT) {
            log.warn("Termin '{}' hat die Iterationsgrenze erreicht; Serie wird abgeschnitten", label);
        }
    }

    private String readLabel(VEvent event) {
        if (event.getSummary() == null) {
            return null;
        }
        String value = event.getSummary().getValue();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Ganztagestermine (VALUE=DATE, wie bei Abholterminen üblich) verankert biweekly beim
     * Parsen bereits fest in der JVM-Standardzeitzone (unabhängig vom TimeZone-Parameter, den
     * {@link VEvent#getDateIterator(TimeZone)} entgegennimmt) – geprüft anhand der 0.6.8-Jars,
     * da {@link TimezoneInfo#isFloating(biweekly.property.ICalProperty)} für solche Properties
     * fälschlich {@code false} liefert. Für echte DATE-TIME-Termine gilt weiterhin die reguläre
     * Floating-/TZID-Auflösung.
     */
    private TimeZone resolveTimezone(ICalendar ical, DateStart dtstart) {
        ICalDate value = dtstart.getValue();
        if (!value.hasTime()) {
            return TimeZone.getDefault();
        }
        TimezoneInfo tzinfo = ical.getTimezoneInfo();
        if (tzinfo.isFloating(dtstart)) {
            return TimeZone.getDefault();
        }
        TimezoneAssignment assignment = tzinfo.getTimezone(dtstart);
        return assignment == null ? TimeZone.getTimeZone("UTC") : assignment.getTimeZone();
    }
}
