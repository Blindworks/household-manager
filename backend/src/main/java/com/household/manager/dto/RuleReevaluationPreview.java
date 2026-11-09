package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RuleReevaluationPreview {
    private final int totalChanges;
    private final List<RuleReevaluationRow> rows;
    /** Nullable hint, e.g. that the rule catches every payment of a payment intermediary. */
    private final String warning;
}
