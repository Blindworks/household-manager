package com.household.manager.calendar;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class RecurrenceExpansionService {

    /** Harte Kappe pro Abfrage — eine pathologische Regel darf nichts festfahren. */
    static final int MAX_OCCURRENCES = 1000;

    /** @throws ResponseStatusException 400, wenn die Regel kein gueltiges RRULE ist */
    public void validate(String rrule) {
        parse(rrule);
    }

    /**
     * Ob die Regel ab dem Seriendatum ueberhaupt jemals ein Vorkommen erzeugt.
     *
     * <p>Fragt bewusst nur den Iterator nach seinem ersten Treffer, statt ein festes
     * Zeitfenster (z.B. ein Jahr) zu expandieren: eine gueltige Regel kann einen spaeten
     * ersten Treffer haben (z.B. "jaehrlich am 29. Februar" ab einem Nicht-Schaltjahr —
     * der erste Treffer liegt erst zwei Jahre spaeter) und waere bei einer Fensterpruefung
     * faelschlich als "kein Vorkommen" erkannt worden.
     */
    public boolean hasAnyOccurrence(String rrule, LocalDate seriesStart) {
        RecurrenceRule rule = parse(rrule);
        try {
            return rule.iterator(toDateTime(seriesStart)).hasNext();
        } catch (IllegalArgumentException ex) {
            // Syntaktisch gueltige Regel, die aber nie ein Vorkommen erzeugen kann
            // (z.B. 30. Februar) - lib-recur wirft das bereits bei der Iterator-Konstruktion.
            return false;
        }
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
        if (occurrences.size() == MAX_OCCURRENCES && iterator.hasNext()) {
            log.warn("RRULE '{}' hat die Expansionsgrenze von {} Vorkommen erreicht; "
                            + "Serie wird im Fenster {} bis {} abgeschnitten",
                    rrule, MAX_OCCURRENCES, from, to);
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
