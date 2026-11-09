package com.household.manager.service;

import com.household.manager.dto.*;
import com.household.manager.finance.PaymentIntermediary;
import com.household.manager.finance.RuleConditionsValidator;
import com.household.manager.finance.RuleOrdering;
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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Re-evaluates all non-manual transactions against a (hypothetical or stored) rule set. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleReevaluationService {

    static final int MAX_PREVIEW_ROWS = 200;
    private static final int DEFAULT_PRIORITY = 100;

    private final CategorizationRuleRepository ruleRepository;
    private final TransactionRepository transactionRepository;
    private final CategorizationService categorizationService;

    @Transactional(readOnly = true)
    public RuleReevaluationPreview preview(RuleChangeRequest change) {
        if (change == null || change.getType() == null) {
            throw new IllegalArgumentException("Aenderungstyp fehlt.");
        }
        List<CategorizationRule> active = RuleOrdering.sort(
                hypotheticalRules(change).stream().filter(CategorizationRule::isEnabled).toList());

        List<RuleReevaluationRow> changes = new ArrayList<>();
        for (Transaction tx : transactionRepository.findByManuallyCategorizedFalse()) {
            Long newCategory = categorizationService.findCategory(tx, active);
            if (!Objects.equals(tx.getCategoryId(), newCategory)) {
                changes.add(RuleReevaluationRow.builder()
                        .transactionId(tx.getId()).bookingDate(tx.getBookingDate())
                        .counterpartyName(tx.getCounterpartyName()).purpose(tx.getPurpose())
                        .amount(tx.getAmount()).oldCategoryId(tx.getCategoryId())
                        .newCategoryId(newCategory).build());
            }
        }
        List<RuleReevaluationRow> rows = changes.stream()
                .sorted(Comparator.comparing(RuleReevaluationRow::getBookingDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_PREVIEW_ROWS)
                .toList();

        boolean carriesRule = change.getType() == RuleChangeType.CREATE || change.getType() == RuleChangeType.UPDATE;
        String warning = carriesRule ? PaymentIntermediary.broadRuleWarning(change.getRule().getConditions()) : null;
        return RuleReevaluationPreview.builder()
                .totalChanges(changes.size()).rows(rows).warning(warning).build();
    }

    /** Applies the stored active rule set to all non-manual transactions; returns the changed count. */
    @Transactional
    public int applyAll() {
        List<CategorizationRule> active = categorizationService.loadActiveRules();
        int count = 0;
        for (Transaction tx : transactionRepository.findByManuallyCategorizedFalse()) {
            Long newCategory = categorizationService.findCategory(tx, active);
            if (!Objects.equals(tx.getCategoryId(), newCategory)) {
                tx.setCategoryId(newCategory);
                transactionRepository.save(tx);
                count++;
            }
        }
        log.info("Re-evaluated rules: {} transactions changed", count);
        return count;
    }

    /** Detached copies only: managed entities must never be touched, JPA would flush them. */
    private List<CategorizationRule> hypotheticalRules(RuleChangeRequest change) {
        RuleChangeType type = change.getType();
        boolean targetsExisting = type == RuleChangeType.UPDATE || type == RuleChangeType.DELETE;
        List<CategorizationRule> rules = new ArrayList<>();
        boolean found = false;
        for (CategorizationRule stored : ruleRepository.findAll()) {
            if (targetsExisting && Objects.equals(stored.getId(), change.getRuleId())) {
                found = true;
                if (type == RuleChangeType.UPDATE) {
                    rules.add(fromRequest(stored.getId(), change.getRule(), stored));
                }
            } else {
                rules.add(copy(stored));
            }
        }
        if (targetsExisting && !found) {
            throw new IllegalArgumentException("Unknown rule id: " + change.getRuleId());
        }
        if (type == RuleChangeType.CREATE) {
            rules.add(fromRequest(null, change.getRule(), null));
        }
        return rules;
    }

    private CategorizationRule fromRequest(Long id, CategorizationRuleRequest request,
                                         CategorizationRule stored) {
        if (request == null) {
            throw new IllegalArgumentException("Regel fehlt.");
        }
        RuleConditionsValidator.validate(request.getConditions());
        if (request.getCategoryId() == null) {
            throw new IllegalArgumentException("Kategorie fehlt.");
        }
        return CategorizationRule.builder()
                .id(id)
                .conditions(CategorizationRuleService.toEntityConditions(request.getConditions()))
                .categoryId(request.getCategoryId())
                .priority(request.getPriority() != null ? request.getPriority()
                        : stored != null ? stored.getPriority() : DEFAULT_PRIORITY)
                .enabled(request.getEnabled() != null ? request.getEnabled()
                        : stored == null || stored.isEnabled())
                .build();
    }

    private CategorizationRule copy(CategorizationRule source) {
        List<CategorizationRuleCondition> conditions = new ArrayList<>();
        for (CategorizationRuleCondition c : source.getConditions()) {
            conditions.add(CategorizationRuleCondition.builder().position(c.getPosition())
                    .matchField(c.getMatchField()).matchType(c.getMatchType()).pattern(c.getPattern()).build());
        }
        return CategorizationRule.builder().id(source.getId()).conditions(conditions)
                .categoryId(source.getCategoryId()).priority(source.getPriority())
                .enabled(source.isEnabled()).build();
    }
}
