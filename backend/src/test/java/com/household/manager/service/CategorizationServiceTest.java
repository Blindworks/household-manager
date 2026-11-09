package com.household.manager.service;

import com.household.manager.dto.RuleSuggestion;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.*;
import com.household.manager.repository.CategorizationRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

class CategorizationServiceTest {

    private CategorizationRuleRepository ruleRepository;
    private CategorizationService service;

    @BeforeEach
    void setUp() {
        ruleRepository = Mockito.mock(CategorizationRuleRepository.class);
        service = new CategorizationService(ruleRepository, new RuleMatcher(), new CounterpartyNameNormalizer(),
                new com.household.manager.finance.MerchantNameExtractor());
    }

    private CategorizationRule rule(int priority, String pattern, long categoryId) {
        return ruleWith(priority, categoryId, pattern);
    }

    private CategorizationRule ruleWith(int priority, long categoryId, String... patterns) {
        List<CategorizationRuleCondition> conditions = new java.util.ArrayList<>();
        for (int i = 0; i < patterns.length; i++) {
            conditions.add(CategorizationRuleCondition.builder().position(i)
                    .matchField(RuleMatchField.COUNTERPARTY_NAME).matchType(RuleMatchType.CONTAINS)
                    .pattern(patterns[i]).build());
        }
        return CategorizationRule.builder().conditions(conditions)
                .categoryId(categoryId).priority(priority).enabled(true).build();
    }

    @Test
    void firstMatchingRuleByPriorityWins() {
        List<CategorizationRule> rules = List.of(rule(10, "netflix", 5L), rule(20, "net", 9L));
        Transaction tx = Transaction.builder().counterpartyName("NETFLIX").build();
        assertEquals(5L, service.findCategory(tx, rules));
    }

    @Test
    void returnsNullWhenNoRuleMatches() {
        Transaction tx = Transaction.builder().counterpartyName("ALDI").build();
        assertNull(service.findCategory(tx, List.of(rule(10, "netflix", 5L))));
    }

    @Test
    void suggestRuleUsesNormalizedCounterpartyName() {
        Transaction tx = Transaction.builder().counterpartyName("Netflix 12345 01.06.2026").build();
        RuleSuggestion suggestion = service.suggestRule(tx, 5L);
        assertEquals(1, suggestion.getConditions().size());
        assertEquals(RuleMatchField.COUNTERPARTY_NAME, suggestion.getConditions().get(0).getField());
        assertEquals(RuleMatchType.CONTAINS, suggestion.getConditions().get(0).getMatchType());
        assertEquals("NETFLIX", suggestion.getConditions().get(0).getPattern());
        assertEquals(5L, suggestion.getCategoryId());
    }

    @Test
    void suggestRuleReturnsNullWhenAlreadyCoveredByEnabledRule() {
        when(ruleRepository.findByEnabledTrueOrderByPriorityAsc())
                .thenReturn(List.of(rule(10, "netflix", 5L)));
        Transaction tx = Transaction.builder().counterpartyName("NETFLIX").build();
        assertNull(service.suggestRule(tx, 5L),
                "no suggestion when an existing rule already assigns this category");
    }

    @Test
    void loadActiveRulesIsSortedBySpecificity() {
        CategorizationRule single = ruleWith(10, 1L, "a");
        CategorizationRule dual = ruleWith(100, 2L, "a", "b");
        when(ruleRepository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(single, dual));
        assertSame(dual, service.loadActiveRules().get(0));
    }

    private static final String SHELL_PURPOSE =
            "1053795742551/. SHELL DEUTSCHLAND GmbH, Ihr Einkauf bei SHELL DEUTSCHLAND GmbH";

    @Test
    void paypalSuggestionHasCounterpartyAndPurposeCondition() {
        Transaction tx = Transaction.builder().counterpartyName("PayPal Europe S.a.r.l. et Cie S.C.A")
                .purpose(SHELL_PURPOSE).build();
        RuleSuggestion s = service.suggestRule(tx, 5L);
        assertEquals(2, s.getConditions().size());
        assertEquals(RuleMatchField.COUNTERPARTY_NAME, s.getConditions().get(0).getField());
        assertEquals(RuleMatchType.CONTAINS, s.getConditions().get(0).getMatchType());
        assertEquals("PAYPAL", s.getConditions().get(0).getPattern());
        assertEquals(RuleMatchField.PURPOSE, s.getConditions().get(1).getField());
        assertEquals(RuleMatchType.CONTAINS, s.getConditions().get(1).getMatchType());
        assertEquals("SHELL DEUTSCHLAND", s.getConditions().get(1).getPattern());
        assertNull(s.getHint());
    }

    @Test
    void paypalWithoutMerchantPhraseLeavesPurposePatternEmpty() {
        Transaction tx = Transaction.builder().counterpartyName("PayPal Europe S.a.r.l. et Cie S.C.A")
                .purpose("Referenz 4711").build();
        RuleSuggestion s = service.suggestRule(tx, 5L);
        assertEquals("", s.getConditions().get(1).getPattern());
        assertTrue(s.getHint().startsWith("Bitte den Händler"));
    }

    @Test
    void amzSuggestionUsesMatchedToken() {
        Transaction tx = Transaction.builder().counterpartyName("AMZN Mktp DE").purpose("Bestellung").build();
        assertEquals("AMZN", service.suggestRule(tx, 5L).getConditions().get(0).getPattern());
    }
}
