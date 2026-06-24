package com.household.manager.finance;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** Outcome of comparing spending against a budget limit. */
@Data
@Builder
public class BudgetEvaluation {
    private final BigDecimal limit;
    private final BigDecimal spent;
    private final int percent;
    private final BudgetStatus status;
}
