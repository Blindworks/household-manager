package com.household.manager.dto;

import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizationRuleRequest {
    @NotNull private RuleMatchField field;
    @NotNull private RuleMatchType matchType;
    @NotBlank private String pattern;
    @NotNull private Long categoryId;
    private Integer priority;
    private Boolean enabled;
    /** When true, apply this rule to existing uncategorized transactions immediately. */
    private boolean applyToExisting;
}
