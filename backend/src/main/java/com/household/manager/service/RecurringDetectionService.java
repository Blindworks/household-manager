package com.household.manager.service;

import com.household.manager.dto.RecurringDetectionResponse;
import com.household.manager.dto.RecurringPaymentResponse;
import com.household.manager.finance.DetectedSeries;
import com.household.manager.finance.RecurrenceAnalyzer;
import com.household.manager.finance.RecurringBooking;
import com.household.manager.finance.RecurringMerchantKey;
import com.household.manager.finance.RecurringSeriesDetector;
import com.household.manager.model.entity.RecurringPayment;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.RecurringPaymentRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scans an account's debits for recurring payments and keeps {@code recurring_payments} in step:
 * a detected series continues its stored row (also a dismissed one, so a price increase still finds
 * it) or becomes a new candidate. "Ended" is not stored but computed when reading.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringDetectionService {

    private static final BigDecimal MATCH_TOLERANCE = new BigDecimal("0.10");
    private static final double ENDED_AFTER_INTERVALS = 1.5;
    private static final int LOOKBACK_YEARS = 2;

    private final TransactionRepository transactionRepository;
    private final RecurringPaymentRepository recurringRepository;
    private final RecurringMerchantKey merchantKey;
    private final RecurringSeriesDetector detector;
    private final Clock clock;

    /**
     * Detects recurring series for an account and persists them. Every matched stored row counts as
     * updated, whether or not a value actually changed.
     */
    @Transactional
    public RecurringDetectionResponse detect(Long accountId) {
        List<DetectedSeries> detected = detector.detect(loadBookings(accountId));
        List<RecurringPayment> unmatched = new ArrayList<>(recurringRepository.findByAccountId(accountId));

        int created = 0;
        int updated = 0;
        for (DetectedSeries series : detected) {
            Optional<RecurringPayment> match = findMatch(series, unmatched);
            if (match.isPresent()) {
                unmatched.remove(match.get());
                recurringRepository.save(continueRow(match.get(), series));
                updated++;
            } else {
                recurringRepository.save(newCandidate(accountId, series));
                created++;
            }
        }
        log.info("Recurring detection for account {}: {} created, {} updated", accountId, created, updated);
        return RecurringDetectionResponse.builder().created(created).updated(updated).build();
    }

    /** Lists non-dismissed rows: active before ended, then by next due date (null last). */
    @Transactional(readOnly = true)
    public List<RecurringPaymentResponse> list(Boolean confirmed) {
        List<RecurringPayment> items = (confirmed == null)
                ? recurringRepository.findByDismissedFalse()
                : recurringRepository.findByConfirmedAndDismissedFalse(confirmed);
        LocalDate today = LocalDate.now(clock);
        return items.stream()
                .map(rp -> toResponse(rp, today))
                .sorted(Comparator.comparing(RecurringPaymentResponse::isEnded)
                        .thenComparing(RecurringPaymentResponse::getNextDueDate,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional
    public RecurringPaymentResponse confirm(Long id) {
        RecurringPayment rp = findOrThrow(id);
        rp.setConfirmed(true);
        return toResponse(recurringRepository.save(rp), LocalDate.now(clock));
    }

    /** Marks a row as dismissed instead of deleting it, so later runs don't recreate it. */
    @Transactional
    public void dismiss(Long id) {
        RecurringPayment rp = findOrThrow(id);
        rp.setDismissed(true);
        recurringRepository.save(rp);
    }

    private List<RecurringBooking> loadBookings(Long accountId) {
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusYears(LOOKBACK_YEARS);
        List<RecurringBooking> bookings = new ArrayList<>();
        for (Transaction tx : transactionRepository
                .findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(accountId, from, to)) {
            if (tx.getAmount() == null || tx.getAmount().signum() >= 0) {
                continue; // expenses only
            }
            merchantKey.keyFor(tx.getCounterpartyName(), tx.getPurpose()).ifPresent(key ->
                    bookings.add(new RecurringBooking(tx.getBookingDate(), tx.getAmount(), key, tx.getCategoryId())));
        }
        return bookings;
    }

    /**
     * Same key and interval, and the stored amount within 10 % of any amount of the series; among
     * several candidates the one closest to the series' current amount wins.
     */
    private Optional<RecurringPayment> findMatch(DetectedSeries series, List<RecurringPayment> candidates) {
        BigDecimal current = series.expectedAmount().abs();
        return candidates.stream()
                .filter(rp -> Objects.equals(rp.getCounterpartyPattern(), series.merchantKey()))
                .filter(rp -> rp.getInterval() == series.interval())
                .filter(rp -> rp.getExpectedAmount() != null)
                .filter(rp -> withinToleranceOfAny(rp.getExpectedAmount().abs(), series.amounts()))
                .min(Comparator.comparing(rp -> rp.getExpectedAmount().abs().subtract(current).abs()));
    }

    private static boolean withinToleranceOfAny(BigDecimal stored, List<BigDecimal> amounts) {
        return amounts.stream().map(BigDecimal::abs).anyMatch(a ->
                stored.subtract(a).abs().compareTo(a.multiply(MATCH_TOLERANCE)) <= 0);
    }

    /** Continues a stored row; confirmed and dismissed stay as they are. */
    private static RecurringPayment continueRow(RecurringPayment rp, DetectedSeries series) {
        rp.setExpectedAmount(series.expectedAmount());
        rp.setNextDueDate(series.nextDueDate());
        rp.setLastSeenDate(series.lastSeenDate());
        if (series.categoryId() != null) {
            rp.setCategoryId(series.categoryId());
        }
        return rp;
    }

    private static RecurringPayment newCandidate(Long accountId, DetectedSeries series) {
        return RecurringPayment.builder()
                .accountId(accountId)
                .counterpartyPattern(series.merchantKey())
                .categoryId(series.categoryId())
                .expectedAmount(series.expectedAmount())
                .interval(series.interval())
                .nextDueDate(series.nextDueDate())
                .lastSeenDate(series.lastSeenDate())
                .confirmed(false)
                .dismissed(false)
                .build();
    }

    private RecurringPayment findOrThrow(Long id) {
        return recurringRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown recurring id: " + id));
    }

    private static boolean isEnded(RecurringPayment rp, LocalDate today) {
        if (rp.getLastSeenDate() == null || rp.getInterval() == null) {
            return false;
        }
        long graceDays = Math.round(ENDED_AFTER_INTERVALS * RecurrenceAnalyzer.intervalDays(rp.getInterval()));
        return today.isAfter(rp.getLastSeenDate().plusDays(graceDays));
    }

    private static RecurringPaymentResponse toResponse(RecurringPayment rp, LocalDate today) {
        return RecurringPaymentResponse.builder()
                .id(rp.getId()).accountId(rp.getAccountId())
                .counterpartyPattern(rp.getCounterpartyPattern())
                .categoryId(rp.getCategoryId()).expectedAmount(rp.getExpectedAmount())
                .interval(rp.getInterval()).nextDueDate(rp.getNextDueDate())
                .lastSeenDate(rp.getLastSeenDate())
                .confirmed(rp.isConfirmed())
                .ended(isEnded(rp, today))
                .build();
    }
}
