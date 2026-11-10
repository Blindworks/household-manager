package com.household.manager.finance;

import com.household.manager.model.entity.RecurrenceInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecurringSeriesDetectorTest {

    private final RecurringSeriesDetector detector = new RecurringSeriesDetector(new RecurrenceAnalyzer());

    @Test
    void splitsParallelContractsByAmount() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("DRILLISCH ONLINE GMBH", "-4.99", LocalDate.of(2025, 1, 28), 12));
        bookings.addAll(monthly("DRILLISCH ONLINE GMBH", "-7.99", LocalDate.of(2025, 1, 18), 12));

        List<DetectedSeries> result = sortedByAmount(detector.detect(bookings));

        assertEquals(2, result.size());
        assertAmount("-7.99", result.get(0).expectedAmount());
        assertAmount("-4.99", result.get(1).expectedAmount());
        result.forEach(s -> assertEquals(RecurrenceInterval.MONTHLY, s.interval()));
    }

    @Test
    void paypalMerchantsAreSeparateSeries() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("PAYPAL:SPOTIFY AB", "-12.99", LocalDate.of(2025, 2, 5), 10));
        bookings.addAll(monthly("PAYPAL:TELEKOM DEUTSCHLAND", "-17.00", LocalDate.of(2025, 1, 10), 12));
        LocalDate date = LocalDate.of(2025, 1, 2);
        for (int i = 0; i < 30; i++) {
            date = date.plusDays(3 + (i * 7) % 11);
            BigDecimal amount = new BigDecimal("6.00").add(new BigDecimal("0.47").multiply(BigDecimal.valueOf(i)));
            bookings.add(new RecurringBooking(date, amount.negate(), "PAYPAL:SHELL DEUTSCHLAND", null, null));
        }

        List<DetectedSeries> result = detector.detect(bookings);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> s.merchantKey().equals("PAYPAL:SPOTIFY AB")));
        assertTrue(result.stream().anyMatch(s -> s.merchantKey().equals("PAYPAL:TELEKOM DEUTSCHLAND")));
    }

    @Test
    void irregularBakeryIsNoSeries() {
        List<RecurringBooking> bookings = List.of(
                booking("BAECKEREI", "-4.99", LocalDate.of(2026, 1, 3)),
                booking("BAECKEREI", "-4.99", LocalDate.of(2026, 1, 20)),
                booking("BAECKEREI", "-4.99", LocalDate.of(2026, 3, 30)));

        assertTrue(detector.detect(bookings).isEmpty());
    }

    @Test
    void fluctuatingGroceriesAreNoSeries() {
        // Monthly shopping whose amounts jump up and down: the split amount groups are scattered in time.
        String[] amounts = {"45.00", "58.20", "49.10", "61.30", "52.40", "46.80",
                "59.90", "50.70", "55.10", "47.60", "62.00", "53.30"};
        List<RecurringBooking> bookings = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            bookings.add(booking("SUPERMARKT", "-" + amounts[i], LocalDate.of(2025, 1, 15).plusMonths(i)));
        }

        assertTrue(detector.detect(bookings).isEmpty());
    }

    @Test
    void priceIncreaseIsMergedIntoOneSeries() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("GIGANETZ", "-29.80", LocalDate.of(2025, 1, 1), 5));
        bookings.addAll(monthly("GIGANETZ", "-84.80", LocalDate.of(2025, 6, 1), 6));

        List<DetectedSeries> result = detector.detect(bookings);

        assertEquals(1, result.size());
        DetectedSeries series = result.get(0);
        assertAmount("-84.80", series.expectedAmount());
        assertTrue(containsAmount(series.amounts(), "-29.80"));
        assertTrue(containsAmount(series.amounts(), "-84.80"));
        assertEquals(LocalDate.of(2025, 1, 1), series.firstSeenDate());
        assertEquals(LocalDate.of(2025, 11, 1), series.lastSeenDate());
        assertEquals(LocalDate.of(2025, 12, 1), series.nextDueDate());
    }

    @Test
    void mergingChainsOverSeveralPriceChanges() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("STROM", "-50.00", LocalDate.of(2024, 1, 3), 4));
        bookings.addAll(monthly("STROM", "-60.00", LocalDate.of(2024, 5, 3), 4));
        bookings.addAll(monthly("STROM", "-75.00", LocalDate.of(2024, 9, 3), 4));

        List<DetectedSeries> result = detector.detect(bookings);

        assertEquals(1, result.size());
        assertAmount("-75.00", result.get(0).expectedAmount());
        assertEquals(3, result.get(0).amounts().size());
        assertEquals(LocalDate.of(2024, 1, 3), result.get(0).firstSeenDate());
    }

    @Test
    void freshPriceChangeIsAttached() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("MAINOVA", "-69.00", LocalDate.of(2025, 5, 2), 6));
        bookings.add(booking("MAINOVA", "-88.00", LocalDate.of(2025, 11, 3)));

        List<DetectedSeries> result = detector.detect(bookings);

        assertEquals(1, result.size());
        DetectedSeries series = result.get(0);
        assertAmount("-88.00", series.expectedAmount());
        assertEquals(LocalDate.of(2025, 11, 3), series.lastSeenDate());
        assertEquals(LocalDate.of(2025, 12, 3), series.nextDueDate());
        assertTrue(containsAmount(series.amounts(), "-69.00"));
        assertTrue(containsAmount(series.amounts(), "-88.00"));
    }

    @Test
    void categoryOfLatestBooking() {
        List<RecurringBooking> bookings = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            bookings.add(new RecurringBooking(LocalDate.of(2025, 1, 10).plusMonths(i),
                    new BigDecimal("-9.99"), "NETFLIX", 3L, null));
        }
        bookings.add(new RecurringBooking(LocalDate.of(2025, 5, 10), new BigDecimal("-9.99"), "NETFLIX", 7L, null));

        List<DetectedSeries> result = detector.detect(bookings);

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).categoryId());
    }

    @Test
    void attachedTermUsesStartOfLastTermAsLastSeen() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("MAINOVA", "-69.00", LocalDate.of(2025, 5, 2), 6));
        bookings.add(booking("MAINOVA", "-88.00", LocalDate.of(2025, 11, 3)));
        bookings.add(booking("MAINOVA", "-88.00", LocalDate.of(2025, 11, 6)));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-88.00", series.expectedAmount());
        assertEquals(LocalDate.of(2025, 11, 3), series.lastSeenDate());
        assertEquals(LocalDate.of(2025, 12, 3), series.nextDueDate());
    }

    @Test
    void settlementBetweenTwoTariffsBridgesThem() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("STADTWERK", "-69.00", LocalDate.of(2024, 5, 2), 6));
        bookings.add(booking("STADTWERK", "-151.67", LocalDate.of(2024, 11, 4)));
        bookings.addAll(monthly("STADTWERK", "-88.00", LocalDate.of(2024, 12, 3), 4));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-88.00", series.expectedAmount());
        assertTrue(containsAmount(series.amounts(), "-69.00"));
        assertTrue(containsAmount(series.amounts(), "-151.67"));
        assertTrue(containsAmount(series.amounts(), "-88.00"));
        assertEquals(LocalDate.of(2024, 5, 2), series.firstSeenDate());
        assertEquals(LocalDate.of(2025, 3, 3), series.lastSeenDate());
        assertEquals(LocalDate.of(2025, 4, 3), series.nextDueDate());
    }

    @Test
    void prorationBetweenTwoTariffsBridgesThem() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("NETZANBIETER", "-29.80", LocalDate.of(2025, 1, 1), 5));
        bookings.add(booking("NETZANBIETER", "-40.45", LocalDate.of(2025, 6, 1)));
        bookings.addAll(monthly("NETZANBIETER", "-84.80", LocalDate.of(2025, 7, 1), 6));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-84.80", series.expectedAmount());
        assertTrue(containsAmount(series.amounts(), "-40.45"));
        assertEquals(LocalDate.of(2025, 1, 1), series.firstSeenDate());
        assertEquals(LocalDate.of(2025, 12, 1), series.lastSeenDate());
    }

    @Test
    void oneOffBetweenTermsIsNotAttached() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("STADTWERK", "-88.00", LocalDate.of(2025, 1, 3), 6));
        bookings.add(booking("STADTWERK", "-151.67", LocalDate.of(2025, 6, 20)));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-88.00", series.expectedAmount());
        assertEquals(LocalDate.of(2025, 6, 3), series.lastSeenDate());
        assertEquals(LocalDate.of(2025, 7, 3), series.nextDueDate());
        assertFalse(containsAmount(series.amounts(), "-151.67"));
    }

    @Test
    void seriesFarApartAreNotMerged() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("VEREIN", "-20.00", LocalDate.of(2024, 1, 5), 4));
        bookings.addAll(monthly("VEREIN", "-30.00", LocalDate.of(2024, 7, 5), 4));

        assertEquals(2, detector.detect(bookings).size());
    }

    @Test
    void bridgeWithTooLargeSecondGapDoesNotMergeOrAttach() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("VEREIN", "-20.00", LocalDate.of(2024, 1, 5), 4));
        bookings.add(booking("VEREIN", "-45.00", LocalDate.of(2024, 5, 6)));
        bookings.addAll(monthly("VEREIN", "-30.00", LocalDate.of(2024, 8, 5), 4));

        List<DetectedSeries> result = sortedByAmount(detector.detect(bookings));

        assertEquals(2, result.size());
        result.forEach(s -> assertFalse(containsAmount(s.amounts(), "-45.00")));
        assertEquals(LocalDate.of(2024, 4, 5), result.get(1).lastSeenDate());
    }

    @Test
    void smallGroupBeforeSeriesIsNotAttached() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.add(booking("FITNESS", "-15.00", LocalDate.of(2025, 1, 10)));
        bookings.addAll(monthly("FITNESS", "-25.00", LocalDate.of(2025, 2, 10), 5));

        DetectedSeries series = single(detector.detect(bookings));

        assertEquals(LocalDate.of(2025, 2, 10), series.firstSeenDate());
        assertFalse(containsAmount(series.amounts(), "-15.00"));
    }

    @Test
    void priceChangeAttachesToSeriesWithNearestAmount() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("DRILLISCH", "-4.99", LocalDate.of(2025, 1, 28), 6));
        bookings.addAll(monthly("DRILLISCH", "-7.99", LocalDate.of(2025, 1, 18), 6));
        bookings.add(booking("DRILLISCH", "-8.50", LocalDate.of(2025, 7, 22)));

        List<DetectedSeries> result = sortedByAmount(detector.detect(bookings));

        assertEquals(2, result.size());
        assertAmount("-8.50", result.get(0).expectedAmount());
        assertAmount("-4.99", result.get(1).expectedAmount());
        assertFalse(containsAmount(result.get(1).amounts(), "-8.50"));
    }

    @Test
    void creepingInstalmentsStayOneSeries() {
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("ABSCHLAG", "-50.00", LocalDate.of(2025, 1, 5), 3));
        bookings.addAll(monthly("ABSCHLAG", "-52.50", LocalDate.of(2025, 4, 5), 3));
        bookings.addAll(monthly("ABSCHLAG", "-55.00", LocalDate.of(2025, 7, 5), 3));
        bookings.addAll(monthly("ABSCHLAG", "-58.00", LocalDate.of(2025, 10, 5), 3));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-58.00", series.expectedAmount());
        assertEquals(LocalDate.of(2025, 1, 5), series.firstSeenDate());
    }

    @Test
    void tooWideAmountChainIsSplitInsteadOfDropped() {
        // 50.00 -> 52.50 -> 55.00 -> 57.50 chains in steps of at most 5 % but spreads 14 %: split, then merged.
        List<RecurringBooking> bookings = new ArrayList<>();
        bookings.addAll(monthly("ABSCHLAG", "-50.00", LocalDate.of(2025, 1, 5), 3));
        bookings.addAll(monthly("ABSCHLAG", "-52.50", LocalDate.of(2025, 4, 5), 3));
        bookings.addAll(monthly("ABSCHLAG", "-55.00", LocalDate.of(2025, 7, 5), 3));
        bookings.addAll(monthly("ABSCHLAG", "-57.50", LocalDate.of(2025, 10, 5), 3));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-57.50", series.expectedAmount());
        assertEquals(LocalDate.of(2025, 1, 5), series.firstSeenDate());
        assertEquals(LocalDate.of(2025, 12, 5), series.lastSeenDate());
    }

    @Test
    void sameDayTieIsBrokenByTransactionId() {
        List<RecurringBooking> bookings = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            bookings.add(new RecurringBooking(LocalDate.of(2025, 1, 10).plusMonths(i),
                    new BigDecimal("-9.99"), "NETFLIX", 3L, (long) i));
        }
        LocalDate lastDay = LocalDate.of(2025, 4, 10);
        bookings.add(new RecurringBooking(lastDay, new BigDecimal("-10.29"), "NETFLIX", 9L, 20L));
        bookings.add(new RecurringBooking(lastDay, new BigDecimal("-9.99"), "NETFLIX", 3L, 10L));

        DetectedSeries series = single(detector.detect(bookings));

        assertAmount("-10.29", series.expectedAmount());
        assertEquals(9L, series.categoryId());
    }

    @Test
    void supermarketCoincidenceIsDropped() {
        List<RecurringBooking> bookings = new ArrayList<>();
        LocalDate day = LocalDate.of(2025, 1, 2);
        for (int i = 0; i < 50; i++) {
            day = day.plusDays(2 + (i * 5) % 9);
            int euros = 10 + (i * 37) % 81;
            if (euros >= 28 && euros <= 38) {
                euros += 15; // keep the coincidental quarterly group its own amount group
            }
            bookings.add(booking("SUPERMARKT", "-" + euros + "." + (i * 13) % 100 / 10 + "0", day));
        }
        LocalDate first = LocalDate.of(2025, 2, 10);
        bookings.add(booking("SUPERMARKT", "-32.68", first));
        bookings.add(booking("SUPERMARKT", "-33.49", first.plusDays(91)));
        bookings.add(booking("SUPERMARKT", "-31.51", first.plusDays(182)));
        bookings.add(booking("SUPERMARKT", "-33.47", first.plusDays(273)));

        assertTrue(detector.detect(bookings).isEmpty());
    }

    private static DetectedSeries single(List<DetectedSeries> result) {
        assertEquals(1, result.size(), "expected one series but was " + result);
        return result.get(0);
    }

    private static List<RecurringBooking> monthly(String key, String amount, LocalDate firstDate, int count) {
        List<RecurringBooking> bookings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bookings.add(booking(key, amount, firstDate.plusMonths(i)));
        }
        return bookings;
    }

    private static RecurringBooking booking(String key, String amount, LocalDate date) {
        return new RecurringBooking(date, new BigDecimal(amount), key, null, null);
    }

    private static List<DetectedSeries> sortedByAmount(List<DetectedSeries> series) {
        List<DetectedSeries> sorted = new ArrayList<>(series);
        sorted.sort(Comparator.comparing(DetectedSeries::expectedAmount));
        return sorted;
    }

    private static boolean containsAmount(List<BigDecimal> amounts, String expected) {
        return amounts.stream().anyMatch(a -> a.compareTo(new BigDecimal(expected)) == 0);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
