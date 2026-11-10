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
import java.util.stream.Collectors;

/**
 * Finds recurring payments per merchant: splits a merchant's bookings into amount groups,
 * runs each group through the {@link RecurrenceAnalyzer}, merges consecutive series of the same
 * cadence (price changes, also across a one-off settlement in between) and attaches a fresh price
 * change that is too short to be a series yet.
 */
@Component
@RequiredArgsConstructor
public class RecurringSeriesDetector {

    private static final BigDecimal CHAIN_STEP = new BigDecimal("1.05");
    private static final BigDecimal MAX_GROUP_SPREAD = new BigDecimal("0.10");
    private static final int MIN_TERMS = 3;
    private static final int TERM_WINDOW_DAYS = 5;
    private static final double MAX_GAP_FACTOR = 1.5;
    private static final double DUE_WINDOW_FACTOR = 0.25;

    /** Chronological; same-day bookings ordered by transaction id, so "latest" is deterministic. */
    private static final Comparator<RecurringBooking> CHRONOLOGICAL = Comparator
            .comparing(RecurringBooking::date)
            .thenComparing(RecurringBooking::transactionId, Comparator.nullsFirst(Comparator.naturalOrder()));

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
            List<RecurringBooking> ordered = group.stream().sorted(CHRONOLOGICAL).toList();
            analyzer.analyze(dates(ordered), amounts(ordered)).ifPresentOrElse(
                    found -> series.add(new Series(found, ordered)),
                    () -> {
                        if (termStarts(ordered).size() < MIN_TERMS) {
                            smallGroups.add(ordered);
                        }
                    });
        }
        smallGroups.sort(Comparator.comparing(RecurringSeriesDetector::firstDate));
        List<Series> merged = mergePriceChanges(series, smallGroups);
        attachFreshPriceChanges(merged, smallGroups);
        return merged.stream().map(s -> s.toDetected(merchantKey)).toList();
    }

    /**
     * Chains amounts (ascending by absolute value) while each is at most 5 % above the previous;
     * a chain spreading more than 10 % around its median is split at its largest step.
     */
    private static List<List<RecurringBooking>> amountGroups(List<RecurringBooking> bookings) {
        List<RecurringBooking> sorted = new ArrayList<>(bookings);
        sorted.sort(Comparator.comparing(b -> b.amount().abs()));

        List<List<RecurringBooking>> chains = new ArrayList<>();
        List<RecurringBooking> current = new ArrayList<>();
        BigDecimal previous = null;
        for (RecurringBooking booking : sorted) {
            BigDecimal amount = booking.amount().abs();
            if (previous != null && amount.compareTo(previous.multiply(CHAIN_STEP)) > 0) {
                chains.add(current);
                current = new ArrayList<>();
            }
            current.add(booking);
            previous = amount;
        }
        if (!current.isEmpty()) {
            chains.add(current);
        }
        List<List<RecurringBooking>> groups = new ArrayList<>();
        chains.forEach(chain -> splitTooWide(chain, groups));
        return groups;
    }

    /** Chain is sorted ascending by absolute amount; splits recursively at the largest relative step. */
    private static void splitTooWide(List<RecurringBooking> chain, List<List<RecurringBooking>> out) {
        if (chain.size() <= 1 || hasNarrowSpread(chain)) {
            out.add(chain);
            return;
        }
        int splitAfter = largestStepIndex(chain);
        splitTooWide(chain.subList(0, splitAfter + 1), out);
        splitTooWide(chain.subList(splitAfter + 1, chain.size()), out);
    }

    /** Index i where amount[i+1] / amount[i] is largest (a step up from zero counts as largest). */
    private static int largestStepIndex(List<RecurringBooking> chain) {
        int best = 0;
        BigDecimal bestRatio = null;
        for (int i = 0; i < chain.size() - 1; i++) {
            BigDecimal low = chain.get(i).amount().abs();
            BigDecimal high = chain.get(i + 1).amount().abs();
            if (low.signum() == 0) {
                if (high.signum() > 0) {
                    return i;
                }
                continue;
            }
            BigDecimal ratio = high.divide(low, 6, RoundingMode.HALF_UP);
            if (bestRatio == null || ratio.compareTo(bestRatio) > 0) {
                bestRatio = ratio;
                best = i;
            }
        }
        return best;
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

    /**
     * Joins time-disjoint series of the same cadence whose gap is at most 1.5 intervals, or which a
     * short group in between bridges (each gap at most 1.5 intervals, e.g. a settlement or a
     * prorated transition invoice); may chain. Used bridge groups are removed from {@code smallGroups}.
     */
    private static List<Series> mergePriceChanges(List<Series> series, List<List<RecurringBooking>> smallGroups) {
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
            if (older != null) {
                older.absorb(younger);
            } else if (!bridge(merged, younger, smallGroups)) {
                merged.add(younger);
            }
        }
        return merged;
    }

    private static boolean bridge(List<Series> merged, Series younger, List<List<RecurringBooking>> smallGroups) {
        for (List<RecurringBooking> group : smallGroups) {
            LocalDate groupStart = firstDate(group);
            LocalDate groupLastTerm = lastTermStart(group);
            if (!groupLastTerm.isBefore(younger.firstSeen)
                    || !withinGap(groupLastTerm, younger.firstSeen, younger.interval)) {
                continue;
            }
            Series older = merged.stream()
                    .filter(candidate -> candidate.interval == younger.interval)
                    .filter(candidate -> candidate.lastSeen.isBefore(groupStart))
                    .filter(candidate -> withinGap(candidate.lastSeen, groupStart, candidate.interval))
                    .min(Comparator.comparing(candidate -> amountDistance(candidate.expectedAmount, younger.expectedAmount)))
                    .orElse(null);
            if (older != null) {
                older.bookings.addAll(group);
                older.absorb(younger);
                smallGroups.remove(group);
                return true;
            }
        }
        return false;
    }

    /**
     * A short group becomes a series' current price only if it falls into that series' due window
     * (a real price change replaces a term, a one-off in between does not) and no other series of
     * the merchant starts after it (then the group belongs to the transition, not to a new price).
     */
    private static void attachFreshPriceChanges(List<Series> series, List<List<RecurringBooking>> smallGroups) {
        for (List<RecurringBooking> group : smallGroups) {
            LocalDate start = firstDate(group);
            BigDecimal amount = latest(group).amount();
            series.stream()
                    .filter(s -> series.stream().noneMatch(other -> other != s && other.firstSeen.isAfter(start)))
                    .filter(s -> withinDueWindow(start, s))
                    .filter(s -> fitsInterval(group, s.interval))
                    .min(Comparator.comparing(s -> amountDistance(s.expectedAmount, amount)))
                    .ifPresent(s -> s.attach(group));
        }
    }

    private static boolean withinDueWindow(LocalDate date, Series series) {
        long offset = Math.abs(ChronoUnit.DAYS.between(series.nextDue, date));
        return offset <= DUE_WINDOW_FACTOR * RecurrenceAnalyzer.intervalDays(series.interval);
    }

    /** A two-term group must itself be one interval apart; a single term fits any cadence. */
    private static boolean fitsInterval(List<RecurringBooking> group, RecurrenceInterval interval) {
        List<LocalDate> terms = termStarts(group);
        long length = RecurrenceAnalyzer.intervalDays(interval);
        for (int i = 1; i < terms.size(); i++) {
            long gap = ChronoUnit.DAYS.between(terms.get(i - 1), terms.get(i));
            if (Math.abs(gap - length) > DUE_WINDOW_FACTOR * length) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinGap(LocalDate from, LocalDate to, RecurrenceInterval interval) {
        return ChronoUnit.DAYS.between(from, to) <= MAX_GAP_FACTOR * RecurrenceAnalyzer.intervalDays(interval);
    }

    private static BigDecimal amountDistance(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs();
    }

    /** First day of each term; a term spans 5 days from its first booking (same rule as the analyzer). */
    private static List<LocalDate> termStarts(List<RecurringBooking> group) {
        List<LocalDate> starts = new ArrayList<>();
        for (LocalDate date : dates(group).stream().sorted().toList()) {
            if (starts.isEmpty() || ChronoUnit.DAYS.between(starts.get(starts.size() - 1), date) > TERM_WINDOW_DAYS) {
                starts.add(date);
            }
        }
        return starts;
    }

    private static LocalDate lastTermStart(List<RecurringBooking> group) {
        List<LocalDate> starts = termStarts(group);
        return starts.get(starts.size() - 1);
    }

    private static LocalDate firstDate(List<RecurringBooking> group) {
        return group.stream().map(RecurringBooking::date).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static RecurringBooking latest(List<RecurringBooking> group) {
        return group.stream().max(CHRONOLOGICAL).orElseThrow();
    }

    private static List<LocalDate> dates(List<RecurringBooking> group) {
        return group.stream().map(RecurringBooking::date).toList();
    }

    private static List<BigDecimal> amounts(List<RecurringBooking> group) {
        return group.stream().map(RecurringBooking::amount).toList();
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
            bookings.addAll(group);
            expectedAmount = latest(group).amount();
            lastSeen = lastTermStart(group);
            nextDue = RecurrenceAnalyzer.nextDue(lastSeen, interval);
        }

        private DetectedSeries toDetected(String merchantKey) {
            Long categoryId = latest(bookings).categoryId();
            List<BigDecimal> distinctAmounts = new ArrayList<>();
            bookings.stream()
                    .sorted(CHRONOLOGICAL)
                    .map(RecurringBooking::amount)
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
