package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.CategorizationRuleCondition;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleOrderingTest {

    private CategorizationRule rule(long id, int priority, int conditionCount) {
        List<CategorizationRuleCondition> conditions = new ArrayList<>();
        for (int i = 0; i < conditionCount; i++) {
            conditions.add(CategorizationRuleCondition.builder().position(i)
                    .matchField(RuleMatchField.PURPOSE).matchType(RuleMatchType.CONTAINS)
                    .pattern("p" + i).build());
        }
        return CategorizationRule.builder().id(id).priority(priority).categoryId(1L)
                .enabled(true).conditions(conditions).build();
    }

    @Test
    void moreConditionsBeforePriorityThenId() {
        CategorizationRule a = rule(1, 10, 1);
        CategorizationRule b = rule(2, 100, 2);
        CategorizationRule c = rule(3, 50, 2);
        CategorizationRule d = rule(4, 50, 2);

        assertEquals(List.of(c, d, b, a), RuleOrdering.sort(List.of(a, b, c, d)));
    }
}
