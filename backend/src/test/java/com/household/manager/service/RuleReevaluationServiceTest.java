package com.household.manager.service;

import com.household.manager.dto.*;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.*;
import com.household.manager.repository.CategorizationRuleRepository;
import com.household.manager.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuleReevaluationServiceTest {

    private CategorizationRuleRepository ruleRepository;
    private TransactionRepository transactionRepository;
    private RuleReevaluationService service;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(CategorizationRuleRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        CategorizationService categorizationService =
                new CategorizationService(ruleRepository, new RuleMatcher(), new CounterpartyNameNormalizer());
        service = new RuleReevaluationService(ruleRepository, transactionRepository, categorizationService);
    }

    private static RuleConditionDto dto(RuleMatchField f, String p) {
        return RuleConditionDto.builder().field(f).matchType(RuleMatchType.CONTAINS).pattern(p).build();
    }

    private static CategorizationRule rule(Long id, long cat, boolean enabled, RuleConditionDto... conds) {
        List<CategorizationRuleCondition> list = new ArrayList<>();
        for (int i = 0; i < conds.length; i++) {
            list.add(CategorizationRuleCondition.builder().position(i).matchField(conds[i].getField())
                    .matchType(conds[i].getMatchType()).pattern(conds[i].getPattern()).build());
        }
        return CategorizationRule.builder().id(id).conditions(list).categoryId(cat).priority(100)
                .enabled(enabled).build();
    }

    private static Transaction tx(long id, String name, String purpose, Long cat, LocalDate date) {
        return Transaction.builder().id(id).counterpartyName(name).purpose(purpose).categoryId(cat)
                .bookingDate(date).amount(new BigDecimal("-10.00")).build();
    }

    private static CategorizationRuleRequest request(long cat, Boolean enabled, RuleConditionDto... conds) {
        return CategorizationRuleRequest.builder().conditions(List.of(conds)).categoryId(cat)
                .enabled(enabled).build();
    }

    private static RuleChangeRequest change(RuleChangeType type, Long ruleId, CategorizationRuleRequest r) {
        return RuleChangeRequest.builder().type(type).ruleId(ruleId).rule(r).build();
    }

    private void givenRules(CategorizationRule... rules) {
        when(ruleRepository.findAll()).thenReturn(List.of(rules));
        when(ruleRepository.findByEnabledTrueOrderByPriorityAsc())
                .thenReturn(List.of(rules).stream().filter(CategorizationRule::isEnabled).toList());
    }

    @Test
    void previewCreateMovesPaypalShellToNewCategory() {
        givenRules(rule(1L, 7, true, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal")));
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(List.of(
                tx(1, "PayPal Europe", "Shell Deutschland 123", 7L, LocalDate.of(2026, 1, 1)),
                tx(2, "PayPal Europe", "Spotify", 7L, LocalDate.of(2026, 1, 2))));

        RuleReevaluationPreview p = service.preview(change(RuleChangeType.CREATE, null, request(9, null,
                dto(RuleMatchField.COUNTERPARTY_NAME, "paypal"),
                dto(RuleMatchField.PURPOSE, "shell deutschland"))));

        assertEquals(1, p.getTotalChanges());
        assertEquals(1L, p.getRows().get(0).getTransactionId());
        assertEquals(7L, p.getRows().get(0).getOldCategoryId());
        assertEquals(9L, p.getRows().get(0).getNewCategoryId());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void previewDeleteShowsTransactionsLosingTheirCategory() {
        givenRules(rule(1L, 7, true, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal")));
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(List.of(
                tx(1, "PayPal", "x", 7L, LocalDate.of(2026, 1, 1))));

        RuleReevaluationPreview p = service.preview(change(RuleChangeType.DELETE, 1L, null));

        assertEquals(1, p.getTotalChanges());
        assertEquals(7L, p.getRows().get(0).getOldCategoryId());
        assertNull(p.getRows().get(0).getNewCategoryId());
    }

    @Test
    void previewIgnoresDisabledRulesIncludingTheEditedOne() {
        givenRules(rule(1L, 7, true, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal")));
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(List.of(
                tx(1, "PayPal", "x", 7L, LocalDate.of(2026, 1, 1))));

        RuleReevaluationPreview p = service.preview(change(RuleChangeType.UPDATE, 1L,
                request(7, false, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal"))));

        assertEquals(1, p.getTotalChanges());
        assertNull(p.getRows().get(0).getNewCategoryId());
    }

    @Test
    void previewUpdateDoesNotMutateManagedEntities() {
        CategorizationRule existing = rule(1L, 7, true, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal"));
        givenRules(existing);
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(List.of());

        service.preview(change(RuleChangeType.UPDATE, 1L, request(9, false,
                dto(RuleMatchField.PURPOSE, "other"), dto(RuleMatchField.COUNTERPARTY_NAME, "b"))));

        assertEquals(7L, existing.getCategoryId());
        assertTrue(existing.isEnabled());
        assertEquals(1, existing.getConditions().size());
        assertEquals("paypal", existing.getConditions().get(0).getPattern());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void previewCapsRowsAt200NewestFirst() {
        givenRules(rule(1L, 7, true, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal")));
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            txs.add(tx(i, "PayPal", "x", 7L, LocalDate.of(2026, 1, 1).plusDays(i)));
        }
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(txs);

        RuleReevaluationPreview p = service.preview(change(RuleChangeType.DELETE, 1L, null));

        assertEquals(250, p.getTotalChanges());
        assertEquals(200, p.getRows().size());
        assertEquals(LocalDate.of(2026, 1, 1).plusDays(249), p.getRows().get(0).getBookingDate());
    }

    @Test
    void previewCarriesBroadRuleWarning() {
        givenRules();
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(List.of());

        assertEquals("Diese Regel trifft alle PayPal-Zahlungen.", service.preview(change(RuleChangeType.CREATE,
                null, request(9, null, dto(RuleMatchField.COUNTERPARTY_NAME, "PAYPAL")))).getWarning());
        assertNull(service.preview(change(RuleChangeType.CREATE, null, request(9, null,
                dto(RuleMatchField.COUNTERPARTY_NAME, "PAYPAL"),
                dto(RuleMatchField.PURPOSE, "shell")))).getWarning());
    }

    @Test
    void previewRejectsInvalidChange() {
        givenRules();
        assertThrows(IllegalArgumentException.class, () -> service.preview(change(RuleChangeType.CREATE, null,
                request(9, null, dto(RuleMatchField.COUNTERPARTY_NAME, "")))));
        assertThrows(IllegalArgumentException.class, () -> service.preview(change(RuleChangeType.UPDATE, 99L,
                request(9, null, dto(RuleMatchField.COUNTERPARTY_NAME, "a")))));
        assertThrows(IllegalArgumentException.class, () -> service.preview(change(RuleChangeType.DELETE, 99L, null)));
    }

    @Test
    void applyAllSavesOnlyChangedTransactions() {
        givenRules(rule(1L, 7, true, dto(RuleMatchField.COUNTERPARTY_NAME, "paypal")));
        when(transactionRepository.findByManuallyCategorizedFalse()).thenReturn(List.of(
                tx(1, "PayPal", "x", 7L, LocalDate.of(2026, 1, 1)),
                tx(2, "PayPal", "x", null, LocalDate.of(2026, 1, 1)),
                tx(3, "REWE", "x", null, LocalDate.of(2026, 1, 1))));

        assertEquals(1, service.applyAll());
        verify(transactionRepository, times(1)).save(any());
    }
}
