package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CategorizationRuleResponse {
    private final Long id;
    private final List<RuleConditionDto> conditions;
    private final Long categoryId;
    private final int priority;
    private final boolean enabled;
    private final int appliedToExistingCount;
}
