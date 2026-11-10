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
            bookings.add(new RecurringBooking(date, amount.negate(), "PAYPAL:SHELL DEUTSCHLAND", null));
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
        List<RecurringBooking> bookings = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BigDecimal amount = new BigDecimal("45.00").add(new BigDecimal("1.36").multiply(BigDecimal.valueOf(i)));
            bookings.add(new RecurringBooking(LocalDate.of(2025, 1, 15).plusMonths(i), amount.negate(),
                    "SUPERMARKT", null));
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
                    new BigDecimal("-9.99"), "NETFLIX", 3L));
        }
        bookings.add(new RecurringBooking(LocalDate.of(2025, 5, 10), new BigDecimal("-9.99"), "NETFLIX", 7L));

        List<DetectedSeries> result = detector.detect(bookings);

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).categoryId());
    }

    private static List<RecurringBooking> monthly(String key, String amount, LocalDate firstDate, int count) {
        List<RecurringBooking> bookings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bookings.add(booking(key, amount, firstDate.plusMonths(i)));
        }
        return bookings;
    }

    private static RecurringBooking booking(String key, String amount, LocalDate date) {
        return new RecurringBooking(date, new BigDecimal(amount), key, null);
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
