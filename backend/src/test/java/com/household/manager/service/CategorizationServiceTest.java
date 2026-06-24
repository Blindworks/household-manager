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
        service = new CategorizationService(ruleRepository, new RuleMatcher(), new CounterpartyNameNormalizer());
    }

    private CategorizationRule rule(int priority, String pattern, long categoryId) {
        return CategorizationRule.builder()
                .matchField(RuleMatchField.COUNTERPARTY_NAME).matchType(RuleMatchType.CONTAINS)
                .pattern(pattern).categoryId(categoryId).priority(priority).enabled(true).build();
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
        assertEquals(RuleMatchField.COUNTERPARTY_NAME, suggestion.getField());
        assertEquals(RuleMatchType.CONTAINS, suggestion.getMatchType());
        assertEquals("NETFLIX", suggestion.getPattern());
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
}
