package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecurrenceAnalyzerTest {

    private final RecurrenceAnalyzer analyzer = new RecurrenceAnalyzer();

    @Test
    void detectsMonthlyRecurrenceWithNextDueDate() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 2),
                LocalDate.of(2026, 3, 2), LocalDate.of(2026, 4, 2));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("-9.99"), new BigDecimal("-9.99"),
                new BigDecimal("-9.99"), new BigDecimal("-9.99"));

        Optional<RecurrenceResult> result = analyzer.analyze(dates, amounts);

        assertTrue(result.isPresent());
        assertEquals(RecurrenceInterval.MONTHLY, result.get().getInterval());
        assertEquals(0, new BigDecimal("-9.99").compareTo(result.get().getExpectedAmount()));
        assertEquals(LocalDate.of(2026, 5, 2), result.get().getNextDueDate());
    }

    @Test
    void rejectsTooFewOccurrences() {
        List<LocalDate> dates = List.of(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 2));
        List<BigDecimal> amounts = List.of(new BigDecimal("-9.99"), new BigDecimal("-9.99"));
        assertTrue(analyzer.analyze(dates, amounts).isEmpty());
    }

    @Test
    void rejectsIrregularGaps() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 9),
                LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 1));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("-5"), new BigDecimal("-5"),
                new BigDecimal("-5"), new BigDecimal("-5"));
        assertTrue(analyzer.analyze(dates, amounts).isEmpty());
    }

    @Test
    void detectsYearlyRecurrence() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2024, 6, 1), LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1));
        List<BigDecimal> amounts = List.of(
                new BigDecimal("-120"), new BigDecimal("-120"), new BigDecimal("-120"));
        Optional<RecurrenceResult> result = analyzer.analyze(dates, amounts);
        assertTrue(result.isPresent());
        assertEquals(RecurrenceInterval.YEARLY, result.get().getInterval());
    }

    private static List<BigDecimal> same(int n, String amount) {
        return java.util.Collections.nCopies(n, new BigDecimal(amount));
    }

    private static LocalDate d(int m, int day) {
        return LocalDate.of(2026, m, day);
    }

    @Test
    void bookingWithinFiveDaysJoinsTerm() {
        List<LocalDate> dates = List.of(d(1, 1), d(2, 1), d(3, 1), d(3, 4), d(4, 1), d(5, 1));
        RecurrenceResult r = analyzer.analyze(dates, same(6, "-10")).orElseThrow();
        assertEquals(RecurrenceInterval.MONTHLY, r.getInterval());
        assertEquals(d(1, 1), r.getFirstSeenDate());
        assertEquals(d(5, 1), r.getLastSeenDate());
        assertEquals(d(6, 1), r.getNextDueDate());
    }

    @Test
    void skippedMonthIsNoBreak() {
        List<LocalDate> dates = List.of(d(1, 1), d(2, 1), d(4, 1), d(5, 1), d(6, 1));
        assertEquals(RecurrenceInterval.MONTHLY, analyzer.analyze(dates, same(5, "-10")).orElseThrow().getInterval());
    }

    @Test
    void duplicateWithinFiveDaysDoesNotDoubleAmount() {
        List<LocalDate> dates = List.of(d(1, 1), d(1, 1), d(2, 1), d(2, 1), d(3, 1), d(3, 1), d(4, 1), d(4, 1));
        RecurrenceResult r = analyzer.analyze(dates, same(8, "-25.00")).orElseThrow();
        assertEquals(RecurrenceInterval.MONTHLY, r.getInterval());
        assertEquals(0, new BigDecimal("-25.00").compareTo(r.getExpectedAmount()));
    }

    @Test
    void latestAmountWins() {
        List<LocalDate> dates = List.of(d(1, 1), d(2, 1), d(3, 1), d(4, 1));
        List<BigDecimal> amounts = List.of(new BigDecimal("-21.96"), new BigDecimal("-21.96"),
                new BigDecimal("-21.96"), new BigDecimal("-22.50"));
        assertEquals(0, new BigDecimal("-22.50").compareTo(analyzer.analyze(dates, amounts).orElseThrow().getExpectedAmount()));
    }

    @Test
    void toleratesQuarterOfGapsOff() {
        List<LocalDate> dates = List.of(d(1, 1), d(2, 1), d(3, 1), d(3, 13), d(4, 1), d(5, 1), d(6, 1), d(7, 1), d(8, 1));
        assertEquals(RecurrenceInterval.MONTHLY, analyzer.analyze(dates, same(9, "-10")).orElseThrow().getInterval());
    }

    @Test
    void rejectsIrregular() {
        LocalDate day = d(1, 1);
        java.util.ArrayList<LocalDate> dates = new java.util.ArrayList<>(List.of(day));
        for (int gap : new int[]{4, 20, 18, 7, 14, 7}) {
            day = day.plusDays(gap);
            dates.add(day);
        }
        assertTrue(analyzer.analyze(dates, same(dates.size(), "-5")).isEmpty());
    }

    @Test
    void quarterly() {
        LocalDate a = d(1, 1);
        List<LocalDate> dates = List.of(a, a.plusDays(91), a.plusDays(179), a.plusDays(273));
        assertEquals(RecurrenceInterval.QUARTERLY, analyzer.analyze(dates, same(4, "-30")).orElseThrow().getInterval());
    }

    @Test
    void intervalDaysAreRounded() {
        assertEquals(30, RecurrenceAnalyzer.intervalDays(RecurrenceInterval.MONTHLY));
        assertEquals(91, RecurrenceAnalyzer.intervalDays(RecurrenceInterval.QUARTERLY));
        assertEquals(365, RecurrenceAnalyzer.intervalDays(RecurrenceInterval.YEARLY));
    }
}
