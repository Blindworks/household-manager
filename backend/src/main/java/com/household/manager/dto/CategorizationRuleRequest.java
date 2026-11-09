package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizationRuleRequest {
    @NotNull private List<RuleConditionDto> conditions;
    @NotNull private Long categoryId;
    private Integer priority;
    private Boolean enabled;
    /** When true, re-evaluate all transactions not categorized by hand immediately. */
    private boolean applyToExisting;
}
