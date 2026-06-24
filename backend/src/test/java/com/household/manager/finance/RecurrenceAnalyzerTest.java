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
}
