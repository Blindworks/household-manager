package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyCashflowTest {

    private static final Long RENT = 1L;
    private static final Long SHOPPING = 2L;
    private static final Long SALARY = 3L;
    private static final Set<Long> EXPENSE_CATEGORIES = Set.of(RENT, SHOPPING);

    private static MonthlyCashflow.CategoryFlow flow(Long categoryId, String outflow, String inflow) {
        return new MonthlyCashflow.CategoryFlow(categoryId, new BigDecimal(outflow), new BigDecimal(inflow));
    }

    @Test
    void rentRefundReducesRentExpenseAndIsNoIncome() {
        MonthlyCashflow.Result result = MonthlyCashflow.compute(List.of(
                flow(RENT, "-2028.23", "950.00"),
                flow(SALARY, "0", "3000.00")), EXPENSE_CATEGORIES);

        assertThat(result.totalExpenses()).isEqualByComparingTo("1078.23");
        assertThat(result.totalIncome()).isEqualByComparingTo("3000.00");
        assertThat(result.expensesByCategory()).containsOnlyKeys(RENT);
        assertThat(result.expensesByCategory().get(RENT)).isEqualByComparingTo("1078.23");
    }

    @Test
    void refundAboveExpensesCountsOnlyTheExcessAsIncome() {
        MonthlyCashflow.Result result = MonthlyCashflow.compute(List.of(
                flow(SHOPPING, "-20.00", "50.00")), EXPENSE_CATEGORIES);

        assertThat(result.totalExpenses()).isEqualByComparingTo("0");
        assertThat(result.totalIncome()).isEqualByComparingTo("30.00");
        assertThat(result.expensesByCategory()).isEmpty();
    }

    @Test
    void returnInShoppingCategoryIsNetted() {
        MonthlyCashflow.Result result = MonthlyCashflow.compute(List.of(
                flow(SHOPPING, "-120.00", "45.99")), EXPENSE_CATEGORIES);

        assertThat(result.totalExpenses()).isEqualByComparingTo("74.01");
        assertThat(result.totalIncome()).isEqualByComparingTo("0");
    }

    @Test
    void uncategorizedAndIncomeCategoriesStaySplitBySign() {
        MonthlyCashflow.Result result = MonthlyCashflow.compute(List.of(
                flow(null, "-40.00", "15.00"),
                flow(SALARY, "-5.00", "3000.00")), EXPENSE_CATEGORIES);

        assertThat(result.totalExpenses()).isEqualByComparingTo("45.00");
        assertThat(result.totalIncome()).isEqualByComparingTo("3015.00");
        assertThat(result.expensesByCategory().get(null)).isEqualByComparingTo("40.00");
        assertThat(result.expensesByCategory().get(SALARY)).isEqualByComparingTo("5.00");
    }

    @Test
    void emptyMonthIsZero() {
        MonthlyCashflow.Result result = MonthlyCashflow.compute(List.of(), EXPENSE_CATEGORIES);

        assertThat(result.totalExpenses()).isEqualByComparingTo("0");
        assertThat(result.totalIncome()).isEqualByComparingTo("0");
        assertThat(result.expensesFor(RENT)).isEqualByComparingTo("0");
    }
}
