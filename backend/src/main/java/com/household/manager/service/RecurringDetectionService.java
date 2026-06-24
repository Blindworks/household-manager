package com.household.manager.service;

import com.household.manager.dto.RecurringPaymentResponse;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RecurrenceAnalyzer;
import com.household.manager.finance.RecurrenceResult;
import com.household.manager.model.entity.RecurringPayment;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.RecurringPaymentRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scans an account's expense transactions for regular patterns and stores unconfirmed
 * recurring-payment candidates for the user to confirm.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringDetectionService {

    private final TransactionRepository transactionRepository;
    private final RecurringPaymentRepository recurringRepository;
    private final CounterpartyNameNormalizer normalizer;
    private final RecurrenceAnalyzer analyzer;

    /** Detect recurring candidates for an account; returns the newly created candidates. */
    @Transactional
    public List<RecurringPaymentResponse> detect(Long accountId) {
        // Look back two years for enough history.
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(2);
        List<Transaction> txs = transactionRepository
                .findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(accountId, from, to)
                .stream()
                .filter(t -> t.getAmount().signum() < 0) // expenses only
                .toList();

        Map<String, List<Transaction>> grouped = txs.stream()
                .collect(Collectors.groupingBy(t -> normalizer.normalize(t.getCounterpartyName())));

        List<RecurringPaymentResponse> created = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.isBlank()) {
                continue;
            }
            List<Transaction> group = entry.getValue();
            List<LocalDate> dates = group.stream().map(Transaction::getBookingDate).toList();
            List<java.math.BigDecimal> amounts = group.stream().map(Transaction::getAmount).toList();

            analyzer.analyze(dates, amounts).ifPresent(result -> {
                if (alreadyKnown(accountId, pattern, result)) {
                    return;
                }
                RecurringPayment saved = recurringRepository.save(RecurringPayment.builder()
                        .accountId(accountId)
                        .counterpartyPattern(pattern)
                        .categoryId(group.get(0).getCategoryId())
                        .expectedAmount(result.getExpectedAmount())
                        .interval(result.getInterval())
                        .nextDueDate(result.getNextDueDate())
                        .confirmed(false)
                        .build());
                created.add(toResponse(saved));
            });
        }
        log.info("Detected {} new recurring candidates for account {}", created.size(), accountId);
        return created;
    }

    @Transactional(readOnly = true)
    public List<RecurringPaymentResponse> list(Boolean confirmed) {
        List<RecurringPayment> items = (confirmed == null)
                ? recurringRepository.findAll()
                : recurringRepository.findByConfirmed(confirmed);
        return items.stream().map(this::toResponse).toList();
    }

    @Transactional
    public RecurringPaymentResponse confirm(Long id) {
        RecurringPayment rp = recurringRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown recurring id: " + id));
        rp.setConfirmed(true);
        return toResponse(recurringRepository.save(rp));
    }

    @Transactional
    public void delete(Long id) {
        recurringRepository.deleteById(id);
    }

    private boolean alreadyKnown(Long accountId, String pattern, RecurrenceResult result) {
        return recurringRepository
                .findByAccountIdAndCounterpartyPatternAndInterval(accountId, pattern, result.getInterval())
                .isPresent();
    }

    private RecurringPaymentResponse toResponse(RecurringPayment rp) {
        return RecurringPaymentResponse.builder()
                .id(rp.getId()).accountId(rp.getAccountId())
                .counterpartyPattern(rp.getCounterpartyPattern())
                .categoryId(rp.getCategoryId()).expectedAmount(rp.getExpectedAmount())
                .interval(rp.getInterval()).nextDueDate(rp.getNextDueDate())
                .confirmed(rp.isConfirmed())
                .build();
    }
}
