package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects whether dated amounts form a regular monthly/quarterly/yearly pattern.
 * Bookings within {@link #TERM_WINDOW_DAYS} days of a term's first booking belong to that
 * term. The rhythm is tolerant: skipped periods and a share of off gaps are accepted.
 * Pairs with a missing date or amount are skipped; among bookings of the same day the one given
 * last counts as the latest (callers pass them in a deterministic order).
 */
@Component
public class RecurrenceAnalyzer {

    private static final int MIN_TERMS = 3;
    private static final int TERM_WINDOW_DAYS = 5;
    private static final double MEDIAN_TOLERANCE = 0.20;
    private static final double GAP_TOLERANCE = 0.25;
    private static final double MIN_FIT_SHARE = 0.75;

    public Optional<RecurrenceResult> analyze(List<LocalDate> dates, List<BigDecimal> amounts) {
        if (dates == null || amounts == null || dates.size() != amounts.size()) {
            return Optional.empty();
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            if (dates.get(i) != null && amounts.get(i) != null) {
                order.add(i);
            }
        }
        order.sort(Comparator.comparing(dates::get));

        List<LocalDate> terms = new ArrayList<>();
        for (int idx : order) {
            LocalDate date = dates.get(idx);
            if (terms.isEmpty() || ChronoUnit.DAYS.between(terms.get(terms.size() - 1), date) > TERM_WINDOW_DAYS) {
                terms.add(date);
            }
        }
        if (terms.size() < MIN_TERMS) {
            return Optional.empty();
        }

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < terms.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(terms.get(i - 1), terms.get(i)));
        }
        RecurrenceInterval interval = classify(median(gaps));
        if (interval == null || fitShare(gaps, exactLength(interval)) < MIN_FIT_SHARE) {
            return Optional.empty();
        }

        LocalDate first = terms.get(0);
        LocalDate last = terms.get(terms.size() - 1);
        BigDecimal latestAmount = amounts.get(order.get(order.size() - 1));
        LocalDate nextDue = nextDue(last, interval);

        return Optional.of(RecurrenceResult.builder()
                .interval(interval).expectedAmount(latestAmount).nextDueDate(nextDue)
                .firstSeenDate(first).lastSeenDate(last)
                .build());
    }

    /** Due date of the term after {@code last}. */
    public static LocalDate nextDue(LocalDate last, RecurrenceInterval interval) {
        return switch (interval) {
            case MONTHLY -> last.plusMonths(1);
            case QUARTERLY -> last.plusMonths(3);
            case YEARLY -> last.plusYears(1);
        };
    }

    /** Rounded interval length in days, for the 1.5x rules of later steps. */
    public static long intervalDays(RecurrenceInterval interval) {
        return switch (interval) {
            case MONTHLY -> 30;
            case QUARTERLY -> 91;
            case YEARLY -> 365;
        };
    }

    private static double exactLength(RecurrenceInterval interval) {
        return switch (interval) {
            case MONTHLY -> 30.4;
            case QUARTERLY -> 91.3;
            case YEARLY -> 365;
        };
    }

    private static double median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static RecurrenceInterval classify(double medianGap) {
        RecurrenceInterval best = null;
        double bestMiss = Double.MAX_VALUE;
        for (RecurrenceInterval candidate : RecurrenceInterval.values()) {
            double length = exactLength(candidate);
            double miss = Math.abs(medianGap - length);
            if (miss <= MEDIAN_TOLERANCE * length && miss < bestMiss) {
                best = candidate;
                bestMiss = miss;
            }
        }
        return best;
    }

    /** Share of gaps within 25% of one or two interval lengths (a skipped period is no break). */
    private static double fitShare(List<Long> gaps, double length) {
        double tolerance = GAP_TOLERANCE * length;
        long fitting = gaps.stream()
                .filter(g -> Math.abs(g - length) <= tolerance || Math.abs(g - 2 * length) <= tolerance)
                .count();
        return (double) fitting / gaps.size();
    }
}
