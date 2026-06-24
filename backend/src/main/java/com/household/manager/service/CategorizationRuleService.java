package com.household.manager.service;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.CategorizationRuleRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategorizationRuleService {

    private final CategorizationRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final RuleMatcher ruleMatcher;

    @Transactional(readOnly = true)
    public List<CategorizationRuleResponse> getAll() {
        return ruleRepository.findAll().stream().map(r -> toResponse(r, 0)).toList();
    }

    @Transactional
    public CategorizationRuleResponse create(CategorizationRuleRequest request) {
        CategorizationRule rule = ruleRepository.save(CategorizationRule.builder()
                .matchField(request.getField())
                .matchType(request.getMatchType())
                .pattern(request.getPattern())
                .categoryId(request.getCategoryId())
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build());

        int applied = 0;
        if (request.isApplyToExisting()) {
            applied = applyRuleToUncategorized(rule);
        }
        return toResponse(rule, applied);
    }

    @Transactional
    public CategorizationRuleResponse update(Long id, CategorizationRuleRequest request) {
        CategorizationRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule id: " + id));
        rule.setMatchField(request.getField());
        rule.setMatchType(request.getMatchType());
        rule.setPattern(request.getPattern());
        rule.setCategoryId(request.getCategoryId());
        if (request.getPriority() != null) rule.setPriority(request.getPriority());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        return toResponse(ruleRepository.save(rule), 0);
    }

    @Transactional
    public void delete(Long id) {
        ruleRepository.deleteById(id);
    }

    /** Apply all enabled rules to transactions that have no category and were not set by hand. */
    @Transactional
    public int applyAllToUncategorized() {
        List<CategorizationRule> rules = ruleRepository.findByEnabledTrueOrderByPriorityAsc();
        List<Transaction> targets =
                transactionRepository.findByCategoryIdIsNullAndManuallyCategorizedFalse();
        int count = 0;
        for (Transaction tx : targets) {
            for (CategorizationRule rule : rules) {
                if (ruleMatcher.matches(rule, tx)) {
                    tx.setCategoryId(rule.getCategoryId());
                    transactionRepository.save(tx);
                    count++;
                    break;
                }
            }
        }
        log.info("Applied rules to {} previously uncategorized transactions", count);
        return count;
    }

    private int applyRuleToUncategorized(CategorizationRule rule) {
        List<Transaction> targets =
                transactionRepository.findByCategoryIdIsNullAndManuallyCategorizedFalse();
        int count = 0;
        for (Transaction tx : targets) {
            if (ruleMatcher.matches(rule, tx)) {
                tx.setCategoryId(rule.getCategoryId());
                transactionRepository.save(tx);
                count++;
            }
        }
        return count;
    }

    private CategorizationRuleResponse toResponse(CategorizationRule r, int applied) {
        return CategorizationRuleResponse.builder()
                .id(r.getId()).field(r.getMatchField()).matchType(r.getMatchType())
                .pattern(r.getPattern()).categoryId(r.getCategoryId())
                .priority(r.getPriority()).enabled(r.isEnabled())
                .appliedToExistingCount(applied)
                .build();
    }
}
