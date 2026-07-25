package com.household.manager.calendar;

import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Einzige Stelle mit lib-recur-Spezifika (RRULE-Parsing und -Expansion) — nach dem
 * Projektmuster "brittle Fremd-API in eine Klasse sperren".
 *
 * <p>Bibliotheks-Falle: {@link DateTime} zaehlt Monate 0-basiert; die Umrechnung
 * passiert ausschliesslich in {@link #toDateTime}/{@link #toLocalDate}.
 */
@Service
public class RecurrenceExpansionService {

    /** Harte Kappe pro Abfrage — eine pathologische Regel darf nichts festfahren. */
    static final int MAX_OCCURRENCES = 1000;

    /** @throws ResponseStatusException 400, wenn die Regel kein gueltiges RRULE ist */
    public void validate(String rrule) {
        parse(rrule);
    }

    /**
     * Expandiert die Regel ab Serienstart und liefert alle Vorkommen-Daten im Fenster
     * [from, to] (einschliesslich), gekappt bei {@link #MAX_OCCURRENCES}.
     */
    public List<LocalDate> expand(String rrule, LocalDate seriesStart, LocalDate from, LocalDate to) {
        RecurrenceRuleIterator iterator = parse(rrule).iterator(toDateTime(seriesStart));
        if (from.isAfter(seriesStart)) {
            iterator.fastForward(toDateTime(from));
        }
        List<LocalDate> occurrences = new ArrayList<>();
        while (iterator.hasNext() && occurrences.size() < MAX_OCCURRENCES) {
            LocalDate occurrence = toLocalDate(iterator.nextDateTime());
            if (occurrence.isAfter(to)) {
                break;
            }
            if (!occurrence.isBefore(from)) {
                occurrences.add(occurrence);
            }
        }
        return occurrences;
    }

    private RecurrenceRule parse(String rrule) {
        try {
            return new RecurrenceRule(rrule);
        } catch (InvalidRecurrenceRuleException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Wiederholungsregel ist ungueltig: " + ex.getMessage());
        }
    }

    private DateTime toDateTime(LocalDate date) {
        return new DateTime(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
    }

    private LocalDate toLocalDate(DateTime dateTime) {
        return LocalDate.of(dateTime.getYear(), dateTime.getMonth() + 1, dateTime.getDayOfMonth());
    }
}
