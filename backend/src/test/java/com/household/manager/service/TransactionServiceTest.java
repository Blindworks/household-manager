package com.household.manager.service;

import com.household.manager.dto.CategorizeResponse;
import com.household.manager.dto.RuleSuggestion;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

    private TransactionRepository transactionRepository;
    private CategorizationService categorizationService;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        transactionRepository = Mockito.mock(TransactionRepository.class);
        categorizationService = Mockito.mock(CategorizationService.class);
        service = new TransactionService(transactionRepository, categorizationService);
    }

    @Test
    void categorizeMarksManualAndReturnsSuggestion() {
        Transaction tx = Transaction.builder().id(1L).counterpartyName("NETFLIX").build();
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));
        when(categorizationService.suggestRule(any(Transaction.class), Mockito.eq(5L)))
                .thenReturn(RuleSuggestion.builder()
                        .field(RuleMatchField.COUNTERPARTY_NAME).pattern("NETFLIX").categoryId(5L).build());

        CategorizeResponse response = service.categorize(1L, 5L);

        assertTrue(response.getTransaction().isManuallyCategorized());
        assertEquals(5L, response.getTransaction().getCategoryId());
        assertNotNull(response.getRuleSuggestion());
        assertEquals("NETFLIX", response.getRuleSuggestion().getPattern());
    }
}
