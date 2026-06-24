package com.household.manager.dto;

import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import lombok.Builder;
import lombok.Data;

/** A proposed categorization rule derived from a manual category change. */
@Data
@Builder
public class RuleSuggestion {
    private final RuleMatchField field;
    private final RuleMatchType matchType;
    private final String pattern;
    private final Long categoryId;
}
