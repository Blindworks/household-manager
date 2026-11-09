package com.household.manager.service;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.dto.RuleConditionDto;
import com.household.manager.finance.RuleConditionsValidator;
import com.household.manager.finance.RuleOrdering;
import com.household.manager.finance.RuleMatcher;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.CategorizationRuleCondition;
import com.household.manager.model.entity.Transaction;
import com.household.manager.repository.CategorizationRuleRepository;
import com.household.manager.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

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
        RuleConditionsValidator.validate(request.getConditions());
        CategorizationRule rule = ruleRepository.save(CategorizationRule.builder()
                .conditions(toEntityConditions(request.getConditions()))
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
        RuleConditionsValidator.validate(request.getConditions());
        rule.getConditions().clear();
        rule.getConditions().addAll(toEntityConditions(request.getConditions()));
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
        List<CategorizationRule> rules =
                RuleOrdering.sort(ruleRepository.findByEnabledTrueOrderByPriorityAsc());
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

    static List<CategorizationRuleCondition> toEntityConditions(List<RuleConditionDto> dtos) {
        return new ArrayList<>(IntStream.range(0, dtos.size())
                .mapToObj(i -> CategorizationRuleCondition.builder()
                        .position(i)
                        .matchField(dtos.get(i).getField())
                        .matchType(dtos.get(i).getMatchType())
                        .pattern(dtos.get(i).getPattern())
                        .build())
                .toList());
    }

    private CategorizationRuleResponse toResponse(CategorizationRule r, int applied) {
        return CategorizationRuleResponse.builder()
                .id(r.getId())
                .conditions(r.getConditions().stream()
                        .map(c -> RuleConditionDto.builder()
                                .field(c.getMatchField()).matchType(c.getMatchType())
                                .pattern(c.getPattern()).build())
                        .toList())
                .categoryId(r.getCategoryId())
                .priority(r.getPriority()).enabled(r.isEnabled())
                .appliedToExistingCount(applied)
                .build();
    }
}
