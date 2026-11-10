package com.household.manager.service;

import com.household.manager.finance.MonthlyCashflow;
import com.household.manager.model.entity.Category;
import com.household.manager.model.entity.CategoryKind;
import com.household.manager.repository.CategoryRepository;
import com.household.manager.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CashflowServiceTest {

    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final CashflowService service = new CashflowService(transactions, categories);

    @Test
    void netsRefundsOnlyInExpenseCategoriesOfTheMonth() {
        when(categories.findAll()).thenReturn(List.of(
                Category.builder().id(1L).name("Miete").kind(CategoryKind.EXPENSE).build(),
                Category.builder().id(2L).name("Gehalt").kind(CategoryKind.INCOME).build()));
        when(transactions.sumFlowsByCategory(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null))
                .thenReturn(List.of(
                        new Object[]{1L, new BigDecimal("-2028.23"), new BigDecimal("950.00")},
                        new Object[]{2L, BigDecimal.ZERO, new BigDecimal("3000.00")},
                        new Object[]{null, new BigDecimal("-12.50"), BigDecimal.ZERO}));

        MonthlyCashflow.Result result = service.forMonth(YearMonth.of(2026, 11), null);

        assertThat(result.expensesFor(1L)).isEqualByComparingTo("1078.23");
        assertThat(result.expensesFor(null)).isEqualByComparingTo("12.50");
        assertThat(result.totalExpenses()).isEqualByComparingTo("1090.73");
        assertThat(result.totalIncome()).isEqualByComparingTo("3000.00");
    }
}
