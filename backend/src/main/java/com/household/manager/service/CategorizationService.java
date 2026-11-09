package com.household.manager.service;

import com.household.manager.dto.RuleConditionDto;
import com.household.manager.dto.RuleSuggestion;
import com.household.manager.finance.CounterpartyNameNormalizer;
import com.household.manager.finance.MerchantNameExtractor;
import com.household.manager.finance.PaymentIntermediary;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.finance.RuleOrdering;
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
import java.util.Optional;

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
    private final MerchantNameExtractor merchantNameExtractor;

    /** Returns the category id of the first matching rule in the given order (callers pass RuleOrdering order), or null. */
    public Long findCategory(Transaction tx, List<CategorizationRule> rulesByPriority) {
        for (CategorizationRule rule : rulesByPriority) {
            if (ruleMatcher.matches(rule, tx)) {
                return rule.getCategoryId();
            }
        }
        return null;
    }

    /** Loads enabled rules once, sorted by {@link RuleOrdering} (callers use this before a batch). */
    @Transactional(readOnly = true)
    public List<CategorizationRule> loadActiveRules() {
        return RuleOrdering.sort(ruleRepository.findByEnabledTrueOrderByPriorityAsc());
    }

    /**
     * Build a rule suggestion from a just-corrected transaction, or null if an existing
     * enabled rule would already assign this same category to it.
     */
    @Transactional(readOnly = true)
    public RuleSuggestion suggestRule(Transaction tx, Long categoryId) {
        List<CategorizationRule> active = RuleOrdering.sort(ruleRepository.findByEnabledTrueOrderByPriorityAsc());
        Long alreadyAssigned = findCategory(tx, active);
        if (alreadyAssigned != null && alreadyAssigned.equals(categoryId)) {
            return null;
        }
        Optional<PaymentIntermediary> intermediary = PaymentIntermediary.detect(tx.getCounterpartyName());
        if (intermediary.isPresent()) {
            return intermediarySuggestion(tx, categoryId, intermediary.get());
        }
        String pattern = normalizer.normalize(tx.getCounterpartyName());
        if (pattern.isBlank()) {
            return null;
        }
        return RuleSuggestion.builder()
                .conditions(List.of(condition(RuleMatchField.COUNTERPARTY_NAME, pattern)))
                .categoryId(categoryId)
                .build();
    }

    private RuleSuggestion intermediarySuggestion(Transaction tx, Long categoryId, PaymentIntermediary intermediary) {
        String merchant = merchantNameExtractor.extract(intermediary, tx.getPurpose()).orElse("");
        String hint = merchant.isEmpty()
                ? "Bitte den Händler im Verwendungszweck ergänzen – sonst trifft die Regel alle "
                        + intermediary.displayName() + "-Zahlungen."
                : null;
        return RuleSuggestion.builder()
                .conditions(List.of(
                        condition(RuleMatchField.COUNTERPARTY_NAME, PaymentIntermediary.matchedToken(tx.getCounterpartyName())),
                        condition(RuleMatchField.PURPOSE, merchant)))
                .categoryId(categoryId)
                .hint(hint)
                .build();
    }

    private static RuleConditionDto condition(RuleMatchField field, String pattern) {
        return RuleConditionDto.builder().field(field).matchType(RuleMatchType.CONTAINS).pattern(pattern).build();
    }
}
