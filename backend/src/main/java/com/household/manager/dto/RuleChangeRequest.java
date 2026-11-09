package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A hypothetical change to the rule set whose effect on existing transactions is previewed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleChangeRequest {
    @NotNull private RuleChangeType type;
    private Long ruleId;
    private CategorizationRuleRequest rule;
}
