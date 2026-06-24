package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

/** Result of a manual categorization: the updated transaction plus an optional rule suggestion. */
@Data
@Builder
public class CategorizeResponse {
    private final TransactionResponse transaction;
    private final RuleSuggestion ruleSuggestion; // null if none is worth suggesting
}
