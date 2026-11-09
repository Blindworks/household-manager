package com.household.manager.service;

import com.household.manager.dto.CategorizationRuleRequest;
import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.dto.RuleConditionDto;
import com.household.manager.finance.RuleConditionsValidator;
import com.household.manager.finance.RuleOrdering;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.CategorizationRuleCondition;
import com.household.manager.repository.CategorizationRuleRepository;
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
    private final RuleReevaluationService reevaluationService;

    @Transactional(readOnly = true)
    public List<CategorizationRuleResponse> getAll() {
        return RuleOrdering.sort(ruleRepository.findAll()).stream().map(r -> toResponse(r, 0)).toList();
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
            applied = reevaluationService.applyAll();
        }
        return toResponse(rule, applied);
    }

    @Transactional
    public CategorizationRuleResponse update(Long id, CategorizationRuleRequest request) {
        RuleConditionsValidator.validate(request.getConditions());
        CategorizationRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown rule id: " + id));
        rule.getConditions().clear();
        rule.getConditions().addAll(toEntityConditions(request.getConditions()));
        rule.setCategoryId(request.getCategoryId());
        if (request.getPriority() != null) rule.setPriority(request.getPriority());
        if (request.getEnabled() != null) rule.setEnabled(request.getEnabled());
        CategorizationRule saved = ruleRepository.save(rule);
        int applied = request.isApplyToExisting() ? reevaluationService.applyAll() : 0;
        return toResponse(saved, applied);
    }

    /** Deletes the rule; with {@code apply} the remaining rules are re-applied to existing transactions. */
    @Transactional
    public int delete(Long id, boolean apply) {
        ruleRepository.deleteById(id);
        return apply ? reevaluationService.applyAll() : 0;
    }

    @Transactional
    public int applyAll() {
        return reevaluationService.applyAll();
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
