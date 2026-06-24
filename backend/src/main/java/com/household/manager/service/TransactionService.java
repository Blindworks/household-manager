package com.household.manager.service;

import com.household.manager.dto.CategorizeResponse;
import com.household.manager.dto.RuleSuggestion;
import com.household.manager.dto.TransactionResponse;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    /** List transactions in a date range, optionally filtered by account, ordered newest first. */
    @Transactional(readOnly = true)
    public List<TransactionResponse> list(Long accountId, LocalDate from, LocalDate to) {
        List<Transaction> txs = (accountId == null)
                ? transactionRepository.findByBookingDateBetweenOrderByBookingDateDesc(from, to)
                : transactionRepository.findByAccountIdAndBookingDateBetweenOrderByBookingDateDesc(
                        accountId, from, to);
        return txs.stream().map(this::toResponse).toList();
    }

    /** Set a category manually and return an optional rule suggestion. */
    @Transactional
    public CategorizeResponse categorize(Long transactionId, Long categoryId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown transaction id: " + transactionId));
        tx.setCategoryId(categoryId);
        tx.setManuallyCategorized(true);
        Transaction saved = transactionRepository.save(tx);

        RuleSuggestion suggestion = categorizationService.suggestRule(saved, categoryId);
        return CategorizeResponse.builder()
                .transaction(toResponse(saved))
                .ruleSuggestion(suggestion)
                .build();
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId()).accountId(t.getAccountId())
                .bookingDate(t.getBookingDate()).valueDate(t.getValueDate())
                .amount(t.getAmount()).currency(t.getCurrency())
                .counterpartyName(t.getCounterpartyName()).counterpartyIban(t.getCounterpartyIban())
                .purpose(t.getPurpose()).categoryId(t.getCategoryId())
                .manuallyCategorized(t.isManuallyCategorized())
                .build();
    }
}
