package com.household.manager.finance;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Computes spend-vs-limit percentage and traffic-light status. */
@Component
public class BudgetEvaluator {

    public BudgetEvaluation evaluate(BigDecimal limit, BigDecimal spent) {
        BigDecimal safeLimit = limit == null ? BigDecimal.ZERO : limit;
        BigDecimal safeSpent = spent == null ? BigDecimal.ZERO : spent;

        int percent;
        if (safeLimit.compareTo(BigDecimal.ZERO) <= 0) {
            percent = safeSpent.compareTo(BigDecimal.ZERO) > 0 ? 999 : 0;
        } else {
            percent = safeSpent.multiply(BigDecimal.valueOf(100))
                    .divide(safeLimit, 0, RoundingMode.HALF_UP).intValue();
        }

        BudgetStatus status;
        if (percent > 100) {
            status = BudgetStatus.RED;
        } else if (percent >= 80) {
            status = BudgetStatus.YELLOW;
        } else {
            status = BudgetStatus.GREEN;
        }

        return BudgetEvaluation.builder()
                .limit(safeLimit).spent(safeSpent).percent(percent).status(status)
                .build();
    }
}
