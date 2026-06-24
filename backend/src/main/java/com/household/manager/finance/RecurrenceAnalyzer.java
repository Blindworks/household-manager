package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Detects whether a series of dated amounts forms a regular monthly/quarterly/yearly
 * pattern. Requires at least 3 occurrences with consistent gaps (within tolerance).
 */
@Component
public class RecurrenceAnalyzer {

    private static final int MIN_OCCURRENCES = 3;

    public Optional<RecurrenceResult> analyze(List<LocalDate> dates, List<BigDecimal> amounts) {
        if (dates == null || dates.size() < MIN_OCCURRENCES) {
            return Optional.empty();
        }
        List<LocalDate> sorted = new ArrayList<>(dates);
        sorted.sort(LocalDate::compareTo);

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(sorted.get(i - 1), sorted.get(i)));
        }
        double avgGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);

        RecurrenceInterval interval = classify(avgGap);
        if (interval == null || !gapsConsistent(gaps, avgGap)) {
            return Optional.empty();
        }

        BigDecimal expected = averageAmount(amounts);
        LocalDate last = sorted.get(sorted.size() - 1);
        LocalDate nextDue = switch (interval) {
            case MONTHLY -> last.plusMonths(1);
            case QUARTERLY -> last.plusMonths(3);
            case YEARLY -> last.plusYears(1);
        };

        return Optional.of(RecurrenceResult.builder()
                .interval(interval).expectedAmount(expected).nextDueDate(nextDue)
                .build());
    }

    private RecurrenceInterval classify(double avgGap) {
        if (avgGap >= 26 && avgGap <= 35) {
            return RecurrenceInterval.MONTHLY;
        }
        if (avgGap >= 82 && avgGap <= 98) {
            return RecurrenceInterval.QUARTERLY;
        }
        if (avgGap >= 350 && avgGap <= 380) {
            return RecurrenceInterval.YEARLY;
        }
        return null;
    }

    /** Every gap must be within 25% of the average gap. */
    private boolean gapsConsistent(List<Long> gaps, double avgGap) {
        double tolerance = avgGap * 0.25;
        return gaps.stream().allMatch(g -> Math.abs(g - avgGap) <= tolerance);
    }

    private BigDecimal averageAmount(List<BigDecimal> amounts) {
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
    }
}
