package com.household.manager.dto;

import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategorizationRuleResponse {
    private final Long id;
    private final RuleMatchField field;
    private final RuleMatchType matchType;
    private final String pattern;
    private final Long categoryId;
    private final int priority;
    private final boolean enabled;
    private final int appliedToExistingCount;
}
