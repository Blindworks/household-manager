package com.household.manager.service;

import com.household.manager.dto.RuleSuggestion;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.CategorizationRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Applies categorization rules and proposes new rules after manual corrections.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategorizationService {

    private final CategorizationRuleRepository ruleRepository;
    private final RuleMatcher ruleMatcher;
    private final CounterpartyNameNormalizer normalizer;

    /** Returns the category id of the first matching rule (by priority), or null. */
    public Long findCategory(Transaction tx, List<CategorizationRule> rulesByPriority) {
        for (CategorizationRule rule : rulesByPriority) {
            if (ruleMatcher.matches(rule, tx)) {
                return rule.getCategoryId();
            }
        }
        return null;
    }

    /** Loads enabled rules once (callers use this before a batch). */
    @Transactional(readOnly = true)
    public List<CategorizationRule> loadActiveRules() {
        return ruleRepository.findByEnabledTrueOrderByPriorityAsc();
    }

    /**
     * Build a rule suggestion from a just-corrected transaction, or null if an existing
     * enabled rule would already assign this same category to it.
     */
    @Transactional(readOnly = true)
    public RuleSuggestion suggestRule(Transaction tx, Long categoryId) {
        List<CategorizationRule> active = ruleRepository.findByEnabledTrueOrderByPriorityAsc();
        Long alreadyAssigned = findCategory(tx, active);
        if (alreadyAssigned != null && alreadyAssigned.equals(categoryId)) {
            return null;
        }
        String pattern = normalizer.normalize(tx.getCounterpartyName());
        if (pattern.isBlank()) {
            return null;
        }
        return RuleSuggestion.builder()
                .field(RuleMatchField.COUNTERPARTY_NAME)
                .matchType(RuleMatchType.CONTAINS)
                .pattern(pattern)
                .categoryId(categoryId)
                .build();
    }
}
