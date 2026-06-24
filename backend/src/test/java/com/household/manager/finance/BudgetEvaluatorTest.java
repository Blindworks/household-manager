package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetEvaluatorTest {

    private final BudgetEvaluator evaluator = new BudgetEvaluator();

    @Test
    void belowEightyPercentIsGreen() {
        BudgetEvaluation e = evaluator.evaluate(new BigDecimal("100"), new BigDecimal("50"));
        assertEquals(50, e.getPercent());
        assertEquals(BudgetStatus.GREEN, e.getStatus());
    }

    @Test
    void betweenEightyAndHundredIsYellow() {
        assertEquals(BudgetStatus.YELLOW,
                evaluator.evaluate(new BigDecimal("100"), new BigDecimal("90")).getStatus());
        assertEquals(BudgetStatus.YELLOW,
                evaluator.evaluate(new BigDecimal("100"), new BigDecimal("100")).getStatus());
    }

    @Test
    void aboveHundredIsRed() {
        assertEquals(BudgetStatus.RED,
                evaluator.evaluate(new BigDecimal("100"), new BigDecimal("120")).getStatus());
    }

    @Test
    void zeroLimitWithSpendingIsRed() {
        assertEquals(BudgetStatus.RED,
                evaluator.evaluate(BigDecimal.ZERO, new BigDecimal("1")).getStatus());
    }
}
