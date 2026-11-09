package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A proposed categorization rule derived from a manual category change. */
@Data
@Builder
public class RuleSuggestion {
    private final List<RuleConditionDto> conditions;
    private final Long categoryId;
    /** Optional note for the user, e.g. why the suggestion has more than one condition. */
    private final String hint;
}
