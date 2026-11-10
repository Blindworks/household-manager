package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Finds recurring payments per merchant: splits a merchant's bookings into amount groups,
 * runs each group through the {@link RecurrenceAnalyzer}, merges consecutive series of the same
 * cadence (price changes) and attaches a fresh price change that is too short to be a series.
 */
@Component
@RequiredArgsConstructor
public class RecurringSeriesDetector {

    private static final BigDecimal CHAIN_STEP = new BigDecimal("1.05");
    private static final BigDecimal MAX_GROUP_SPREAD = new BigDecimal("0.10");
    private static final int MIN_TERMS = 3;
    private static final int TERM_WINDOW_DAYS = 5;
    private static final double MAX_GAP_FACTOR = 1.5;

    private final RecurrenceAnalyzer analyzer;

    public List<DetectedSeries> detect(List<RecurringBooking> bookings) {
        Map<String, List<RecurringBooking>> byMerchant = bookings.stream()
                .filter(b -> b.date() != null && b.amount() != null)
                .filter(b -> b.merchantKey() != null && !b.merchantKey().isBlank())
                .collect(Collectors.groupingBy(RecurringBooking::merchantKey, LinkedHashMap::new, Collectors.toList()));

        List<DetectedSeries> result = new ArrayList<>();
        byMerchant.forEach((key, merchantBookings) -> result.addAll(detectForMerchant(key, merchantBookings)));
        return result;
    }

    private List<DetectedSeries> detectForMerchant(String merchantKey, List<RecurringBooking> bookings) {
        List<Series> series = new ArrayList<>();
        List<List<RecurringBooking>> smallGroups = new ArrayList<>();
        for (List<RecurringBooking> group : amountGroups(bookings)) {
            analyzer.analyze(dates(group), amounts(group)).ifPresentOrElse(
                    found -> series.add(new Series(found, group)),
                    () -> {
                        if (termCount(group) < MIN_TERMS) {
                            smallGroups.add(group);
                        }
                    });
        }
        List<Series> merged = mergePriceChanges(series);
        attachFreshPriceChanges(merged, smallGroups);
        return merged.stream().map(s -> s.toDetected(merchantKey)).toList();
    }

    /** Chains amounts (ascending by absolute value) while each is at most 5 % above the previous. */
    private static List<List<RecurringBooking>> amountGroups(List<RecurringBooking> bookings) {
        List<RecurringBooking> sorted = new ArrayList<>(bookings);
        sorted.sort(Comparator.comparing(b -> b.amount().abs()));

        List<List<RecurringBooking>> groups = new ArrayList<>();
        List<RecurringBooking> current = new ArrayList<>();
        BigDecimal previous = null;
        for (RecurringBooking booking : sorted) {
            BigDecimal amount = booking.amount().abs();
            if (previous != null && amount.compareTo(previous.multiply(CHAIN_STEP)) > 0) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(booking);
            previous = amount;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups.stream().filter(RecurringSeriesDetector::hasNarrowSpread).toList();
    }

    /** Group is sorted ascending by absolute amount. */
    private static boolean hasNarrowSpread(List<RecurringBooking> group) {
        BigDecimal min = group.get(0).amount().abs();
        BigDecimal max = group.get(group.size() - 1).amount().abs();
        BigDecimal median = median(group.stream().map(b -> b.amount().abs()).toList());
        if (median.signum() == 0) {
            return max.signum() == 0;
        }
        BigDecimal spread = max.subtract(min).divide(median, 6, RoundingMode.HALF_UP);
        return spread.compareTo(MAX_GROUP_SPREAD) <= 0;
    }

    private static BigDecimal median(List<BigDecimal> sortedValues) {
        int n = sortedValues.size();
        if (n % 2 == 1) {
            return sortedValues.get(n / 2);
        }
        return sortedValues.get(n / 2 - 1).add(sortedValues.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    /** Joins time-disjoint series of the same cadence whose gap is at most 1.5 intervals; may chain. */
    private static List<Series> mergePriceChanges(List<Series> series) {
        List<Series> byStart = new ArrayList<>(series);
        byStart.sort(Comparator.comparing(s -> s.firstSeen));

        List<Series> merged = new ArrayList<>();
        for (Series younger : byStart) {
            Series older = merged.stream()
                    .filter(candidate -> candidate.interval == younger.interval)
                    .filter(candidate -> candidate.lastSeen.isBefore(younger.firstSeen))
                    .filter(candidate -> withinGap(candidate.lastSeen, younger.firstSeen, candidate.interval))
                    .min(Comparator.comparing(candidate -> amountDistance(candidate.expectedAmount, younger.expectedAmount)))
                    .orElse(null);
            if (older == null) {
                merged.add(younger);
            } else {
                older.absorb(younger);
            }
        }
        return merged;
    }

    /** A short group after a series' last term becomes that series' current price. */
    private static void attachFreshPriceChanges(List<Series> series, List<List<RecurringBooking>> smallGroups) {
        List<List<RecurringBooking>> byStart = new ArrayList<>(smallGroups);
        byStart.sort(Comparator.comparing(RecurringSeriesDetector::firstDate));

        for (List<RecurringBooking> group : byStart) {
            LocalDate start = firstDate(group);
            BigDecimal amount = latest(group).amount();
            series.stream()
                    .filter(s -> s.lastSeen.isBefore(start))
                    .filter(s -> withinGap(s.lastSeen, start, s.interval))
                    .min(Comparator.comparing(s -> amountDistance(s.expectedAmount, amount)))
                    .ifPresent(s -> s.attach(group));
        }
    }

    private static boolean withinGap(LocalDate from, LocalDate to, RecurrenceInterval interval) {
        return ChronoUnit.DAYS.between(from, to) <= MAX_GAP_FACTOR * RecurrenceAnalyzer.intervalDays(interval);
    }

    private static BigDecimal amountDistance(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs();
    }

    private static int termCount(List<RecurringBooking> group) {
        int terms = 0;
        LocalDate termStart = null;
        for (LocalDate date : dates(group).stream().sorted().toList()) {
            if (termStart == null || ChronoUnit.DAYS.between(termStart, date) > TERM_WINDOW_DAYS) {
                terms++;
                termStart = date;
            }
        }
        return terms;
    }

    private static LocalDate firstDate(List<RecurringBooking> group) {
        return group.stream().map(RecurringBooking::date).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static RecurringBooking latest(List<RecurringBooking> group) {
        return group.stream().max(Comparator.comparing(RecurringBooking::date)).orElseThrow();
    }

    private static List<LocalDate> dates(List<RecurringBooking> group) {
        return group.stream().map(RecurringBooking::date).toList();
    }

    private static List<BigDecimal> amounts(List<RecurringBooking> group) {
        return group.stream().map(RecurringBooking::amount).toList();
    }

    private static LocalDate advance(LocalDate date, RecurrenceInterval interval) {
        return switch (interval) {
            case MONTHLY -> date.plusMonths(1);
            case QUARTERLY -> date.plusMonths(3);
            case YEARLY -> date.plusYears(1);
        };
    }

    /** Mutable working state of one series while merging and attaching. */
    private static final class Series {
        private final RecurrenceInterval interval;
        private final LocalDate firstSeen;
        private final List<RecurringBooking> bookings;
        private BigDecimal expectedAmount;
        private LocalDate lastSeen;
        private LocalDate nextDue;

        private Series(RecurrenceResult found, List<RecurringBooking> group) {
            this.interval = found.getInterval();
            this.firstSeen = found.getFirstSeenDate();
            this.lastSeen = found.getLastSeenDate();
            this.nextDue = found.getNextDueDate();
            this.expectedAmount = found.getExpectedAmount();
            this.bookings = new ArrayList<>(group);
        }

        private void absorb(Series younger) {
            bookings.addAll(younger.bookings);
            expectedAmount = younger.expectedAmount;
            lastSeen = younger.lastSeen;
            nextDue = younger.nextDue;
        }

        private void attach(List<RecurringBooking> group) {
            RecurringBooking newest = latest(group);
            bookings.addAll(group);
            expectedAmount = newest.amount();
            lastSeen = newest.date();
            nextDue = advance(lastSeen, interval);
        }

        private DetectedSeries toDetected(String merchantKey) {
            Long categoryId = latest(bookings).categoryId();
            List<BigDecimal> distinctAmounts = new ArrayList<>();
            bookings.stream()
                    .sorted(Comparator.comparing(RecurringBooking::date))
                    .map(RecurringBooking::amount)
                    .filter(Objects::nonNull)
                    .forEach(amount -> {
                        if (distinctAmounts.stream().noneMatch(a -> a.compareTo(amount) == 0)) {
                            distinctAmounts.add(amount);
                        }
                    });
            return new DetectedSeries(merchantKey, interval, expectedAmount, firstSeen, lastSeen, nextDue,
                    categoryId, List.copyOf(distinctAmounts));
        }
    }
}
