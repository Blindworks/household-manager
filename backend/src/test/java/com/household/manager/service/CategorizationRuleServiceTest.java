package com.household.manager.service;

import com.household.manager.dto.CategorizationRuleResponse;
import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.CategorizationRuleCondition;
import com.household.manager.repository.CategorizationRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CategorizationRuleServiceTest {

    private static CategorizationRule rule(long id, int priority) {
        CategorizationRule r = CategorizationRule.builder().categoryId(1L).priority(priority).enabled(true)
                .conditions(new java.util.ArrayList<CategorizationRuleCondition>()).build();
        r.setId(id);
        return r;
    }

    @Test
    void getAllReturnsRulesInEvaluationOrder() {
        CategorizationRuleRepository repo = mock(CategorizationRuleRepository.class);
        when(repo.findAll()).thenReturn(List.of(rule(1, 200), rule(2, 10)));
        CategorizationRuleService service = new CategorizationRuleService(repo, mock(RuleReevaluationService.class));

        List<Long> ids = service.getAll().stream().map(CategorizationRuleResponse::getId).toList();

        assertEquals(List.of(2L, 1L), ids);
    }
}
